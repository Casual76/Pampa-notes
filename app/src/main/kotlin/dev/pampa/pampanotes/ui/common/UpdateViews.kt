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
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidIndeterminateBar
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidUpdate
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateChannelStyle
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateStrings
import dev.antigravity.fluidengine.ui.fluid.FluidUpdateUiState
import dev.antigravity.fluidengine.ui.fluid.fluidUpdateSection
import dev.antigravity.fluidengine.ui.fluid.toFluidUpdateRelease
import dev.antigravity.fluidengine.ui.fluid.toFluidUpdateState
import dev.antigravity.fluidengine.ui.theme.FluidCard
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
      update?.changelog?.let { FluidUpdate.notesSummary(it) }?.takeIf { it.isNotBlank() }?.let {
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
 * Come l'engine vede lo stato degli aggiornamenti dell'app.
 *
 * L'ordine e' quello delle cose che contano di piu': il permesso mancante e l'installazione in corso
 * parlano della versione che si sta installando, un controllo in corso copre quello che si sapeva, e
 * una versione trovata resta offerta anche se l'ultimo controllo e' fallito (`UpdateController` non la
 * cancella). Puro, cosi' si prova senza disegnare.
 */
fun UpdateUiState.toFluidUpdateState(): FluidUpdateUiState {
  val release = available?.toFluidUpdateRelease()
  return when {
    release != null && needsPermission -> FluidUpdateUiState.NeedsPermission(release)
    release != null && install != null -> install.toFluidUpdateState(release)
    checking -> FluidUpdateUiState.Checking
    release != null -> FluidUpdateUiState.Available(release)
    checkFailed -> FluidUpdateUiState.CheckFailed()
    lastCheckedAt > 0L -> FluidUpdateUiState.UpToDate
    else -> FluidUpdateUiState.Idle
  }
}

/**
 * Informazioni → Aggiornamenti: sono le righe dell'engine (`fluidUpdateSection`) — il controllo e
 * l'installazione sono la stessa riga, «controllato 5 minuti fa», le beta come interruttore. Qui si
 * fa solo il collegamento col controllore dell'app: lo stato, i due tocchi, il canale.
 *
 * La versione installata non e' una riga di questa sezione: sta sopra, con quella dell'engine.
 */
fun LazyListScope.updatesSection(
  state: UpdateUiState,
  viewModel: UpdateViewModel,
  strings: FluidUpdateStrings,
) {
  fluidUpdateSection(
    state = state.toFluidUpdateState(),
    onCheck = viewModel::checkNow,
    onInstall = viewModel::install,
    lastCheckedAtMillis = state.lastCheckedAt.takeIf { it > 0L },
    channel = if (state.beta) UpdateChannel.BETA else UpdateChannel.STABLE,
    onChannelChange = { viewModel.setBeta(it == UpdateChannel.BETA) },
    channelStyle = FluidUpdateChannelStyle.BetaToggle,
    supported = state.supported,
    strings = strings,
  )
}
