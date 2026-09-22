package dev.pampa.pampanotes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.share.ShareInfo
import dev.pampa.pampanotes.core.share.ShareRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SharesUiState(
  val loading: Boolean = true,
  val configured: Boolean = true,
  val shares: List<ShareInfo> = emptyList(),
  val error: String? = null,
)

/**
 * Il pannello delle condivisioni: i link che si sono dati in giro, quanto pesano, quando sono
 * stati aperti, e la revoca. Il controllo e' sul server, per proprietario: qui si vede solo il
 * proprio.
 */
@HiltViewModel
class SharesViewModel @Inject constructor(
  private val repository: ShareRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(SharesUiState())
  val uiState: StateFlow<SharesUiState> = _uiState.asStateFlow()

  init {
    load()
  }

  fun load() {
    viewModelScope.launch {
      _uiState.update { it.copy(loading = true, error = null) }
      if (!repository.configured()) {
        _uiState.update { it.copy(loading = false, configured = false, shares = emptyList()) }
        return@launch
      }
      try {
        val shares = repository.list()
        _uiState.update { it.copy(loading = false, configured = true, shares = shares) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        _uiState.update { it.copy(loading = false, configured = true, error = error.message ?: error::class.java.simpleName) }
      }
    }
  }

  fun revoke(shareId: String) {
    viewModelScope.launch {
      try {
        repository.revoke(shareId)
        _uiState.update { s -> s.copy(shares = s.shares.filterNot { it.shareId == shareId }, error = null) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        _uiState.update { it.copy(error = error.message ?: error::class.java.simpleName) }
      }
    }
  }
}
