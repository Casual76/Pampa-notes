package dev.pampa.pampanotes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tirare giù una lista = un giro di sincronizzazione, lo stesso di «Sincronizza adesso».
 *
 * Il gesto e l'indicatore sono quelli di `FluidScreen` (`isRefreshing`/`onRefresh`): il bordo
 * elastico e' uno solo, e la rotellina sta nello spazio che il bordo apre. Qui c'e' solo cosa fare:
 * il giro parte col `REPLACE` di [WorkScheduler.syncNow] — un tocco, anche su un giro fallito che
 * aspetta il suo tentativo — e la rotellina resta finche' il [dev.pampa.pampanotes.work.SyncWorker]
 * non ha finito, o al piu' [MAX_WAIT_MS]: senza rete il lavoro resta in attesa, e una rotellina che
 * non si ferma mai dice «bloccato», non «aspetto la rete».
 *
 * Senza sincronizzazione il gesto non c'e' ([PullToSync.onRefresh] e' null): tirare e vedere una
 * rotellina che non fa niente sarebbe peggio di una lista che non si tira.
 */
@HiltViewModel
class PullToSyncViewModel @Inject constructor(
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
) : ViewModel() {

  val available: StateFlow<Boolean> = settingsStore.settings
    .map { it.syncEnabled && it.syncServerUrl.isNotBlank() }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

  private val _refreshing = MutableStateFlow(false)
  val refreshing: StateFlow<Boolean> = _refreshing

  fun refresh() {
    if (_refreshing.value) return
    _refreshing.value = true
    viewModelScope.launch {
      try {
        if (!settingsStore.current().syncEnabled) return@launch
        val id = scheduler.syncNow(force = true)
        withTimeoutOrNull(MAX_WAIT_MS) {
          // Finito, oppure tornato in attesa dopo un tentativo andato male (rete, server): il giro
          // riprovera' da se', ma chi ha tirato ha gia' la sua risposta.
          scheduler.observeWork(id).first { info ->
            info == null || info.state.isFinished || (info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount > 0)
          }
        }
      } finally {
        _refreshing.value = false
      }
    }
  }

  private companion object {
    const val MAX_WAIT_MS = 20_000L
  }
}

/** Quello che `FluidScreen` vuole per il suo tirare-per-aggiornare. */
data class PullToSync(val isRefreshing: Boolean, val onRefresh: (() -> Unit)?)

@Composable
fun rememberPullToSync(viewModel: PullToSyncViewModel = hiltViewModel()): PullToSync {
  val available by viewModel.available.collectAsStateWithLifecycle()
  val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
  return PullToSync(isRefreshing = refreshing, onRefresh = if (available) viewModel::refresh else null)
}
