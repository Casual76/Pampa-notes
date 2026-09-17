package dev.pampa.pampanotes.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.FolderRow
import dev.pampa.pampanotes.core.repo.FolderRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FoldersUiState(
  val folders: List<FolderRow> = emptyList(),
  val loading: Boolean = true,
) {
  val isEmpty: Boolean get() = !loading && folders.isEmpty()
}

/** L'indice delle cartelle di primo livello: da qui si scende fino alla singola nota. */
@HiltViewModel
class FoldersViewModel @Inject constructor(
  private val folders: FolderRepository,
) : ViewModel() {

  val uiState: StateFlow<FoldersUiState> = folders.observeChildren(null)
    .map { FoldersUiState(folders = it, loading = false) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoldersUiState())

  fun create(name: String, tone: String?, icon: String?) {
    viewModelScope.launch { folders.create(name = name, parentId = null, tone = tone, icon = icon) }
  }

  fun update(id: String, name: String, tone: String?, icon: String?) {
    viewModelScope.launch { folders.update(id, name, tone, icon) }
  }

  fun delete(id: String) {
    viewModelScope.launch { folders.delete(id) }
  }
}
