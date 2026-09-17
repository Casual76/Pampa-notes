package dev.pampa.pampanotes.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.NoteRow
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

data class HomeUiState(
  val recent: List<RecentNote> = emptyList(),
  val noteCount: Int = 0,
  val folderCount: Int = 0,
  val audioMinutes: Int = 0,
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
) : ViewModel() {

  val uiState: StateFlow<HomeUiState> = combine(
    notes.observeRecent(12),
    notes.observeCount(),
    folders.observeAll(),
    jobs.observeActiveCount(),
  ) { recent, noteCount, allFolders, activeJobs ->
    val byId = allFolders.associateBy { it.id }
    HomeUiState(
      recent = recent.map { RecentNote(it, byId[it.note.folderId]) },
      noteCount = noteCount,
      folderCount = allFolders.size,
      audioMinutes = (recent.sumOf { it.audioDurationMs } / 60_000).toInt(),
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
