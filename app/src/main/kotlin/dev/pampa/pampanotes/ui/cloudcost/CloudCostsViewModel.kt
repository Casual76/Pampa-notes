package dev.pampa.pampanotes.ui.cloudcost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.spending.*
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudCostsUiState(
  val data: CloudCostData? = null,
  val settings: PampaSettings = PampaSettings(),
  val quota: KeyQuota? = null,
  val refreshing: Boolean = false,
  val saving: Boolean = false,
  val error: String? = null,
)

@HiltViewModel
class CloudCostsViewModel @Inject constructor(
  private val spending: CloudSpending,
  settings: PampaSettingsStore,
) : ViewModel() {
  private val mutable = MutableStateFlow(CloudCostsUiState())
  val state = mutable.asStateFlow()
  init {
    viewModelScope.launch {
      combine(spending.changes, settings.settings, spending.keyChanges) { _, prefs, _ -> prefs }.collect { prefs ->
        attempt { mutable.update { it.copy(data = spending.data(), settings = prefs, quota = spending.currentQuota()) } }
      }
    }
  }
  fun refresh(openRouterModel: String? = null) = viewModelScope.launch {
    mutable.update { it.copy(refreshing = true, error = null) }
    attempt { spending.refresh(openRouterModel) }
    mutable.update { it.copy(refreshing = false) }
  }
  fun save(settings: CloudCostSettings, onSaved: () -> Unit) = viewModelScope.launch {
    mutable.update { it.copy(saving = true, error = null) }
    attempt { spending.configure(settings); onSaved() }
    mutable.update { it.copy(saving = false) }
  }
  fun reconcile(id: String, amount: String) = viewModelScope.launch { attempt { spending.reconcile(id, amount) } }
  fun reset() = viewModelScope.launch { attempt { spending.resetConfirmedTotals() } }
  private suspend fun attempt(block: suspend () -> Unit) {
    try { block() } catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) { mutable.update { it.copy(error = if (error is TranscriptionError) error.code else "cloud_cost_storage") } }
  }
}
