package dev.pampa.pampanotes.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.FolderDao
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.NoteRow
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SubjectMinutes
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.NoteRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Una nota recente insieme alla cartella da cui viene: la card mostra il colore della materia. */
data class RecentNote(
  val row: NoteRow,
  val folder: FolderEntity?,
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
)

data class HomeUiState(
  val recent: List<RecentNote> = emptyList(),
  val noteCount: Int = 0,
  val folderCount: Int = 0,
  val stats: HomeStats = HomeStats(),
  val activeJobs: Int = 0,
  val loading: Boolean = true,
) {
  val isEmpty: Boolean get() = !loading && recent.isEmpty()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
  private val notes: NoteRepository,
  private val folders: FolderRepository,
  jobs: JobDao,
  audioParts: AudioPartDao,
  transcripts: TranscriptDao,
  sessions: SessionDao,
  folderDao: FolderDao,
) : ViewModel() {

  private val stats = combine(
    audioParts.observeTotalDuration(),
    transcripts.observeWordTotal(),
    sessions.observeLessonDays(),
    folderDao.observeTopSubject(),
  ) { audioMs, words, days, top -> HomeStats(audioMs = audioMs, words = words, lessonDays = days, topSubject = top) }

  val uiState: StateFlow<HomeUiState> = combine(
    notes.observeRecent(12),
    notes.observeCount(),
    folders.observeAll(),
    jobs.observeActiveCount(),
    stats,
  ) { recent, noteCount, allFolders, activeJobs, stats ->
    val byId = allFolders.associateBy { it.id }
    HomeUiState(
      recent = recent.map { RecentNote(it, byId[it.note.folderId]) },
      noteCount = noteCount,
      folderCount = allFolders.size,
      stats = stats,
      activeJobs = activeJobs,
      loading = false,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

  fun togglePinned(noteId: String, pinned: Boolean) {
    viewModelScope.launch { notes.setPinned(noteId, pinned) }
  }

  fun deleteNote(noteId: String) {
    viewModelScope.launch { notes.delete(noteId) }
  }
}
