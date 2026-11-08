package dev.pampa.pampanotes.ui.cloudcost

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.spending.CloudPreflight
import dev.pampa.pampanotes.core.spending.CloudSpending
import dev.pampa.pampanotes.ui.common.jobErrorText
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class CloudPreflightUiState(val items: List<CloudPreflight> = emptyList(), val loading: Boolean = true, val failed: Boolean = false) {
  val cloudItems: List<CloudPreflight> get() = items.filter { it.provider != "custom" }
  val predictedMaxUsd: String? get() {
    val estimates = cloudItems.map { it.estimate.maxUsd }
    return if (estimates.isEmpty() || estimates.any { it == null }) null else
      estimates.fold(java.math.BigDecimal.ZERO) { sum, it -> sum + java.math.BigDecimal(it) }.toPlainString()
  }
  val availableUsd: String? get() = cloudItems.mapNotNull { it.totals.availableUsd?.toBigDecimalOrNull() }.minOrNull()?.toPlainString()
  val batchOverBudget: Boolean get() = predictedMaxUsd?.toBigDecimalOrNull()?.let { max ->
    availableUsd?.toBigDecimalOrNull()?.let { max > it }
  } == true
  val blocked: Boolean get() = items.any { it.blockedReason != null } || batchOverBudget
}

/** A single API supports session buttons and selected/bulk session IDs. No paid network calls. */
@HiltViewModel
class CloudPreflightViewModel @Inject constructor(private val spending: CloudSpending) : ViewModel() {
  private val mutable = MutableStateFlow(CloudPreflightUiState())
  val state = mutable.asStateFlow()
  private val watchers = mutableListOf<Job>()
  fun observe(sessionIds: List<String>, provider: String? = null, allowExplicitCloud: Boolean = false, modelOverride: String? = null) {
    watchers.forEach { it.cancel() }; watchers.clear()
    mutable.value = CloudPreflightUiState()
    val results = mutableMapOf<String, CloudPreflight>()
    val failures = mutableSetOf<String>()
    val refreshedModels = mutableSetOf<String>()
    val ids = sessionIds.distinct()
    ids.forEach { id -> watchers += viewModelScope.launch {
      spending.observePreflight(id, provider, allowExplicitCloud, modelOverride).catch {
        failures += id
        mutable.value = mutable.value.copy(loading = results.size + failures.size < ids.size, failed = true)
      }.collect { result ->
        if (result.provider == "openrouter" && result.estimate.maxUsd == null && refreshedModels.add(result.model)) {
          viewModelScope.launch {
            try { spending.refresh(result.model) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
          }
        }
        results[id] = result
        mutable.value = CloudPreflightUiState(items = ids.mapNotNull { results[it] }, loading = results.size + failures.size < ids.size, failed = failures.isNotEmpty())
      }
    } }
    if (ids.isEmpty()) mutable.value = CloudPreflightUiState(loading = false)
  }
}

@Composable
fun CloudPreflightSummary(sessionIds: List<String>, provider: String? = null, allowExplicitCloud: Boolean = false, viewModel: CloudPreflightViewModel = hiltViewModel(), modelOverride: String? = null) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(sessionIds, provider, allowExplicitCloud, modelOverride) { viewModel.observe(sessionIds, provider, allowExplicitCloud, modelOverride) }
  CloudPreflightSummary(state)
}

@Composable
fun CloudPreflightSummary(state: CloudPreflightUiState) {
  if (state.items.all { it.provider == "custom" } && !state.loading && !state.failed) return
  FluidSectionHeader(title = stringResource(R.string.cloud_preflight_title))
  if (state.loading) FluidSectionFootnote(text = stringResource(R.string.cloud_cost_loading))
  if (state.failed) FluidSectionFootnote(text = stringResource(R.string.cloud_preflight_unavailable))
  if (state.cloudItems.size > 1) FluidSectionFootnote(text = stringResource(R.string.cloud_preflight_batch,
    state.cloudItems.size, state.predictedMaxUsd?.let { usd(it) } ?: stringResource(R.string.cloud_preflight_unknown)))
  state.cloudItems.take(3).forEach { item ->
    FluidSectionFootnote(text = if (item.durationMs > 0)
      stringResource(R.string.cloud_preflight_duration, item.model, (item.durationMs + 59_999) / 60_000)
      else stringResource(R.string.cloud_preflight_unknown_duration, item.model))
    val estimate = item.estimate
    FluidSectionFootnote(text = when {
      estimate.userEstimated -> stringResource(R.string.cloud_preflight_user_estimate, usd(estimate.maxUsd))
      estimate.maxUsd != null -> stringResource(R.string.cloud_preflight_estimate, usd(estimate.minUsd), usd(estimate.maxUsd))
      else -> stringResource(R.string.cloud_preflight_unknown)
    })
  }
  state.availableUsd?.let { FluidSectionFootnote(text = "${stringResource(R.string.cloud_cost_available)}: ${usd(it)}") }
  state.cloudItems.mapNotNull { it.blockedReason }.distinct().forEach {
    FluidInlineMessage(message = jobErrorText(it, null), tone = FluidTone.Danger)
  }
  if (state.batchOverBudget) FluidInlineMessage(message = stringResource(R.string.cloud_error_budget), tone = FluidTone.Danger)
  FluidSectionFootnote(text = stringResource(R.string.cloud_preflight_detail))
}
