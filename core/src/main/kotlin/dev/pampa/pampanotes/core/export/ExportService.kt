package dev.pampa.pampanotes.core.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampanotes.core.db.FolderDao
import dev.pampa.pampanotes.core.db.FolderEntity
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
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
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
)

class ExportFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

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
  private val files: AppFiles,
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
        val wanted = descendants(scope.id, all.values.toList())
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

  /** Scrive il pacchetto e torna dove lo ha messo. */
  suspend fun export(
    set: ExportSet,
    options: ExportOptions,
    destination: ExportDestination,
    labels: ExportLabels = ExportLabels(),
    onProgress: (Float) -> Unit = {},
  ): ExportResult = withContext(io) {
    val writer = BundleWriter(audioDir = files.audio, sourcesDir = files.sources, labels = labels)
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

    if (format == ExportFormat.FILES) return@withContext exportLoose(set, options, destination, writer, name, onProgress)

    val single = format == ExportFormat.SINGLE
    val mime = if (single) "text/markdown" else "application/zip"
    when (destination) {
      is ExportDestination.Share -> {
        val target = File(files.exports, name)
        runCatching {
          target.outputStream().use { out ->
            if (single) out.write(writer.single(set, options).toByteArray(Charsets.UTF_8))
            else writer.write(set, options, out, onProgress)
          }
        }.getOrElse {
          target.delete()
          throw ExportFailure("non sono riuscito a scrivere il file", it)
        }
        ExportResult(Uri.fromFile(target), name, target.length(), mime, set.notes.size, target, skippedAudio = skipped, skippedSources = skippedSources)
      }

      is ExportDestination.Folder -> {
        val tree = folderOf(destination)
        // Un file con lo stesso nome se ne va: due export dello stesso minuto sono lo stesso export
        // rifatto, e lasciare "bundle (1).zip" accanto a "bundle.zip" confonde e basta.
        tree.findFile(name)?.delete()
        val document = tree.createFile(mime, name)
          ?: throw ExportFailure("non sono riuscito a creare il file nella cartella scelta")

        runCatching {
          val stream = context.contentResolver.openOutputStream(document.uri)
            ?: throw ExportFailure("la cartella scelta non accetta scritture")
          stream.use { out ->
            if (single) out.write(writer.single(set, options).toByteArray(Charsets.UTF_8))
            else writer.write(set, options, out, onProgress)
          }
        }.getOrElse {
          // Un file a meta' e' peggio di nessun file: chi lo apre trova un archivio rotto e non sa
          // che l'export era fallito.
          document.delete()
          throw if (it is ExportFailure) it else ExportFailure("la scrittura si e' interrotta", it)
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
  ): ExportResult {
    val directory = File(files.exports, name)
    directory.deleteRecursively()
    val written = runCatching { writer.writeLoose(set, options, directory, onProgress) }.getOrElse {
      directory.deleteRecursively()
      throw ExportFailure("non sono riuscito a scrivere i file", it)
    }
    val size = written.sumOf { it.length() }

    return when (destination) {
      is ExportDestination.Share ->
        ExportResult(Uri.fromFile(directory), name, size, "text/markdown", set.notes.size, file = directory, files = written, isDirectory = true)

      is ExportDestination.Folder -> {
        val tree = folderOf(destination)
        tree.findFile(name)?.delete()
        val folder = tree.createDirectory(name)
          ?: throw ExportFailure("non sono riuscito a creare la cartella")
        runCatching {
          written.forEach { file ->
            val mime = if (file.extension == "png") "image/png" else "text/markdown"
            val document = folder.createFile(mime, file.name)
              ?: throw ExportFailure("non sono riuscito a creare ${file.name}")
            val stream = context.contentResolver.openOutputStream(document.uri)
              ?: throw ExportFailure("la cartella scelta non accetta scritture")
            stream.use { out -> file.inputStream().use { it.copyTo(out) } }
          }
        }.getOrElse {
          folder.delete()
          throw if (it is ExportFailure) it else ExportFailure("la scrittura si e' interrotta", it)
        }
        directory.deleteRecursively()
        ExportResult(folder.uri, name, size, "text/markdown", set.notes.size, isDirectory = true)
      }
    }
  }

  private fun folderOf(destination: ExportDestination.Folder): DocumentFile {
    val tree = DocumentFile.fromTreeUri(context, destination.treeUri)
      ?: throw ExportFailure("la cartella scelta non e' piu' raggiungibile")
    if (!tree.canWrite()) throw ExportFailure("non ho il permesso di scrivere in quella cartella")
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
    // Le pagine scritte a mano sono contenuto, non provenienza: vanno negli appunti, sempre. Solo
    // quelle che stanno qui: un collegamento a un'immagine che non c'e' e' peggio di niente.
    val pages = allSources.filter { it.derivedFromId != null && it.storedFileName?.let { name -> files.sourceFile(name).exists() } == true }
      .sortedWith(compareBy({ it.importedAt }, { it.originalName }))
    return ExportNote(
      note = note,
      folderPath = folderPath,
      tags = tags.tags(note.id),
      handwriting = pages.mapIndexed { index, page -> ExportImage(page.storedFileName!!, index + 1, page.sizeBytes) },
      sources = allSources.filter { it.derivedFromId == null }.map { source ->
        ExportSource(
          originalName = source.originalName,
          kind = source.kind,
          sha256 = source.sha256,
          sizeBytes = source.sizeBytes,
          storedFileName = source.storedFileName,
          status = source.status,
          extractedChars = source.extractedChars,
        )
      },
      sessions = gatheredSessions,
    )
  }

  /** Una cartella e tutte quelle che contiene, a qualunque profondita'. */
  private fun descendants(rootId: String, all: List<FolderEntity>): Set<String> {
    val byParent = all.groupBy { it.parentId }
    val result = mutableSetOf<String>()
    val queue = ArrayDeque(listOf(rootId))
    while (queue.isNotEmpty()) {
      val id = queue.removeFirst()
      if (!result.add(id)) continue
      byParent[id]?.forEach { queue.addLast(it.id) }
    }
    return result
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
