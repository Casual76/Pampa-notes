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

      ImportStep.REVIEW -> {
        val samsung = state.samsungNote
        if (samsung != null) {
          samsungStep(state, samsung, onSelectFolder, onTitleChange, onNext, { creatingFolder = true })
        } else {
          reviewStep(state, onToggle, onNext, onClose)
        }
      }
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
    FluidListGroup {
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

/**
 * Il percorso corto: una nota di Samsung Notes, da sola.
 *
 * E' il caso di tutti i giorni, quindi non passa dal wizard a quattro passi: una schermata che dice
 * cosa ha letto — titolo, quanti paragrafi, quante registrazioni e quanto durano — propone la
 * materia indovinata dal titolo, e un tasto. La destinazione si sceglie qui, non in un passo dopo,
 * perche' l'unica domanda che resta e' «in quale cartella», e una domanda sola non merita una pagina.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.samsungStep(
  state: ImportUiState,
  candidate: ImportCandidate,
  onSelectFolder: (String) -> Unit,
  onTitleChange: (String) -> Unit,
  onNext: () -> Unit,
  onCreateFolder: () -> Unit,
) {
  val doc = candidate.sdocx ?: return

  item {
    FluidListGroup {
      FluidListRow(
        title = doc.title ?: candidate.displayName,
        subtitle = samsungSummary(doc),
        eyebrow = stringResource(R.string.import_samsung_eyebrow),
        meta = Formats.bytes(candidate.sizeBytes),
        tone = if (candidate.isDuplicate) FluidTone.Warning else FluidTone.Primary,
      )
    }
  }
  if (candidate.isDuplicate) {
    // Qui non c'e' un interruttore da accendere: si dice cosa succede premendo il tasto.
    item {
      FluidSectionFootnote(
        text = stringResource(R.string.import_samsung_duplicate, candidate.duplicateOfNoteTitle.orEmpty()),
      )
    }
  }

  item {
    FluidTextField(
      value = state.newNoteTitle,
      onValueChange = onTitleChange,
      label = stringResource(R.string.import_samsung_title),
      modifier = Modifier.fillMaxWidth(),
    )
  }

  item { FluidSectionHeader(title = stringResource(R.string.import_samsung_folder)) }
  if (state.folders.isEmpty()) {
    item {
      FluidEmptyState(
        title = stringResource(R.string.home_empty_title),
        detail = stringResource(R.string.import_no_folder_detail),
      )
    }
  } else {
    item {
      FluidListGroup {
        state.folders.forEachIndexed { index, folder ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = folder.name,
            subtitle = state.folderPaths[folder.id]?.takeIf { it.isNotBlank() } ?: stringResource(R.string.import_folder_root),
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

  item {
    FluidButton(
      text = stringResource(R.string.action_import),
      onClick = onNext,
      enabled = state.selectedFolderId != null && state.newNoteTitle.isNotBlank(),
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/** «4 paragrafi · 2 registrazioni, 1 h 8» — quello che si e' letto, prima di importare. */
@Composable
private fun samsungSummary(doc: dev.pampa.pampanotes.core.importing.SdocxDocument): String {
  val pieces = buildList {
    if (doc.paragraphCount > 0) add(pluralStringResource(R.plurals.import_samsung_paragraphs, doc.paragraphCount, doc.paragraphCount))
    if (doc.recordings.isNotEmpty()) {
      val recordings = pluralStringResource(R.plurals.import_samsung_recordings, doc.recordings.size, doc.recordings.size)
      add(if (doc.totalDurationMs > 0) "$recordings, ${Formats.durationShort(doc.totalDurationMs)}" else recordings)
    }
  }
  return pieces.ifEmpty { listOf(stringResource(R.string.import_samsung_empty)) }.joinToString(" · ")
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
      FluidListGroup {
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
    FluidListGroup {
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
    FluidListGroup {
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
      FluidListGroup {
        outcome.imported.forEachIndexed { index, item ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = item.displayName,
            subtitle = item.detail ?: importedSubtitle(item),
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
private fun importedSubtitle(item: dev.pampa.pampanotes.core.importing.ImportedItem): String = when {
  item.charsAdded > 0 -> pluralStringResource(R.plurals.note_source_chars, item.charsAdded, item.charsAdded)
  // Una registrazione non e' un allegato: e' finita in una sessione, ed e' quello che va detto.
  item.kind == SourceKind.AUDIO -> stringResource(R.string.import_done_recording)
  else -> stringResource(R.string.note_source_attached)
}

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
