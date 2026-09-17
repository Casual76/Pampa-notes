package dev.pampa.pampanotes.ui.note

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SessionWithParts
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.NoteRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class NoteTab { TEXT, AUDIO, SOURCES }

data class NoteUiState(
  val note: NoteEntity? = null,
  val folderPath: String = "",
  val tags: List<String> = emptyList(),
  val sessions: List<SessionWithParts> = emptyList(),
  val sources: List<SourceEntity> = emptyList(),
  val loading: Boolean = true,
) {
  val audioDurationMs: Long get() = sessions.sumOf { it.durationMs }
  val partCount: Int get() = sessions.sumOf { it.parts.size }
}

@HiltViewModel
class NoteViewModel @Inject constructor(
  savedStateHandle: SavedStateHandle,
  private val notes: NoteRepository,
  private val folders: FolderRepository,
  sessions: SessionDao,
  sources: SourceDao,
) : ViewModel() {

  private val noteId: String = savedStateHandle.get<String>("noteId").orEmpty()
  private val folderPath = MutableStateFlow("")

  val uiState: StateFlow<NoteUiState> = combine(
    notes.observe(noteId),
    notes.observeTags(noteId),
    sessions.observeByNote(noteId),
    sources.observeByNote(noteId),
    folderPath,
  ) { note, tags, sessionList, sourceList, path ->
    NoteUiState(
      note = note,
      folderPath = path,
      tags = tags,
      sessions = sessionList,
      sources = sourceList,
      loading = false,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteUiState())

  init {
    viewModelScope.launch {
      notes.get(noteId)?.let { folderPath.value = folders.pathString(it.folderId) }
    }
  }

  fun rename(title: String) = viewModelScope.launch { notes.setTitle(noteId, title) }

  fun setTags(values: List<String>) = viewModelScope.launch { notes.setTags(noteId, values) }

  fun togglePinned() = viewModelScope.launch {
    val current = notes.get(noteId) ?: return@launch
    notes.setPinned(noteId, !current.pinned)
  }

  fun delete(onDone: () -> Unit) = viewModelScope.launch {
    notes.delete(noteId)
    onDone()
  }
}
