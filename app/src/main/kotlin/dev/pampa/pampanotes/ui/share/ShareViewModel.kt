package dev.pampa.pampanotes.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.share.ShareInfo
import dev.pampa.pampanotes.core.share.SharePlan
import dev.pampa.pampanotes.core.share.ShareProgress
import dev.pampa.pampanotes.core.share.ShareRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareUiState(
  val loading: Boolean = true,
  val plan: SharePlan? = null,
  val running: Boolean = false,
  val progress: ShareProgress? = null,
  /** Il link, appena creato o gia' vivo da prima. */
  val result: ShareInfo? = null,
  val error: String? = null,
)

/**
 * Il pannello «Condividi con un link» di una nota.
 *
 * Prima si guarda cosa comporta (quanto audio, se l'indice e' configurato, se un link c'e' gia'),
 * poi si fa. Il lavoro gira nel ViewModel e non in un worker: dura quanto dura un caricamento, e
 * chi chiude il pannello a meta' vuole davvero fermarlo.
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
  private val repository: ShareRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(ShareUiState())
  val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()
  private var running: Job? = null

  fun start(noteId: String) {
    _uiState.value = ShareUiState()
    viewModelScope.launch {
      val plan = runCatching { repository.plan(noteId) }.getOrElse { SharePlan(configured = false, parts = 0, bytes = 0, unavailable = 0) }
      _uiState.update { it.copy(loading = false, plan = plan, result = plan.existing) }
    }
  }

  fun share(noteId: String) {
    if (running?.isActive == true) return
    running = viewModelScope.launch {
      _uiState.update { it.copy(running = true, error = null, progress = null) }
      try {
        val info = repository.share(noteId) { p -> _uiState.update { it.copy(progress = p) } }
        _uiState.update { it.copy(running = false, result = info, progress = null) }
      } catch (cancelled: CancellationException) {
        _uiState.update { it.copy(running = false, progress = null) }
        throw cancelled
      } catch (error: Exception) {
        _uiState.update { it.copy(running = false, progress = null, error = error.message ?: error::class.java.simpleName) }
      }
    }
  }

  fun cancel() {
    running?.cancel()
  }

  fun revoke(noteId: String) {
    val shareId = _uiState.value.result?.shareId ?: return
    viewModelScope.launch {
      try {
        repository.revoke(shareId)
        _uiState.update { it.copy(result = null, error = null) }
        start(noteId)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        _uiState.update { it.copy(error = error.message ?: error::class.java.simpleName) }
      }
    }
  }
}
