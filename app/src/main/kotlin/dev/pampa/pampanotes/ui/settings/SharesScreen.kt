package dev.pampa.pampanotes.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidQuickAction
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.nav.SettingsSection
import dev.pampa.pampanotes.ui.share.shareStatus

/**
 * Condivisioni: i link che si sono dati in giro. Titolo, quando, quante volte e' stato aperto,
 * quanto audio tiene in cloud, e la revoca. E' l'unico posto dove si vede tutto insieme: dalla
 * nota si vede solo il suo link.
 */
@Composable
fun SharesSectionRoute(
  onBack: () -> Unit,
  viewModel: SharesViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val copied = stringResource(R.string.share_copied)
  val copyLabel = stringResource(R.string.share_copy)
  val sendLabel = stringResource(R.string.share_send)
  val openLabel = stringResource(R.string.share_open)
  val revokeLabel = stringResource(R.string.share_revoke)

  fun copy(url: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Pampa Notes", url))
    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
  }

  fun send(title: String, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, title)
      putExtra(Intent.EXTRA_TEXT, url)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
  }

  fun open(url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
  }

  FluidScreen(
    title = SettingsSection.SHARES.label(),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Bars),
  ) {
    item { FluidSectionFootnote(text = stringResource(R.string.shares_intro)) }

    when {
      !state.configured -> item {
        FluidInlineMessage(
          title = stringResource(R.string.share_not_configured_title),
          message = stringResource(R.string.share_not_configured_detail),
          tone = FluidTone.Warning,
        )
      }

      !state.loading && state.shares.isEmpty() && state.error == null -> item {
        FluidEmptyState(
          title = stringResource(R.string.shares_empty_title),
          detail = stringResource(R.string.shares_empty_detail),
        )
      }

      state.shares.isNotEmpty() -> item {
        FluidListGroup {
          state.shares.forEachIndexed { index, share ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = share.title,
              subtitle = shareStatus(share.opens, share.openedAt, share.audioBytes),
              eyebrow = stringResource(R.string.shares_created, Formats.relativeDate(share.createdAt)),
              onClick = { open(share.url) },
              contextActions = {
                listOf(
                  FluidContextAction(label = openLabel) { open(share.url) },
                  FluidContextAction(label = copyLabel) { copy(share.url) },
                  FluidContextAction(label = sendLabel) { send(share.title, share.url) },
                  FluidContextAction(label = revokeLabel, destructive = true) { viewModel.revoke(share.shareId) },
                )
              },
            )
          }
        }
      }
    }

    state.error?.let { error ->
      item { FluidInlineMessage(title = stringResource(R.string.share_error_title), message = error, tone = FluidTone.Danger) }
    }

    if (state.configured) {
      item {
        FluidQuickAction(
          label = stringResource(R.string.shares_refresh),
          onClick = viewModel::load,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.shares_footnote)) }
  }
}
