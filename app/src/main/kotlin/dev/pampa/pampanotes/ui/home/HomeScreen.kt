package dev.pampa.pampanotes.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidBarAction
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.fluidContextMenu
import dev.antigravity.fluidengine.ui.fluid.FluidHero
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMetric
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidQuickAction
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.folderIconOf
import dev.pampa.pampanotes.ui.common.folderVividColors
import dev.pampa.pampanotes.ui.common.toneFromName

/**
 * La Home: quello che hai caricato per ultimo, in ordine di tempo.
 *
 * Non e' l'indice delle cartelle — quello e' la scheda accanto — ma il punto in cui si riprende in
 * mano l'ultima cosa importata, che e' quasi sempre quella che si stava cercando.
 */
@Composable
fun HomeRoute(
  onOpenNote: (String) -> Unit,
  onImport: () -> Unit,
  onOpenJobs: () -> Unit,
  viewModel: HomeViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var pendingDelete by remember { mutableStateOf<RecentNote?>(null) }

  val pinLabel = stringResource(R.string.note_pin)
  val unpinLabel = stringResource(R.string.note_unpin)
  val deleteLabel = stringResource(R.string.action_delete)
  val importLabel = stringResource(R.string.action_import)

  FluidScreen(
    title = stringResource(R.string.home_title),
    subtitle = stringResource(R.string.home_subtitle),
    // Il fondale: e' quello che il vetro delle card ha da rifrangere. Senza, il materiale non si
    // vede e la pagina torna quella grigia di prima.
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Glow),
    actions = {
      FluidBarAction(
        icon = Icons.Rounded.Add,
        contentDescription = importLabel,
        onClick = onImport,
      )
    },
  ) {
    item {
      FluidHero(
        tone = FluidHeroTone.PrimaryToSecondary,
        motif = FluidHeroMotif.Glow,
        eyebrow = stringResource(R.string.home_hero_eyebrow),
        value = state.noteCount.toString(),
        title = pluralStringResource(R.plurals.home_hero_notes, state.noteCount, state.noteCount),
        description = stringResource(R.string.home_hero_description),
        icon = Icons.Rounded.AutoAwesome,
        metrics = buildList {
          add(
            FluidHeroMetric(
              label = stringResource(R.string.home_metric_folders),
              value = state.folderCount.toString(),
            ),
          )
          if (state.audioMinutes > 0) {
            add(FluidHeroMetric(label = stringResource(R.string.home_metric_audio), value = "${state.audioMinutes}′"))
          }
          if (state.activeJobs > 0) {
            add(
              FluidHeroMetric(
                label = stringResource(R.string.home_metric_jobs),
                value = state.activeJobs.toString(),
                onClick = onOpenJobs,
              ),
            )
          }
        },
      )
    }

    if (state.isEmpty) {
      item {
        FluidEmptyState(
          title = stringResource(R.string.home_empty_title),
          detail = stringResource(R.string.home_empty_detail),
        )
      }
      item {
        FluidQuickAction(
          label = importLabel,
          onClick = onImport,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    } else {
      item { FluidSectionHeader(title = stringResource(R.string.home_section_recent)) }
      items(state.recent, key = { it.row.note.id }) { recent ->
        RecentNoteCard(
          recent = recent,
          onClick = { onOpenNote(recent.row.note.id) },
          contextActions = {
            listOf(
              FluidContextAction(label = if (recent.row.note.pinned) unpinLabel else pinLabel) {
                viewModel.togglePinned(recent.row.note.id, !recent.row.note.pinned)
              },
              FluidContextAction(label = deleteLabel, destructive = true) { pendingDelete = recent },
            )
          },
        )
      }
    }
  }

  pendingDelete?.let { recent ->
    FluidAlert(
      onDismissRequest = { pendingDelete = null },
      title = stringResource(R.string.note_delete_title, recent.row.note.title),
      message = stringResource(R.string.note_delete_message),
      actions = listOf(
        FluidAlertAction(
          label = deleteLabel,
          emphasis = FluidAlertAction.Emphasis.Destructive,
          onClick = {
            viewModel.deleteNote(recent.row.note.id)
            pendingDelete = null
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { pendingDelete = null }),
      ),
    )
  }
}

/**
 * Una nota recente: la piastrella colorata della sua cartella, il titolo, due righe di testo.
 *
 * Il colore sta sulla piastrella, non sulla card: e' la stessa regola delle liste raggruppate, e
 * vale anche qui, perche' una colonna di card tutte sature diventa un patchwork.
 */
@Composable
private fun RecentNoteCard(
  recent: RecentNote,
  onClick: () -> Unit,
  contextActions: () -> List<FluidContextAction>,
) {
  val tone = toneFromName(recent.folder?.tone)
  val vivid = folderVividColors(tone)
  val note = recent.row.note

  FluidCard(
    onClick = onClick,
    glass = true,
    modifier = Modifier.fluidContextMenu(contextActions),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(ContinuousCornerShape(FluidRadius.Control))
          .background(vivid.start.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = folderIconOf(recent.folder?.icon),
          contentDescription = null,
          tint = vivid.start,
          modifier = Modifier.size(22.dp),
        )
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        recent.folder?.let { folder ->
          Text(
            text = folder.name.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = vivid.start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Text(
          text = note.title,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        val preview = note.body.lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }?.trim()
        if (!preview.isNullOrBlank()) {
          Text(
            text = preview,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Row(
          modifier = Modifier.padding(top = 2.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = Formats.relativeDate(note.updatedAt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (recent.row.audioCount > 0) {
            FluidStatusBadge(label = Formats.durationShort(recent.row.audioDurationMs), tone = FluidTone.Info)
          }
          if (recent.row.untranscribedSessions > 0) {
            FluidStatusBadge(label = stringResource(R.string.note_to_transcribe), tone = FluidTone.Warning)
          }
        }
      }
    }
  }
}
