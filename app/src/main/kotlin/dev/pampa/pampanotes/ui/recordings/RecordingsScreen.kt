package dev.pampa.pampanotes.ui.recordings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.IosShare
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
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.FolderRow
import dev.pampa.pampanotes.core.export.ExportScope
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.ui.common.ComputerOnlyConfirmAlert
import dev.pampa.pampanotes.core.stats.StatsFormat
import dev.pampa.pampanotes.ui.common.FolderEditorSheet
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.OverflowMenuButton
import dev.pampa.pampanotes.ui.common.folderIconOf
import dev.pampa.pampanotes.ui.common.jobBadgeLabel
import dev.pampa.pampanotes.ui.common.rememberPullToSync
import dev.pampa.pampanotes.ui.common.toneFromName
import dev.pampa.pampanotes.ui.export.ExportSheet
import dev.pampa.pampanotes.ui.common.SelectionMark
import dev.pampa.pampanotes.ui.common.rememberSelection
import dev.pampa.pampanotes.ui.common.selectionTitle
import androidx.compose.material.icons.rounded.Delete
import dev.pampa.pampanotes.ui.folder.SectionMoveAlert

import androidx.compose.material.icons.rounded.DriveFileMove
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.fluid.fluidGlassGroups
import dev.pampa.pampanotes.core.repo.PersonalScope
import dev.pampa.pampanotes.ui.common.FolderPickerSheet
import dev.pampa.pampanotes.ui.common.rememberComputerOnly

/** The same session-first library at the root and inside an optional personal folder. */
@Composable
fun RecordingsRoute(
  onOpenFolder: (String) -> Unit,
  onOpenNote: (String) -> Unit,
  onListenSession: (String) -> Unit,
  onImportInto: (String) -> Unit,
  onBack: (() -> Unit)? = null,
  viewModel: RecordingsViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val transcribeAllAsk by viewModel.transcribeAllAsk.collectAsStateWithLifecycle()
  val computerOnlyAsk by viewModel.computerOnlyAsk.collectAsStateWithLifecycle()
  var editing by remember { mutableStateOf<RecordingsFolderEdit?>(null) }
  var pendingFolderDelete by remember { mutableStateOf<FolderRow?>(null) }
  var movingOut by remember { mutableStateOf<FolderRow?>(null) }
  var exporting by remember { mutableStateOf<ExportScope?>(null) }
  var deleting by remember { mutableStateOf<Set<String>?>(null) }
  var moving by remember { mutableStateOf(false) }
  var moveTarget by remember { mutableStateOf<String?>(null) }
  val selection = rememberSelection()
  val selecting = selection.active
  val selected = RecordingCatalog.selected(selection.ids, state.recordings.map { it.id })
  val pull = rememberPullToSync()
  val computerOnly = rememberComputerOnly()
  val newFolderLabel = stringResource(R.string.rv2_new_folder)
  val importLabel = stringResource(R.string.rv2_import)
  val defaultFolderName = stringResource(R.string.recordings_title)
  val editLabel = stringResource(R.string.action_edit)
  val exportLabel = stringResource(R.string.action_export)
  val deleteLabel = stringResource(R.string.action_delete)
  val materialLabel = stringResource(R.string.rv2_associated_material)
  val transcribeLabel = stringResource(R.string.selection_transcribe)
  val moveLabel = stringResource(R.string.selection_move)
  val toSchoolLabel = stringResource(R.string.recordings_move_out)
  val keepHereLabel = stringResource(R.string.recordings_keep_here)
  val computerOnlyLabel = stringResource(R.string.recordings_computer_only)
  val selectLabel = stringResource(R.string.action_select)
  val anyPending = state.recordings.any { it.id in selected && it.worthTranscribing > 0 && it.job == null }
  val importAction = { viewModel.prepareImport(defaultFolderName, onImportInto) }
  val visible = state.recordings.filter {
    state.query.isBlank() || it.session.session.title.contains(state.query, true) ||
      it.note.title.contains(state.query, true) || it.session.session.date.contains(state.query, true)
  }
  val materials = state.materials.filter { state.query.isBlank() || it.note.title.contains(state.query, true) || it.note.body.contains(state.query, true) }

  FluidScreen(
    title = if (selecting) selectionTitle(selection) else state.folder?.name ?: stringResource(R.string.recordings_title),
    subtitle = if (selecting) null else stringResource(R.string.rv2_subtitle),
    onBack = if (selecting) selection::exit else onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Secondary, motif = FluidHeroMotif.Ripples),
    isRefreshing = pull.isRefreshing,
    onRefresh = pull.onRefresh.takeUnless { selecting },
    actions = {
      if (selecting) {
        FluidBarAction(icon = Icons.Rounded.IosShare, contentDescription = exportLabel, enabled = selected.isNotEmpty(), onClick = {
          exporting = ExportScope.Sessions(selected.toList(), defaultFolderName)
          selection.exit()
        })
        if (anyPending) FluidBarAction(icon = Icons.Rounded.Mic, contentDescription = transcribeLabel, onClick = {
          viewModel.askTranscribe(selected)
          selection.exit()
        })
        FluidBarAction(icon = Icons.Rounded.DriveFileMove, contentDescription = moveLabel, enabled = selected.isNotEmpty(), onClick = { moving = true })
        FluidBarAction(icon = Icons.Rounded.Delete, contentDescription = deleteLabel, enabled = selected.isNotEmpty(), onClick = { deleting = selected })
      } else {
        FluidBarAction(icon = Icons.Rounded.Add, contentDescription = importLabel, onClick = importAction)
        OverflowMenuButton(actions = {
          buildList {
            add(FluidContextAction(label = newFolderLabel) { editing = RecordingsFolderEdit.New })
            if (state.recordings.isNotEmpty()) add(FluidContextAction(label = selectLabel) { selection.start() })
            state.folder?.let { folder ->
              add(FluidContextAction(label = editLabel) { editing = RecordingsFolderEdit.Existing(FolderRow(folder, 0, 0)) })
              add(FluidContextAction(label = exportLabel) { exporting = ExportScope.Folder(folder.id) })
              add(computerOnly.folderAction(folder.id, folder.name))
              if (folder.parentId == null) add(FluidContextAction(label = toSchoolLabel) { movingOut = FolderRow(folder, 0, 0) })
              add(FluidContextAction(label = deleteLabel, destructive = true) { pendingFolderDelete = FolderRow(folder, 0, 0) })
            }
            if (state.folder == null && !state.mirror) {
              if (state.keepHere) add(FluidContextAction(label = computerOnlyLabel, enabled = state.hasComputer) { viewModel.askComputerOnly() })
              else add(FluidContextAction(label = keepHereLabel) { viewModel.keepHere() })
            }
          }
        })
      }
    },
  ) {
    if (!selecting && viewModel.folderId == null && state.stats.recordedMs > 0) item(key = "hero") { RecordingsHero(state.stats) }
    if (!selecting && (state.recordings.isNotEmpty() || state.materials.isNotEmpty() || state.query.isNotBlank())) item(key = "search") {
      FluidTextField(value = state.query, onValueChange = viewModel::setQuery, placeholder = stringResource(R.string.rv2_search), modifier = Modifier.fillMaxWidth())
    }
    if (visible.isNotEmpty()) {
      item(key = "recordings-header") { FluidSectionHeader(title = stringResource(R.string.recordings_section_list)) }
      fluidGlassGroups(items = visible, key = "recordings") { item ->
        val checked = item.id in selected
        FluidListRow(
          title = item.session.session.title.ifBlank { item.note.title },
          subtitle = computerOnly.noteSubtitle(item.note.id, item.note.folderId, recordingSubtitle(item)),
          badge = recordingBadge(item),
          tone = if (checked) FluidTone.Primary else FluidTone.Neutral,
          leading = {
            if (selecting) SelectionMark(checked)
            else Icon(imageVector = if (item.session.parts.isEmpty()) Icons.Rounded.Description else Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.rv2_open_recording))
          },
          onClick = { if (selecting) selection.toggle(item.id) else onListenSession(item.id) },
          contextActions = if (selecting) null else ({
            buildList {
              add(FluidContextAction(label = materialLabel) { onOpenNote(item.note.id) })
              add(FluidContextAction(label = selectLabel) { selection.start(item.id) })
              add(FluidContextAction(label = moveLabel) { selection.start(item.id); moving = true })
              if (item.toTranscribe > 0 && item.job == null) add(FluidContextAction(label = transcribeLabel) { viewModel.askTranscribe(setOf(item.id)) })
              add(FluidContextAction(label = exportLabel) {
                exporting = ExportScope.Sessions(listOf(item.id), item.session.session.title.ifBlank { item.note.title })
              })
              add(FluidContextAction(label = deleteLabel, destructive = true) { deleting = setOf(item.id) })
            }
          }),
        )
      }
    }
    if (!selecting && state.canTranscribeAll) item(key = "transcribe-all") {
      FluidButton(text = stringResource(R.string.home_todo_transcribe_all), onClick = viewModel::askTranscribeAll,
        style = FluidButtonStyle.Tinted, fillWidth = true, leading = { Icon(Icons.Rounded.Mic, contentDescription = null) })
    }
    if (!selecting && materials.isNotEmpty()) {
      item(key = "material-header") { FluidSectionHeader(title = stringResource(R.string.rv2_materials)) }
      fluidGlassGroups(items = materials, key = "materials") { row ->
        FluidListRow(
          title = row.note.title,
          subtitle = stringResource(R.string.rv2_material_detail),
          leading = { Icon(Icons.Rounded.Description, contentDescription = null) },
          onClick = { onOpenNote(row.note.id) },
        )
      }
    }
    if (!selecting && state.folders.isNotEmpty()) {
      item(key = "folders-header") { FluidSectionHeader(title = stringResource(R.string.rv2_optional_folders)) }
      fluidGlassGroups(items = state.folders, key = "folders") { row ->
        FluidListRow(
          title = row.folder.name,
          subtitle = stringResource(R.string.rv2_folder_detail),
          tone = toneFromName(row.folder.tone),
          leading = { Icon(folderIconOf(row.folder.icon), contentDescription = null) },
          onClick = { onOpenFolder(row.folder.id) },
          contextActions = {
            buildList {
              add(FluidContextAction(label = importLabel) { onImportInto(row.folder.id) })
              add(FluidContextAction(label = editLabel) { editing = RecordingsFolderEdit.Existing(row) })
              add(FluidContextAction(label = exportLabel) { exporting = ExportScope.Folder(row.folder.id) })
              add(computerOnly.folderAction(row.folder.id, row.folder.name))
              if (row.folder.parentId == null) add(FluidContextAction(label = toSchoolLabel) { movingOut = row })
              add(FluidContextAction(label = deleteLabel, destructive = true) { pendingFolderDelete = row })
            }
          },
        )
      }
    }
    if (!selecting && !state.loading && visible.isEmpty() && materials.isEmpty()) {
      item(key = "empty") {
        FluidEmptyState(
          title = if (state.query.isBlank()) stringResource(R.string.recordings_none_title) else stringResource(R.string.search_no_results, state.query),
          detail = stringResource(R.string.rv2_empty_detail),
        )
      }
      item(key = "import") { FluidButton(text = importLabel, onClick = importAction, fillWidth = true) }
    }
    if (!selecting) item(key = "where") {
      FluidSectionFootnote(text = stringResource(when (state.where) {
        RecordingsWhere.MIRROR -> R.string.recordings_footnote_mirror
        RecordingsWhere.HERE -> R.string.recordings_footnote_here
        RecordingsWhere.COMPUTER -> R.string.recordings_footnote_computer
        RecordingsWhere.NO_ARCHIVE -> R.string.recordings_footnote_no_archive
        RecordingsWhere.NO_COMPUTER -> R.string.recordings_footnote_no_computer
      }))
    }
  }

  transcribeAllAsk?.let { ask ->
    val custom = ask.provider == TranscriptionProviderId.CUSTOM
    val provider = stringResource(when (ask.provider.id) {
      "custom" -> R.string.provider_custom
      "openrouter" -> R.string.provider_openrouter
      "mistral" -> R.string.provider_mistral
      else -> R.string.provider_groq
    })
    FluidAlert(
      onDismissRequest = viewModel::dismissTranscribeAll,
      title = pluralStringResource(R.plurals.recordings_transcribe_all_title, ask.sessionIds.size, ask.sessionIds.size),
      message = listOfNotNull(
        stringResource(R.string.rv2_transcribe_message, Formats.durationShort(ask.durationMs), provider),
        if (custom) null else stringResource(R.string.recordings_transcribe_all_cloud),
        if (ask.skippedSilent > 0) stringResource(R.string.recordings_transcribe_all_skipped) else null,
      ).joinToString(" "),
      actions = listOf(
        FluidAlertAction(label = transcribeLabel, emphasis = FluidAlertAction.Emphasis.Preferred, onClick = viewModel::confirmTranscribeAll),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = viewModel::dismissTranscribeAll),
      ),
    )
  }
  computerOnlyAsk?.let { preview ->
    ComputerOnlyConfirmAlert(title = stringResource(R.string.computer_only_confirm_title, stringResource(R.string.recordings_title)),
      preview = preview, onConfirm = viewModel::confirmComputerOnly, onDismiss = viewModel::dismissComputerOnly, confirmLabel = computerOnlyLabel)
  }
  editing?.let { request ->
    val existing = (request as? RecordingsFolderEdit.Existing)?.row?.folder
    FolderEditorSheet(title = if (existing == null) newFolderLabel else editLabel,
      initialName = existing?.name.orEmpty(), initialTone = existing?.tone, initialIcon = existing?.icon,
      placeholder = stringResource(R.string.recordings_folder_placeholder), onDismiss = { editing = null },
      onConfirm = { name, tone, icon ->
        if (existing == null) viewModel.createFolder(name, tone, icon) else viewModel.updateFolder(existing.id, name, tone, icon)
        editing = null
      })
  }
  exporting?.let { ExportSheet(scope = it, onDismiss = { exporting = null }) }
  deleting?.let { ids ->
    FluidAlert(onDismissRequest = { deleting = null }, title = stringResource(R.string.rv2_delete_title),
      message = pluralStringResource(R.plurals.rv2_delete_message, ids.size, ids.size),
      actions = listOf(
        FluidAlertAction(label = deleteLabel, emphasis = FluidAlertAction.Emphasis.Destructive, onClick = {
          viewModel.deleteRecordings(ids); deleting = null; selection.exit()
        }),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { deleting = null }),
      ))
  }
  if (moving) FolderPickerSheet(title = moveLabel, folders = state.allFolders, excludeId = state.folder?.id,
    personalFolderIds = PersonalScope.folderIds(state.allFolders), onDismiss = { moving = false },
    onPick = { target -> moving = false; moveTarget = target })
  moveTarget?.let { target ->
    RecordingMoveAlert(name = state.allFolders.firstOrNull { it.id == target }?.name.orEmpty(),
      toPersonal = PersonalScope.isPersonal(target, state.allFolders), count = selected.size,
      onDismiss = { moveTarget = null }, onConfirm = {
        viewModel.moveRecordings(selected, target); moveTarget = null; selection.exit()
      })
  }
  movingOut?.let { row ->
    SectionMoveAlert(name = row.folder.name, toPersonal = false, onDismiss = { movingOut = null }, onConfirm = {
      viewModel.moveToSchool(row.folder.id); movingOut = null
      if (row.folder.id == viewModel.folderId) onBack?.invoke()
    })
  }
  pendingFolderDelete?.let { row ->
    FluidAlert(onDismissRequest = { pendingFolderDelete = null }, title = stringResource(R.string.folder_delete_title, row.folder.name),
      message = stringResource(R.string.rv2_delete_folder_message), actions = listOf(
        FluidAlertAction(label = deleteLabel, emphasis = FluidAlertAction.Emphasis.Destructive, onClick = {
          viewModel.deleteFolder(row.folder.id); pendingFolderDelete = null
          if (row.folder.id == viewModel.folderId) onBack?.invoke()
        }),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { pendingFolderDelete = null }),
      ))
  }
}

private sealed interface RecordingsFolderEdit {
  data object New : RecordingsFolderEdit
  data class Existing(val row: FolderRow) : RecordingsFolderEdit
}

@Composable
private fun RecordingMoveAlert(name: String, toPersonal: Boolean, count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  val where by hiltViewModel<dev.pampa.pampanotes.ui.common.ComputerOnlyViewModel>().state.collectAsStateWithLifecycle()
  FluidAlert(onDismissRequest = onDismiss, title = stringResource(R.string.selection_move),
    message = listOfNotNull(
      pluralStringResource(R.plurals.rv2_move_message, count, count, name),
      if (toPersonal) null else stringResource(R.string.rv2_move_school),
      if (!toPersonal && where.personalOnComputer) stringResource(R.string.recordings_move_out_files) else null,
    ).joinToString(" "),
    actions = listOf(
      FluidAlertAction(label = stringResource(R.string.selection_move), emphasis = FluidAlertAction.Emphasis.Preferred, onClick = onConfirm),
      FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = onDismiss),
    ))
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
private fun recordingSubtitle(item: RecordingItem): String = buildList {
  add(item.session.session.date)
  item.folder?.name?.let(::add)
  if (item.session.parts.isNotEmpty()) add(Formats.durationShort(item.session.durationMs))
}.joinToString(" · ")

@Composable
private fun recordingBadge(item: RecordingItem): (@Composable () -> Unit)? {
  val (label, tone) = when {
    item.job != null -> jobBadgeLabel(item.job) to FluidTone.Primary
    item.elsewhere != null -> stringResource(R.string.transcribing_elsewhere, item.elsewhere.device) to FluidTone.Primary
    item.toTranscribe > 0 && item.failed?.errorCode == "no_speech" -> stringResource(R.string.recordings_silent) to FluidTone.Neutral
    item.toTranscribe > 0 && item.failed != null -> stringResource(R.string.recordings_failed) to FluidTone.Danger
    item.toTranscribe > 0 -> stringResource(R.string.note_to_transcribe) to FluidTone.Warning
    item.session.session.activeTranscriptId != null -> stringResource(R.string.recordings_transcribed) to FluidTone.Success
    else -> return null
  }
  return { FluidStatusBadge(label = label, tone = tone) }
}
