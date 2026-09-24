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
import dev.pampa.pampanotes.core.importing.HandwritingPages
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
    manifest(
      app, includeAudio, includeSources,
      databaseBytes = liveDatabase().length(),
      audioBytes = if (includeAudio) files.sizeOf(files.audio) else 0,
      sourceBytes = if (includeSources) files.sizeOf(files.sources) else 0,
    )
  }

  /**
   * Vero finche' il database e' aperto. Un ripristino fallito prima di chiuderlo lascia l'app com'era
   * (e chi chiama rimette in moto quello che aveva fermato); uno fallito dopo vuole un riavvio.
   */
  val databaseOpen: Boolean get() = database.isOpen

  /** Scrive il backup nella cartella scelta e torna dove l'ha messo. */
  suspend fun write(
    tree: Uri,
    app: String,
    includeAudio: Boolean,
    includeSources: Boolean,
    onProgress: (Float) -> Unit = {},
  ): BackupResult = withContext(io) {
    val folder = DocumentFile.fromTreeUri(context, tree)
      ?: throw BackupFailure(BackupFailure.Reason.FOLDER_GONE)
    if (!folder.canWrite()) throw BackupFailure(BackupFailure.Reason.NOT_WRITABLE)

    val snapshot = snapshotDatabase()
    try {
      val audio = if (includeAudio) filesIn(files.audio) else emptyList()
      val sourceFiles = if (includeSources) filesIn(files.sources) else emptyList()
      // I pesi dal file che si scriveranno, non dalla cartella: e' quello che si confronta dopo.
      val card = manifest(app, includeAudio, includeSources, snapshot.length(), audio.sumOf { it.length() }, sourceFiles.sumOf { it.length() })
      val name = backupFileName(card.createdAt)

      // Si scrive con un nome provvisorio e si rinomina alla fine: un file col nome di un backup e'
      // un backup finito. Se l'app muore a meta' — un backup con le registrazioni dura minuti — nella
      // cartella resta un `.partial` che nessuno scambia per un backup da ripristinare. Il tipo e'
      // generico apposta: con `application/zip` alcuni provider aggiungono `.zip` in fondo al nome.
      val partialName = "$name$PARTIAL"
      folder.findFile(partialName)?.delete()
      val document = folder.createFile(PARTIAL_MIME, partialName)
        ?: throw BackupFailure(BackupFailure.Reason.CREATE)

      val trailer = try {
        val stream = context.contentResolver.openOutputStream(document.uri)
          ?: throw BackupFailure(BackupFailure.Reason.NOT_WRITABLE)
        stream.use { out -> BackupArchive.write(out, card, snapshot, audio, sourceFiles, onProgress) }
      } catch (e: Throwable) {
        // Un archivio a meta' e' peggio di nessun archivio: chi prova a ripristinarlo lo scopre nel
        // momento in cui ha gia' perso il resto.
        runCatching { document.delete() }
        if (e is kotlin.coroutines.cancellation.CancellationException || e is BackupFailure) throw e
        throw BackupFailure(BackupFailure.Reason.INTERRUPTED, e)
      }

      // Uno stesso nome se ne va, ma solo adesso che quello nuovo e' completo: due backup dello
      // stesso minuto sono lo stesso backup rifatto.
      folder.findFile(name)?.delete()
      val finished = if (document.renameTo(name)) {
        document
      } else {
        // Un provider che non sa rinominare: si copia nel nome buono. Costa una seconda scrittura,
        // ma e' l'unico modo di non lasciare il backup con un nome che dice «a meta'».
        copyToFinal(folder, document, name)
      }

      settings.setLastBackupAt(card.createdAt)
      // Quello che e' entrato davvero: un file tolto a meta' backup non c'e', e la schermata non lo
      // conta.
      BackupResult(finished.uri, name, finished.length(), card.copy(audioBytes = trailer.audioBytes, sourceBytes = trailer.sourceBytes, sealed = true))
    } finally {
      snapshot.delete()
    }
  }

  private fun copyToFinal(folder: DocumentFile, partial: DocumentFile, name: String): DocumentFile {
    val target = folder.createFile(MIME, name) ?: throw BackupFailure(BackupFailure.Reason.CREATE)
    try {
      val input = context.contentResolver.openInputStream(partial.uri) ?: throw BackupFailure(BackupFailure.Reason.OPEN)
      val output = context.contentResolver.openOutputStream(target.uri) ?: throw BackupFailure(BackupFailure.Reason.NOT_WRITABLE)
      input.use { source -> output.use { sink -> source.copyTo(sink, 64 * 1024) } }
    } catch (e: Throwable) {
      runCatching { target.delete() }
      runCatching { partial.delete() }
      if (e is BackupFailure) throw e
      throw BackupFailure(BackupFailure.Reason.INTERRUPTED, e)
    }
    runCatching { partial.delete() }
    return target
  }

  /** Cosa c'e' dentro quel file, senza aprirlo davvero: il manifesto e' la prima voce. */
  suspend fun inspect(uri: Uri): BackupManifest = withContext(io) {
    val stream = context.contentResolver.openInputStream(uri)
      ?: throw BackupFailure(BackupFailure.Reason.OPEN)
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
    // La versione si controlla sul manifesto, prima di estrarre: un backup piu' nuovo si rifiuta
    // senza aver scritto un byte.
    val current = database.openHelper.readableDatabase.version
    val staged = try {
      val stream = context.contentResolver.openInputStream(uri)
        ?: throw BackupFailure(BackupFailure.Reason.OPEN)
      stream.use { BackupArchive.extract(it, staging, onProgress, maxDatabaseVersion = current) }
    } catch (failure: Throwable) {
      staging.deleteRecursively()
      throw failure
    }

    try {
      database.close()
      val live = liveDatabase()
      // Il giornale di scrittura appartiene al database di prima: lasciarlo accanto a quello nuovo
      // vorrebbe dire rigiocarci sopra transazioni di un altro archivio. Si mette da parte, non si
      // cancella, finche' quello nuovo non e' al suo posto: uno spostamento fallito a meta' (disco
      // pieno) lasciava l'app senza nessuno dei due.
      val current = listOf(live, File(live.path + "-wal"), File(live.path + "-shm"))
      val aside = current.map { File(it.path + ASIDE) }
      aside.forEach { it.delete() }
      current.zip(aside).forEach { (from, to) -> if (from.exists() && !from.renameTo(to)) throw BackupFailure(BackupFailure.Reason.INTERRUPTED) }
      try {
        move(staged.database, live)
      } catch (e: Throwable) {
        live.delete()
        aside.zip(current).forEach { (from, to) -> if (from.exists()) from.renameTo(to) }
        throw e
      }
      aside.forEach { it.delete() }

      if (staged.manifest.includesAudio) replaceDir(staged.audio, files.audio)
      if (staged.manifest.includesSources) replaceDir(staged.sources, files.sources)
      // I lavori a meta' parlavano di righe che non ci sono piu'.
      files.jobs.deleteRecursively()
      files.jobs.mkdirs()

      staged.manifest.settings?.let { restoreSettings(it) }
      settings.setOnboardingDone(true)
      // I giri unici dell'avvio ricominciano: il database ripristinato puo' essere di prima delle
      // date vere e delle pagine a mano, e i segni di «fatto» parlavano di quello di prima.
      settings.resetBackfills()
      File(files.root, HandwritingPages.TRIED_DIR).deleteRecursively()
      // Lo stesso di SyncRepository.afterRestore, qui per non far dipendere il backup dal sync: con
      // l'id di prima il database ripristinato non si riprenderebbe mai le righe che questo telefono
      // ha scritto dopo il backup, e il server gli lascerebbe sovrascriverle.
      settings.forgetSyncDevice()
      settings.setSyncOrphanAttempts(emptyMap())
      settings.clearSyncReviveRoots(settings.syncReviveRoots())
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
    audioBytes: Long,
    sourceBytes: Long,
  ): BackupManifest = BackupManifest(
    createdAt = System.currentTimeMillis(),
    app = app,
    databaseVersion = database.openHelper.readableDatabase.version,
    includesAudio = includeAudio,
    includesSources = includeSources,
    audioBytes = audioBytes,
    sourceBytes = sourceBytes,
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
    settings.setSpeakerSeparation(saved.separation())
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
    private const val PARTIAL = ".partial"
    private const val PARTIAL_MIME = "application/octet-stream"
    private const val ASIDE = ".before-restore"
  }
}
