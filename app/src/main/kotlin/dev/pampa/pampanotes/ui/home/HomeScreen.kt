package dev.pampa.pampanotes.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidBarAction
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidHero
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMetric
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidMetricTile
import dev.antigravity.fluidengine.ui.theme.FluidQuickAction
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.NoteRow
import dev.pampa.pampanotes.core.export.ExportScope
import dev.pampa.pampanotes.core.model.Dates
import dev.pampa.pampanotes.core.stats.StatsFormat
import dev.pampa.pampanotes.core.stats.TranscriptionStats
import dev.pampa.pampanotes.core.stats.displayTitle
import dev.pampa.pampanotes.core.stats.lessonMs
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.folderIconOf
import dev.pampa.pampanotes.ui.common.jobStateLabel
import dev.pampa.pampanotes.ui.common.UpdateHomeCard
import dev.pampa.pampanotes.ui.common.UpdateViewModel
import dev.pampa.pampanotes.ui.common.folderVividColors
import dev.pampa.pampanotes.ui.common.toneFromName
import dev.pampa.pampanotes.ui.common.rememberComputerOnly
import dev.pampa.pampanotes.ui.common.rememberPullToSync
import dev.pampa.pampanotes.ui.export.ExportSheet
import java.time.LocalDate

/**
 * La Home: prima quello che c'e' da fare, poi quello che hai scritto per ultimo.
 *
 * Non e' l'indice delle cartelle — quello e' la scheda accanto — ma il punto in cui si riprende in
 * mano il lavoro: la lezione che si stava ascoltando, le registrazioni ancora da trascrivere, e le
 * note in ordine di quando sono state scritte davvero (vedi `NoteDates`), non di quando sono
 * arrivate. Le righe sono quelle compatte di una lista raggruppata: venti note si scorrono con un
 * pollice, venti card con due righe d'anteprima no.
 */
@Composable
fun HomeRoute(
  onOpenNote: (String) -> Unit,
  onImport: () -> Unit,
  onOpenJobs: () -> Unit,
  onResumeSession: (String) -> Unit,
  viewModel: HomeViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val updates: UpdateViewModel = hiltViewModel()
  val update by updates.state.collectAsStateWithLifecycle()
  var pendingDelete by remember { mutableStateOf<RecentNote?>(null) }
  val computerOnly = rememberComputerOnly()
  var exporting by remember { mutableStateOf<RecentNote?>(null) }
  // Tirando giu' la home si sincronizza: quello che si e' fatto sull'altro dispositivo, adesso.
  val pull = rememberPullToSync()

  val pinLabel = stringResource(R.string.note_pin)
  val unpinLabel = stringResource(R.string.note_unpin)
  val deleteLabel = stringResource(R.string.action_delete)
  val exportLabel = stringResource(R.string.action_export)
  val importLabel = stringResource(R.string.action_import)
  val hideLabel = stringResource(R.string.home_resume_hide)

  // Il menu tenendo premuto una nota, uguale in «Da fare» e in «Ultime note».
  val noteContextActions: (RecentNote) -> List<FluidContextAction> = { recent ->
    listOf(
      FluidContextAction(label = if (recent.row.note.pinned) unpinLabel else pinLabel) {
        viewModel.togglePinned(recent.row.note.id, !recent.row.note.pinned)
      },
      FluidContextAction(label = exportLabel) { exporting = recent },
      computerOnly.noteAction(recent.row.note.id, recent.row.note.folderId, recent.row.note.title),
      FluidContextAction(label = deleteLabel, destructive = true) { pendingDelete = recent },
    )
  }

  FluidScreen(
    title = stringResource(R.string.home_title),
    subtitle = stringResource(R.string.home_subtitle),
    // Il fondale: e' quello che il vetro delle card ha da rifrangere. Senza, il materiale non si
    // vede e la pagina torna quella grigia di prima.
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Glow),
    isRefreshing = pull.isRefreshing,
    onRefresh = pull.onRefresh,
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

    // Una versione nuova, sopra tutto il resto: e' l'unico modo in cui la trova chi non apre lo store.
    if (update.supported && update.offerOnHome) {
      item(key = "update") { UpdateHomeCard(update, updates) }
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
      state.resume?.let { card ->
        item(key = "resume-header") { FluidSectionHeader(title = stringResource(R.string.home_resume_section)) }
        item(key = "resume") {
          ResumeRow(
            card = card,
            onClick = { onResumeSession(card.sessionId) },
            contextActions = { listOf(FluidContextAction(label = hideLabel) { viewModel.dismissResume() }) },
          )
        }
      }

      if (state.todo.isNotEmpty()) {
        item(key = "todo-header") {
          FluidSectionHeader(
            title = stringResource(R.string.home_section_todo),
            detail = if (state.todoCount > state.todo.size) {
              stringResource(R.string.home_todo_more, state.todo.size, state.todoCount)
            } else {
              null
            },
          )
        }
        item(key = "todo") {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NoteGroup(notes = state.todo, onOpenNote = onOpenNote, contextActions = noteContextActions)
            if (state.canTranscribeAll) {
              FluidButton(
                text = stringResource(R.string.home_todo_transcribe_all),
                onClick = viewModel::transcribeAll,
                style = FluidButtonStyle.Tinted,
                fillWidth = true,
                leading = { Icon(Icons.Rounded.Mic, contentDescription = null) },
              )
            }
          }
        }
      }

      if (state.recent.isNotEmpty()) {
        item(key = "recent-header") { FluidSectionHeader(title = stringResource(R.string.home_section_recent)) }
        item(key = "recent") {
          NoteGroup(notes = state.recent, onOpenNote = onOpenNote, contextActions = noteContextActions)
        }
      }

      transcriptionStatsSection(state.stats.transcription)
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
 * Un gruppo di note, una riga ciascuna: la materia nel suo colore sopra il titolo, una riga di
 * contenuto, la data vera, e a destra quello che conta adesso — il lavoro in corso, «Da
 * trascrivere», o quanto dura.
 */
@Composable
private fun NoteGroup(
  notes: List<RecentNote>,
  onOpenNote: (String) -> Unit,
  contextActions: (RecentNote) -> List<FluidContextAction>,
) {
  val pinned = stringResource(R.string.note_pinned)
  FluidListGroup {
    notes.forEachIndexed { index, recent ->
      if (index > 0) FluidListDivider()
      val note = recent.row.note
      val date = Formats.relativeDate(note.updatedAt)
      FluidListRow(
        title = note.title,
        subtitle = noteSubtitle(recent.row),
        eyebrow = recent.folder?.name,
        meta = if (note.pinned) "$pinned · $date" else date,
        tone = toneFromName(recent.folder?.tone),
        badge = noteBadge(recent),
        // L'icona nel colore della materia, come la sua tessera: la riga e' compatta, ma Storia si
        // riconosce ancora a colpo d'occhio.
        leading = {
          Icon(
            imageVector = folderIconOf(recent.folder?.icon),
            contentDescription = null,
            tint = folderVividColors(toneFromName(recent.folder?.tone)).start,
          )
        },
        onClick = { onOpenNote(note.id) },
        contextActions = { contextActions(recent) },
      )
    }
  }
}

/**
 * La riga di contenuto: quante lezioni e quante fonti, o le prime parole per una nota di solo testo.
 * Una riga sola — l'anteprima di due righe era quello che rendeva la home lunga il doppio.
 */
@Composable
private fun noteSubtitle(row: NoteRow): String {
  val parts = buildList {
    if (row.sessionCount > 0) add(pluralStringResource(R.plurals.home_session_count, row.sessionCount, row.sessionCount))
    if (row.sourceCount > 0) add(pluralStringResource(R.plurals.note_source_count, row.sourceCount, row.sourceCount))
  }
  if (parts.isNotEmpty()) return parts.joinToString(" · ")
  return firstWords(row.note.body) ?: stringResource(R.string.note_text_only)
}

/** Le prime parole del corpo, senza i titoli Markdown, tagliate a una parola intera. */
private fun firstWords(body: String, maxChars: Int = 60): String? {
  val line = body.lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }?.trim() ?: return null
  if (line.length <= maxChars) return line
  val cut = line.take(maxChars).substringBeforeLast(' ').ifBlank { line.take(maxChars) }
  return "$cut…"
}

@Composable
private fun noteBadge(recent: RecentNote): (@Composable () -> Unit)? {
  val job = recent.job
  val row = recent.row
  val (label, tone) = when {
    job != null -> jobBadgeLabel(job) to FluidTone.Primary
    recent.toTranscribe > 0 -> stringResource(R.string.note_to_transcribe) to FluidTone.Warning
    // Tutto quello che mancava lo sta trascrivendo un altro dispositivo: e' in corso, non da fare.
    recent.elsewhere != null && recent.elsewhere.untranscribed > 0 ->
      stringResource(R.string.transcribing_elsewhere, recent.elsewhere.device) to FluidTone.Primary
    row.audioCount > 0 -> Formats.durationShort(row.audioDurationMs) to FluidTone.Neutral
    else -> return null
  }
  return { FluidStatusBadge(label = label, tone = tone) }
}

/** «In coda», «Trascrizione · 42%»: lo stato, e quanto manca quando lo si sa. */
@Composable
private fun jobBadgeLabel(job: JobEntity): String {
  val state = jobStateLabel(job.state)
  val percent = (job.progress * 100).toInt()
  return if (job.state.isRunning && percent in 1..99) stringResource(R.string.home_job_progress, state, percent) else state
}

/**
 * «Riprendi ad ascoltare»: la nota, il giorno della lezione e il minuto, e quanto ne manca. Un
 * tocco apre la sessione e riparte da li'; tenendo premuto la si toglie.
 */
@Composable
private fun ResumeRow(
  card: ResumeCard,
  onClick: () -> Unit,
  contextActions: () -> List<FluidContextAction>,
) {
  val day = Dates.parseOrNull(card.sessionDate)?.let { Formats.relativeDate(it) } ?: card.sessionDate
  val lesson = if (card.sessionTitle.isBlank()) day else "${card.sessionTitle} · $day"
  val remaining = (card.durationMs - card.positionMs).coerceAtLeast(0)
  val remainingLabel = stringResource(R.string.home_resume_left, Formats.durationShort(remaining))
  val resumeLabel = stringResource(R.string.home_resume_play)
  FluidListGroup {
    FluidListRow(
      title = card.noteTitle,
      subtitle = stringResource(R.string.home_resume_position, lesson, Formats.duration(card.positionMs)),
      eyebrow = card.folder?.name,
      meta = stringResource(R.string.home_resume_listened, Formats.relativeDate(card.at)),
      tone = toneFromName(card.folder?.tone),
      badge = if (card.durationMs > 0) {
        { FluidStatusBadge(label = remainingLabel, tone = FluidTone.Info) }
      } else {
        null
      },
      leading = { Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = resumeLabel) },
      onClick = onClick,
      contextActions = contextActions,
    )
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
