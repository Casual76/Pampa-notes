package dev.pampa.pampanotes.ui.onboarding

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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
 *
 * Con l'accesso Google compilato c'e' un passo in piu', subito dopo il benvenuto: chi ha gia'
 * usato l'app entra con l'account, e i passi dopo trovano gia' scritto quello che l'account sa —
 * il computer di casa per primo.
 */
@Composable
fun OnboardingRoute(
  onDone: () -> Unit,
  viewModel: SettingsViewModel = hiltViewModel(),
  onboarding: OnboardingViewModel = hiltViewModel(),
) {
  // Il passo dell'account c'e' solo se questa build sa entrare con Google: senza client ID non
  // c'e' niente da proporre, e un passo che dice «non disponibile» e' un passo in piu' e basta.
  val steps = remember { OnboardingStep.entries.filter { it != OnboardingStep.ACCOUNT || onboarding.accountAvailable } }
  // Salvabile: una rotazione a meta' del primo avvio non deve rimandare al benvenuto.
  var index by rememberSaveable { mutableIntStateOf(0) }
  val step = steps[index.coerceIn(0, steps.lastIndex)]
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val services by viewModel.services.collectAsStateWithLifecycle()
  val account by onboarding.account.collectAsStateWithLifecycle()
  val context = LocalContext.current

  var groqKey by remember { mutableStateOf("") }
  // Gli indirizzi sopravvivono a una rotazione: riscriverli a meta' primo avvio e' il modo di farli
  // saltare. Il codice e la chiave di Groq invece no — lo stato salvato finisce su disco, in chiaro,
  // e un segreto non ci va: al massimo si riscrive.
  var endpointUrl by rememberSaveable(settings.endpointUrl) { mutableStateOf(settings.endpointUrl) }
  var endpointRemoteUrl by rememberSaveable(settings.endpointRemoteUrl) { mutableStateOf(settings.endpointRemoteUrl) }
  var endpointToken by remember { mutableStateOf("") }
  // Il computer arrivato dall'account si mostra come una riga; «Cambia» riapre i campi.
  var editingEndpoint by rememberSaveable { mutableStateOf(false) }
  val endpointFromAccount = settings.endpointFromAccount && settings.hasEndpoint && !editingEndpoint

  // Quello che e' scritto nei campi vale anche senza «Prova»: andare avanti e' gia' un «si', questo».
  val leaveStep = {
    if (step == OnboardingStep.PROVIDER && !endpointFromAccount &&
      (endpointUrl != settings.endpointUrl || endpointRemoteUrl != settings.endpointRemoteUrl || endpointToken.isNotBlank())
    ) {
      viewModel.saveEndpoint(endpointUrl, endpointRemoteUrl, endpointToken)
      endpointToken = ""
    }
  }

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

  val last = steps.lastIndex
  val goBack: () -> Unit = {
    leaveStep()
    index -= 1
  }
  // Il tasto indietro del sistema fa quello che fa la freccia: un passo indietro. Al benvenuto
  // lascia fare al sistema, che chiude l'app.
  BackHandler(enabled = index > 0, onBack = goBack)
  FluidScreen(
    title = stringResource(step.title),
    subtitle = stringResource(R.string.onboarding_step, index + 1, last + 1),
    onBack = if (index > 0) goBack else null,
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Glow),
  ) {
    when (step) {
      OnboardingStep.WELCOME -> welcomeStep()
      OnboardingStep.ACCOUNT -> accountStep(
        account = settings.syncAccount,
        state = account,
        computer = settings.endpointName.ifBlank { settings.endpointUrl.ifBlank { settings.endpointRemoteUrl } }
          .takeIf { settings.endpointFromAccount && settings.hasEndpoint },
        onSignIn = { onboarding.signIn(context) },
      )
      OnboardingStep.IMPORT -> importStep(settings.autoTranscribeOnImport, viewModel::setAutoTranscribe)
      OnboardingStep.PROVIDER -> providerStep(
        provider = settings.preferredProvider,
        onProvider = viewModel::setPreferredProvider,
        accountComputer = settings.endpointName.ifBlank { settings.endpointUrl.ifBlank { settings.endpointRemoteUrl } }
          .takeIf { endpointFromAccount },
        onChangeComputer = { editingEndpoint = true },
        services = services,
        groqKey = groqKey,
        onGroqKeyChange = { groqKey = it },
        onVerifyGroq = {
          viewModel.saveGroqKey(groqKey)
          groqKey = ""
        },
        endpointUrl = endpointUrl,
        onEndpointUrlChange = { endpointUrl = it },
        endpointRemoteUrl = endpointRemoteUrl,
        onEndpointRemoteUrlChange = { endpointRemoteUrl = it },
        endpointToken = endpointToken,
        onEndpointTokenChange = { endpointToken = it },
        onTestEndpoint = {
          viewModel.setEndpoint(endpointUrl, settings.endpointModel)
          viewModel.setEndpointRemoteUrl(endpointRemoteUrl)
          if (endpointToken.isNotBlank()) viewModel.setEndpointToken(endpointToken)
          viewModel.testEndpoint(endpointUrl, endpointRemoteUrl)
        },
      )

      OnboardingStep.BACKUP -> backupStep(
        folderChosen = settings.backupFolderUri.isNotBlank(),
        onPickFolder = { pickFolder.launch(null) },
      )
    }

    item {
      FluidButton(
        text = stringResource(if (index == last) R.string.onboarding_start else R.string.onboarding_next),
        onClick = {
          leaveStep()
          if (index == last) onDone() else index++
        },
        // Mentre l'accesso lavora si aspetta: andare avanti a meta' vorrebbe dire arrivare a «Chi
        // trascrive» un attimo prima che il computer dell'account ci arrivi.
        enabled = !account.busy,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    if (index < last) {
      item {
        FluidButton(
          text = stringResource(R.string.onboarding_skip),
          onClick = {
            leaveStep()
            onDone()
          },
          style = FluidButtonStyle.Plain,
          fillWidth = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

/** I passi, nell'ordine. L'account viene subito dopo il benvenuto: quello che porta cambia i passi dopo. */
private enum class OnboardingStep(val title: Int) {
  WELCOME(R.string.onboarding_welcome_title),
  ACCOUNT(R.string.onboarding_account_title),
  IMPORT(R.string.onboarding_import_title),
  PROVIDER(R.string.onboarding_provider_title),
  BACKUP(R.string.onboarding_backup_title),
}

/**
 * «Hai gia' usato Pampa Notes?» Chi ha gia' un account entra, e trova le sue cose e il computer
 * di casa; chi e' nuovo va avanti. Non e' una registrazione: non si crea niente che non si possa
 * fare dopo, da Sincronizzazione.
 */
private fun LazyListScope.accountStep(
  account: String,
  state: AccountStepState,
  /** Nome o indirizzo del computer arrivato dall'account, se e' arrivato. */
  computer: String?,
  onSignIn: () -> Unit,
) {
  item { FluidSectionFootnote(text = stringResource(R.string.onboarding_account_detail)) }
  if (account.isNotBlank() && !state.busy) {
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.onboarding_account_welcome, account),
          subtitle = when (val notes = state.notes) {
            null -> stringResource(R.string.onboarding_account_background)
            0 -> stringResource(R.string.onboarding_account_empty)
            else -> pluralStringResource(R.plurals.onboarding_account_notes, notes, notes)
          },
          tone = FluidTone.Success,
          leading = { RowIcon(Icons.Rounded.CheckCircle, FluidTone.Success) },
        )
        if (computer != null) {
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.onboarding_computer_from_account, computer),
            subtitle = stringResource(R.string.onboarding_computer_from_account_detail),
            leading = { RowIcon(Icons.Rounded.Computer, FluidTone.Info) },
          )
        }
      }
    }
  } else {
    item {
      FluidButton(
        text = stringResource(R.string.onboarding_account_signin),
        onClick = onSignIn,
        enabled = !state.busy,
        loading = state.busy,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
  state.error?.let { error ->
    item { FluidInlineMessage(title = stringResource(R.string.sync_google_failed), message = error, tone = FluidTone.Danger) }
  }
  item { FluidSectionFootnote(text = stringResource(R.string.onboarding_account_later)) }
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
  /** Nome o indirizzo del computer arrivato dall'account e non ancora toccato qui; `null` altrimenti. */
  accountComputer: String?,
  onChangeComputer: () -> Unit,
  services: ServicesUiState,
  groqKey: String,
  onGroqKeyChange: (String) -> Unit,
  onVerifyGroq: () -> Unit,
  endpointUrl: String,
  onEndpointUrlChange: (String) -> Unit,
  endpointRemoteUrl: String,
  onEndpointRemoteUrlChange: (String) -> Unit,
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
  } else if (accountComputer != null) {
    // Arrivato con l'accesso: indirizzi e token ci sono gia', e chiederli di nuovo sarebbe chiedere
    // proprio quello che l'account doveva risparmiare.
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.onboarding_computer_from_account, accountComputer),
          subtitle = stringResource(R.string.onboarding_computer_from_account_detail),
          tone = FluidTone.Success,
          leading = { RowIcon(Icons.Rounded.Computer, FluidTone.Success) },
        )
      }
    }
    item {
      FluidButton(
        text = stringResource(R.string.onboarding_computer_change),
        onClick = onChangeComputer,
        style = FluidButtonStyle.Plain,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
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
        value = endpointRemoteUrl,
        onValueChange = onEndpointRemoteUrlChange,
        label = stringResource(R.string.settings_endpoint_remote_url),
        placeholder = "100.x.y.z:8765",
        supportingText = stringResource(R.string.settings_endpoint_remote_hint),
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
        enabled = (endpointUrl.isNotBlank() || endpointRemoteUrl.isNotBlank()) && services.endpointCheck !is CheckState.Running,
        loading = services.endpointCheck is CheckState.Running,
        style = FluidButtonStyle.Tinted,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    (services.endpointCheck as? CheckState.Ok)?.let { ok ->
      item {
        FluidInlineMessage(
          message = stringResource(if (ok.detail == SettingsViewModel.ENDPOINT_VIA_REMOTE) R.string.settings_endpoint_ok_remote else R.string.settings_endpoint_ok_lan, ok.latencyMs),
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
