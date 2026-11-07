package dev.pampa.pampanotes.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChoiceRow
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.transcription.OpenRouterTranscriptionProvider
import dev.pampa.pampanotes.ui.common.jobErrorText

/** Same setup on first launch and in Services; key drafts never enter saved instance state. */
internal fun LazyListScope.openRouterSettings(
  settings: PampaSettings,
  services: ServicesUiState,
  key: String,
  onKeyChange: (String) -> Unit,
  onSave: () -> Unit,
  viewModel: SettingsViewModel,
) {
  item { FluidSectionHeader(title = stringResource(R.string.provider_openrouter)) }
  item { FluidSectionFootnote(text = stringResource(R.string.openrouter_requirements)) }
  item {
    FluidTextField(
      value = key, onValueChange = onKeyChange,
      label = stringResource(R.string.openrouter_key), secret = true,
      supportingText = stringResource(if (services.openRouterKeyPresent) R.string.openrouter_key_saved else R.string.openrouter_key_hint),
      modifier = Modifier.fillMaxWidth(),
    )
  }
  item {
    FluidButton(
      text = stringResource(R.string.settings_verify), onClick = onSave,
      loading = services.openRouterCheck is CheckState.Running,
      enabled = key.isNotBlank() && services.openRouterCheck !is CheckState.Running,
      style = FluidButtonStyle.Tinted, fillWidth = true,
    )
  }
  if (services.openRouterKeyPresent) {
    item {
      FluidButton(
        text = stringResource(R.string.openrouter_refresh), onClick = viewModel::loadOpenRouterModels,
        enabled = services.openRouterCheck !is CheckState.Running,
        style = FluidButtonStyle.Plain, fillWidth = true,
      )
    }
    item {
      FluidButton(
        text = stringResource(R.string.openrouter_remove_key), onClick = viewModel::clearOpenRouterKey,
        style = FluidButtonStyle.Plain, fillWidth = true,
      )
    }
  }
  (services.openRouterCheck as? CheckState.Failed)?.let { failed ->
    item {
      FluidInlineMessage(
        title = stringResource(R.string.provider_openrouter),
        message = jobErrorText(failed.reason, null, OpenRouterTranscriptionProvider.ID), tone = FluidTone.Danger,
      )
    }
  }
  if (services.openRouterCheck is CheckState.Running) {
    item { FluidSectionFootnote(text = stringResource(R.string.openrouter_loading)) }
  }
  if (services.openRouterModels.isNotEmpty()) {
    item { FluidSectionHeader(title = stringResource(R.string.openrouter_model)) }
    item {
      FluidListGroup {
        services.openRouterModels.forEach { model ->
          FluidChoiceRow(
            title = model, selected = model == settings.openRouterTranscriptionModel,
            onSelect = { viewModel.setOpenRouterModel(model) },
          )
        }
      }
    }
  }
  if (settings.openRouterTranscriptionModel.isNotBlank()) {
    item { FluidSectionFootnote(text = stringResource(R.string.openrouter_selected, settings.openRouterTranscriptionModel)) }
  }
  item { FluidSectionFootnote(text = stringResource(R.string.openrouter_timing_detail)) }
}
