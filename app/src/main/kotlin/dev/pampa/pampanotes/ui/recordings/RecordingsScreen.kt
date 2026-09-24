package dev.pampa.pampanotes.ui.recordings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GraphicEq
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
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidQuickAction
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.FolderRow
import dev.pampa.pampanotes.core.export.ExportScope
import dev.pampa.pampanotes.core.stats.StatsFormat
import dev.pampa.pampanotes.ui.common.FolderEditorSheet
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.OverflowMenuButton
import dev.pampa.pampanotes.ui.common.folderIconOf
import dev.pampa.pampanotes.ui.common.jobBadgeLabel
import dev.pampa.pampanotes.ui.common.rememberPullToSync
import dev.pampa.pampanotes.ui.common.toneFromName
import dev.pampa.pampanotes.ui.export.ExportSheet
import dev.pampa.pampanotes.ui.folder.SectionMoveAlert

/**
 * Registrazioni: l'audio che non e' una lezione — una registrazione di diciannove ore, un'intervista,
 * un viaggio — accanto alle materie, senza mescolarsi con loro.
 *
 * Il contrario della home: li' la cosa importante e' il testo che si da' all'assistente, qui e'
 * l'audio. Un tocco su una registrazione la **ascolta** (la sessione con l'audio piu' recente),
 * tenendo premuto c'e' tutto il resto; in cima i numeri di questa sezione soltanto. Nessuna materia:
 * la schermata non si iscrive a `ReportSubject`, e resta dell'accento dell'app.
 */
@Composable
fun RecordingsRoute(
  onOpenFolder: (String) -> Unit,
  onOpenNote: (String) -> Unit,
  onOpenSession: (String) -> Unit,
  onImportInto: (String) -> Unit,
  onImport: () -> Unit,
  viewModel: RecordingsViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var editing by remember { mutableStateOf<RecordingsFolderEdit?>(null) }
  var pendingFolderDelete by remember { mutableStateOf<FolderRow?>(null) }
  var pendingNoteDelete by remember { mutableStateOf<RecordingItem?>(null) }
  var movingOut by remember { mutableStateOf<FolderRow?>(null) }
  var exporting by remember { mutableStateOf<ExportScope?>(null) }
  // Tirando giu' si sincronizza, come nelle altre schede (vedi PullToSync).
  val pull = rememberPullToSync()

  val newFolderLabel = stringResource(R.string.home_new_folder)
  val importLabel = stringResource(R.string.action_import)
  val editLabel = stringResource(R.string.action_edit)
  val exportLabel = stringResource(R.string.action_export)
  val deleteLabel = stringResource(R.string.action_delete)
  val openNoteLabel = stringResource(R.string.recordings_open_note)
  val transcribeLabel = stringResource(R.string.selection_transcribe)
  val importHereLabel = stringResource(R.string.recordings_import_here)
  val toSchoolLabel = stringResource(R.string.recordings_move_out)
  val keepHereLabel = stringResource(R.string.recordings_keep_here)
  val computerOnlyLabel = stringResource(R.string.recordings_computer_only)

  // «Importa» della barra: dentro l'unica cartella, se ce n'e' una sola; se no il wizard, che le
  // mostra tutte con Registrazioni a parte.
  val importAction: () -> Unit = {
    state.folders.singleOrNull()?.let { onImportInto(it.folder.id) } ?: onImport()
  }

  FluidScreen(
    title = stringResource(R.string.recordings_title),
    subtitle = stringResource(R.string.recordings_subtitle),
    // Onde e non le righe del quaderno: qui si ascolta, non si prendono appunti.
    ambient = FluidAmbient(tone = FluidHeroTone.Secondary, motif = FluidHeroMotif.Ripples),
    isRefreshing = pull.isRefreshing,
    onRefresh = pull.onRefresh,
    actions = {
      FluidBarAction(icon = Icons.Rounded.Add, contentDescription = newFolderLabel, onClick = { editing = RecordingsFolderEdit.New })
      if (!state.isEmpty) {
        OverflowMenuButton(
          actions = {
            buildList {
              add(FluidContextAction(label = importLabel) { importAction() })
              // Di serie le Registrazioni stanno solo sul computer; il tablet a casa puo' tenerle.
              if (state.keepHere) {
                add(FluidContextAction(label = computerOnlyLabel, enabled = state.hasComputer) { viewModel.setKeepHere(false) })
              } else {
                add(FluidContextAction(label = keepHereLabel) { viewModel.setKeepHere(true) })
              }
            }
          },
        )
      }
    },
  ) {
    if (state.isEmpty) {
      item {
        FluidEmptyState(
          title = stringResource(R.string.recordings_empty_title),
          detail = stringResource(R.string.recordings_empty_detail),
        )
      }
      item {
        FluidQuickAction(label = newFolderLabel, onClick = { editing = RecordingsFolderEdit.New }, modifier = Modifier.fillMaxWidth())
      }
      return@FluidScreen
    }

    item(key = "hero") { RecordingsHero(state.stats) }

    item(key = "folders-header") { FluidSectionHeader(title = stringResource(R.string.home_section_folders)) }
    item(key = "folders") {
      FluidListGroup {
        state.folders.forEachIndexed { index, row ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = row.folder.name,
            subtitle = folderSubtitle(row),
            tone = toneFromName(row.folder.tone),
            leading = { Icon(imageVector = folderIconOf(row.folder.icon), contentDescription = null) },
            onClick = { onOpenFolder(row.folder.id) },
            contextActions = {
              listOf(
                FluidContextAction(label = importHereLabel) { onImportInto(row.folder.id) },
                FluidContextAction(label = editLabel) { editing = RecordingsFolderEdit.Existing(row) },
                FluidContextAction(label = exportLabel) { exporting = ExportScope.Folder(row.folder.id) },
                FluidContextAction(label = toSchoolLabel) { movingOut = row },
                FluidContextAction(label = deleteLabel, destructive = true) { pendingFolderDelete = row },
              )
            },
          )
        }
      }
    }

    if (state.recordings.isEmpty()) {
      item(key = "no-recordings") {
        FluidEmptyState(
          title = stringResource(R.string.recordings_none_title),
          detail = stringResource(R.string.recordings_none_detail),
        )
      }
      item(key = "no-recordings-import") {
        FluidQuickAction(label = importLabel, onClick = importAction, modifier = Modifier.fillMaxWidth())
      }
    } else {
      item(key = "recordings-header") { FluidSectionHeader(title = stringResource(R.string.recordings_section_list)) }
      item(key = "recordings") {
        FluidListGroup {
          state.recordings.forEachIndexed { index, item ->
            if (index > 0) FluidListDivider()
            val note = item.row.note
            FluidListRow(
              title = note.title,
              subtitle = recordingSubtitle(item),
              meta = Formats.relativeDate(note.updatedAt),
              badge = recordingBadge(item),
              // Il tocco ascolta: e' la cosa che si viene a fare qui.
              leading = { Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.recordings_listen)) },
              onClick = { viewModel.listen(note.id, onSession = onOpenSession, onNote = onOpenNote) },
              contextActions = {
                buildList {
                  add(FluidContextAction(label = openNoteLabel) { onOpenNote(note.id) })
                  if (item.toTranscribe > 0 && item.job == null) {
                    add(FluidContextAction(label = transcribeLabel) { viewModel.transcribe(listOf(note.id)) })
                  }
                  add(FluidContextAction(label = exportLabel) { exporting = ExportScope.Note(note.id) })
                  add(FluidContextAction(label = deleteLabel, destructive = true) { pendingNoteDelete = item })
                }
              },
            )
          }
        }
      }
      if (state.canTranscribeAll) {
        item(key = "transcribe-all") {
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

    item(key = "where") {
      FluidSectionFootnote(
        text = stringResource(
          when {
            state.keepHere -> R.string.recordings_footnote_here
            state.hasComputer -> R.string.recordings_footnote_computer
            else -> R.string.recordings_footnote_no_computer
          },
        ),
      )
    }
  }

  editing?.let { request ->
    val existing = (request as? RecordingsFolderEdit.Existing)?.row?.folder
    FolderEditorSheet(
      title = if (existing == null) newFolderLabel else editLabel,
      initialName = existing?.name.orEmpty(),
      initialTone = existing?.tone,
      initialIcon = existing?.icon,
      onDismiss = { editing = null },
      onConfirm = { name, tone, icon ->
        if (existing == null) viewModel.createFolder(name, tone, icon) else viewModel.updateFolder(existing.id, name, tone, icon)
        editing = null
      },
    )
  }

  exporting?.let { scope -> ExportSheet(scope = scope, onDismiss = { exporting = null }) }

  movingOut?.let { row ->
    SectionMoveAlert(
      name = row.folder.name,
      toPersonal = false,
      onConfirm = {
        viewModel.moveToSchool(row.folder.id)
        movingOut = null
      },
      onDismiss = { movingOut = null },
    )
  }

  pendingFolderDelete?.let { row ->
    FluidAlert(
      onDismissRequest = { pendingFolderDelete = null },
      title = stringResource(R.string.folder_delete_title, row.folder.name),
      message = stringResource(R.string.folder_delete_message),
      actions = listOf(
        FluidAlertAction(
          label = deleteLabel,
          emphasis = FluidAlertAction.Emphasis.Destructive,
          onClick = {
            viewModel.deleteFolder(row.folder.id)
            pendingFolderDelete = null
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { pendingFolderDelete = null }),
      ),
    )
  }

  pendingNoteDelete?.let { item ->
    FluidAlert(
      onDismissRequest = { pendingNoteDelete = null },
      title = stringResource(R.string.note_delete_title, item.row.note.title),
      message = stringResource(R.string.note_delete_message),
      actions = listOf(
        FluidAlertAction(
          label = deleteLabel,
          emphasis = FluidAlertAction.Emphasis.Destructive,
          onClick = {
            viewModel.deleteNote(item.row.note.id)
            pendingNoteDelete = null
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { pendingNoteDelete = null }),
      ),
    )
  }
}

private sealed interface RecordingsFolderEdit {
  data object New : RecordingsFolderEdit
  data class Existing(val row: FolderRow) : RecordingsFolderEdit
}

/**
 * I numeri della sezione: il grande e' quanto si e' registrato, perche' qui l'audio viene prima del
 * testo; sotto quanto e' gia' trascritto, le parole e la velocita'. Una tessera senza niente di vero
 * da dire non c'e', come nella home.
 */
@Composable
private fun RecordingsHero(stats: RecordingsStats) {
  val transcription = stats.transcription
  FluidHero(
    tone = FluidHeroTone.Secondary,
    motif = FluidHeroMotif.Ripples,
    eyebrow = stringResource(R.string.recordings_hero_eyebrow),
    value = Formats.durationShort(stats.recordedMs),
    title = stringResource(R.string.recordings_hero_title),
    description = if (transcription.lessons > 0) {
      pluralStringResource(
        R.plurals.recordings_hero_description,
        transcription.lessons,
        Formats.durationShort(transcription.transcribedMs),
        transcription.lessons,
      )
    } else {
      stringResource(R.string.recordings_hero_description_none)
    },
    icon = Icons.Rounded.GraphicEq,
    metrics = buildList {
      if (transcription.transcribedMs > 0) {
        add(FluidHeroMetric(label = stringResource(R.string.recordings_metric_transcribed), value = Formats.durationShort(transcription.transcribedMs)))
      }
      if (stats.words > 0) {
        add(FluidHeroMetric(label = stringResource(R.string.home_metric_words), value = Formats.compact(stats.words)))
      }
      transcription.speed?.let { speed ->
        add(FluidHeroMetric(label = stringResource(R.string.home_stats_speed_label), value = StatsFormat.factor(speed.average)))
      }
    },
  )
}

@Composable
private fun folderSubtitle(row: FolderRow): String {
  val parts = buildList {
    if (row.noteCount > 0) add(pluralStringResource(R.plurals.folder_note_count, row.noteCount, row.noteCount))
    if (row.childCount > 0) add(pluralStringResource(R.plurals.folder_child_count, row.childCount, row.childCount))
  }
  return if (parts.isEmpty()) stringResource(R.string.folder_empty_meta) else parts.joinToString(" · ")
}

/** La cartella e quanto dura: di una registrazione si vuole sapere prima di tutto quanto e' lunga. */
@Composable
private fun recordingSubtitle(item: RecordingItem): String {
  val parts = buildList {
    item.folder?.name?.let(::add)
    if (item.row.audioCount > 0) add(Formats.durationShort(item.row.audioDurationMs))
    if (item.row.sessionCount > 1) add(pluralStringResource(R.plurals.home_session_count, item.row.sessionCount, item.row.sessionCount))
  }
  return parts.ifEmpty { listOf(stringResource(R.string.note_text_only)) }.joinToString(" · ")
}

/** Lo stato della trascrizione: un lavoro in corso, «Da trascrivere», dove, o «Trascritta». */
@Composable
private fun recordingBadge(item: RecordingItem): (@Composable () -> Unit)? {
  val (label, tone) = when {
    item.job != null -> jobBadgeLabel(item.job) to FluidTone.Primary
    item.toTranscribe > 0 -> stringResource(R.string.note_to_transcribe) to FluidTone.Warning
    item.elsewhere != null && item.elsewhere.untranscribed > 0 ->
      stringResource(R.string.transcribing_elsewhere, item.elsewhere.device) to FluidTone.Primary
    item.row.audioCount > 0 -> stringResource(R.string.recordings_transcribed) to FluidTone.Success
    else -> return null
  }
  return { FluidStatusBadge(label = label, tone = tone) }
}
