package dev.pampa.pampanotes.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.FolderRow
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

data class HomeUiState(
  val folders: List<FolderRow> = emptyList(),
  val recentNotes: List<NoteRow> = emptyList(),
  val noteCount: Int = 0,
  val folderCount: Int = 0,
  val activeJobs: Int = 0,
  val loading: Boolean = true,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
  private val folders: FolderRepository,
  private val notes: NoteRepository,
  jobs: JobDao,
) : ViewModel() {

  val uiState: StateFlow<HomeUiState> = combine(
    folders.observeChildren(null),
    notes.observeRecent(6),
    notes.observeCount(),
    folders.observeAll(),
    jobs.observeActiveCount(),
  ) { rootFolders, recent, noteCount, allFolders, activeJobs ->
    HomeUiState(
      folders = rootFolders,
      recentNotes = recent,
      noteCount = noteCount,
      folderCount = allFolders.size,
      activeJobs = activeJobs,
      loading = false,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

  fun createFolder(name: String, tone: String?) {
    viewModelScope.launch { folders.create(name = name, parentId = null, tone = tone) }
  }

  fun renameFolder(id: String, name: String) {
    viewModelScope.launch { folders.rename(id, name) }
  }

  fun deleteFolder(id: String) {
    viewModelScope.launch { folders.delete(id) }
  }
}
