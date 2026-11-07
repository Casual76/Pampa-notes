package dev.pampa.pampanotes.ui.cloudrelay

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.relay.RelayResult
import dev.pampa.pampanotes.core.relay.RelayState
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.SheetBody
import java.text.DateFormat
import java.util.Date

@Composable
fun CloudRelaySheet(onDismiss: () -> Unit, viewModel: CloudRelayViewModel = hiltViewModel()) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reloadJobs() }
  CloudRelayPanel(state, viewModel.actions(), onDismiss)
}

/** The FullScreen portal owns scrolling and chrome; its body must have no vertical scroll owner. */
@Composable
internal fun CloudRelayPanel(state: CloudRelayUiState, actions: CloudRelayActions, onDismiss: () -> Unit) {
  FluidGlassModalPortal(visible = true, onDismissRequest = onDismiss,
    presentation = FluidGlassModalPresentation.FullScreen, paneTitle = stringResource(R.string.relay_title)) {
    SheetBody(scrollable = false) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LocalFluidItemSpacing.current)) {
        relayItems(state, actions, forNote = true).forEach { it.content() }
      }
    }
  }
}

fun LazyListScope.cloudRelaySection(state: CloudRelayUiState, viewModel: CloudRelayViewModel, forNote: Boolean = false) {
  relayItems(state, viewModel.actions(), forNote).forEach { row -> item(key = row.key) { row.content() } }
}

internal class CloudRelayActions(
  val send: () -> Unit,
  val receive: () -> Unit,
  val setAutomaticReceive: (Boolean) -> Unit,
  val resume: () -> Unit,
  val cancel: () -> Unit,
  val resend: (String) -> Unit,
)

private fun CloudRelayViewModel.actions() = CloudRelayActions(
  send = { send() }, receive = ::receive, setAutomaticReceive = ::setAutomaticReceive,
  resume = { reloadJobs(); resume() }, cancel = ::cancel,
  resend = { noteId -> send(resend = true, targetNote = noteId) },
)

private class RelayItem(val key: String, val content: @Composable () -> Unit)

/** One set of rows/states/actions for the lazy Sync page and the ordinary modal Column. */
private fun relayItems(state: CloudRelayUiState, actions: CloudRelayActions, forNote: Boolean): List<RelayItem> = buildList {
  fun item(key: String, content: @Composable () -> Unit) { add(RelayItem(key, content)) }
  if (!forNote) item(key = "relay-title") { FluidSectionHeader(title = stringResource(R.string.relay_title)) }
  item(key = "relay-intro") { FluidSectionFootnote(text = stringResource(R.string.relay_intro)) }
  if (!forNote) item(key = "relay-auto") {
    FluidListGroup {
      FluidToggleRow(title = stringResource(R.string.relay_auto), subtitle = stringResource(R.string.relay_auto_detail),
        checked = state.automaticReceive, onCheckedChange = actions.setAutomaticReceive, enabled = state.configured)
    }
  }
  if (!state.configured) item(key = "relay-account") { FluidInlineMessage(title = stringResource(R.string.relay_account),
    message = stringResource(R.string.relay_auth), tone = FluidTone.Warning) }
  item(key = "relay-send") {
    FluidButton(text = stringResource(if (forNote) R.string.relay_send_note else R.string.relay_send_all),
      onClick = actions.send, enabled = state.configured && !state.queueing && !state.working,
      loading = state.queueing, fillWidth = true, modifier = Modifier.fillMaxWidth())
  }
  item(key = "relay-receive") {
    FluidButton(text = stringResource(R.string.relay_receive), onClick = actions.receive,
      enabled = state.configured && !state.queueing && !state.working, style = FluidButtonStyle.Tinted,
      fillWidth = true, modifier = Modifier.fillMaxWidth())
  }
  if (state.working || state.queueing) item(key = "relay-cancel") {
    FluidInlineMessage(title = stringResource(if (state.queueing) R.string.relay_preparing else R.string.relay_working),
      message = stringResource(R.string.relay_wait_detail), tone = FluidTone.Info)
    FluidButton(text = stringResource(R.string.relay_cancel), onClick = actions.cancel, style = FluidButtonStyle.Plain)
  } else item(key = "relay-resume") {
    FluidButton(text = stringResource(R.string.relay_resume), onClick = actions.resume,
      enabled = state.configured, style = FluidButtonStyle.Plain, fillWidth = true)
  }
  if (state.cancelled) item(key = "relay-cancelled") { FluidSectionFootnote(text = stringResource(R.string.relay_cancelled)) }
  state.progress?.let { progress -> item(key = "relay-progress") { RelayStatus(progress, state.fileName(progress)) } }
  if (state.checked && !state.queueing && !state.working && state.results.isEmpty()) {
    item(key = "relay-none") { FluidSectionFootnote(text = stringResource(R.string.relay_none)) }
  }
  val outcomes = state.results.filter { result ->
    (state.configured || result.state != RelayState.AUTH_REQUIRED) &&
      (relayIsProblem(result.state) || (result.state in setOf(RelayState.RECEIVED, RelayState.ALREADY_LOCAL) &&
        state.jobs.none { it.result.state == result.state }))
  }.distinctBy { Triple(it.entityId, it.state, it.code) }
  outcomes.forEachIndexed { index, result -> item(key = "relay-result-$index") { RelayStatus(result, state.fileName(result)) } }
  // Each file has its own status, including partial failures. Never reduce a batch to 'success'.
  state.jobs.forEachIndexed { index, job ->
    item(key = "relay-job-${job.result.jobId ?: index}") {
      FluidListGroup {
        FluidListRow(title = job.name.ifBlank { stringResource(R.string.relay_file) },
          subtitle = relayDetail(job.result), tone = if (relayIsProblem(job.result.state)) FluidTone.Warning else FluidTone.Neutral)
        if (relayCanResend(job.result.state) && job.noteId != null) {
          FluidListDivider()
          FluidListRow(title = stringResource(R.string.relay_resend_note), subtitle = stringResource(R.string.relay_resend_detail),
            onClick = if (state.configured && !state.queueing && !state.working) ({ actions.resend(job.noteId) }) else null)
        }
      }
    }
  }
  item(key = "relay-limits") { FluidSectionFootnote(text = stringResource(R.string.relay_limits)) }
}

@Composable
private fun RelayStatus(result: RelayResult, name: String?) {
  FluidInlineMessage(title = name ?: stringResource(R.string.relay_title), message = relayDetail(result),
    tone = if (relayIsProblem(result.state)) FluidTone.Warning else FluidTone.Info)
  if (result.totalBytes > 0 && result.state in setOf(RelayState.UPLOADING, RelayState.DOWNLOADING)) {
    FluidProgressBar(progress = { (result.bytes.toFloat() / result.totalBytes).coerceIn(0f, 1f) })
  }
}

@Composable
private fun relayDetail(result: RelayResult): String = buildList {
  add(stringResource(relayMessage(result)))
  if (result.totalBytes > 0) add(stringResource(R.string.relay_bytes, Formats.bytes(result.bytes), Formats.bytes(result.totalBytes)))
  result.expiresAt?.let { expiry ->
    add(stringResource(R.string.relay_expiry, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(expiry))))
  }
}.joinToString(" · ")

private fun CloudRelayUiState.fileName(result: RelayResult): String? = result.kind?.let { kind ->
  result.entityId?.let { fileNames[kind to it] }
}
