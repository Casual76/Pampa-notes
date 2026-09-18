package dev.pampa.pampanotes.ui.folder

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
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
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidQuickAction
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.export.ExportScope
import dev.pampa.pampanotes.ui.export.ExportSheet
import dev.pampa.pampanotes.core.db.FolderRow
import dev.pampa.pampanotes.core.db.NoteRow
import dev.pampa.pampanotes.ui.common.FolderEditorSheet
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.FolderIcon
import dev.pampa.pampanotes.ui.common.ambientMotifOf
import dev.pampa.pampanotes.ui.common.ambientToneOf
import dev.pampa.pampanotes.ui.common.folderIconOf
import dev.pampa.pampanotes.ui.common.toneFromName

@Composable
fun FolderRoute(
  folderId: String,
  onBack: () -> Unit,
  onOpenFolder: (String) -> Unit,
  onOpenNote: (String) -> Unit,
  onImport: () -> Unit,
  viewModel: FolderViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  FolderScreen(
    state = state,
    onBack = onBack,
    onOpenFolder = onOpenFolder,
    onOpenNote = onOpenNote,
    onImport = onImport,
    onQueryChange = viewModel::setQuery,
    onCreateSubfolder = viewModel::createSubfolder,
    onCreateNote = { title -> viewModel.createNote(title) { id -> onOpenNote(id) } },
    onUpdateFolder = viewModel::updateFolder,
    onDeleteFolder = viewModel::deleteFolder,
    onDeleteNote = viewModel::deleteNote,
    onTogglePinned = viewModel::togglePinned,
  )
}

@Composable
private fun FolderScreen(
  state: FolderUiState,
  onBack: () -> Unit,
  onOpenFolder: (String) -> Unit,
  onOpenNote: (String) -> Unit,
  onImport: () -> Unit,
  onQueryChange: (String) -> Unit,
  onCreateSubfolder: (String, String?, String?) -> Unit,
  onCreateNote: (String) -> Unit,
  onUpdateFolder: (String, String, String?, String?) -> Unit,
  onDeleteFolder: (String) -> Unit,
  onDeleteNote: (String) -> Unit,
  onTogglePinned: (String, Boolean) -> Unit,
) {
  var creatingFolder by remember { mutableStateOf(false) }
  var creatingNote by remember { mutableStateOf(false) }
  var renaming by remember { mutableStateOf<FolderRow?>(null) }
  var pendingFolderDelete by remember { mutableStateOf<FolderRow?>(null) }
  var pendingNoteDelete by remember { mutableStateOf<NoteRow?>(null) }
  var exporting by remember { mutableStateOf(false) }
  // Una riga tenuta premuta: la sottocartella o la nota da dare all'assistente.
  var exportingScope by remember { mutableStateOf<ExportScope?>(null) }

  // Le etichette dei menu si leggono qui: le lambda che le ricevono non sono composable.
  val renameLabel = stringResource(R.string.action_rename)
  val deleteLabel = stringResource(R.string.action_delete)
  val pinLabel = stringResource(R.string.note_pin)
  val unpinLabel = stringResource(R.string.note_unpin)
  val newNoteLabel = stringResource(R.string.folder_new_note)
  val newSubfolderLabel = stringResource(R.string.folder_new_subfolder)
  val importLabel = stringResource(R.string.action_import)
  val exportLabel = stringResource(R.string.action_export)

  val folderTone = toneFromName(state.folder?.tone)
  val folderIcon = FolderIcon.fromKey(state.folder?.icon)

  FluidScreen(
    title = state.folder?.name ?: stringResource(R.string.folder_loading),
    subtitle = state.path.dropLast(1).joinToString(" / ") { it.name }.takeIf { it.isNotEmpty() },
    onBack = onBack,
    // Il fondale prende il colore della cartella: la pagina e la sua tessera si somigliano.
    ambient = FluidAmbient(tone = ambientToneOf(folderTone), motif = ambientMotifOf(folderIcon)),
    actions = {
      FluidBarAction(
        icon = Icons.Rounded.Add,
        contentDescription = newNoteLabel,
        onClick = { creatingNote = true },
        // Tenuto: il tasto si apre nel proprio menu, dove c'e' anche la sottocartella.
        actions = {
          listOf(
            FluidContextAction(label = newNoteLabel) { creatingNote = true },
            FluidContextAction(label = importLabel) { onImport() },
            FluidContextAction(label = newSubfolderLabel) { creatingFolder = true },
            FluidContextAction(label = exportLabel) { exporting = true },
          )
        },
      )
    },
  ) {
    if (state.notes.size >= SEARCH_THRESHOLD) {
      item {
        FluidTextField(
          value = state.query,
          onValueChange = onQueryChange,
          placeholder = stringResource(R.string.folder_search_placeholder),
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }

    if (state.subfolders.isNotEmpty()) {
      item { FluidSectionHeader(title = stringResource(R.string.home_section_folders)) }
      item {
        FluidListGroup {
          state.subfolders.forEachIndexed { index, row ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = row.folder.name,
              subtitle = folderSubtitle(row),
              tone = toneFromName(row.folder.tone),
              onClick = { onOpenFolder(row.folder.id) },
              contextActions = {
                listOf(
                  FluidContextAction(label = renameLabel) { renaming = row },
                  FluidContextAction(label = exportLabel) { exportingScope = ExportScope.Folder(row.folder.id) },
                  FluidContextAction(label = deleteLabel, destructive = true) { pendingFolderDelete = row },
                )
              },
            )
          }
        }
      }
    }

    val notes = state.visibleNotes
    if (notes.isNotEmpty()) {
      if (state.subfolders.isNotEmpty()) {
        item { FluidSectionHeader(title = stringResource(R.string.folder_section_notes)) }
      }
      item {
        FluidListGroup {
          notes.forEachIndexed { index, row ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = row.note.title,
              subtitle = noteSubtitle(row),
              eyebrow = if (row.note.pinned) stringResource(R.string.note_pinned) else null,
              meta = Formats.relativeDate(row.note.updatedAt),
              badge = noteBadge(row),
              onClick = { onOpenNote(row.note.id) },
              contextActions = {
                listOf(
                  FluidContextAction(label = if (row.note.pinned) unpinLabel else pinLabel) {
                    onTogglePinned(row.note.id, !row.note.pinned)
                  },
                  FluidContextAction(label = exportLabel) { exportingScope = ExportScope.Note(row.note.id) },
                  FluidContextAction(label = deleteLabel, destructive = true) { pendingNoteDelete = row },
                )
              },
            )
          }
        }
      }
    }

    if (state.subfolders.isEmpty() && notes.isEmpty() && !state.loading) {
      item {
        FluidEmptyState(
          title = if (state.query.isBlank()) stringResource(R.string.folder_empty_title) else stringResource(R.string.search_no_results, state.query),
          detail = stringResource(R.string.folder_empty_detail),
        )
      }
      item {
        FluidQuickAction(
          label = stringResource(R.string.folder_new_note),
          onClick = { creatingNote = true },
          modifier = Modifier.fillMaxWidth(),
        )
      }
      item {
        FluidQuickAction(
          label = importLabel,
          onClick = onImport,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }

  if (creatingFolder) {
    FolderEditorSheet(
      title = stringResource(R.string.folder_new_subfolder),
      initialName = "",
      initialTone = null,
      initialIcon = null,
      onDismiss = { creatingFolder = false },
      onConfirm = { name, tone, icon ->
        onCreateSubfolder(name, tone, icon)
        creatingFolder = false
      },
    )
  }

  if (creatingNote) {
    FolderEditorSheet(
      title = stringResource(R.string.folder_new_note),
      initialName = "",
      initialTone = null,
      initialIcon = null,
      onDismiss = { creatingNote = false },
      onConfirm = { name, _, _ ->
        onCreateNote(name)
        creatingNote = false
      },
    )
  }

  state.folder?.let { folder ->
    if (exporting) ExportSheet(scope = ExportScope.Folder(folder.id), onDismiss = { exporting = false })
    exportingScope?.let { ExportSheet(scope = it, onDismiss = { exportingScope = null }) }
  }

  renaming?.let { row ->
    FolderEditorSheet(
      title = renameLabel,
      initialName = row.folder.name,
      initialTone = row.folder.tone,
      initialIcon = row.folder.icon,
      onDismiss = { renaming = null },
      onConfirm = { name, tone, icon ->
        onUpdateFolder(row.folder.id, name, tone, icon)
        renaming = null
      },
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
            onDeleteFolder(row.folder.id)
            pendingFolderDelete = null
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { pendingFolderDelete = null }),
      ),
    )
  }

  pendingNoteDelete?.let { row ->
    FluidAlert(
      onDismissRequest = { pendingNoteDelete = null },
      title = stringResource(R.string.note_delete_title, row.note.title),
      message = stringResource(R.string.note_delete_message),
      actions = listOf(
        FluidAlertAction(
          label = deleteLabel,
          emphasis = FluidAlertAction.Emphasis.Destructive,
          onClick = {
            onDeleteNote(row.note.id)
            pendingNoteDelete = null
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { pendingNoteDelete = null }),
      ),
    )
  }
}

/** Sopra questa soglia la cartella si cerca invece di scorrerla. */
private const val SEARCH_THRESHOLD = 8

@Composable
private fun folderSubtitle(row: FolderRow): String {
  val parts = buildList {
    if (row.noteCount > 0) add(pluralStringResource(R.plurals.folder_note_count, row.noteCount, row.noteCount))
    if (row.childCount > 0) add(pluralStringResource(R.plurals.folder_child_count, row.childCount, row.childCount))
  }
  return if (parts.isEmpty()) stringResource(R.string.folder_empty_meta) else parts.joinToString(" · ")
}

@Composable
private fun noteSubtitle(row: NoteRow): String {
  val parts = buildList {
    if (row.audioCount > 0) add(Formats.durationShort(row.audioDurationMs))
    if (row.sourceCount > 0) add(pluralStringResource(R.plurals.note_source_count, row.sourceCount, row.sourceCount))
  }
  return if (parts.isEmpty()) stringResource(R.string.note_text_only) else parts.joinToString(" · ")
}

@Composable
private fun noteBadge(row: NoteRow): (@Composable () -> Unit)? {
  if (row.untranscribedSessions <= 0) return null
  val label = stringResource(R.string.note_to_transcribe)
  return {
    dev.antigravity.fluidengine.ui.theme.FluidStatusBadge(
      label = label,
      tone = dev.antigravity.fluidengine.ui.theme.FluidTone.Warning,
    )
  }
}
