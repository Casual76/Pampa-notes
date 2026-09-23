package dev.pampa.pampanotes.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListScope
import dev.antigravity.fluidengine.ui.theme.FluidMetricTile
import dev.pampa.pampanotes.core.stats.StatsFormat
import dev.pampa.pampanotes.core.stats.TranscriptionStats
import dev.pampa.pampanotes.core.stats.displayTitle
import dev.pampa.pampanotes.core.stats.lessonMs
import java.time.LocalDate
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
import dev.pampa.pampanotes.core.export.ExportScope
import dev.pampa.pampanotes.ui.export.ExportSheet

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
  var exporting by remember { mutableStateOf<RecentNote?>(null) }

  val pinLabel = stringResource(R.string.note_pin)
  val unpinLabel = stringResource(R.string.note_unpin)
  val deleteLabel = stringResource(R.string.action_delete)
  val exportLabel = stringResource(R.string.action_export)
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
        description = funDescription(state.stats),
        icon = Icons.Rounded.AutoAwesome,
        metrics = buildList {
          add(
            FluidHeroMetric(
              label = stringResource(R.string.home_metric_folders),
              value = state.folderCount.toString(),
            ),
          )
          if (state.stats.audioMs >= 60_000) {
            add(FluidHeroMetric(label = stringResource(R.string.home_metric_hours), value = Formats.durationShort(state.stats.audioMs)))
          }
          if (state.stats.words > 0) {
            add(FluidHeroMetric(label = stringResource(R.string.home_metric_words), value = Formats.compact(state.stats.words)))
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
      transcriptionStatsSection(state.stats.transcription)
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
              FluidContextAction(label = exportLabel) { exporting = recent },
              FluidContextAction(label = deleteLabel, destructive = true) { pendingDelete = recent },
            )
          },
        )
      }
    }
  }

  exporting?.let { recent ->
    ExportSheet(scope = ExportScope.Note(recent.row.note.id), onDismiss = { exporting = null })
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


/**
 * La frase sotto il numero grande: due cose vere e un po' divertenti sull'archivio.
 *
 * Le parole trascritte confrontate con qualcosa che si sa quanto e' — un romanzo sono novantamila
 * parole, una pagina trecento — e la materia piu' ascoltata. Al massimo due frasi: la terza e' un
 * bollettino. Quando non c'e' ancora niente da dire, resta la frase di sempre.
 */
@Composable
private fun funDescription(stats: HomeStats): String {
  val sentences = buildList {
    if (stats.words >= 3_000) {
      val comparison = if (stats.words >= 90_000) {
        val novels = (stats.words / 90_000).toInt()
        pluralStringResource(R.plurals.home_fun_novels, novels, novels)
      } else {
        val pages = (stats.words / 300).toInt()
        pluralStringResource(R.plurals.home_fun_pages, pages, pages)
      }
      add(stringResource(R.string.home_fun_words, Formats.spoken(stats.words), comparison))
    }
    stats.topSubject?.takeIf { it.durationMs >= 60_000 }?.let { top ->
      add(stringResource(R.string.home_fun_subject, top.name, Formats.durationShort(top.durationMs)))
    }
    if (size < 2 && stats.lessonDays >= 2) add(stringResource(R.string.home_fun_days, stats.lessonDays))
  }
  return sentences.take(2).joinToString(" ").ifEmpty { stringResource(R.string.home_hero_description) }
}

/** Una tessera della sezione «Le tue trascrizioni», gia' scritta. */
private data class StatTile(val label: String, val value: String, val detail: String, val tone: FluidTone = FluidTone.Neutral)

/**
 * «Le tue trascrizioni»: quante ore, quanto svelto, chi parla piu' veloce, la lezione piu' lunga.
 *
 * Quattro tessere al massimo, due per riga, e ognuna c'e' solo se ha qualcosa di vero da dire: la
 * velocita' finche' questo dispositivo non ha misurato niente non c'e', i record con una lezione
 * sola nemmeno. Una sezione senza tessere non ha neanche il titolo.
 */
private fun LazyListScope.transcriptionStatsSection(stats: TranscriptionStats) {
  if (stats.isEmpty) return
  item(key = "stats-header") { FluidSectionHeader(title = stringResource(R.string.home_stats_section)) }
  item(key = "stats-tiles") {
    val tiles = transcriptionTiles(stats)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      tiles.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
          row.forEach { tile ->
            FluidMetricTile(
              label = tile.label,
              value = tile.value,
              detail = tile.detail,
              tone = tile.tone,
              glass = true,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun transcriptionTiles(stats: TranscriptionStats): List<StatTile> = buildList {
  // Sempre, quando c'e' almeno una lezione: e' la tessera che fa esistere la sezione (vedi
  // [TranscriptionStats.isEmpty]), e un titolo senza tessere sotto sarebbe una promessa vuota.
  if (stats.lessons > 0) {
    add(
      StatTile(
        label = stringResource(R.string.home_stats_transcribed_label),
        value = Formats.durationShort(stats.transcribedMs),
        detail = pluralStringResource(
          R.plurals.home_stats_transcribed_detail,
          stats.lessons,
          StatsFormat.count(stats.words),
          stats.lessons,
        ),
      ),
    )
  }
  stats.speed?.let { speed ->
    add(
      StatTile(
        label = stringResource(R.string.home_stats_speed_label),
        value = StatsFormat.factor(speed.average),
        // Le corse arrivano da tutti i dispositivi: il record fatto altrove dice dove.
        detail = speed.bestElsewhere.let { elsewhere ->
          when {
            speed.runs > 1 && elsewhere != null ->
              stringResource(R.string.home_stats_speed_detail_elsewhere, StatsFormat.factor(speed.best), elsewhere)
            speed.runs > 1 -> stringResource(R.string.home_stats_speed_detail, StatsFormat.factor(speed.best))
            elsewhere != null -> stringResource(R.string.home_stats_speed_detail_single_elsewhere, elsewhere)
            else -> stringResource(R.string.home_stats_speed_detail_single)
          }
        },
        // La tessera della velocita' prende l'accento: e' il numero che la home non aveva.
        tone = FluidTone.Primary,
      ),
    )
  }
  stats.fastestPace?.let { pace ->
    add(
      StatTile(
        label = stringResource(R.string.home_stats_pace_label),
        value = stringResource(R.string.home_stats_pace_value, pace.wordsPerMinute),
        detail = stringResource(R.string.home_stats_pace_detail, pace.session.displayTitle),
      ),
    )
  }
  stats.longest?.let { lesson ->
    val date = runCatching { LocalDate.parse(lesson.sessionDate) }.getOrNull()
    add(
      StatTile(
        label = stringResource(R.string.home_stats_longest_label),
        value = Formats.durationShort(lesson.lessonMs),
        detail = date?.let { stringResource(R.string.home_stats_longest_detail, lesson.displayTitle, Formats.relativeDate(it)) }
          ?: lesson.displayTitle,
      ),
    )
  }
}
