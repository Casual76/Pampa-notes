package dev.pampa.pampanotes.core.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.archive.ArchiveMismatch
import dev.pampa.pampanotes.core.archive.ArchiveMissing
import dev.pampa.pampanotes.core.archive.ArchiveRepository
import dev.pampa.pampanotes.core.archive.FetchProgress
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.FolderDao
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.NoteTagDao
import dev.pampa.pampanotes.core.db.SegmentDao
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.model.slugify
import dev.pampa.pampanotes.core.repo.FolderRepository
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Dove finisce il bundle. */
sealed interface ExportDestination {
  /** Una cartella scelta dall'utente: Drive, OneDrive, la memoria del telefono. */
  data class Folder(val treeUri: Uri) : ExportDestination

  /** La cache dell'app, per passare il file al foglio di condivisione. */
  data object Share : ExportDestination
}

data class ExportResult(
  val uri: Uri,
  val displayName: String,
  val sizeBytes: Long,
  val mimeType: String,
  val noteCount: Int,
  /**
   * Il file su disco, quando il bundle e' finito nella cache.
   *
   * Serve a chi deve passarlo al foglio di condivisione: un `file://` non si puo' consegnare a
   * un'altra app, ci vuole un URI del FileProvider, e per costruirlo ci vuole il File.
   */
  val file: File? = null,
  /**
   * I file scritti, quando il pacchetto e' sciolto: si condividono tutti insieme. Vuota per lo ZIP e
   * per il file singolo, che sono un file solo.
   */
  val files: List<File> = emptyList(),
  /** Vero per i file sciolti: [uri] e' una cartella, e una cartella non si "apre" con un'app. */
  val isDirectory: Boolean = false,
  /** Registrazioni chieste e non entrate: il file sta su un altro dispositivo. Si dice, non si tace. */
  val skippedAudio: Int = 0,
  /** Originali chiesti e non entrati, per lo stesso motivo. */
  val skippedSources: Int = 0,
  /** Registrazioni, originali e pagine a mano che sono dentro davvero: la schermata dice questi. */
  val includedAudio: Int = 0,
  val includedSources: Int = 0,
  val includedPages: Int = 0,
  /** Pagine scritte a mano che non sono entrate, per lo stesso motivo delle registrazioni. */
  val skippedPages: Int = 0,
)

/** Com'e' andato il prelievo dal computer di casa prima di scrivere. */
data class ExportFetchOutcome(
  val fetched: Int,
  /** Quelli che non sono arrivati, col perche'. Comprende quelli che non si potevano nemmeno chiedere. */
  val missing: List<MissingFile>,
)

/**
 * Un export non riuscito, con il perche' in un codice: la frase la sceglie la schermata, nella
 * lingua dell'app. Dal modulo `:core` le stringhe dell'app non si leggono.
 */
class ExportFailure(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause) {
  enum class Reason {
    /** Il file (o i file sciolti) nella cache non si sono scritti. */
    WRITE,
    /** La cartella scelta non ha lasciato creare il file o la cartella. */
    CREATE,
    /** La cartella scelta non accetta scritture, o non se ne ha piu' il permesso. */
    NOT_WRITABLE,
    /** La cartella scelta non si raggiunge piu'. */
    FOLDER_GONE,
    /** La scrittura si e' fermata a meta'. */
    INTERRUPTED,
  }
}

/**
 * Raccoglie e scrive.
 *
 * Due passaggi separati apposta: [gather] legge il database e basta, [export] scrive e basta. La
 * schermata puo' cosi' mostrare quante note ha preso e quanto pesera' prima di chiedere dove
 * salvarlo, che e' l'unico momento utile per accorgersi di aver scelto l'ambito sbagliato.
 */
@Singleton
class ExportService @Inject constructor(
  @ApplicationContext private val context: Context,
  private val notes: NoteDao,
  private val folders: FolderDao,
  private val tags: NoteTagDao,
  private val sessions: SessionDao,
  private val transcripts: TranscriptDao,
  private val segments: SegmentDao,
  private val sources: SourceDao,
  private val audioParts: AudioPartDao,
  private val files: AppFiles,
  private val fetcher: ArchiveFetcher,
) {

  private val io = Dispatchers.IO

  /**
   * Legge dal database tutto quello che serve a scrivere.
   *
   * @param everythingLabel come si chiama "tutto l'archivio" nella lingua dell'app.
   */
  suspend fun gather(
    scope: ExportScope,
    options: ExportOptions,
    generator: String,
    everythingLabel: String,
  ): ExportSet = withContext(io) {
    val all = folders.all().associateBy { it.id }
    fun pathOf(folderId: String): List<String> {
      val path = ArrayDeque<String>()
      var current = all[folderId]
      var guard = 0
      while (current != null && guard++ < 64) {
        path.addFirst(current.name)
        current = current.parentId?.let { all[it] }
      }
      return path.toList()
    }

    val (selected, label) = when (scope) {
      is ExportScope.Everything -> notes.all() to everythingLabel
      is ExportScope.Note -> {
        val note = notes.get(scope.id) ?: return@withContext empty(everythingLabel, generator)
        listOf(note) to note.title
      }

      is ExportScope.Notes -> notes.getAll(scope.ids) to scope.label

      is ExportScope.Folder -> {
        // La cartella e tutte quelle che contiene: esportare "Storia" e non prendere "Storia /
        // Novecento" sarebbe una sorpresa, non una scelta.
        val wanted = FolderRepository.descendants(scope.id, all.values.toList())
        notes.all().filter { it.folderId in wanted } to (all[scope.id]?.name ?: everythingLabel)
      }
    }

    val gathered = selected
      .sortedWith(compareBy({ pathOf(it.folderId).joinToString("/") }, { -it.updatedAt }))
      .map { note -> gatherNote(note, pathOf(note.folderId), options) }

    ExportSet(
      scopeLabel = label,
      scopeSlug = label.slugify(40),
      notes = gathered,
      generator = generator,
      exportedAtMillis = System.currentTimeMillis(),
    )
  }

  /** Vero se il file e' gia' su questo dispositivo. */
  private fun isPresent(file: ExportFileRef): Boolean = when (file.kind) {
    ExportFileKind.AUDIO -> files.audioFile(file.fileName).exists()
    ExportFileKind.SOURCE, ExportFileKind.PAGE -> files.sourceFile(file.fileName).exists()
  }

  /** Quali file chiesti mancano qui, e quali di questi il computer di casa puo' dare. */
  suspend fun plan(set: ExportSet, options: ExportOptions): ExportFetchPlan = withContext(io) {
    ExportFiles.plan(set, options, ::isPresent)
  }

  /**
   * Scarica dal computer di casa quello che [plan] ha trovato da scaricare.
   *
   * Prima di scrivere e non durante: se il computer non risponde lo si sa prima che esista un file a
   * meta', e la persona puo' scegliere se esportare senza. Al primo «non risponde» ci si ferma — e'
   * lo stesso computer per tutti, e gli altri aspetterebbero ognuno il suo timeout per fallire uguale.
   * Un file che il computer dice di non avere, o che arriva diverso, riguarda quel file solo.
   */
  suspend fun fetchMissing(plan: ExportFetchPlan, onProgress: (FetchProgress) -> Unit = {}): ExportFetchOutcome = withContext(io) {
    val missing = plan.unobtainable.toMutableList()
    val total = plan.toFetch.size
    var fetched = 0
    var unreachable = false
    plan.toFetch.forEachIndexed { index, file ->
      if (unreachable) {
        missing += MissingFile(file, MissingReason.UNREACHABLE)
        return@forEachIndexed
      }
      onProgress(FetchProgress(index, total, file.name, index.toFloat() / total))
      val within: (Long, Long) -> Unit = { received, size ->
        val part = if (size > 0) (received.toFloat() / size).coerceIn(0f, 1f) else 0f
        onProgress(FetchProgress(index, total, file.name, (index + part) / total))
      }
      try {
        when (file.kind) {
          ExportFileKind.AUDIO -> {
            val row = audioParts.get(file.id) ?: throw ArchiveMissing(file.name)
            fetcher.fetchPart(row, within)
          }

          ExportFileKind.SOURCE, ExportFileKind.PAGE -> {
            val row = sources.get(file.id) ?: throw ArchiveMissing(file.name)
            fetcher.fetchSource(row, within)
          }
        }
        fetched++
      } catch (error: IOException) {
        val reason = when {
          error is ArchiveMissing -> MissingReason.NOT_ARCHIVED
          error !is ArchiveMismatch && ArchiveRepository.isUnreachable(error) -> MissingReason.UNREACHABLE
          else -> MissingReason.FAILED
        }
        if (reason == MissingReason.UNREACHABLE) unreachable = true
        missing += MissingFile(file, reason)
      }
    }
    onProgress(FetchProgress(total, total, "", 1f))
    ExportFetchOutcome(fetched, missing)
  }

  /** Scrive il pacchetto e torna dove lo ha messo. */
  suspend fun export(
    set: ExportSet,
    options: ExportOptions,
    destination: ExportDestination,
    labels: ExportLabels = ExportLabels(),
    onProgress: (Float) -> Unit = {},
  ): ExportResult = withContext(io) {
    val result = write(
      set = ExportFiles.withoutMissingPages(set) { files.sourceFile(it.storedFileName).exists() },
      options = options,
      destination = destination,
      labels = labels,
      onProgress = onProgress,
    )
    // Quello che e' dentro davvero, contato adesso che e' scritto: la schermata dice questo, non
    // quello che si era chiesto.
    val wanted = ExportFiles.wanted(set, options)
    fun count(kind: ExportFileKind, present: Boolean) = wanted.count { it.kind == kind && isPresent(it) == present }
    result.copy(
      includedAudio = count(ExportFileKind.AUDIO, true),
      includedSources = count(ExportFileKind.SOURCE, true),
      includedPages = count(ExportFileKind.PAGE, true),
      skippedPages = count(ExportFileKind.PAGE, false),
    )
  }

  private suspend fun write(
    set: ExportSet,
    options: ExportOptions,
    destination: ExportDestination,
    labels: ExportLabels,
    onProgress: (Float) -> Unit,
  ): ExportResult {
    // «Annulla» deve fermare davvero: la scrittura e' codice bloccante, che da solo non si accorge
    // che la coroutine e' stata annullata. Il writer chiede a ogni file (e a ogni blocco di un file
    // lungo) se deve continuare, e un annullamento arriva come CancellationException.
    val job = currentCoroutineContext()
    val checkpoint: () -> Unit = { job.ensureActive() }
    val writer = BundleWriter(audioDir = files.audio, sourcesDir = files.sources, labels = labels, checkpoint = checkpoint)
    val format = options.format
    val name = fileName(set, format)
    // Con l'indice in cloud una nota puo' avere le righe delle registrazioni e non i file: il
    // pacchetto esce lo stesso, ma chi lo riceve deve sapere che e' piu' leggero di quello chiesto.
    val skipped = if (format == ExportFormat.BUNDLE && options.includeAudio) {
      set.notes.sumOf { note -> note.sessions.sumOf { session -> session.parts.count { !File(files.audio, it.fileName).exists() } } }
    } else {
      0
    }
    val skippedSources = if (format == ExportFormat.BUNDLE && options.includeSources) {
      set.notes.sumOf { note -> note.sources.count { source -> source.storedFileName != null && !File(files.sources, source.storedFileName).exists() } }
    } else {
      0
    }

    if (format == ExportFormat.FILES) return exportLoose(set, options, destination, writer, name, onProgress, checkpoint)

    val single = format == ExportFormat.SINGLE
    val mime = if (single) "text/markdown" else "application/zip"
    return when (destination) {
      is ExportDestination.Share -> {
        val target = File(files.exports, name)
        // Si scrive con un altro nome e si rinomina solo alla fine: un file col nome buono e' un
        // file finito, anche se l'app muore a meta' e nessuno arriva a cancellare il parziale.
        val partial = File(files.exports, "$name.partial")
        try {
          partial.outputStream().use { out ->
            if (single) out.write(writer.single(set, options).toByteArray(Charsets.UTF_8))
            else writer.write(set, options, out, onProgress)
          }
          target.delete()
          if (!partial.renameTo(target)) throw IOException("rename")
        } catch (e: Throwable) {
          partial.delete()
          if (e is CancellationException) throw e
          throw ExportFailure(ExportFailure.Reason.WRITE, e)
        }
        ExportResult(Uri.fromFile(target), name, target.length(), mime, set.notes.size, target, skippedAudio = skipped, skippedSources = skippedSources)
      }

      is ExportDestination.Folder -> {
        val tree = folderOf(destination)
        // Un file con lo stesso nome se ne va: due export dello stesso minuto sono lo stesso export
        // rifatto, e lasciare "bundle (1).zip" accanto a "bundle.zip" confonde e basta.
        //
        // Qui si scrive col nome buono e non con un nome provvisorio: rinominare un documento SAF
        // non e' una cosa che tutti i provider sanno fare, e un `.partial` rimasto su Drive perche'
        // il rename non e' passato sarebbe peggio. Il parziale si cancella se qualcosa va storto,
        // compreso «Annulla».
        tree.findFile(name)?.delete()
        val document = tree.createFile(mime, name)
          ?: throw ExportFailure(ExportFailure.Reason.CREATE)

        try {
          val stream = context.contentResolver.openOutputStream(document.uri)
            ?: throw ExportFailure(ExportFailure.Reason.NOT_WRITABLE)
          stream.use { out ->
            if (single) out.write(writer.single(set, options).toByteArray(Charsets.UTF_8))
            else writer.write(set, options, out, onProgress)
          }
        } catch (e: Throwable) {
          // Un file a meta' e' peggio di nessun file: chi lo apre trova un archivio rotto e non sa
          // che l'export era fallito.
          runCatching { document.delete() }
          if (e is CancellationException || e is ExportFailure) throw e
          throw ExportFailure(ExportFailure.Reason.INTERRUPTED, e)
        }
        ExportResult(document.uri, name, document.length(), mime, set.notes.size, skippedAudio = skipped, skippedSources = skippedSources)
      }
    }
  }

  /**
   * I file sciolti: si scrivono sempre nella cache, in una cartella col nome del pacchetto, e da li'
   * o si condividono tutti insieme o si copiano in una sottocartella di quella scelta.
   *
   * Passare dalla cache anche per la cartella costa una copia, ma un file a meta' nella cartella
   * dell'utente non puo' succedere: se la scrittura fallisce, fallisce prima di toccarla.
   */
  private fun exportLoose(
    set: ExportSet,
    options: ExportOptions,
    destination: ExportDestination,
    writer: BundleWriter,
    name: String,
    onProgress: (Float) -> Unit,
    checkpoint: () -> Unit,
  ): ExportResult {
    val directory = File(files.exports, name)
    // Come per lo ZIP: la cartella col nome buono c'e' solo quando e' completa.
    val partial = File(files.exports, "$name.partial")
    directory.deleteRecursively()
    partial.deleteRecursively()
    // Verso una cartella scelta c'e' anche la copia, che su Drive e' la parte lenta: la barra ne
    // tiene conto, meta' per scrivere e meta' per copiare, invece di arrivare in fondo e fermarsi.
    val toFolder = destination is ExportDestination.Folder
    val written = try {
      val files = writer.writeLoose(set, options, partial) { onProgress(if (toFolder) it / 2 else it) }
      if (!partial.renameTo(directory)) throw IOException("rename")
      files.map { File(directory, it.relativeTo(partial).path) }
    } catch (e: Throwable) {
      partial.deleteRecursively()
      directory.deleteRecursively()
      if (e is CancellationException) throw e
      throw ExportFailure(ExportFailure.Reason.WRITE, e)
    }
    val size = written.sumOf { it.length() }

    return when (destination) {
      is ExportDestination.Share ->
        ExportResult(Uri.fromFile(directory), name, size, "text/markdown", set.notes.size, file = directory, files = written, isDirectory = true)

      is ExportDestination.Folder -> {
        val tree = folderOf(destination)
        tree.findFile(name)?.delete()
        val folder = tree.createDirectory(name)
          ?: throw ExportFailure(ExportFailure.Reason.CREATE)
        try {
          written.forEachIndexed { index, file ->
            checkpoint()
            val mime = if (file.extension == "png") "image/png" else "text/markdown"
            val document = folder.createFile(mime, file.name)
              ?: throw ExportFailure(ExportFailure.Reason.CREATE)
            val stream = context.contentResolver.openOutputStream(document.uri)
              ?: throw ExportFailure(ExportFailure.Reason.NOT_WRITABLE)
            stream.use { out -> file.inputStream().use { it.copyTo(out) } }
            onProgress(0.5f + 0.5f * (index + 1) / written.size)
          }
        } catch (e: Throwable) {
          runCatching { folder.delete() }
          directory.deleteRecursively()
          if (e is CancellationException || e is ExportFailure) throw e
          throw ExportFailure(ExportFailure.Reason.INTERRUPTED, e)
        }
        directory.deleteRecursively()
        ExportResult(folder.uri, name, size, "text/markdown", set.notes.size, isDirectory = true)
      }
    }
  }

  private fun folderOf(destination: ExportDestination.Folder): DocumentFile {
    val tree = DocumentFile.fromTreeUri(context, destination.treeUri)
      ?: throw ExportFailure(ExportFailure.Reason.FOLDER_GONE)
    if (!tree.canWrite()) throw ExportFailure(ExportFailure.Reason.NOT_WRITABLE)
    return tree
  }

  // -----------------------------------------------------------------------------------------------

  private suspend fun gatherNote(note: NoteEntity, folderPath: List<String>, options: ExportOptions): ExportNote {
    val sessionRows = sessions.byNote(note.id)
    val gatheredSessions = sessionRows.mapIndexed { index, row ->
      val ordered = row.partsSorted
      var offset = 0L
      val parts = ordered.map { part ->
        val start = offset
        offset += part.durationMs
        ExportPart(
          id = part.id,
          originalName = part.originalName,
          fileName = part.fileName,
          durationMs = part.durationMs,
          startMs = start,
          sizeBytes = part.sizeBytes,
          archived = part.archivedAt > 0,
        )
      }

      val versions = transcripts.bySession(row.session.id)
      val raw = versions.firstOrNull { it.kind == TranscriptKind.RAW }
      val chosen = when (options.transcript) {
        TranscriptChoice.RAW -> raw
        // La migliore: quella che l'utente sta guardando nell'app, altrimenti la grezza. Esportare
        // una versione diversa da quella a schermo sarebbe una sorpresa dentro un file.
        TranscriptChoice.BEST -> versions.firstOrNull { it.id == row.session.activeTranscriptId } ?: raw
      }

      ExportSession(
        id = row.session.id,
        number = index + 1,
        title = row.session.title,
        date = row.session.date,
        parts = parts,
        transcript = chosen,
        raw = raw,
        segments = raw?.let { segments.byTranscript(it.id) }.orEmpty(),
      )
    }

    val allSources = sources.byNote(note.id)
    // Le pagine scritte a mano sono contenuto, non provenienza: vanno negli appunti, sempre. Quelle
    // che stanno qui, e quelle che il computer di casa ha: queste si scaricano prima di scrivere, e
    // se non arrivano escono dal pacchetto (un collegamento a un'immagine che non c'e' e' peggio di
    // niente). Una pagina che non sta da nessuna delle due parti non si promette nemmeno.
    val pages = allSources.filter { source ->
      source.derivedFromId != null &&
        source.storedFileName?.let { name -> files.sourceFile(name).exists() || source.archivedAt > 0 } == true
    }.sortedWith(compareBy({ it.importedAt }, { it.originalName }))
    return ExportNote(
      note = note,
      folderPath = folderPath,
      tags = tags.tags(note.id),
      handwriting = pages.mapIndexed { index, page ->
        ExportImage(page.storedFileName!!, index + 1, page.sizeBytes, id = page.id, archived = page.archivedAt > 0)
      },
      sources = allSources.filter { it.derivedFromId == null }.map { source ->
        ExportSource(
          originalName = source.originalName,
          kind = source.kind,
          sha256 = source.sha256,
          sizeBytes = source.sizeBytes,
          storedFileName = source.storedFileName,
          status = source.status,
          extractedChars = source.extractedChars,
          id = source.id,
          archived = source.archivedAt > 0,
        )
      },
      sessions = gatheredSessions,
    )
  }

  private fun empty(label: String, generator: String) = ExportSet(
    scopeLabel = label,
    scopeSlug = label.slugify(40),
    notes = emptyList(),
    generator = generator,
    exportedAtMillis = System.currentTimeMillis(),
  )

  companion object {
    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)

    /**
     * `pampa-notes-storia-20260917-1830.zip`: si riconosce e si ordina da solo. Il formato sciolto e'
     * una cartella con lo stesso nome, senza estensione.
     */
    fun fileName(set: ExportSet, format: ExportFormat): String {
      val when_ = Instant.ofEpochMilli(set.exportedAtMillis).atZone(ZoneId.systemDefault()).format(stamp)
      val scope = set.scopeSlug.ifBlank { "note" }
      return "pampa-notes-$scope-$when_" + when (format) {
        ExportFormat.BUNDLE -> ".zip"
        ExportFormat.SINGLE -> ".md"
        ExportFormat.FILES -> ""
      }
    }
  }
}
