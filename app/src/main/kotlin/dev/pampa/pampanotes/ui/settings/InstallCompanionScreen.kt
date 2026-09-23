package dev.pampa.pampanotes.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallDesktop
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.ui.common.RowIcon
import dev.pampa.pampanotes.ui.common.jobErrorText

/**
 * Dove si scarica il companion per Windows. Le release `companion-v*` del repository: la ricerca
 * tiene fuori quelle dell'app, se un giorno ce ne saranno.
 */
const val COMPANION_RELEASES_URL = "https://github.com/Casual76/Pampa-notes/releases?q=companion&expanded=true"

/**
 * «Installa sul tuo computer»: la strada per chi non ha ancora il companion.
 *
 * Il setup si scarica **sul PC**, non qui: per questo il tasto principale e' «Condividi il link»
 * (per mandarlo al computer via mail o chat) e «Apri» e' il secondo. Tre passi, cosa serve, e «Cerca
 * il computer», che prova l'indirizzo gia' collegato: dopo l'installazione il QR lo scrive da solo, e
 * questa pagina serve a vedere che risponde.
 */
@Composable
fun InstallCompanionRoute(
  onBack: () -> Unit,
  viewModel: SettingsViewModel = hiltViewModel(),
) {
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val services by viewModel.services.collectAsStateWithLifecycle()
  val context = LocalContext.current
  // Il risultato di «Cerca» vale per la ricerca fatta qui: quello rimasto dalla pagina dei servizi
  // non deve comparire prima che si tocchi il tasto.
  var searched by remember { mutableStateOf(false) }
  FluidScreen(
    title = stringResource(R.string.install_title),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Bars),
  ) {
    installCompanionContent(
      settings = settings,
      check = if (searched) services.endpointCheck else CheckState.Idle,
      onShare = { shareCompanionLink(context) },
      onOpen = { openCompanionLink(context) },
      onSearch = {
        searched = true
        viewModel.testEndpoint(settings.endpointUrl, settings.endpointRemoteUrl)
      },
    )
  }
}

internal fun LazyListScope.installCompanionContent(
  settings: PampaSettings,
  check: CheckState,
  onShare: () -> Unit,
  onOpen: () -> Unit,
  onSearch: () -> Unit,
) {
  item { FluidSectionFootnote(text = stringResource(R.string.install_intro)) }

  item { FluidSectionHeader(title = stringResource(R.string.install_needs_header)) }
  item {
    FluidListGroup {
      FluidListRow(title = stringResource(R.string.install_need_windows), subtitle = stringResource(R.string.install_need_windows_detail))
      FluidListDivider()
      FluidListRow(title = stringResource(R.string.install_need_gpu), subtitle = stringResource(R.string.install_need_gpu_detail))
      FluidListDivider()
      FluidListRow(title = stringResource(R.string.install_need_space), subtitle = stringResource(R.string.install_need_space_detail))
    }
  }

  item { FluidSectionHeader(title = stringResource(R.string.install_steps_header)) }
  item {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.install_step_download),
        subtitle = stringResource(R.string.install_step_download_detail),
        leading = { RowIcon(Icons.Rounded.Download, FluidTone.Primary) },
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.install_step_install),
        subtitle = stringResource(R.string.install_step_install_detail),
        leading = { RowIcon(Icons.Rounded.InstallDesktop, FluidTone.Primary) },
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.install_step_scan),
        subtitle = stringResource(R.string.install_step_scan_detail),
        leading = { RowIcon(Icons.Rounded.QrCodeScanner, FluidTone.Primary) },
      )
    }
  }
  item {
    FluidButton(
      text = stringResource(R.string.install_share),
      onClick = onShare,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
  item {
    FluidButton(
      text = stringResource(R.string.install_open),
      onClick = onOpen,
      style = FluidButtonStyle.Tinted,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
  item { FluidSectionFootnote(text = stringResource(R.string.install_link_footnote, COMPANION_RELEASES_URL)) }

  item {
    FluidSectionHeader(
      title = stringResource(R.string.install_search_header),
      detail = stringResource(if (settings.hasEndpoint) R.string.install_search_detail else R.string.install_search_none),
    )
  }
  item {
    FluidButton(
      text = stringResource(R.string.install_search),
      onClick = onSearch,
      enabled = settings.hasEndpoint && check !is CheckState.Running,
      loading = check is CheckState.Running,
      style = FluidButtonStyle.Tinted,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
  (check as? CheckState.Ok)?.let { ok ->
    item {
      FluidInlineMessage(
        message = stringResource(
          if (ok.detail == SettingsViewModel.ENDPOINT_VIA_REMOTE) R.string.settings_endpoint_ok_remote else R.string.settings_endpoint_ok_lan,
          ok.latencyMs,
        ),
        title = stringResource(R.string.install_search_found),
        tone = FluidTone.Success,
      )
    }
  }
  (check as? CheckState.Failed)?.let { failed ->
    item {
      FluidInlineMessage(
        message = jobErrorText(failed.reason, null, TranscriptionProviderId.CUSTOM.id),
        title = stringResource(R.string.install_search_missing),
        tone = FluidTone.Danger,
      )
    }
  }
}

/** Il link come testo, per mandarlo al PC: mail, chat, «Invia a me stesso». */
fun shareCompanionLink(context: Context) {
  val text = context.getString(R.string.install_share_text, COMPANION_RELEASES_URL)
  val send = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.install_share_subject))
    putExtra(Intent.EXTRA_TEXT, text)
  }
  context.startActivity(Intent.createChooser(send, context.getString(R.string.install_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openCompanionLink(context: Context) {
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(COMPANION_RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  } catch (_: ActivityNotFoundException) {
    // Nessun browser: resta «Condividi», che almeno lo copia.
    shareCompanionLink(context)
  }
}
