package dev.pampa.pampanotes.ui.cloudcost

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.spending.*
import dev.pampa.pampanotes.ui.common.PageActions
import dev.pampa.pampanotes.ui.common.SheetBody
import dev.pampa.pampanotes.ui.common.jobErrorText
import java.math.BigDecimal
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** Compact entry: all detailed controls live in the existing modal host, without a new route. */
@Composable
fun CloudCostsEntry() {
  var open by remember { mutableStateOf(false) }
  FluidListGroup {
    FluidListRow(title = stringResource(R.string.cloud_cost_title), subtitle = stringResource(R.string.cloud_cost_entry_detail), onClick = { open = true })
  }
  if (open) CloudCostsSheet(onDismiss = { open = false })
}

@Composable
fun CloudCostsSheet(onDismiss: () -> Unit, viewModel: CloudCostsViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val data = state.data
  val saved = data?.settings ?: CloudCostSettings()
  var budgetOn by remember(saved) { mutableStateOf(saved.budgetUsd != null) }
  var budget by remember(saved) { mutableStateOf(saved.budgetUsd.orEmpty()) }
  var period by remember(saved) { mutableStateOf(saved.period) }
  var jobOn by remember(saved) { mutableStateOf(saved.perJobUsd != null) }
  var jobLimit by remember(saved) { mutableStateOf(saved.perJobUsd.orEmpty()) }
  var unknown by remember(saved) { mutableStateOf(saved.unknownAction) }
  var advanced by remember { mutableStateOf(false) }
  var reset by remember { mutableStateOf(false) }
  val provider = state.settings.transcriptionProvider.id
  val model = when (provider) {
    "mistral" -> state.settings.mistralTranscriptionModel
    "groq" -> state.settings.groqTranscriptionModel
    else -> state.settings.openRouterTranscriptionModel
  }
  val rateKey = "$provider/$model"
  var rate by remember(saved, rateKey) { mutableStateOf(saved.userRatesPerMinute[rateKey].orEmpty()) }
  val budgetValid = !budgetOn || Usd.parseInput(budget) != null
  val jobValid = !jobOn || Usd.parseInput(jobLimit) != null
  val rateValid = unknown != UnknownCostAction.USER_RATE || Usd.parseInput(rate) != null
  val uri = LocalUriHandler.current

  FluidGlassModalPortal(visible = true, onDismissRequest = onDismiss,
    presentation = FluidGlassModalPresentation.FullScreen, paneTitle = stringResource(R.string.cloud_cost_title),
    footer = {
      PageActions {
        FluidButton(text = stringResource(R.string.cloud_cost_save), fillWidth = true,
          enabled = data != null && budgetValid && jobValid && rateValid && !state.saving, loading = state.saving,
          onClick = {
            val rates = if (unknown == UnknownCostAction.USER_RATE) saved.userRatesPerMinute + (rateKey to Usd.parseInput(rate)!!) else saved.userRatesPerMinute
            viewModel.save(saved.copy(budgetUsd = if (budgetOn) Usd.parseInput(budget) else null, period = period,
              perJobUsd = if (jobOn) Usd.parseInput(jobLimit) else null, unknownAction = unknown, userRatesPerMinute = rates), onDismiss)
          })
      }
    }) {
    SheetBody(scrollable = false) {
      FluidSectionFootnote(text = stringResource(R.string.cloud_cost_scope))
      state.error?.let { FluidInlineMessage(message = jobErrorText(it, null, "openrouter"), tone = FluidTone.Danger) }
      if (data == null) {
        FluidSectionFootnote(text = stringResource(R.string.cloud_cost_loading))
      } else {
        val totals = SpendingLedger.totals(data, System.currentTimeMillis())
        FluidListGroup {
          FluidListRow(title = stringResource(R.string.cloud_cost_actual), subtitle = "", meta = usd(totals.actualUsd))
          FluidListDivider()
          FluidListRow(title = stringResource(R.string.cloud_cost_reserved), meta = usd(totals.reservedUsd),
            subtitle = stringResource(R.string.cloud_cost_unknown_count, totals.unknownRequests))
          if (totals.availableUsd != null) {
            FluidListDivider()
            FluidListRow(title = stringResource(R.string.cloud_cost_available), subtitle = "", meta = usd(totals.availableUsd))
          }
        }
        FluidSectionFootnote(text = stringResource(R.string.cloud_cost_pending_detail))
        FluidSectionHeader(title = stringResource(R.string.cloud_cost_budget))
        FluidListGroup {
          FluidToggleRow(title = stringResource(R.string.cloud_cost_budget_enabled), checked = budgetOn, onCheckedChange = { budgetOn = it })
        }
        if (budgetOn) {
          UsdField(budget, { budget = it }, R.string.cloud_cost_budget_amount, budgetValid)
          FluidListGroup {
            BudgetPeriod.entries.forEachIndexed { index, choice ->
              if (index > 0) FluidListDivider()
              FluidChoiceRow(title = stringResource(if (choice == BudgetPeriod.DAILY) R.string.cloud_cost_daily else R.string.cloud_cost_monthly),
                selected = period == choice, onSelect = { period = choice })
            }
          }
          FluidSectionFootnote(text = stringResource(R.string.cloud_cost_zone, saved.zoneId))
        }
        FluidSectionHeader(title = stringResource(R.string.cloud_cost_price))
        Text(model.ifBlank { stringResource(R.string.cloud_cost_model_unselected) }, style = MaterialTheme.typography.titleMedium)
        PriceSummary(data.prices[model])
        FluidButton(text = stringResource(R.string.cloud_cost_refresh), onClick = { viewModel.refresh() },
          loading = state.refreshing, enabled = !state.refreshing, style = FluidButtonStyle.Tinted, fillWidth = true)
        FluidSectionHeader(title = stringResource(R.string.cloud_cost_key))
        val quota = state.quota
        if (quota == null) FluidSectionFootnote(text = stringResource(R.string.cloud_cost_key_unavailable)) else {
          FluidListGroup {
            FluidListRow(title = stringResource(R.string.cloud_cost_key_limit), subtitle = "", meta = usd(quota.limit))
            FluidListDivider()
            FluidListRow(title = stringResource(R.string.cloud_cost_key_remaining), subtitle = "", meta = usd(quota.remaining))
            FluidListDivider()
            FluidListRow(title = stringResource(R.string.cloud_cost_key_reset), subtitle = "", meta = quota.reset ?: stringResource(R.string.cloud_cost_not_available))
            FluidListDivider()
            FluidListRow(title = stringResource(R.string.cloud_cost_key_usage), subtitle = "", meta = usd(quota.usage))
            FluidListDivider()
            FluidListRow(title = stringResource(R.string.cloud_cost_key_daily), subtitle = "", meta = usd(quota.daily))
            FluidListDivider()
            FluidListRow(title = stringResource(R.string.cloud_cost_key_weekly), subtitle = "", meta = usd(quota.weekly))
            FluidListDivider()
            FluidListRow(title = stringResource(R.string.cloud_cost_key_monthly), subtitle = "", meta = usd(quota.monthly))
            if (quota.effectiveRemaining != null) {
              FluidListDivider()
              FluidListRow(title = stringResource(R.string.cloud_cost_effective_remaining), subtitle = "", meta = usd(quota.effectiveRemaining))
              FluidListDivider()
              FluidListRow(title = stringResource(R.string.cloud_cost_effective_limit), subtitle = "", meta = usd(quota.effectiveLimit))
              FluidListDivider()
              FluidListRow(title = stringResource(R.string.cloud_cost_key_reset), subtitle = quota.effectiveReset ?: stringResource(R.string.cloud_cost_not_available))
            }
          }
          FluidSectionFootnote(text = stringResource(R.string.cloud_cost_updated, dateTime(quota.fetchedAt)))
        }
        FluidButton(text = stringResource(R.string.cloud_cost_key_manage), onClick = { uri.openUri("https://openrouter.ai/settings/keys") },
          style = FluidButtonStyle.Plain, fillWidth = true)
        FluidSectionFootnote(text = stringResource(R.string.cloud_cost_limits_detail))
        FluidButton(text = stringResource(R.string.cloud_cost_advanced), onClick = { advanced = !advanced }, style = FluidButtonStyle.Plain, fillWidth = true)
        if (advanced) {
          FluidListGroup {
            FluidToggleRow(title = stringResource(R.string.cloud_cost_job_enabled), checked = jobOn, onCheckedChange = { jobOn = it })
          }
          if (jobOn) UsdField(jobLimit, { jobLimit = it }, R.string.cloud_cost_job_amount, jobValid)
          FluidSectionHeader(title = stringResource(R.string.cloud_cost_unknown_policy))
          FluidListGroup {
            UnknownCostAction.entries.forEachIndexed { index, action ->
              if (index > 0) FluidListDivider()
              FluidChoiceRow(title = stringResource(when (action) {
                UnknownCostAction.BLOCK -> R.string.cloud_cost_unknown_block
                UnknownCostAction.ALLOW -> R.string.cloud_cost_unknown_allow
                UnknownCostAction.USER_RATE -> R.string.cloud_cost_unknown_rate
              }), selected = unknown == action, onSelect = { unknown = action })
            }
          }
          FluidSectionFootnote(text = stringResource(R.string.cloud_cost_unknown_detail))
          if (unknown == UnknownCostAction.USER_RATE) {
            Text(model, style = MaterialTheme.typography.titleSmall)
            UsdField(rate, { rate = it }, R.string.cloud_cost_rate_amount, rateValid)
          }
          FluidSectionHeader(title = stringResource(R.string.cloud_cost_reconcile))
          FluidSectionFootnote(text = stringResource(R.string.cloud_cost_reconcile_detail))
          data.charges.filter { it.settledAt == null }.forEach { charge ->
            key(charge.id) { ReconcileRow(charge, viewModel::reconcile) }
          }
          FluidButton(text = stringResource(R.string.cloud_cost_reset), onClick = { reset = true }, style = FluidButtonStyle.Plain, fillWidth = true)
        }
      }
    }
  }
  if (reset) FluidAlert(onDismissRequest = { reset = false }, title = stringResource(R.string.cloud_cost_reset),
    message = stringResource(R.string.cloud_cost_reset_detail), actions = listOf(
      FluidAlertAction(label = stringResource(R.string.cloud_cost_confirm), onClick = { viewModel.reset(); reset = false }),
      FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { reset = false }),
    ))
}

@Composable private fun PriceSummary(price: ModelPrice?) {
  if (price == null) { FluidSectionFootnote(text = stringResource(R.string.cloud_cost_price_unknown)); return }
  val fresh = price.fresh(System.currentTimeMillis())
  val text = when {
    !fresh -> stringResource(R.string.cloud_cost_price_stale)
    !price.complete || price.unit == PriceUnit.UNKNOWN -> stringResource(R.string.cloud_cost_price_unknown)
    price.unit == PriceUnit.TOKEN -> stringResource(R.string.cloud_cost_price_tokens,
      usd(Usd.amount(price.minRate)?.multiply(BigDecimal(1_000_000))?.toPlainString()),
      usd(Usd.amount(price.maxRate)?.multiply(BigDecimal(1_000_000))?.toPlainString()),
      usd(Usd.amount(price.outputRate)?.multiply(BigDecimal(1_000_000))?.toPlainString()))
    else -> stringResource(R.string.cloud_cost_price_duration,
      usd(Usd.amount(price.minRate)?.multiply(BigDecimal(60))?.toPlainString()),
      usd(Usd.amount(price.maxRate)?.multiply(BigDecimal(60))?.toPlainString()))
  }
  FluidSectionFootnote(text = text)
  FluidSectionFootnote(text = stringResource(R.string.cloud_cost_updated, dateTime(price.fetchedAt)))
}

@Composable private fun ReconcileRow(charge: CloudCharge, onReconcile: (String, String) -> Unit) {
  var amount by remember { mutableStateOf("") }
  var confirm by remember { mutableStateOf(false) }
  Text("${charge.provider} · ${charge.model}", style = MaterialTheme.typography.titleSmall)
  FluidSectionFootnote(text = dateTime(charge.createdAt))
  UsdField(amount, { amount = it }, R.string.cloud_cost_reconcile_amount, amount.isBlank() || Usd.parseInput(amount) != null)
  FluidButton(text = stringResource(R.string.cloud_cost_reconcile_confirm), onClick = { confirm = true },
    enabled = Usd.parseInput(amount) != null, style = FluidButtonStyle.Tinted, fillWidth = true)
  if (confirm) FluidAlert(onDismissRequest = { confirm = false }, title = stringResource(R.string.cloud_cost_reconcile_confirm),
    message = stringResource(R.string.cloud_cost_reconcile_warning, usd(Usd.parseInput(amount))), actions = listOf(
      FluidAlertAction(label = stringResource(R.string.cloud_cost_confirm), onClick = { onReconcile(charge.id, amount); confirm = false }),
      FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { confirm = false }),
    ))
}

@Composable internal fun UsdField(value: String, onChange: (String) -> Unit, label: Int, valid: Boolean) {
  FluidTextField(value = value, onValueChange = onChange, label = stringResource(label),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    supportingText = stringResource(if (valid) R.string.cloud_cost_decimal_hint else R.string.cloud_cost_invalid_amount),
    modifier = Modifier.fillMaxWidth())
}

@Composable internal fun usd(value: String?): String {
  val amount = value?.let { runCatching { BigDecimal(it) }.getOrNull() }
    ?: return stringResource(R.string.cloud_cost_not_available)
  val format = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).apply { minimumFractionDigits = 2; maximumFractionDigits = 6 }
  return stringResource(R.string.cloud_cost_usd, format.format(amount))
}

@Composable private fun dateTime(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
  LocalConfiguration.current.locales[0]).format(Date(time))

@Composable
fun FolderCloudSheet(folderId: String, onDismiss: () -> Unit, viewModel: FolderCloudViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(folderId) { viewModel.load(folderId) }
  var rule by remember(folderId, state.setting) { mutableStateOf(state.setting.rule) }
  var model by remember(folderId, state.setting) { mutableStateOf(state.setting.openRouterModel.orEmpty()) }
  FluidGlassModalPortal(visible = true, onDismissRequest = onDismiss, presentation = FluidGlassModalPresentation.FullScreen,
    paneTitle = stringResource(R.string.cloud_folder_title), footer = {
      PageActions { FluidButton(text = stringResource(R.string.cloud_cost_save), fillWidth = true,
        enabled = state.effective != null && !state.error,
        onClick = { viewModel.save(folderId, FolderCloudSetting(rule, model), onDismiss) }) }
    }) {
    SheetBody(scrollable = false) {
      FluidSectionFootnote(text = stringResource(R.string.cloud_folder_scope))
      if (state.error) FluidInlineMessage(message = stringResource(R.string.cloud_cost_storage_error), tone = FluidTone.Danger)
      state.effective?.let { effective ->
        FluidSectionFootnote(text = when {
          effective.reason == "companion_only" -> stringResource(R.string.cloud_folder_global_block)
          effective.sourceName != null -> stringResource(if (effective.allowed) R.string.cloud_folder_effective_allow else R.string.cloud_folder_effective_deny, effective.sourceName.orEmpty())
          effective.allowed -> stringResource(R.string.cloud_folder_effective_default)
          else -> stringResource(R.string.cloud_folder_broken)
        })
        if (effective.model != null) FluidSectionFootnote(text = stringResource(R.string.cloud_folder_effective_model, effective.model.orEmpty(), effective.modelSourceName.orEmpty()))
      }
      FluidListGroup {
        CloudFolderRule.entries.forEachIndexed { index, choice ->
          if (index > 0) FluidListDivider()
          FluidChoiceRow(title = stringResource(when (choice) {
            CloudFolderRule.INHERIT -> R.string.cloud_folder_inherit
            CloudFolderRule.ALLOW -> R.string.cloud_folder_allow
            CloudFolderRule.DENY -> R.string.cloud_folder_deny
          }), selected = rule == choice, onSelect = { rule = choice })
        }
      }
      FluidTextField(value = model, onValueChange = { model = it }, label = stringResource(R.string.cloud_folder_model),
        supportingText = stringResource(R.string.cloud_folder_model_hint), modifier = Modifier.fillMaxWidth())
      FluidSectionFootnote(text = stringResource(R.string.cloud_folder_inflight))
    }
  }
}
