package dev.pampa.pampanotes.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidIndeterminateBar
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.update.UpdateController
import dev.pampa.pampanotes.update.UpdateUiState
import javax.inject.Inject

/** Il controllore degli aggiornamenti, per le schermate: vive nel processo, non nella pagina. */
@HiltViewModel
class UpdateViewModel @Inject constructor(private val controller: UpdateController) : ViewModel() {
  val state = controller.state
  fun checkNow() = controller.checkNow()
  fun install() = controller.install()
  fun ignore() = controller.ignore()
  fun dismissInstall() = controller.dismissInstall()
  fun setBeta(on: Boolean) = controller.setBeta(on)
  fun onResume() = controller.onResume()
}

/**
 * La scheda in cima alla home quando c'e' una versione nuova: cosa cambia, «Aggiorna» e «Non ora».
 * Durante l'installazione la stessa scheda dice a che punto e': chi ha toccato «Aggiorna» non deve
 * andare a cercare dove sia finito il download.
 */
@Composable
fun UpdateHomeCard(state: UpdateUiState, viewModel: UpdateViewModel) {
  val update = state.available
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
  FluidCard {
    Text(
      text = update?.let { stringResource(R.string.update_card_title, it.version) } ?: stringResource(R.string.update_title),
      style = MaterialTheme.typography.titleMedium,
    )
    val install = state.install
    if (state.needsPermission) {
      PermissionHint(onOpen = viewModel::install, onDismiss = viewModel::dismissInstall)
    } else if (install == null) {
      update?.changelog?.let { changelogSummary(it) }?.takeIf { it.isNotBlank() }?.let {
        Text(text = it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FluidButton(
          text = stringResource(R.string.update_later),
          onClick = viewModel::ignore,
          style = FluidButtonStyle.Plain,
          modifier = Modifier.weight(1f),
        )
        FluidButton(
          text = stringResource(R.string.update_install),
          onClick = viewModel::install,
          style = FluidButtonStyle.Tinted,
          modifier = Modifier.weight(1f),
        )
      }
    } else {
      InstallProgress(install, onDismiss = viewModel::dismissInstall, onRetry = viewModel::install)
    }
  }
}

/** Android chiede il permesso una volta: lo si dice prima, e l'installazione riparte al ritorno. */
@Composable
private fun PermissionHint(onOpen: () -> Unit, onDismiss: () -> Unit) {
  Text(text = stringResource(R.string.update_permission), style = MaterialTheme.typography.bodyMedium)
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
    FluidButton(text = stringResource(R.string.action_close), onClick = onDismiss, style = FluidButtonStyle.Plain, modifier = Modifier.weight(1f))
    FluidButton(text = stringResource(R.string.update_permission_open), onClick = onOpen, style = FluidButtonStyle.Tinted, modifier = Modifier.weight(1f))
  }
}

@Composable
private fun InstallProgress(install: AppUpdateInstallState, onDismiss: () -> Unit, onRetry: () -> Unit) {
  Text(text = installText(install), style = MaterialTheme.typography.bodyMedium)
  when (install) {
    is AppUpdateInstallState.Downloading -> FluidProgressBar(progress = { install.progress }, modifier = Modifier.fillMaxWidth())
    is AppUpdateInstallState.Verifying, is AppUpdateInstallState.Installing -> FluidIndeterminateBar(modifier = Modifier.fillMaxWidth())
    is AppUpdateInstallState.Error -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      FluidButton(text = stringResource(R.string.action_close), onClick = onDismiss, style = FluidButtonStyle.Plain, modifier = Modifier.weight(1f))
      FluidButton(text = stringResource(R.string.action_retry), onClick = onRetry, style = FluidButtonStyle.Tinted, modifier = Modifier.weight(1f))
    }
    else -> Unit
  }
}

/** Le frasi dell'installazione, nella lingua dell'app: quelle dell'engine sono scritte in italiano. */
@Composable
fun installText(install: AppUpdateInstallState): String = when (install) {
  is AppUpdateInstallState.Downloading -> if (install.totalBytes > 0) {
    stringResource(R.string.update_downloading, (install.progress * 100).toInt(), Formats.bytes(install.totalBytes))
  } else {
    stringResource(R.string.update_downloading_unknown)
  }
  is AppUpdateInstallState.Verifying -> stringResource(R.string.update_verifying)
  is AppUpdateInstallState.Installing -> stringResource(R.string.update_installing)
  is AppUpdateInstallState.AwaitingUserAction -> stringResource(R.string.update_awaiting)
  is AppUpdateInstallState.Installed -> stringResource(R.string.update_installed)
  is AppUpdateInstallState.Error -> stringResource(R.string.update_error, install.message)
}

/**
 * Informazioni → Aggiornamenti: la versione di adesso, cosa c'e' di nuovo, il tasto, le beta. Qui la
 * versione a cui si e' detto «non ora» si vede lo stesso: e' il posto dove la si va a cercare.
 */
fun LazyListScope.updatesSection(state: UpdateUiState, viewModel: UpdateViewModel) {
  item { FluidSectionHeader(title = stringResource(R.string.update_title)) }
  if (!state.supported) {
    item {
      FluidListGroup {
        FluidListRow(title = stringResource(R.string.update_debug_title), subtitle = stringResource(R.string.update_debug_detail))
      }
    }
    return
  }
  item {
    val update = state.available
    FluidListGroup {
      FluidListRow(
        title = when {
          update != null -> stringResource(R.string.update_card_title, update.version)
          state.checking -> stringResource(R.string.update_checking)
          else -> stringResource(R.string.update_none)
        },
        subtitle = when {
          update != null -> changelogSummary(update.changelog).ifBlank { stringResource(R.string.update_no_notes) }
          state.checkFailed -> stringResource(R.string.update_check_failed)
          state.lastCheckedAt > 0 -> stringResource(R.string.update_last_checked, Formats.relativeDate(state.lastCheckedAt))
          else -> stringResource(R.string.update_never_checked)
        },
        badge = if (update != null) {
          { FluidStatusBadge(label = stringResource(R.string.update_badge_new), tone = FluidTone.Primary) }
        } else {
          null
        },
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.update_beta),
        subtitle = stringResource(R.string.update_beta_detail),
        badge = { FluidSwitch(checked = state.beta, onCheckedChange = viewModel::setBeta) },
      )
    }
  }
  if (state.needsPermission) {
    item {
      LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
      FluidCard { PermissionHint(onOpen = viewModel::install, onDismiss = viewModel::dismissInstall) }
    }
  }
  state.install?.let { install ->
    item { FluidCard { InstallProgress(install, onDismiss = viewModel::dismissInstall, onRetry = viewModel::install) } }
  }
  item {
    val update = state.available
    FluidButton(
      text = if (update != null) stringResource(R.string.update_install_version, update.version) else stringResource(R.string.update_check_now),
      onClick = if (update != null) viewModel::install else viewModel::checkNow,
      style = if (update != null) FluidButtonStyle.Tinted else FluidButtonStyle.Plain,
      enabled = !state.checking && !state.installing,
      loading = state.checking,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/**
 * Il primo paragrafo del changelog, senza il Markdown: nel changelog dello store il grassetto serve,
 * in una riga di quattro righe al massimo sono solo asterischi.
 */
private fun changelogSummary(changelog: String): String =
  changelog.replace("**", "").split(Regex("\\n\\s*\\n")).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
