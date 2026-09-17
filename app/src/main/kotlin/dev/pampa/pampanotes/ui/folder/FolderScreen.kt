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
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.FolderRow
import dev.pampa.pampanotes.core.db.NoteRow
import dev.pampa.pampanotes.ui.common.FolderEditorSheet
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.toneFromName

@Composable
fun FolderRoute(
  folderId: String,
  onBack: () -> Unit,
  onOpenFolder: (String) -> Unit,
  viewModel: FolderViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  FolderScreen(
    state = state,
    onBack = onBack,
    onOpenFolder = onOpenFolder,
    onQueryChange = viewModel::setQuery,
    onCreateSubfolder = viewModel::createSubfolder,
    onCreateNote = { title -> viewModel.createNote(title) },
    onRenameFolder = viewModel::renameFolder,
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
  onQueryChange: (String) -> Unit,
  onCreateSubfolder: (String, String?) -> Unit,
  onCreateNote: (String) -> Unit,
  onRenameFolder: (String, String) -> Unit,
  onDeleteFolder: (String) -> Unit,
  onDeleteNote: (String) -> Unit,
  onTogglePinned: (String, Boolean) -> Unit,
) {
  var creatingFolder by remember { mutableStateOf(false) }
  var creatingNote by remember { mutableStateOf(false) }
  var renaming by remember { mutableStateOf<FolderRow?>(null) }
  var pendingFolderDelete by remember { mutableStateOf<FolderRow?>(null) }
  var pendingNoteDelete by remember { mutableStateOf<NoteRow?>(null) }

  // Le etichette dei menu si leggono qui: le lambda che le ricevono non sono composable.
  val renameLabel = stringResource(R.string.action_rename)
  val deleteLabel = stringResource(R.string.action_delete)
  val pinLabel = stringResource(R.string.note_pin)
  val unpinLabel = stringResource(R.string.note_unpin)
  val newNoteLabel = stringResource(R.string.folder_new_note)
  val newSubfolderLabel = stringResource(R.string.folder_new_subfolder)

  FluidScreen(
    title = state.folder?.name ?: stringResource(R.string.folder_loading),
    subtitle = state.path.dropLast(1).joinToString(" / ") { it.name }.takeIf { it.isNotEmpty() },
    onBack = onBack,
    actions = {
      FluidBarAction(
        icon = Icons.Rounded.Add,
        contentDescription = newNoteLabel,
        onClick = { creatingNote = true },
        // Tenuto: il tasto si apre nel proprio menu, dove c'e' anche la sottocartella.
        actions = {
          listOf(
            FluidContextAction(label = newNoteLabel) { creatingNote = true },
            FluidContextAction(label = newSubfolderLabel) { creatingFolder = true },
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
              // La nota si apre in M1: per ora la riga mostra quello che c'e' dentro.
              onClick = null,
              contextActions = {
                listOf(
                  FluidContextAction(label = if (row.note.pinned) unpinLabel else pinLabel) {
                    onTogglePinned(row.note.id, !row.note.pinned)
                  },
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
    }
  }

  if (creatingFolder) {
    FolderEditorSheet(
      title = stringResource(R.string.folder_new_subfolder),
      initialName = "",
      initialTone = null,
      showToneChooser = true,
      onDismiss = { creatingFolder = false },
      onConfirm = { name, tone ->
        onCreateSubfolder(name, tone)
        creatingFolder = false
      },
    )
  }

  if (creatingNote) {
    FolderEditorSheet(
      title = stringResource(R.string.folder_new_note),
      initialName = "",
      initialTone = null,
      showToneChooser = false,
      onDismiss = { creatingNote = false },
      onConfirm = { name, _ ->
        onCreateNote(name)
        creatingNote = false
      },
    )
  }

  renaming?.let { row ->
    FolderEditorSheet(
      title = renameLabel,
      initialName = row.folder.name,
      initialTone = row.folder.tone,
      showToneChooser = true,
      onDismiss = { renaming = null },
      onConfirm = { name, _ ->
        onRenameFolder(row.folder.id, name)
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
