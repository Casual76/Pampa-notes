package dev.pampa.pampanotes.ui.folder

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.FolderRow
import dev.pampa.pampanotes.core.db.NoteRow
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.NoteRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FolderUiState(
  val folder: FolderEntity? = null,
  val path: List<FolderEntity> = emptyList(),
  val subfolders: List<FolderRow> = emptyList(),
  val notes: List<NoteRow> = emptyList(),
  val query: String = "",
  val loading: Boolean = true,
) {
  val visibleNotes: List<NoteRow>
    get() = if (query.isBlank()) notes else notes.filter {
      it.note.title.contains(query, ignoreCase = true) || it.note.body.contains(query, ignoreCase = true)
    }
}

@HiltViewModel
class FolderViewModel @Inject constructor(
  savedStateHandle: SavedStateHandle,
  private val folders: FolderRepository,
  private val notes: NoteRepository,
) : ViewModel() {

  private val folderId: String = savedStateHandle.get<String>("folderId").orEmpty()
  private val query = MutableStateFlow("")
  private val path = MutableStateFlow<List<FolderEntity>>(emptyList())

  init {
    viewModelScope.launch { path.value = folders.pathTo(folderId) }
  }

  val uiState: StateFlow<FolderUiState> = combine(
    folders.observe(folderId),
    folders.observeChildren(folderId),
    notes.observeRows(folderId),
    query,
    path,
  ) { folder, subfolders, noteRows, currentQuery, currentPath ->
    FolderUiState(
      folder = folder,
      path = currentPath,
      subfolders = subfolders,
      notes = noteRows,
      query = currentQuery,
      loading = false,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderUiState())

  fun setQuery(value: String) {
    query.value = value
  }

  fun createSubfolder(name: String, tone: String?) {
    viewModelScope.launch { folders.create(name = name, parentId = folderId, tone = tone) }
  }

  fun createNote(title: String, onCreated: (String) -> Unit = {}) {
    viewModelScope.launch {
      val note = notes.create(folderId = folderId, title = title)
      onCreated(note.id)
    }
  }

  fun renameFolder(id: String, name: String) {
    viewModelScope.launch { folders.rename(id, name) }
  }

  fun deleteFolder(id: String) {
    viewModelScope.launch { folders.delete(id) }
  }

  fun deleteNote(id: String) {
    viewModelScope.launch { notes.delete(id) }
  }

  fun togglePinned(id: String, pinned: Boolean) {
    viewModelScope.launch { notes.setPinned(id, pinned) }
  }
}
