package dev.pampa.pampanotes.ui.share

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pampa.pampanotes.ui.common.ConfirmDestructive
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.share.ShareStage
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.PageActions
import dev.pampa.pampanotes.ui.common.SheetBody

/**
 * «Condividi con un link»: la pagina che un compagno apre e ascolta.
 *
 * Prima di fare, si dice cosa comporta — soprattutto quanto audio sale, perche' e' l'unica cosa che
 * costa. Il testo e la trascrizione sono gia' nell'indice; l'audio va caricato una volta e resta
 * finche' il link e' vivo. Il link e' la chiave: chi ce l'ha entra, e revocarlo lo spegne.
 */
@Composable
fun ShareSheet(
  noteId: String,
  onDismiss: () -> Unit,
  viewModel: ShareViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var revoking by remember { mutableStateOf(false) }
  if (revoking) {
    ConfirmDestructive(
      title = stringResource(R.string.share_revoke_title),
      message = stringResource(R.string.share_revoke_message),
      confirmLabel = stringResource(R.string.share_revoke),
      onConfirm = { viewModel.revoke(noteId) },
      onDismiss = { revoking = false },
    )
  }
  val context = LocalContext.current
  val copied = stringResource(R.string.share_copied)

  LaunchedEffect(noteId) { viewModel.start(noteId) }

  fun copy() {
    val url = state.result?.url ?: return
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Pampa Notes", url))
    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
  }

  fun send() {
    val result = state.result ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, result.title)
      putExtra(Intent.EXTRA_TEXT, result.url)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
  }

  FluidGlassModalPortal(
    visible = true,
    // A meta' caricamento il pannello resta: chiuderlo vuol dire fermare, e c'e' un tasto per quello.
    onDismissRequest = { if (!state.running) onDismiss() },
    presentation = FluidGlassModalPresentation.FullScreen,
    paneTitle = stringResource(R.string.share_title),
    footer = {
      PageActions {
        val result = state.result
        when {
          state.running -> FluidButton(
            text = stringResource(R.string.action_cancel),
            onClick = viewModel::cancel,
            style = FluidButtonStyle.Plain,
            fillWidth = true,
            modifier = Modifier.fillMaxWidth(),
          )

          result != null -> {
            FluidButton(text = stringResource(R.string.share_send), onClick = ::send, fillWidth = true, modifier = Modifier.fillMaxWidth())
            FluidButton(text = stringResource(R.string.share_copy), onClick = ::copy, style = FluidButtonStyle.Tinted, fillWidth = true, modifier = Modifier.fillMaxWidth())
            FluidButton(text = stringResource(R.string.action_done), onClick = onDismiss, style = FluidButtonStyle.Plain, fillWidth = true, modifier = Modifier.fillMaxWidth())
          }

          else -> {
            FluidButton(
              text = stringResource(R.string.share_create),
              onClick = { viewModel.share(noteId) },
              enabled = !state.loading && state.plan?.configured == true,
              fillWidth = true,
              modifier = Modifier.fillMaxWidth(),
            )
            FluidButton(text = stringResource(R.string.action_cancel), onClick = onDismiss, style = FluidButtonStyle.Plain, fillWidth = true, modifier = Modifier.fillMaxWidth())
          }
        }
      }
    },
  ) {
    // Il pannello a pagina intera scorre gia' da solo: un secondo scorrimento dentro e' un crash a misura infinita.
    SheetBody(scrollable = false) {
      FluidSectionFootnote(text = stringResource(R.string.share_intro))

      val plan = state.plan
      val result = state.result
      val progress = state.progress
      when {
        state.running && progress != null -> {
          val label = when (progress.stage) {
            ShareStage.SYNCING -> stringResource(R.string.share_stage_syncing)
            ShareStage.CREATING -> stringResource(R.string.share_stage_creating)
            ShareStage.FETCHING -> stringResource(R.string.share_stage_fetching, progress.partName)
            ShareStage.UPLOADING -> stringResource(R.string.share_stage_uploading, progress.partName)
            ShareStage.DONE -> stringResource(R.string.share_stage_done)
          }
          Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            FluidProgressBar(progress = { progress.fraction })
          }
        }

        result != null -> {
          FluidCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(text = result.title, style = MaterialTheme.typography.titleMedium)
              Text(text = result.url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
              Text(
                text = shareStatus(result.opens, result.openedAt, result.audioBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          if (plan != null && plan.parts > 0 && result.audioBytes == 0L) {
            FluidInlineMessage(
              title = stringResource(R.string.share_audio_missing_title),
              message = stringResource(R.string.share_audio_missing_detail),
              tone = FluidTone.Warning,
            )
            FluidButton(text = stringResource(R.string.share_upload_audio), onClick = { viewModel.share(noteId) }, style = FluidButtonStyle.Tinted, fillWidth = true, modifier = Modifier.fillMaxWidth())
          }
          FluidButton(text = stringResource(R.string.share_revoke), onClick = { revoking = true }, style = FluidButtonStyle.Plain, fillWidth = true, modifier = Modifier.fillMaxWidth())
          FluidSectionFootnote(text = stringResource(R.string.share_link_hint))
        }

        plan != null && !plan.configured -> FluidInlineMessage(
          title = stringResource(R.string.share_not_configured_title),
          message = stringResource(R.string.share_not_configured_detail),
          tone = FluidTone.Warning,
        )

        plan != null -> {
          FluidListGroup {
            FluidListRow(
              title = stringResource(R.string.share_text_row),
              subtitle = stringResource(R.string.share_text_detail),
            )
            FluidListDivider()
            FluidListRow(
              title = stringResource(R.string.share_audio_row),
              subtitle = if (plan.parts == 0) {
                stringResource(R.string.share_audio_none)
              } else {
                pluralStringResource(R.plurals.share_audio_detail, plan.parts, plan.parts, Formats.bytes(plan.bytes))
              },
              meta = if (plan.parts == 0) null else Formats.bytes(plan.bytes),
            )
          }
          if (plan.unavailable > 0) {
            FluidInlineMessage(
              title = stringResource(R.string.share_unavailable_title),
              message = pluralStringResource(R.plurals.share_unavailable_detail, plan.unavailable, plan.unavailable),
              tone = FluidTone.Warning,
            )
          }
          FluidSectionFootnote(text = stringResource(R.string.share_link_hint))
        }
      }

      state.error?.let { error ->
        FluidInlineMessage(
          title = stringResource(R.string.share_error_title),
          message = error,
          tone = FluidTone.Danger,
        )
      }
    }
  }
}

/** «Aperto 3 volte, l'ultima oggi · 120 MB di audio», o «Mai aperto». */
@Composable
fun shareStatus(opens: Int, openedAt: Long?, audioBytes: Long): String {
  val opened = if (opens > 0 && openedAt != null) {
    pluralStringResource(R.plurals.share_opened, opens, opens, Formats.relativeDate(openedAt))
  } else {
    stringResource(R.string.share_never_opened)
  }
  val audio = if (audioBytes > 0) stringResource(R.string.share_audio_size, Formats.bytes(audioBytes)) else stringResource(R.string.share_audio_none_short)
  return "$opened · $audio"
}
