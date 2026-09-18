package dev.pampa.pampanotes.ui.onboarding

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.ui.common.RowIcon
import dev.pampa.pampanotes.ui.settings.CheckState
import dev.pampa.pampanotes.ui.settings.ServicesUiState
import dev.pampa.pampanotes.ui.settings.SettingsViewModel
import dev.pampa.pampanotes.ui.common.jobErrorText

/**
 * Il primo avvio.
 *
 * Quattro passi, e nessuno e' obbligatorio tranne leggerli: si puo' arrivare in fondo senza
 * configurare niente, perche' un avvio che non lascia entrare finche' non gli si da' una chiave
 * API e' un avvio che si chiude. Quello che chiede lo chiede pero' **adesso**, che e' l'unico
 * momento in cui qualcuno e' disposto a scrivere l'indirizzo di un server.
 *
 * Non insegna l'interfaccia: dice cosa fa l'app, da dove arrivano gli appunti, chi li trascrive e
 * dove finisce il backup. Un tutorial dei gesti si dimentica prima di essere finito.
 */
@Composable
fun OnboardingRoute(
  onDone: () -> Unit,
  viewModel: SettingsViewModel = hiltViewModel(),
) {
  var step by remember { mutableIntStateOf(0) }
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val services by viewModel.services.collectAsStateWithLifecycle()
  val context = LocalContext.current

  var groqKey by remember { mutableStateOf("") }
  var endpointUrl by remember(settings.endpointUrl) { mutableStateOf(settings.endpointUrl) }
  var endpointToken by remember { mutableStateOf("") }

  val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
      }
      viewModel.setBackupFolder(uri)
    }
  }

  val last = 3
  FluidScreen(
    title = stringResource(titleOf(step)),
    subtitle = stringResource(R.string.onboarding_step, step + 1, last + 1),
    onBack = if (step > 0) ({ step-- }) else null,
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Glow),
  ) {
    when (step) {
      0 -> welcomeStep()
      1 -> importStep(settings.autoTranscribeOnImport, viewModel::setAutoTranscribe)
      2 -> providerStep(
        provider = settings.preferredProvider,
        onProvider = viewModel::setPreferredProvider,
        services = services,
        groqKey = groqKey,
        onGroqKeyChange = { groqKey = it },
        onVerifyGroq = {
          viewModel.saveGroqKey(groqKey)
          groqKey = ""
        },
        endpointUrl = endpointUrl,
        onEndpointUrlChange = { endpointUrl = it },
        endpointToken = endpointToken,
        onEndpointTokenChange = { endpointToken = it },
        onTestEndpoint = {
          viewModel.setEndpoint(endpointUrl, settings.endpointModel)
          if (endpointToken.isNotBlank()) viewModel.setEndpointToken(endpointToken)
          viewModel.testEndpoint(endpointUrl)
        },
      )

      else -> backupStep(
        folderChosen = settings.backupFolderUri.isNotBlank(),
        onPickFolder = { pickFolder.launch(null) },
      )
    }

    item {
      FluidButton(
        text = stringResource(if (step == last) R.string.onboarding_start else R.string.onboarding_next),
        onClick = { if (step == last) onDone() else step++ },
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    if (step < last) {
      item {
        FluidButton(
          text = stringResource(R.string.onboarding_skip),
          onClick = onDone,
          style = FluidButtonStyle.Plain,
          fillWidth = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

private fun titleOf(step: Int): Int = when (step) {
  0 -> R.string.onboarding_welcome_title
  1 -> R.string.onboarding_import_title
  2 -> R.string.onboarding_provider_title
  else -> R.string.onboarding_backup_title
}

private fun LazyListScope.welcomeStep() {
  item { FluidSectionFootnote(text = stringResource(R.string.onboarding_welcome_detail)) }
  item {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.onboarding_step_import),
        subtitle = stringResource(R.string.onboarding_step_import_detail),
        leading = { RowIcon(Icons.Rounded.Download, FluidTone.Success) },
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.onboarding_step_transcribe),
        subtitle = stringResource(R.string.onboarding_step_transcribe_detail),
        leading = { RowIcon(Icons.Rounded.GraphicEq, FluidTone.Info) },
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.onboarding_step_export),
        subtitle = stringResource(R.string.onboarding_step_export_detail),
        leading = { RowIcon(Icons.Rounded.CloudUpload, FluidTone.Primary) },
      )
    }
  }
  item { FluidSectionFootnote(text = stringResource(R.string.onboarding_not_assistant)) }
}

private fun LazyListScope.importStep(
  autoTranscribe: Boolean,
  onAutoTranscribe: (Boolean) -> Unit,
) {
  item { FluidSectionFootnote(text = stringResource(R.string.onboarding_import_detail)) }
  item {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.onboarding_import_samsung),
        subtitle = stringResource(R.string.onboarding_import_samsung_detail),
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.onboarding_import_share),
        subtitle = stringResource(R.string.onboarding_import_share_detail),
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.onboarding_import_picker),
        subtitle = stringResource(R.string.onboarding_import_picker_detail),
      )
    }
  }
  item {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.settings_auto_transcribe),
        subtitle = stringResource(R.string.settings_auto_transcribe_detail),
        badge = { FluidSwitch(checked = autoTranscribe, onCheckedChange = onAutoTranscribe) },
      )
    }
  }
}

private fun LazyListScope.providerStep(
  provider: TranscriptionProviderId,
  onProvider: (TranscriptionProviderId) -> Unit,
  services: ServicesUiState,
  groqKey: String,
  onGroqKeyChange: (String) -> Unit,
  onVerifyGroq: () -> Unit,
  endpointUrl: String,
  onEndpointUrlChange: (String) -> Unit,
  endpointToken: String,
  onEndpointTokenChange: (String) -> Unit,
  onTestEndpoint: () -> Unit,
) {
  item { FluidSectionFootnote(text = stringResource(R.string.onboarding_provider_detail)) }
  item {
    val labels = mapOf(
      TranscriptionProviderId.GROQ to stringResource(R.string.provider_groq),
      TranscriptionProviderId.CUSTOM to stringResource(R.string.provider_custom),
    )
    FluidSegmentedControl(
      options = TranscriptionProviderId.entries.toList(),
      selected = provider,
      onSelect = onProvider,
      label = { labels.getValue(it) },
    )
  }

  if (provider == TranscriptionProviderId.GROQ) {
    item {
      FluidTextField(
        value = groqKey,
        onValueChange = onGroqKeyChange,
        label = stringResource(R.string.settings_groq_key),
        placeholder = "gsk_…",
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        supportingText = stringResource(R.string.settings_groq_key_hint),
        modifier = Modifier.fillMaxWidth(),
      )
    }
    item {
      FluidButton(
        text = stringResource(R.string.settings_verify),
        onClick = onVerifyGroq,
        enabled = groqKey.isNotBlank() && services.groqCheck !is CheckState.Running,
        loading = services.groqCheck is CheckState.Running,
        style = FluidButtonStyle.Tinted,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    (services.groqCheck as? CheckState.Ok)?.let { ok ->
      item {
        FluidInlineMessage(
          message = stringResource(R.string.settings_groq_ok, ok.detail),
          title = stringResource(R.string.settings_groq),
          tone = FluidTone.Success,
        )
      }
    }
    (services.groqCheck as? CheckState.Failed)?.let { failed ->
      item {
        FluidInlineMessage(
          message = jobErrorText(failed.reason, null),
          title = stringResource(R.string.settings_groq),
          tone = FluidTone.Danger,
        )
      }
    }
  } else {
    item { FluidSectionFootnote(text = stringResource(R.string.onboarding_server_detail)) }
    item {
      FluidTextField(
        value = endpointUrl,
        onValueChange = onEndpointUrlChange,
        label = stringResource(R.string.settings_endpoint_url),
        placeholder = "192.168.1.10:8765",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
      )
    }
    item {
      FluidTextField(
        value = endpointToken,
        onValueChange = onEndpointTokenChange,
        label = stringResource(R.string.settings_endpoint_token),
        placeholder = stringResource(R.string.settings_endpoint_token_hint),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
      )
    }
    item {
      FluidButton(
        text = stringResource(R.string.settings_test_connection),
        onClick = onTestEndpoint,
        enabled = endpointUrl.isNotBlank() && services.endpointCheck !is CheckState.Running,
        loading = services.endpointCheck is CheckState.Running,
        style = FluidButtonStyle.Tinted,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    (services.endpointCheck as? CheckState.Ok)?.let { ok ->
      item {
        FluidInlineMessage(
          message = stringResource(R.string.settings_endpoint_ok, ok.latencyMs),
          title = stringResource(R.string.settings_endpoint),
          tone = FluidTone.Success,
        )
      }
    }
    (services.endpointCheck as? CheckState.Failed)?.let { failed ->
      item {
        FluidInlineMessage(
          message = jobErrorText(failed.reason, null),
          title = stringResource(R.string.settings_endpoint),
          tone = FluidTone.Danger,
        )
      }
    }
  }

  item { FluidSectionFootnote(text = stringResource(R.string.onboarding_provider_later)) }
}

private fun LazyListScope.backupStep(
  folderChosen: Boolean,
  onPickFolder: () -> Unit,
) {
  item { FluidSectionFootnote(text = stringResource(R.string.onboarding_backup_detail)) }
  item {
    FluidButton(
      text = stringResource(
        if (folderChosen) R.string.onboarding_backup_chosen else R.string.onboarding_backup_choose,
      ),
      onClick = onPickFolder,
      style = FluidButtonStyle.Tinted,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
  item { FluidSectionFootnote(text = stringResource(R.string.onboarding_backup_later)) }
}
