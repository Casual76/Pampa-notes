package dev.pampa.pampanotes.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.FolderDao
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.NoteRow
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SubjectMinutes
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.NoteRepository
import dev.pampa.pampanotes.core.repo.StatsRepository
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.LastListened
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.stats.TranscriptionStats
import dev.pampa.pampanotes.core.transcription.NoteTranscribingElsewhere
import dev.pampa.pampanotes.core.transcription.TranscribingMarker
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Una nota della home insieme alla cartella da cui viene — la riga prende il colore della materia —
 * e al lavoro in corso su una sua sessione, se c'e': il badge dice a che punto e'.
 */
data class RecentNote(
  val row: NoteRow,
  val folder: FolderEntity?,
  val job: JobEntity? = null,
  /** Lezioni della nota che un altro dispositivo sta trascrivendo: in corso, non da fare. */
  val elsewhere: NoteTranscribingElsewhere? = null,
) {
  /** Le sessioni senza trascrizione che nessuno sta trascrivendo, ne' qui ne' altrove. */
  val toTranscribe: Int get() = row.untranscribedSessions - (elsewhere?.untranscribed ?: 0)
}

/**
 * La scheda «Riprendi ad ascoltare»: la sessione ascoltata per ultima su questo dispositivo, e il
 * punto in cui ci si era fermati.
 */
data class ResumeCard(
  val sessionId: String,
  val noteTitle: String,
  val folderId: String,
  val sessionTitle: String,
  /** yyyy-MM-dd, come la sessione. */
  val sessionDate: String,
  val positionMs: Long,
  val durationMs: Long,
  /** Quando ci si e' fermati. */
  val at: Long,
  /** La materia, per il colore della riga: la mette la home, che ha gia' tutte le cartelle. */
  val folder: FolderEntity? = null,
)

/**
 * I numeri della scheda in cima alla home, contati su **tutto** l'archivio.
 *
 * Prima l'audio si sommava sulle dodici note recenti e diceva «63′» a chi aveva quattordici ore di
 * lezione: un numero che mente e' peggio di nessun numero. Questi vengono dal database con una
 * query ciascuno, e si aggiornano da soli.
 */
data class HomeStats(
  val audioMs: Long = 0,
  /** Le parole trascritte, solo delle grezze: una raffinata e' la stessa lezione detta di nuovo. */
  val words: Long = 0,
  /** In quanti giorni distinti si e' stati a lezione. */
  val lessonDays: Int = 0,
  val topSubject: SubjectMinutes? = null,
  /** Velocita', ritmo, record: la sezione «Le tue trascrizioni». Vedi [TranscriptionStats.aggregate]. */
  val transcription: TranscriptionStats = TranscriptionStats(),
)

data class HomeUiState(
  val recent: List<RecentNote> = emptyList(),
  /** «Da fare»: le prime [HomeViewModel.TODO_SHOWN] note con qualcosa da trascrivere o in corso. */
  val todo: List<RecentNote> = emptyList(),
  /** Quante sono in tutto, anche oltre quelle mostrate. */
  val todoCount: Int = 0,
  /** Le altre da fare, per «Mostra tutte»: prima oltre le prime cinque non c'era modo di vederle. */
  val todoRest: List<RecentNote> = emptyList(),
  /** C'e' almeno una sessione da trascrivere che non e' gia' in coda: «Trascrivi tutte» serve. */
  val canTranscribeAll: Boolean = false,
  val resume: ResumeCard? = null,
  val noteCount: Int = 0,
  val folderCount: Int = 0,
  val stats: HomeStats = HomeStats(),
  val activeJobs: Int = 0,
  val loading: Boolean = true,
) {
  val isEmpty: Boolean get() = !loading && recent.isEmpty() && todo.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
  private val notes: NoteRepository,
  private val folders: FolderRepository,
  private val noteDao: NoteDao,
  jobs: JobDao,
  audioParts: AudioPartDao,
  transcripts: TranscriptDao,
  private val sessions: SessionDao,
  folderDao: FolderDao,
  transcriptionStats: StatsRepository,
  private val transcription: TranscriptionRepository,
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
) : ViewModel() {

  private val stats = combine(
    audioParts.observeTotalDuration(),
    transcripts.observeWordTotal(),
    sessions.observeLessonDays(),
    folderDao.observeTopSubject(),
    transcriptionStats.observe(),
  ) { audioMs, words, days, top, transcription ->
    HomeStats(audioMs = audioMs, words = words, lessonDays = days, topSubject = top, transcription = transcription)
  }

  /** I lavori in corso, per nota: un lavoro guarda una sessione, la home ragiona per note. */
  private val jobsByNote: Flow<Map<String, List<JobEntity>>> = jobs.observeActive().map { active ->
    active.mapNotNull { job -> sessions.get(job.sessionId)?.noteId?.let { it to job } }
      .groupBy({ it.first }, { it.second })
  }

  /** I lavori di qui e quelli degli altri dispositivi, per nota: tutti e due fanno «in corso». */
  private val workByNote: Flow<Pair<Map<String, List<JobEntity>>, Map<String, NoteTranscribingElsewhere>>> = combine(
    jobsByNote,
    transcription.observeElsewhere().map { TranscribingMarker.byNote(it.values) },
  ) { jobs, elsewhere -> jobs to elsewhere }

  /** L'ultima sessione ascoltata, con quello che la scheda mostra; null se finita o cancellata. */
  private val resume: Flow<ResumeCard?> = settingsStore.lastListened.flatMapLatest { last ->
    if (last == null || last.finished) return@flatMapLatest flowOf(null)
    sessions.observe(last.sessionId).flatMapLatest { withParts ->
      val session = withParts?.session ?: return@flatMapLatest flowOf(null)
      val duration = withParts.durationMs.takeIf { it > 0 } ?: last.durationMs
      // Rifatta la sessione da quando la si ascoltava — una parte tolta — il punto puo' cadere oltre
      // la fine, o quasi: e' finita anche cosi'.
      if (duration > 0 && last.positionMs >= duration * LastListened.FINISHED_FRACTION) return@flatMapLatest flowOf(null)
      notes.observe(session.noteId).map { note ->
        note?.let {
          ResumeCard(
            sessionId = session.id,
            noteTitle = it.title,
            folderId = it.folderId,
            sessionTitle = session.title,
            sessionDate = session.date,
            positionMs = last.positionMs,
            durationMs = duration,
            at = last.at,
          )
        }
      }
    }
  }

  private val home = combine(
    notes.observeRecent(RECENT_FETCHED),
    noteDao.observeTodo(TODO_FETCHED),
    folders.observeAll(),
    workByNote,
    resume,
  ) { recent, todo, allFolders, work, resume ->
    val (byNote, elsewhere) = work
    val byId = allFolders.associateBy { it.id }
    fun wrap(row: NoteRow) = RecentNote(row, byId[row.note.folderId], byNote[row.note.id]?.firstOrNull(), elsewhere[row.note.id])
    val shownTodo = todo.take(TODO_SHOWN).map(::wrap)
    val shownIds = shownTodo.mapTo(mutableSetOf()) { it.row.note.id }
    HomeUiState(
      // Una nota che sta gia' in «Da fare» non si ripete sotto: sono le stesse righe, due volte.
      recent = recent.filterNot { it.note.id in shownIds }.take(RECENT_SHOWN).map(::wrap),
      todo = shownTodo,
      todoCount = todo.size,
      todoRest = todo.drop(TODO_SHOWN).take(TODO_EXPANDED - TODO_SHOWN).map(::wrap),
      canTranscribeAll = todo.any { row -> wrap(row).toTranscribe > byNote[row.note.id].orEmpty().size },
      resume = resume?.copy(folder = byId[resume.folderId]),
      folderCount = allFolders.size,
      loading = false,
    )
  }

  val uiState: StateFlow<HomeUiState> = combine(
    home,
    notes.observeCount(),
    jobs.observeActiveCount(),
    stats,
  ) { home, noteCount, activeJobs, stats ->
    home.copy(noteCount = noteCount, activeJobs = activeJobs, stats = stats)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

  fun togglePinned(noteId: String, pinned: Boolean) {
    viewModelScope.launch { notes.setPinned(noteId, pinned) }
  }

  fun deleteNote(noteId: String) {
    viewModelScope.launch { notes.delete(noteId) }
  }

  /**
   * «Trascrivi tutte»: in coda le sessioni senza trascrizione di tutte le note da fare, anche di
   * quelle oltre le cinque mostrate — il tasto dice «tutte». Col servizio delle impostazioni, come
   * la selezione nella cartella; una sessione gia' in coda non si accoda due volte.
   */
  fun transcribeAll() {
    viewModelScope.launch {
      val provider = settingsStore.current().transcriptionProvider
      var any = false
      noteDao.observeTodo(TODO_FETCHED).first().forEach { row ->
        sessions.byNote(row.note.id)
          .filter { it.parts.isNotEmpty() && it.session.activeTranscriptId == null }
          // Quelle che un altro dispositivo sta trascrivendo le salta `enqueue`.
          .forEach { if (transcription.enqueue(it.session.id, provider) != null) any = true }
      }
      if (any) scheduler.kick(provider.id)
    }
  }

  /** «Nascondi» sulla scheda: la sessione non si riprende piu' da qui. */
  fun dismissResume() {
    viewModelScope.launch { settingsStore.clearLastListened() }
  }

  companion object {
    const val RECENT_SHOWN = 20
    const val TODO_SHOWN = 5
    const val TODO_EXPANDED = 60

    /** Le recenti chieste in piu' di quelle mostrate: le note gia' in «Da fare» si tolgono. */
    private const val RECENT_FETCHED = RECENT_SHOWN + TODO_SHOWN
    private const val TODO_FETCHED = 200
  }
}
