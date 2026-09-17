package dev.pampa.pampanotes.core.importing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.Hashing
import dev.pampa.pampanotes.core.model.Ids
import dev.pampa.pampanotes.core.repo.NoteRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dove finisce quello che si importa. */
sealed interface ImportTarget {
  /** Una nota nuova dentro una cartella. Il titolo viene dal nome del file, se non lo si scrive. */
  data class NewNote(val folderId: String, val title: String) : ImportTarget

  /** Una nota che c'e' gia': il testo si aggiunge in fondo, i file si agganciano. */
  data class ExistingNote(val noteId: String) : ImportTarget
}

/** Com'e' andata, elemento per elemento. */
data class ImportOutcome(
  val noteId: String,
  val imported: List<ImportedItem>,
) {
  val failures: List<ImportedItem> get() = imported.filter { it.status == SourceStatus.FAILED }
  val partials: List<ImportedItem> get() = imported.filter { it.status == SourceStatus.PARTIAL }
}

data class ImportedItem(
  val candidateId: String,
  val displayName: String,
  val kind: SourceKind,
  val status: SourceStatus,
  val detail: String? = null,
  val charsAdded: Int = 0,
  /** L'id della fonte salvata; null quando l'import e' fallito prima di salvarla. */
  val sourceId: String? = null,
)

/**
 * Il passaggio fra "l'utente ha scelto dei file" e "la nota adesso contiene qualcosa".
 *
 * Diviso in due fasi apposta. [inspect] guarda i file e li copia in un posto nostro **subito**,
 * perche' l'URI di una condivisione vale finche' l'Activity che l'ha ricevuto e' viva, e il wizard
 * fa domande dopo. [importAll] scrive nel database quando le risposte ci sono.
 */
@Singleton
class ImportCoordinator @Inject constructor(
  @ApplicationContext private val context: Context,
  private val files: AppFiles,
  private val notes: NoteRepository,
  private val noteDao: NoteDao,
  private val sources: SourceDao,
  private val audioParts: AudioPartDao,
  private val sessions: SessionDao,
  private val extractors: TextExtractorRegistry,
  private val audioImporter: AudioImporter,
) {

  /**
   * Copia, riconosce e cerca i doppioni. Non tocca il database delle note.
   *
   * Un elemento che non si riesce nemmeno a leggere non finisce nella lista: se ne accorge chi
   * chiama, confrontando le dimensioni, e l'utente vede solo quello su cui puo' decidere.
   */
  suspend fun inspect(uris: List<Uri>): List<ImportCandidate> = withContext(Dispatchers.IO) {
    uris.mapNotNull { uri -> runCatching { inspectOne(uri) }.getOrNull() }
  }

  /** Il testo incollato o condiviso come testo: niente file, niente copia. */
  suspend fun inspectText(text: String, name: String): ImportCandidate = withContext(Dispatchers.Default) {
    val normalized = PlainTextExtractor.normalizeNewlines(text)
    val sha = Hashing.sha256(normalized)
    val duplicate = sources.findBySha(sha)
    ImportCandidate(
      id = Ids.newId(),
      uri = null,
      file = null,
      displayName = name,
      kind = SourceKind.CLIPBOARD,
      mime = "text/plain",
      sizeBytes = normalized.toByteArray().size.toLong(),
      sha256 = sha,
      inlineText = normalized,
      duplicateOfNoteId = duplicate?.noteId,
      duplicateOfNoteTitle = duplicate?.let { noteDao.get(it.noteId)?.title },
    )
  }

  private suspend fun inspectOne(uri: Uri): ImportCandidate {
    val displayName = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "importato"
    val declaredMime = context.contentResolver.getType(uri)

    val temp = files.tempFile(prefix = "import", suffix = ".part")
    val (sha, size) = context.contentResolver.openInputStream(uri)?.use { input ->
      Hashing.copyHashing(input, temp)
    } ?: throw IllegalStateException("non si apre: $uri")

    val head = temp.inputStream().use { input ->
      val buffer = ByteArray(512)
      val read = input.read(buffer)
      if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    }
    var kind = MimeSniffer.sniff(displayName, declaredMime, head)
    // Uno ZIP senza estensione parlante: si guarda dentro per distinguere Word da Samsung Notes.
    if (kind == SourceKind.SDOCX && !displayName.endsWith(".sdocx", ignoreCase = true) && !displayName.endsWith(".sdoc", ignoreCase = true)) {
      kind = runCatching { ArchiveSniffer.classify(zipEntryNames(temp)) }.getOrDefault(kind)
    }

    // Un doppione: un documento gia' importato, oppure — per l'audio, che non ha una fonte — una
    // parte con lo stesso contenuto, da cui si risale alla nota passando per la sua sessione.
    val duplicateNoteId = sources.findBySha(sha)?.noteId
      ?: audioParts.findBySha(sha)?.let { part -> sessions.get(part.sessionId)?.noteId }

    return ImportCandidate(
      id = Ids.newId(),
      uri = uri,
      file = temp,
      displayName = displayName,
      kind = kind,
      mime = MimeSniffer.mimeFor(kind, declaredMime),
      sizeBytes = size,
      sha256 = sha,
      duplicateOfNoteId = duplicateNoteId,
      duplicateOfNoteTitle = duplicateNoteId?.let { noteDao.get(it)?.title },
      durationMs = if (kind == SourceKind.AUDIO) audioImporter.probeDuration(temp) else 0,
    )
  }

  /**
   * Scrive davvero.
   *
   * @param audioPlacement dove vanno gli audio: nella sessione che c'e' gia' o in una nuova.
   */
  suspend fun importAll(
    candidates: List<ImportCandidate>,
    target: ImportTarget,
    audioPlacement: AudioPlacement = AudioPlacement.NewSession(),
    onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
  ): ImportOutcome = withContext(Dispatchers.IO) {
    val noteId = when (target) {
      is ImportTarget.ExistingNote -> target.noteId
      is ImportTarget.NewNote -> notes.create(folderId = target.folderId, title = target.title).id
    }

    val results = mutableListOf<ImportedItem>()
    val audio = candidates.filter { it.isAudio }
    val documents = candidates.filterNot { it.isAudio }

    documents.forEachIndexed { index, candidate ->
      onProgress(index, candidates.size, candidate.displayName)
      results += importDocument(candidate, noteId)
    }

    if (audio.isNotEmpty()) {
      onProgress(documents.size, candidates.size, audio.first().displayName)
      results += audioImporter.importAll(audio, noteId, audioPlacement)
    }

    onProgress(candidates.size, candidates.size, "")
    notes.touch(noteId)
    ImportOutcome(noteId = noteId, imported = results)
  }

  private suspend fun importDocument(candidate: ImportCandidate, noteId: String): ImportedItem {
    val sourceId = Ids.newId()

    // Il testo incollato non ha un file da conservare: e' gia' tutto nel corpo della nota.
    if (candidate.inlineText != null) {
      notes.appendBody(noteId, candidate.inlineText)
      val source = SourceEntity(
        id = sourceId,
        noteId = noteId,
        kind = candidate.kind,
        originalName = candidate.displayName,
        mime = candidate.mime,
        sizeBytes = candidate.sizeBytes,
        sha256 = candidate.sha256,
        storedFileName = null,
        extractedChars = candidate.inlineText.length,
        status = SourceStatus.OK,
        importedAt = System.currentTimeMillis(),
      )
      sources.upsert(source)
      return ImportedItem(candidate.id, candidate.displayName, candidate.kind, SourceStatus.OK, charsAdded = candidate.inlineText.length, sourceId = sourceId)
    }

    val temp = candidate.file ?: return ImportedItem(candidate.id, candidate.displayName, candidate.kind, SourceStatus.FAILED, "File non disponibile")

    // L'originale si conserva: per riestrarlo dopo, e per metterlo nel bundle di export.
    val storedName = files.newSourceName(sourceId, candidate.displayName, candidate.mime)
    val stored = files.sourceFile(storedName)
    temp.copyTo(stored, overwrite = true)
    temp.delete()

    val extracted = extractors.forKind(candidate.kind)?.extract(stored, candidate.displayName)
    val text = extracted?.text.orEmpty()
    if (text.isNotBlank()) {
      notes.appendBody(noteId, formatForNote(candidate, text))
    }

    val status = extracted?.status ?: SourceStatus.PARTIAL
    val detail = extracted?.detail ?: if (extracted == null) "Tipo non ancora supportato: il file resta allegato" else null

    sources.upsert(
      SourceEntity(
        id = sourceId,
        noteId = noteId,
        kind = candidate.kind,
        originalName = candidate.displayName,
        mime = candidate.mime,
        sizeBytes = candidate.sizeBytes,
        sha256 = candidate.sha256,
        storedFileName = storedName,
        extractedChars = text.length,
        status = status,
        detail = detail,
        importedAt = System.currentTimeMillis(),
      ),
    )
    return ImportedItem(candidate.id, candidate.displayName, candidate.kind, status, detail, text.length, sourceId)
  }

  /**
   * Il testo estratto, con sopra da dove viene.
   *
   * Un intestazione invece di niente perche' una nota che mette insieme tre PDF senza dire dove
   * finisce l'uno e comincia l'altro e' esattamente il pasticcio che l'app dovrebbe evitare.
   */
  private fun formatForNote(candidate: ImportCandidate, text: String): String =
    "## ${candidate.displayName}\n\n${text.trim()}"

  private fun queryDisplayName(uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0) else null
    }
  }.getOrNull()

  private fun zipEntryNames(file: File): List<String> =
    java.util.zip.ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }
}

/** I lettori disponibili, per tipo. */
@Singleton
class TextExtractorRegistry @Inject constructor(
  plain: PlainTextExtractor,
  pdf: PdfTextExtractor,
) {
  private val byKind: Map<SourceKind, TextExtractor> = mapOf(
    SourceKind.TEXT to plain,
    SourceKind.MARKDOWN to plain,
    SourceKind.CLIPBOARD to plain,
    SourceKind.SHARE to plain,
    SourceKind.PDF to pdf,
  )

  fun forKind(kind: SourceKind): TextExtractor? = byKind[kind]
}
