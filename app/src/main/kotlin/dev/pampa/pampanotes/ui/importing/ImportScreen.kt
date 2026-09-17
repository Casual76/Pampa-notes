package dev.pampa.pampanotes.ui.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import dev.pampa.pampanotes.core.importing.ImportCandidate
import dev.pampa.pampanotes.core.model.Dates
import dev.pampa.pampanotes.ui.common.Formats

/**
 * Il wizard: cosa hai scelto, dove va, e — se c'e' audio — in quale sessione.
 *
 * Una schermata invece di un foglio: i passi sono fino a quattro, c'e' una tastiera di mezzo per il
 * titolo, e un elenco di file che puo' essere lungo.
 */
@Composable
fun ImportRoute(
  onClose: () -> Unit,
  onOpenNote: (String) -> Unit,
  viewModel: ImportViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  ImportScreen(
    state = state,
    onClose = onClose,
    onOpenNote = onOpenNote,
    onToggle = viewModel::toggleCandidate,
    onSelectFolder = viewModel::selectFolder,
    onSelectNote = viewModel::selectNote,
    onTitleChange = viewModel::setNewNoteTitle,
    onCreateFolder = viewModel::createFolder,
    onSelectSession = viewModel::setAppendToSession,
    onNext = viewModel::next,
    onBack = viewModel::back,
  )
}

@Composable
private fun ImportScreen(
  state: ImportUiState,
  onClose: () -> Unit,
  onOpenNote: (String) -> Unit,
  onToggle: (String) -> Unit,
  onSelectFolder: (String) -> Unit,
  onSelectNote: (String?) -> Unit,
  onTitleChange: (String) -> Unit,
  onCreateFolder: (String) -> Unit,
  onSelectSession: (String?) -> Unit,
  onNext: () -> Unit,
  onBack: () -> Unit,
) {
  var creatingFolder by remember { mutableStateOf(false) }

  FluidScreen(
    title = stringResource(R.string.import_title),
    subtitle = stepSubtitle(state),
    onBack = if (state.step == ImportStep.REVIEW || state.step == ImportStep.INSPECTING) onClose else onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.TertiaryToPrimary, motif = FluidHeroMotif.Cards),
  ) {
    state.error?.let { message ->
      item { FluidInlineMessage(message = message, title = stringResource(R.string.import_problem), tone = FluidTone.Danger) }
    }

    when (state.step) {
      ImportStep.INSPECTING -> item {
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
          horizontalArrangement = Arrangement.Center,
        ) {
          FluidSpinner()
        }
      }

      ImportStep.REVIEW -> reviewStep(state, onToggle, onNext, onClose)
      ImportStep.DESTINATION -> destinationStep(state, onSelectFolder, onSelectNote, onTitleChange, onNext, { creatingFolder = true })
      ImportStep.AUDIO -> audioStep(state, onSelectSession, onNext)
      ImportStep.RUNNING -> runningStep(state)
      ImportStep.DONE -> doneStep(state, onClose, onOpenNote)
    }
  }

  if (creatingFolder) {
    dev.pampa.pampanotes.ui.common.FolderEditorSheet(
      title = stringResource(R.string.home_new_folder),
      initialName = "",
      initialTone = null,
      initialIcon = null,
      onDismiss = { creatingFolder = false },
      onConfirm = { name, _, _ ->
        onCreateFolder(name)
        creatingFolder = false
      },
    )
  }
}

@Composable
private fun stepSubtitle(state: ImportUiState): String? = when (state.step) {
  ImportStep.INSPECTING -> stringResource(R.string.import_inspecting)
  ImportStep.REVIEW -> pluralStringResource(R.plurals.import_items, state.candidates.size, state.candidates.size)
  ImportStep.DESTINATION -> stringResource(R.string.import_step_destination)
  ImportStep.AUDIO -> stringResource(R.string.import_step_audio)
  ImportStep.RUNNING -> state.progressLabel.takeIf { it.isNotBlank() }
  ImportStep.DONE -> null
}

private fun androidx.compose.foundation.lazy.LazyListScope.reviewStep(
  state: ImportUiState,
  onToggle: (String) -> Unit,
  onNext: () -> Unit,
  onClose: () -> Unit,
) {
  if (state.candidates.isEmpty()) {
    item {
      FluidEmptyState(
        title = stringResource(R.string.import_empty_title),
        detail = stringResource(R.string.import_empty_detail),
      )
    }
    item {
      FluidButton(
        text = stringResource(R.string.action_close),
        onClick = onClose,
        style = FluidButtonStyle.Tinted,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    return
  }

  item {
    FluidListGroup(glass = true) {
      state.candidates.forEachIndexed { index, candidate ->
        if (index > 0) FluidListDivider()
        val included = candidate.id !in state.excluded
        FluidListRow(
          title = candidate.displayName,
          subtitle = candidateSubtitle(candidate),
          eyebrow = kindLabel(candidate.kind),
          tone = if (candidate.isDuplicate) FluidTone.Warning else FluidTone.Neutral,
          badge = { FluidSwitch(checked = included, onCheckedChange = { onToggle(candidate.id) }) },
        )
      }
    }
  }

  if (state.candidates.any { it.isDuplicate }) {
    item { FluidSectionFootnote(text = stringResource(R.string.import_duplicate_note)) }
  }

  item {
    FluidButton(
      text = stringResource(R.string.action_continue),
      onClick = onNext,
      enabled = state.included.isNotEmpty(),
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.destinationStep(
  state: ImportUiState,
  onSelectFolder: (String) -> Unit,
  onSelectNote: (String?) -> Unit,
  onTitleChange: (String) -> Unit,
  onNext: () -> Unit,
  onCreateFolder: () -> Unit,
) {
  item { FluidSectionHeader(title = stringResource(R.string.import_folder)) }

  if (state.folders.isEmpty()) {
    item {
      FluidEmptyState(
        title = stringResource(R.string.home_empty_title),
        detail = stringResource(R.string.import_no_folder_detail),
      )
    }
  } else {
    item {
      FluidListGroup(glass = true) {
        state.folders.forEachIndexed { index, folder ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = folder.name,
            // Il percorso dei soli genitori: una riga che ripete il proprio titolo nel sottotitolo
            // si legge come un difetto. Per una cartella di primo livello si dice che lo e'.
            subtitle = state.folderPaths[folder.id]?.takeIf { it.isNotBlank() }
              ?: stringResource(R.string.import_folder_root),
            onClick = { onSelectFolder(folder.id) },
            badge = if (folder.id == state.selectedFolderId) {
              { FluidStatusBadge(label = stringResource(R.string.import_chosen), tone = FluidTone.Primary) }
            } else {
              null
            },
          )
        }
      }
    }
  }

  item {
    FluidButton(
      text = stringResource(R.string.home_new_folder),
      onClick = onCreateFolder,
      style = FluidButtonStyle.Plain,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }

  item { FluidSectionHeader(title = stringResource(R.string.import_note)) }
  item {
    FluidListGroup(glass = true) {
      FluidListRow(
        title = stringResource(R.string.import_new_note),
        subtitle = stringResource(R.string.import_new_note_detail),
        onClick = { onSelectNote(null) },
        badge = if (state.selectedNoteId == null) {
          { FluidStatusBadge(label = stringResource(R.string.import_chosen), tone = FluidTone.Primary) }
        } else {
          null
        },
      )
      state.notesInFolder.forEach { note ->
        FluidListDivider()
        FluidListRow(
          title = note.title,
          subtitle = stringResource(R.string.import_append_to_note),
          onClick = { onSelectNote(note.id) },
          badge = if (state.selectedNoteId == note.id) {
            { FluidStatusBadge(label = stringResource(R.string.import_chosen), tone = FluidTone.Primary) }
          } else {
            null
          },
        )
      }
    }
  }

  if (state.selectedNoteId == null) {
    item {
      FluidTextField(
        value = state.newNoteTitle,
        onValueChange = onTitleChange,
        label = stringResource(R.string.import_new_note_title),
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }

  item {
    FluidButton(
      text = stringResource(R.string.action_continue),
      onClick = onNext,
      enabled = state.selectedFolderId != null || state.selectedNoteId != null,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.audioStep(
  state: ImportUiState,
  onSelectSession: (String?) -> Unit,
  onNext: () -> Unit,
) {
  item { FluidSectionHeader(title = stringResource(R.string.import_audio_where), detail = stringResource(R.string.import_audio_where_detail)) }

  item {
    FluidListGroup(glass = true) {
      FluidListRow(
        title = stringResource(R.string.import_new_session),
        subtitle = stringResource(R.string.import_new_session_detail),
        onClick = { onSelectSession(null) },
        badge = if (state.appendToSessionId == null) {
          { FluidStatusBadge(label = stringResource(R.string.import_chosen), tone = FluidTone.Primary) }
        } else {
          null
        },
      )
      state.existingSessions.forEachIndexed { index, session ->
        FluidListDivider()
        val prettyDate = Dates.parseOrNull(session.date)?.let { Formats.relativeDate(it) } ?: session.date
        FluidListRow(
          title = stringResource(R.string.import_append_session, index + 1),
          subtitle = if (session.title.isBlank()) prettyDate else "$prettyDate · ${session.title}",
          onClick = { onSelectSession(session.id) },
          badge = if (state.appendToSessionId == session.id) {
            { FluidStatusBadge(label = stringResource(R.string.import_chosen), tone = FluidTone.Primary) }
          } else {
            null
          },
        )
      }
    }
  }

  item {
    FluidButton(
      text = stringResource(R.string.action_import),
      onClick = onNext,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.runningStep(state: ImportUiState) {
  item {
    FluidProgressBar(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
  }
  item {
    FluidSectionFootnote(text = state.progressLabel.ifBlank { stringResource(R.string.import_running) })
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.doneStep(
  state: ImportUiState,
  onClose: () -> Unit,
  onOpenNote: (String) -> Unit,
) {
  val outcome = state.outcome
  if (outcome == null) {
    item {
      FluidEmptyState(
        title = stringResource(R.string.import_failed_title),
        detail = state.error ?: stringResource(R.string.import_failed_detail),
      )
    }
  } else {
    item {
      FluidListGroup(glass = true) {
        outcome.imported.forEachIndexed { index, item ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = item.displayName,
            subtitle = item.detail ?: importedSubtitle(item.charsAdded),
            tone = when (item.status) {
              SourceStatus.OK -> FluidTone.Success
              SourceStatus.PARTIAL -> FluidTone.Warning
              SourceStatus.FAILED -> FluidTone.Danger
            },
          )
        }
      }
    }
    item {
      FluidButton(
        text = stringResource(R.string.import_open_note),
        onClick = { onOpenNote(outcome.noteId) },
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
  item {
    FluidButton(
      text = stringResource(R.string.action_close),
      onClick = onClose,
      style = FluidButtonStyle.Plain,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun importedSubtitle(chars: Int): String =
  if (chars > 0) pluralStringResource(R.plurals.note_source_chars, chars, chars) else stringResource(R.string.note_source_attached)

@Composable
private fun candidateSubtitle(candidate: ImportCandidate): String {
  if (candidate.isDuplicate) {
    return stringResource(R.string.import_duplicate_of, candidate.duplicateOfNoteTitle.orEmpty())
  }
  val size = Formats.bytes(candidate.sizeBytes)
  return if (candidate.isAudio && candidate.durationMs > 0) "${Formats.durationShort(candidate.durationMs)} · $size" else size
}

@Composable
private fun kindLabel(kind: SourceKind): String = stringResource(
  when (kind) {
    SourceKind.PDF -> R.string.kind_pdf
    SourceKind.DOCX -> R.string.kind_docx
    SourceKind.SDOCX -> R.string.kind_sdocx
    SourceKind.MARKDOWN -> R.string.kind_markdown
    SourceKind.TEXT -> R.string.kind_text
    SourceKind.IMAGE -> R.string.kind_image
    SourceKind.AUDIO -> R.string.kind_audio
    SourceKind.CLIPBOARD -> R.string.kind_clipboard
    SourceKind.SHARE -> R.string.kind_share
    SourceKind.OTHER -> R.string.kind_other
  },
)
