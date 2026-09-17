package dev.pampa.pampanotes.ui.home

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
import dev.antigravity.fluidengine.ui.fluid.FluidBarAction
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidHeroCard
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
fun HomeRoute(
  onOpenFolder: (String) -> Unit,
  viewModel: HomeViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  HomeScreen(
    state = state,
    onOpenFolder = onOpenFolder,
    onCreateFolder = viewModel::createFolder,
    onRenameFolder = viewModel::renameFolder,
    onDeleteFolder = viewModel::deleteFolder,
  )
}

@Composable
private fun HomeScreen(
  state: HomeUiState,
  onOpenFolder: (String) -> Unit,
  onCreateFolder: (String, String?) -> Unit,
  onRenameFolder: (String, String) -> Unit,
  onDeleteFolder: (String) -> Unit,
) {
  var editing by remember { mutableStateOf<FolderEditRequest?>(null) }
  var pendingDelete by remember { mutableStateOf<FolderRow?>(null) }

  // Le etichette del menu contestuale si leggono qui: la lambda che le riceve non e' composable.
  val renameLabel = stringResource(R.string.action_rename)
  val deleteLabel = stringResource(R.string.action_delete)
  val newFolderLabel = stringResource(R.string.home_new_folder)

  FluidScreen(
    title = stringResource(R.string.home_title),
    subtitle = stringResource(R.string.home_subtitle),
    actions = {
      FluidBarAction(
        icon = Icons.Rounded.Add,
        contentDescription = newFolderLabel,
        onClick = { editing = FolderEditRequest.New },
      )
    },
  ) {
    item {
      FluidHeroCard(
        title = stringResource(R.string.home_hero_title, state.noteCount, state.folderCount),
        subtitle = if (state.activeJobs > 0) {
          pluralStringResource(R.plurals.home_hero_subtitle_jobs, state.activeJobs, state.activeJobs)
        } else {
          stringResource(R.string.home_hero_subtitle_idle)
        },
      )
    }

    if (state.folders.isEmpty() && !state.loading) {
      item {
        FluidEmptyState(
          title = stringResource(R.string.home_empty_title),
          detail = stringResource(R.string.home_empty_detail),
        )
      }
      item {
        FluidQuickAction(
          label = newFolderLabel,
          onClick = { editing = FolderEditRequest.New },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }

    if (state.folders.isNotEmpty()) {
      item { FluidSectionHeader(title = stringResource(R.string.home_section_folders)) }
      item {
        FluidListGroup {
          state.folders.forEachIndexed { index, row ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = row.folder.name,
              subtitle = folderSubtitle(row),
              tone = toneFromName(row.folder.tone),
              onClick = { onOpenFolder(row.folder.id) },
              contextActions = {
                listOf(
                  FluidContextAction(label = renameLabel) {
                    editing = FolderEditRequest.Rename(row.folder.id, row.folder.name)
                  },
                  FluidContextAction(label = deleteLabel, destructive = true) {
                    pendingDelete = row
                  },
                )
              },
            )
          }
        }
      }
    }

    if (state.recentNotes.isNotEmpty()) {
      item { FluidSectionHeader(title = stringResource(R.string.home_section_recent)) }
      item {
        FluidListGroup {
          state.recentNotes.forEachIndexed { index, row ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = row.note.title,
              subtitle = noteSubtitle(row),
              meta = Formats.relativeDate(row.note.updatedAt),
              onClick = { onOpenFolder(row.note.folderId) },
            )
          }
        }
      }
    }
  }

  editing?.let { request ->
    FolderEditorSheet(
      title = if (request is FolderEditRequest.Rename) renameLabel else newFolderLabel,
      initialName = (request as? FolderEditRequest.Rename)?.name.orEmpty(),
      initialTone = null,
      showToneChooser = request is FolderEditRequest.New,
      onDismiss = { editing = null },
      onConfirm = { name, tone ->
        when (request) {
          FolderEditRequest.New -> onCreateFolder(name, tone)
          is FolderEditRequest.Rename -> onRenameFolder(request.id, name)
        }
        editing = null
      },
    )
  }

  pendingDelete?.let { row ->
    dev.antigravity.fluidengine.ui.fluid.FluidAlert(
      onDismissRequest = { pendingDelete = null },
      title = stringResource(R.string.folder_delete_title, row.folder.name),
      message = stringResource(R.string.folder_delete_message),
      actions = listOf(
        dev.antigravity.fluidengine.ui.fluid.FluidAlertAction(
          label = deleteLabel,
          emphasis = dev.antigravity.fluidengine.ui.fluid.FluidAlertAction.Emphasis.Destructive,
          onClick = {
            onDeleteFolder(row.folder.id)
            pendingDelete = null
          },
        ),
        dev.antigravity.fluidengine.ui.fluid.FluidAlertAction(
          label = stringResource(R.string.action_cancel),
          onClick = { pendingDelete = null },
        ),
      ),
    )
  }
}

private sealed interface FolderEditRequest {
  data object New : FolderEditRequest
  data class Rename(val id: String, val name: String) : FolderEditRequest
}

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
