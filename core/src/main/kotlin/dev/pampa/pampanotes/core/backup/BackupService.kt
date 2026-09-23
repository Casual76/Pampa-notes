package dev.pampa.pampanotes.core.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.FolderDao
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BackupResult(
  val uri: Uri,
  val displayName: String,
  val sizeBytes: Long,
  val manifest: BackupManifest,
)

/**
 * Il backup e il ripristino, su una cartella scelta dall'utente.
 *
 * Un backup e' il database piu' i file, non uno dei due: le righe senza gli audio sono una
 * trascrizione che non si puo' piu' ascoltare, gli audio senza le righe sono file con l'id per
 * nome. Le registrazioni si possono pero' **lasciare fuori**, perche' sono la parte che pesa
 * gigabyte, e chi tiene un backup su Drive spesso vuole solo il testo.
 *
 * Il ripristino **sostituisce**, non fonde. Fondere due archivi vorrebbe dire decidere, riga per
 * riga, quale delle due versioni di una nota vale: nessuna risposta automatica e' quella giusta, e
 * una sbagliata si scopre settimane dopo.
 */
@Singleton
class BackupService @Inject constructor(
  @ApplicationContext private val context: Context,
  private val database: PampaDatabase,
  private val folders: FolderDao,
  private val notes: NoteDao,
  private val sessions: SessionDao,
  private val parts: AudioPartDao,
  private val transcripts: TranscriptDao,
  private val sources: SourceDao,
  private val files: AppFiles,
  private val settings: PampaSettingsStore,
) {

  private val io = Dispatchers.IO

  /** Cosa ci sarebbe dentro un backup fatto adesso: la schermata lo dice prima di scrivere niente. */
  suspend fun preview(app: String, includeAudio: Boolean, includeSources: Boolean): BackupManifest = withContext(io) {
    manifest(app, includeAudio, includeSources, databaseBytes = liveDatabase().length())
  }

  /** Scrive il backup nella cartella scelta e torna dove l'ha messo. */
  suspend fun write(
    tree: Uri,
    app: String,
    includeAudio: Boolean,
    includeSources: Boolean,
    onProgress: (Float) -> Unit = {},
  ): BackupResult = withContext(io) {
    val folder = DocumentFile.fromTreeUri(context, tree)
      ?: throw BackupFailure("la cartella scelta non e' piu' raggiungibile")
    if (!folder.canWrite()) throw BackupFailure("non ho il permesso di scrivere in quella cartella")

    val snapshot = snapshotDatabase()
    try {
      val audio = if (includeAudio) filesIn(files.audio) else emptyList()
      val sourceFiles = if (includeSources) filesIn(files.sources) else emptyList()
      val card = manifest(app, includeAudio, includeSources, snapshot.length())
      val name = backupFileName(card.createdAt)

      // Uno stesso nome se ne va: due backup dello stesso minuto sono lo stesso backup rifatto.
      folder.findFile(name)?.delete()
      val document = folder.createFile(MIME, name)
        ?: throw BackupFailure("non sono riuscito a creare il file nella cartella scelta")

      runCatching {
        val stream = context.contentResolver.openOutputStream(document.uri)
          ?: throw BackupFailure("la cartella scelta non accetta scritture")
        stream.use { out -> BackupArchive.write(out, card, snapshot, audio, sourceFiles, onProgress) }
      }.getOrElse {
        // Un archivio a meta' e' peggio di nessun archivio: chi prova a ripristinarlo lo scopre nel
        // momento in cui ha gia' perso il resto.
        document.delete()
        throw if (it is BackupFailure) it else BackupFailure("la scrittura si e' interrotta", it)
      }

      settings.setLastBackupAt(card.createdAt)
      BackupResult(document.uri, name, document.length(), card)
    } finally {
      snapshot.delete()
    }
  }

  /** Cosa c'e' dentro quel file, senza aprirlo davvero: il manifesto e' la prima voce. */
  suspend fun inspect(uri: Uri): BackupManifest = withContext(io) {
    val stream = context.contentResolver.openInputStream(uri)
      ?: throw BackupFailure("non riesco ad aprire quel file")
    stream.use { BackupArchive.readManifest(it) }
  }

  /**
   * Ripristina, e da qui in poi l'app va riavviata.
   *
   * L'ordine e' tutto: si estrae in una cartella di lavoro, **poi** si chiude il database, e solo
   * alla fine si spostano i file al loro posto. Fino al penultimo passo un errore lascia l'archivio
   * dell'utente esattamente com'era; dopo, il processo non ha piu' un database aperto e chi
   * chiama lo deve far ripartire.
   */
  suspend fun restore(uri: Uri, onProgress: (Float) -> Unit = {}): BackupManifest = withContext(io) {
    val staging = File(files.root, "restore")
    val staged = try {
      val stream = context.contentResolver.openInputStream(uri)
        ?: throw BackupFailure("non riesco ad aprire quel file")
      stream.use { BackupArchive.extract(it, staging, onProgress) }
    } catch (failure: Throwable) {
      staging.deleteRecursively()
      throw failure
    }

    val current = database.openHelper.readableDatabase.version
    if (staged.manifest.databaseVersion > current) {
      staging.deleteRecursively()
      throw BackupFailure("questo backup viene da una versione piu' recente dell'app")
    }

    try {
      database.close()
      val live = liveDatabase()
      // Il giornale di scrittura appartiene al database di prima: lasciarlo accanto a quello nuovo
      // vorrebbe dire rigiocarci sopra transazioni di un altro archivio.
      listOf(live, File(live.path + "-wal"), File(live.path + "-shm")).forEach { it.delete() }
      move(staged.database, live)

      if (staged.manifest.includesAudio) replaceDir(staged.audio, files.audio)
      if (staged.manifest.includesSources) replaceDir(staged.sources, files.sources)
      // I lavori a meta' parlavano di righe che non ci sono piu'.
      files.jobs.deleteRecursively()
      files.jobs.mkdirs()

      staged.manifest.settings?.let { restoreSettings(it) }
      settings.setOnboardingDone(true)
      staged.manifest
    } finally {
      staging.deleteRecursively()
    }
  }

  // -----------------------------------------------------------------------------------------------

  private suspend fun manifest(
    app: String,
    includeAudio: Boolean,
    includeSources: Boolean,
    databaseBytes: Long,
  ): BackupManifest = BackupManifest(
    createdAt = System.currentTimeMillis(),
    app = app,
    databaseVersion = database.openHelper.readableDatabase.version,
    includesAudio = includeAudio,
    includesSources = includeSources,
    audioBytes = if (includeAudio) files.sizeOf(files.audio) else 0,
    sourceBytes = if (includeSources) files.sizeOf(files.sources) else 0,
    databaseBytes = databaseBytes,
    counts = BackupCounts(
      folders = folders.count(),
      notes = notes.count(),
      sessions = sessions.count(),
      parts = parts.all().size,
      transcripts = transcripts.count(),
      sources = sources.all().size,
    ),
    settings = settings.current().toBackup(),
  )

  /**
   * Una copia ferma del database.
   *
   * Room scrive in modalita' WAL: il file principale da solo puo' essere indietro di minuti. Il
   * checkpoint riversa il giornale dentro e lo svuota, cosi' quello che si copia e' tutto quello
   * che c'e'. Si copia, e non si zippa il file vivo, perche' una scrittura a meta' compressione
   * darebbe un archivio incoerente e nessun errore.
   */
  private fun snapshotDatabase(): File {
    runCatching {
      database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
    }
    val copy = files.tempFile("backup-db", ".sqlite")
    liveDatabase().copyTo(copy, overwrite = true)
    return copy
  }

  private fun liveDatabase(): File = context.getDatabasePath(PampaDatabase.NAME)

  private fun filesIn(dir: File): List<File> =
    dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

  /** Sposta, e se il rinomina non passa copia: due cartelle diverse possono stare su due volumi. */
  private fun move(from: File, to: File) {
    to.parentFile?.mkdirs()
    if (from.renameTo(to)) return
    from.copyTo(to, overwrite = true)
    from.delete()
  }

  private fun replaceDir(from: File, to: File) {
    to.deleteRecursively()
    to.mkdirs()
    from.listFiles()?.forEach { file -> move(file, File(to, file.name)) }
  }

  private suspend fun restoreSettings(saved: BackupSettings) {
    settings.setLanguage(saved.language)
    settings.setVocabulary(saved.vocabulary)
    settings.setChunkMinutes(saved.chunkMinutes)
    settings.setGroqMaxUploadMb(saved.groqMaxUploadMb)
    settings.setCustomMaxMinutes(saved.customMaxMinutes)
    settings.setPreferredProvider(saved.providerId())
    settings.setCustomOnly(saved.customOnly)
    settings.setAutoTranscribeOnImport(saved.autoTranscribeOnImport)
    settings.setEndpoint(saved.endpointUrl, saved.endpointName, saved.endpointModel, touch = false)
    settings.setEndpointTimeoutMinutes(saved.endpointTimeoutMinutes)
    settings.setRefinementEnabled(saved.refinementEnabled)
    settings.setRefinementModel(saved.refinementModel)
    settings.setRefinementPreset(saved.preset())
    settings.setRefinementCustomPrompt(saved.refinementCustomPrompt)
    settings.setExportDefaultsJson(saved.exportDefaultsJson)
  }

  companion object {
    const val MIME = "application/zip"
  }
}
