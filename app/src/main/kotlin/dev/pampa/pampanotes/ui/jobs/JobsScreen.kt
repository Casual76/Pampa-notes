package dev.pampa.pampanotes.ui.jobs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidIndeterminateBar
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.ui.common.jobErrorText
import dev.pampa.pampanotes.ui.common.jobPhaseText
import dev.pampa.pampanotes.ui.common.jobStateLabel

/** La coda: cosa sta girando, a che punto, e cosa e' andato storto. */
@Composable
fun JobsRoute(
  onBack: () -> Unit,
  viewModel: JobsViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  val cancelLabel = stringResource(R.string.action_cancel)
  val retryLabel = stringResource(R.string.action_retry)
  val deleteLabel = stringResource(R.string.action_delete)

  FluidScreen(
    title = stringResource(R.string.jobs_title),
    subtitle = stringResource(R.string.jobs_subtitle),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Tertiary, motif = FluidHeroMotif.Ticks),
  ) {
    if (state.isEmpty) {
      item {
        FluidEmptyState(
          title = stringResource(R.string.jobs_empty_title),
          detail = stringResource(R.string.jobs_empty_detail),
        )
      }
      return@FluidScreen
    }

    if (state.active.isNotEmpty()) {
      item { FluidSectionHeader(title = stringResource(R.string.jobs_section_active)) }
      items(state.active, key = { it.job.id }) { row ->
        ActiveJobCard(row = row, onCancel = { viewModel.cancel(row.job.id) }, cancelLabel = cancelLabel)
      }
    }

    if (state.finished.isNotEmpty()) {
      item { FluidSectionHeader(title = stringResource(R.string.jobs_section_finished)) }
      item {
        FluidListGroup {
          state.finished.forEachIndexed { index, row ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = row.noteTitle.ifBlank { stringResource(R.string.jobs_unknown_note) },
              subtitle = row.job.errorCode?.let { jobErrorText(it, row.job.errorMessage) }
                ?: jobStateLabel(row.job.state),
              eyebrow = row.sessionDate.takeIf { it.isNotBlank() },
              tone = when (row.job.state) {
                JobState.DONE -> FluidTone.Success
                JobState.FAILED -> FluidTone.Danger
                else -> FluidTone.Neutral
              },
              badge = { FluidStatusBadge(label = jobStateLabel(row.job.state), tone = toneOf(row.job.state)) },
              contextActions = {
                buildList {
                  if (row.job.state == JobState.FAILED) {
                    add(FluidContextAction(label = retryLabel) { viewModel.retry(row.job.id) })
                  }
                  add(FluidContextAction(label = deleteLabel, destructive = true) { viewModel.delete(row.job.id) })
                }
              },
            )
          }
        }
      }
      item {
        FluidButton(
          text = stringResource(R.string.jobs_clear_finished),
          onClick = viewModel::clearFinished,
          style = FluidButtonStyle.Plain,
          fillWidth = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
private fun ActiveJobCard(row: JobRow, onCancel: () -> Unit, cancelLabel: String) {
  FluidCard {
    FluidListRow(
      title = row.noteTitle.ifBlank { stringResource(R.string.jobs_unknown_note) },
      subtitle = jobPhaseText(row.job),
      eyebrow = jobStateLabel(row.job.state),
      meta = row.sessionDate.takeIf { it.isNotBlank() },
    )
    // Una barra determinata quando il progresso significa qualcosa, indeterminata quando si sta
    // solo aspettando una risposta: fingere una percentuale mentre il server pensa e' peggio che
    // non mostrarla.
    if (row.job.state == JobState.TRANSCRIBING || row.job.state == JobState.STITCHING) {
      FluidIndeterminateBar(modifier = Modifier.fillMaxWidth())
    } else {
      FluidProgressBar(progress = { row.job.progress }, modifier = Modifier.fillMaxWidth())
    }
    FluidButton(
      text = cancelLabel,
      onClick = onCancel,
      style = FluidButtonStyle.Plain,
      enabled = row.job.state != JobState.CANCEL_REQUESTED,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

private fun toneOf(state: JobState): FluidTone = when (state) {
  JobState.DONE -> FluidTone.Success
  JobState.FAILED -> FluidTone.Danger
  JobState.CANCELLED -> FluidTone.Neutral
  else -> FluidTone.Info
}
