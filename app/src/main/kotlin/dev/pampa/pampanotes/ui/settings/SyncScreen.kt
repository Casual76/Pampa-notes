package dev.pampa.pampanotes.ui.settings

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.ui.nav.SettingsSection

/**
 * Sincronizzazione: l'indice in cloud. Solo testo — note, cartelle, trascrizioni — mai un file.
 *
 * Tre campi e un interruttore. L'accesso oggi e' un codice (quello del server, o di sviluppo);
 * quando ci sara' il client ID di Google, al suo posto ci sara' un bottone. Quello che si vede
 * sotto e' l'unica cosa che conta davvero: quante modifiche aspettano, e com'e' andato l'ultimo giro.
 */
@Composable
fun SyncSectionRoute(
  onBack: () -> Unit,
  viewModel: SyncViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var serverUrl by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
  var token by remember { mutableStateOf("") }
  var deviceName by remember(state.deviceName) { mutableStateOf(state.deviceName) }

  FluidScreen(
    title = SettingsSection.SYNC.label(),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Bars),
  ) {
    item { FluidSectionFootnote(text = stringResource(R.string.sync_intro)) }

    item {
      FluidTextField(
        value = serverUrl,
        onValueChange = { serverUrl = it },
        label = stringResource(R.string.sync_server_url),
        placeholder = state.defaultServerUrl.removePrefix("https://").ifBlank { "pampa-notes.qualcuno.workers.dev" },
        supportingText = stringResource(R.string.sync_server_hint),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
      )
    }
    if (state.googleClientId.isBlank()) {
      // Senza un client ID compilato l'accesso e' un codice: quello di sviluppo, o del proprio server.
      item {
        FluidTextField(
          value = token,
          onValueChange = { token = it },
          label = stringResource(R.string.sync_token),
          placeholder = if (state.hasToken) stringResource(R.string.sync_token_present) else stringResource(R.string.sync_token_hint),
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          modifier = Modifier.fillMaxWidth(),
        )
      }
    } else {
      item {
        FluidListGroup {
          FluidListRow(
            title = if (state.account.isNotBlank()) stringResource(R.string.sync_google_account, state.account) else stringResource(R.string.sync_google_title),
            subtitle = stringResource(R.string.sync_google_hint),
          )
        }
      }
      item {
        FluidButton(
          text = stringResource(if (state.account.isBlank()) R.string.sync_google_signin else R.string.sync_google_signout),
          onClick = { if (state.account.isBlank()) viewModel.signInWithGoogle(context) else viewModel.signOut() },
          // Senza indirizzo scritto si entra in quello compilato: `AccountSignIn` lo mette lui.
          enabled = (state.serverUrl.isNotBlank() || state.defaultServerUrl.isNotBlank()) && !state.authBusy,
          loading = state.authBusy,
          style = if (state.account.isBlank()) FluidButtonStyle.Filled else FluidButtonStyle.Plain,
          fillWidth = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      state.authError?.let { error ->
        item { FluidInlineMessage(title = stringResource(R.string.sync_google_failed), message = error, tone = FluidTone.Danger) }
      }
    }
    item {
      FluidTextField(
        value = deviceName,
        onValueChange = { deviceName = it },
        label = stringResource(R.string.sync_device_name),
        supportingText = stringResource(R.string.sync_device_name_hint),
        modifier = Modifier.fillMaxWidth(),
      )
    }
    item {
      FluidButton(
        text = stringResource(R.string.action_save),
        onClick = {
          viewModel.setServerUrl(serverUrl)
          if (token.isNotBlank()) viewModel.setToken(token)
          viewModel.setDeviceName(deviceName)
          token = ""
        },
        enabled = serverUrl.isNotBlank() && (token.isNotBlank() || state.hasToken || state.googleClientId.isNotBlank()),
        style = FluidButtonStyle.Tinted,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.sync_enable),
          subtitle = stringResource(R.string.sync_enable_detail),
          badge = { FluidSwitch(checked = state.enabled, onCheckedChange = viewModel::setEnabled, enabled = state.configured) },
        )
      }
    }
    if (!state.configured) {
      item { FluidSectionFootnote(text = stringResource(if (state.googleClientId.isBlank()) R.string.sync_not_configured else R.string.sync_not_signed_in)) }
    }

    item { FluidSectionHeader(title = stringResource(R.string.sync_status_header)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.sync_pending_title),
          subtitle = pluralStringResource(R.plurals.sync_pending, state.pending, state.pending),
          tone = if (state.pending > 0) FluidTone.Warning else FluidTone.Neutral,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.sync_last_title),
          subtitle = if (state.lastSyncAt > 0) {
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(state.lastSyncAt))
          } else {
            stringResource(R.string.sync_never)
          },
          tone = if (state.lastSyncError.isNotBlank()) FluidTone.Danger else FluidTone.Neutral,
        )
      }
    }

    item {
      FluidButton(
        text = stringResource(if (state.running) R.string.sync_running else R.string.sync_now),
        onClick = viewModel::syncNow,
        enabled = state.enabled && state.configured && !state.running,
        loading = state.running,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    if (state.lastSyncError.isNotBlank()) {
      item {
        FluidInlineMessage(
          title = stringResource(R.string.sync_error_title),
          message = state.lastSyncError,
          tone = FluidTone.Danger,
        )
      }
    }
    state.last?.takeIf { it.error == null }?.let { last ->
      item {
        FluidInlineMessage(
          title = stringResource(R.string.sync_status_header),
          message = stringResource(R.string.sync_report, last.pushed, last.pulled, last.deleted) +
            (if (last.forked > 0) " " + pluralStringResource(R.plurals.sync_forked, last.forked, last.forked) else ""),
          tone = if (last.forked > 0) FluidTone.Warning else FluidTone.Success,
        )
      }
    }

    item { FluidSectionFootnote(text = stringResource(R.string.sync_footnote)) }
  }
}
