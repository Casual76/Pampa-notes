package dev.pampa.pampanotes.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChoiceRow
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.transcription.MistralTranscriptionProvider
import dev.pampa.pampanotes.ui.common.jobErrorText

/** Key drafts are remember-only. Verification lists models, never sends audio. */
internal fun LazyListScope.mistralSettings(settings: PampaSettings, services: ServicesUiState,
  key: String, onKeyChange: (String) -> Unit, onSave: () -> Unit, viewModel: SettingsViewModel) {
  item { FluidSectionHeader(title = stringResource(R.string.provider_mistral)) }
  item { FluidSectionFootnote(text = stringResource(R.string.mistral_requirements)) }
  item {
    FluidTextField(value = key, onValueChange = onKeyChange, label = stringResource(R.string.mistral_key), secret = true,
      supportingText = stringResource(if (services.mistralKeyPresent) R.string.mistral_key_saved else R.string.mistral_key_hint),
      modifier = Modifier.fillMaxWidth())
  }
  item {
    FluidButton(text = stringResource(R.string.settings_verify), onClick = onSave,
      enabled = key.isNotBlank() && services.mistralCheck !is CheckState.Running,
      loading = services.mistralCheck is CheckState.Running, style = FluidButtonStyle.Tinted, fillWidth = true)
  }
  if (services.mistralKeyPresent) item {
    FluidButton(text = stringResource(R.string.mistral_remove_key), onClick = viewModel::clearMistralKey,
      style = FluidButtonStyle.Plain, fillWidth = true)
  }
  (services.mistralCheck as? CheckState.Failed)?.let { failed -> item {
    FluidInlineMessage(title = stringResource(R.string.provider_mistral), message = jobErrorText(failed.reason, null, "mistral"), tone = FluidTone.Danger)
  } }
  if (services.mistralCheck is CheckState.Ok) item {
    FluidInlineMessage(message = stringResource(R.string.mistral_verified), tone = FluidTone.Success)
  }
  item { FluidSectionHeader(title = stringResource(R.string.mistral_model)) }
  item { FluidListGroup {
    MistralTranscriptionProvider.MODELS.forEach { model ->
      FluidChoiceRow(title = model, selected = model == settings.mistralTranscriptionModel,
        onSelect = { viewModel.setMistralModel(model) })
    }
  } }
  item { FluidSectionFootnote(text = stringResource(R.string.mistral_timing)) }
}
