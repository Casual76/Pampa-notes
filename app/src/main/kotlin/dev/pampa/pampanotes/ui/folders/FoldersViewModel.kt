package dev.pampa.pampanotes.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.FolderRow
import dev.pampa.pampanotes.core.repo.FolderRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FoldersUiState(
  val folders: List<FolderRow> = emptyList(),
  /**
   * Le cartelle di Registrazioni, sottocartelle comprese: la barra laterale accende la riga
   * Registrazioni quando e' aperta una di loro.
   */
  val personalFolderIds: Set<String> = emptySet(),
  val loading: Boolean = true,
) {
  val isEmpty: Boolean get() = !loading && folders.isEmpty()
}

/**
 * L'indice delle materie, le cartelle di primo livello: da qui si scende fino alla singola nota.
 *
 * Solo la scuola: le cartelle di Registrazioni stanno nella loro scheda, e una registrazione di un
 * viaggio in mezzo alle tessere di Storia e Filosofia sarebbe la materia che non c'e'. Lo stesso
 * elenco fa la barra laterale del tablet.
 */
@HiltViewModel
class FoldersViewModel @Inject constructor(
  private val folders: FolderRepository,
) : ViewModel() {

  val uiState: StateFlow<FoldersUiState> = combine(folders.observeChildren(null), folders.observeAll()) { rows, all ->
    FoldersUiState(
      folders = rows.filterNot { it.folder.isPersonal },
      personalFolderIds = dev.pampa.pampanotes.core.repo.PersonalScope.folderIds(all),
      loading = false,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoldersUiState())

  fun create(name: String, tone: String?, icon: String?) {
    viewModelScope.launch { folders.create(name = name, parentId = null, tone = tone, icon = icon) }
  }

  fun update(id: String, name: String, tone: String?, icon: String?) {
    viewModelScope.launch { folders.update(id, name, tone, icon) }
  }

  fun delete(id: String) {
    viewModelScope.launch { folders.delete(id) }
  }

  /** «Sposta in Registrazioni»: la materia esce dalle tessere e va nella sua scheda, con tutto dentro. */
  fun moveToRecordings(id: String) {
    viewModelScope.launch { folders.setKind(id, FolderEntity.KIND_PERSONAL) }
  }
}
