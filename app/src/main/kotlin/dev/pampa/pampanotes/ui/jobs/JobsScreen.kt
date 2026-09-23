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
import dev.pampa.pampanotes.core.model.Dates
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.JobProgressBars
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
              eyebrow = sessionDateLabel(row.sessionDate),
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
      // Anche per uno solo: «Riprova» stava solo nel menu che si apre tenendo premuto, e un lavoro
      // fallito senza un tasto sotto sembra un lavoro da buttare.
      val failed = state.finished.count { it.job.state == JobState.FAILED }
      if (failed > 0) {
        item {
          FluidButton(
            text = stringResource(if (failed == 1) R.string.action_retry else R.string.jobs_retry_failed),
            onClick = viewModel::retryAllFailed,
            style = FluidButtonStyle.Tinted,
            fillWidth = true,
            modifier = Modifier.fillMaxWidth(),
          )
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
      meta = sessionDateLabel(row.sessionDate),
    )
    // La sessione intera, e sotto il passo in corso: determinata quando il computer dice a che punto
    // e', che scorre quando si sta solo aspettando una risposta — fingere una percentuale mentre il
    // server pensa e' peggio che non mostrarla.
    JobProgressBars(row.job)
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

/** «oggi», «ieri», «14 mar»: la data di una sessione e' salvata come `2026-09-23`, e cosi' si leggeva. */
@Composable
private fun sessionDateLabel(iso: String): String? {
  if (iso.isBlank()) return null
  return Dates.parseOrNull(iso)?.let { Formats.relativeDate(it) } ?: iso
}
