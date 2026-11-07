package dev.pampa.pampanotes.ui.cloudcost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.spending.*
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FolderCloudUiState(
  val folderId: String? = null,
  val setting: FolderCloudSetting = FolderCloudSetting(),
  val effective: EffectiveCloudPolicy? = null,
  val error: Boolean = false,
)

@HiltViewModel
class FolderCloudViewModel @Inject constructor(
  private val spending: CloudSpending,
) : ViewModel() {
  private val mutable = MutableStateFlow(FolderCloudUiState())
  val state = mutable.asStateFlow()
  private var observing: Job? = null
  fun load(folderId: String) {
    observing?.cancel()
    mutable.value = FolderCloudUiState(folderId = folderId)
    observing = viewModelScope.launch {
      spending.observeFolder(folderId).catch {
        mutable.update { it.copy(error = true) }
      }.collect { snapshot ->
        mutable.update { it.copy(setting = snapshot.setting, effective = snapshot.effective, error = false) }
      }
    }
  }
  fun save(folderId: String, setting: FolderCloudSetting, onSaved: () -> Unit) = viewModelScope.launch {
    try { spending.setFolder(folderId, setting); onSaved() }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { mutable.update { it.copy(error = true) } }
  }
}
