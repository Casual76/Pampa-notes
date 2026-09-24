package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SearchDao
import dev.pampa.pampanotes.core.db.SegmentDao
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.transcription.TranscriptParagraphs
import dev.pampa.pampanotes.core.transcription.TranscriptSearch
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
  /** Titolo e giorno della sessione: con piu' lezioni nella stessa nota, dicono quale. */
  val sessionTitle: String = "",
  val sessionDate: String = "",
)

@Singleton
class SearchRepository @Inject constructor(
  private val search: SearchDao,
  private val notes: NoteRepository,
  private val folders: FolderRepository,
  private val sessions: SessionDao,
  private val transcripts: TranscriptDao,
  private val segments: SegmentDao,
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

    // Una sessione una volta sola: la grezza e la sua raffinata dicono la stessa cosa, e il tocco
    // porta comunque alla stessa registrazione, nello stesso momento.
    val sessionHits = transcriptHits.distinctBy { it.sessionId }
    val sessionRows = runCatching { sessions.getAll(sessionHits.map { it.sessionId }.take(MAX_SQL_ARGS)) }
      .getOrDefault(emptyList())
      .associateBy { it.id }
    val byNote = LinkedHashMap<String, MutableList<TranscriptSnippet>>()
    sessionHits.forEach { hit ->
      val session = sessionRows[hit.sessionId]
      byNote.getOrPut(hit.noteId) { mutableListOf() }
        .add(TranscriptSnippet(hit.transcriptId, hit.sessionId, hit.snippet, session?.title.orEmpty(), session?.date.orEmpty()))
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

  /**
   * «La ricerca che salta al minuto»: il momento in cui, nella registrazione [sessionId], si dice la
   * prima volta quello che si e' cercato. Null se la grezza non c'e' o non lo contiene (il risultato
   * veniva da una raffinata, o FTS4 ha trovato le parole separate da segni che qui contano).
   *
   * Si chiede solo per i risultati che si vedono, e legge il meno possibile: i segmenti senza le
   * parole coi tempi ([SegmentDao.byTranscriptWithoutWords]), e le parole del solo segmento trovato.
   * Gli stessi paragrafi e lo stesso confronto della ricerca dentro la sessione
   * ([TranscriptSearch.firstHit]), cosi' l'occorrenza da cui la sessione parte e' una delle sue.
   */
  suspend fun momentOf(sessionId: String, query: String): TranscriptSearch.Moment? = withContext(Dispatchers.IO) {
    val raw = transcripts.rawForSession(sessionId) ?: return@withContext null
    val light = segments.byTranscriptWithoutWords(raw.id)
    val paragraphs = TranscriptParagraphs.split(light, TranscriptParagraphs.MAX_SEGMENTS_ON_SCREEN)
    val hit = withContext(Dispatchers.Default) { TranscriptSearch.firstHit(paragraphs, query) } ?: return@withContext null
    val paragraph = paragraphs[hit.paragraph]
    val index = TranscriptSearch.segmentAt(paragraph, hit.offset)
    val segment = paragraph.segments[index]
    // Il tempo della parola, se il segmento ne ha; senza, l'inizio del segmento (come `timeOf`).
    val words = segments.wordsOf(segment.id)
    val complete = paragraph.copy(segments = paragraph.segments.toMutableList().also { it[index] = segment.copy(wordsJson = words) })
    TranscriptSearch.Moment(TranscriptSearch.timeOf(complete, hit.offset), hit.query)
  }

  suspend fun rebuildIndex() = withContext(Dispatchers.IO) { search.rebuild() }

  companion object {
    /** Il tetto degli argomenti di una query SQLite su Android: i risultati sono al massimo 60. */
    private const val MAX_SQL_ARGS = 900

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
