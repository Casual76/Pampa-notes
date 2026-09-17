package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SearchDao
import dev.pampa.pampanotes.core.db.SessionDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dove e' stato trovato quello che si cercava. */
data class SearchResult(
  val note: NoteEntity,
  val folderPath: String,
  /** Il pezzo di testo intorno alla parola, con la parola fra parentesi quadre. */
  val noteSnippet: String?,
  val transcriptHits: List<TranscriptSnippet>,
) {
  val total: Int get() = (if (noteSnippet != null) 1 else 0) + transcriptHits.size
}

data class TranscriptSnippet(
  val transcriptId: String,
  val sessionId: String,
  val snippet: String,
)

@Singleton
class SearchRepository @Inject constructor(
  private val search: SearchDao,
  private val notes: NoteRepository,
  private val folders: FolderRepository,
  private val sessions: SessionDao,
) {

  /**
   * Cerca nelle note e nelle trascrizioni, e raggruppa per nota.
   *
   * Raggruppato apposta: una lezione di un'ora nomina "Kant" quaranta volte, e quaranta righe
   * identiche che portano tutte allo stesso posto non sono quaranta risultati.
   */
  suspend fun search(query: String, limit: Int = 60): List<SearchResult> = withContext(Dispatchers.IO) {
    val prepared = prepareQuery(query) ?: return@withContext emptyList()

    val noteHits = runCatching { search.searchNotes(prepared, limit) }.getOrDefault(emptyList())
    val transcriptHits = runCatching { search.searchTranscripts(prepared, limit) }.getOrDefault(emptyList())

    val byNote = LinkedHashMap<String, MutableList<TranscriptSnippet>>()
    transcriptHits.forEach { hit ->
      byNote.getOrPut(hit.noteId) { mutableListOf() }
        .add(TranscriptSnippet(hit.transcriptId, hit.sessionId, hit.snippet))
    }

    val noteSnippets = noteHits.associate { it.noteId to it.snippet }
    val ids = (noteHits.map { it.noteId } + byNote.keys).distinct()
    val loaded = notes.getAll(ids).associateBy { it.id }
    val pathCache = mutableMapOf<String, String>()

    ids.mapNotNull { id ->
      val note = loaded[id] ?: return@mapNotNull null
      val path = pathCache.getOrPut(note.folderId) { folders.pathString(note.folderId) }
      SearchResult(
        note = note,
        folderPath = path,
        noteSnippet = noteSnippets[id],
        // Tre bastano a far capire di cosa si parla; il resto si legge aprendo.
        transcriptHits = byNote[id]?.take(3).orEmpty(),
      )
    }.sortedByDescending { it.note.updatedAt }
  }

  suspend fun rebuildIndex() = withContext(Dispatchers.IO) { search.rebuild() }

  companion object {
    /**
     * Da quello che si scrive a quello che FTS4 capisce.
     *
     * Ogni parola diventa un prefisso (`kant*`), cosi' la ricerca trova qualcosa mentre si scrive
     * invece che solo a parola finita. I caratteri che in FTS4 hanno un significato — virgolette,
     * asterischi, due punti, parentesi, il `-` di esclusione — si tolgono: scritti per sbaglio
     * fanno fallire l'intera query, e in una barra di ricerca capita di continuo.
     */
    fun prepareQuery(raw: String): String? {
      val cleaned = raw.replace(Regex("""["*:()^\-]"""), " ").trim()
      if (cleaned.isBlank()) return null
      val terms = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
      if (terms.isEmpty()) return null
      return terms.joinToString(" ") { "\"$it\"*" }
    }
  }
}
