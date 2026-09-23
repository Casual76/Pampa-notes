package dev.pampa.pampanotes.ui.importing

import androidx.activity.compose.BackHandler
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
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
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
import dev.pampa.pampanotes.core.importing.ImportSummary
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
    onUpdateExisting = viewModel::setUpdateExisting,
    onCreateFolder = viewModel::createFolder,
    onSelectSession = viewModel::setAppendToSession,
    onToggleGroupStart = viewModel::toggleGroupStart,
    onSplitAll = viewModel::splitAllGroups,
    onJoinAll = viewModel::joinAllGroups,
    onGroupTitle = viewModel::setGroupTitle,
    onGroupsAsNotes = viewModel::setGroupsAsNotes,
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
  onUpdateExisting: (Boolean) -> Unit,
  onCreateFolder: (String) -> Unit,
  onSelectSession: (String?) -> Unit,
  onToggleGroupStart: (String) -> Unit,
  onSplitAll: () -> Unit,
  onJoinAll: () -> Unit,
  onGroupTitle: (String, String) -> Unit,
  onGroupsAsNotes: (Boolean) -> Unit,
  onNext: () -> Unit,
  onBack: () -> Unit,
) {
  var creatingFolder by remember { mutableStateOf(false) }
  var confirmingLeave by remember { mutableStateOf(false) }

  // Indietro fa un passo indietro, non chiude il wizard: chi ha scelto la cartella e torna a
  // guardare i file non deve ricominciare da capo. Mentre scrive, chiede: il lavoro vive con la
  // schermata, e uscire lo fermerebbe a meta'. A import finito, chiude.
  val handleBack: () -> Unit = {
    when (state.step) {
      ImportStep.INSPECTING, ImportStep.REVIEW, ImportStep.DONE -> onClose()
      ImportStep.DESTINATION, ImportStep.AUDIO -> onBack()
      ImportStep.RUNNING -> confirmingLeave = true
    }
  }
  BackHandler(onBack = handleBack)

  FluidScreen(
    title = stringResource(R.string.import_title),
    subtitle = stepSubtitle(state),
    onBack = handleBack,
    ambient = FluidAmbient(tone = FluidHeroTone.TertiaryToPrimary, motif = FluidHeroMotif.Cards),
  ) {
    state.error?.let { message ->
      item { FluidInlineMessage(message = message, title = stringResource(R.string.import_problem), tone = FluidTone.Danger) }
    }
    state.errorRes?.takeIf { state.error == null }?.let { res ->
      item { FluidInlineMessage(message = stringResource(res), title = stringResource(R.string.import_problem), tone = FluidTone.Danger) }
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
        // [ImportUiState.samsungNote] e' null quando la nota e' gia' scelta: un `.sdocx` importato
        // dentro una nota passa dall'elenco normale, e il suo testo si accoda come ogni documento.
        val samsung = state.samsungNote
        if (samsung != null) {
          samsungStep(state, samsung, onSelectFolder, onTitleChange, onUpdateExisting, onNext, { creatingFolder = true })
        } else {
          reviewStep(state, onToggle, onNext, onClose)
        }
      }
      ImportStep.DESTINATION -> destinationStep(state, onSelectFolder, onSelectNote, onTitleChange, onNext, { creatingFolder = true })
      ImportStep.AUDIO -> audioStep(state, onSelectSession, onToggleGroupStart, onSplitAll, onJoinAll, onGroupTitle, onGroupsAsNotes, onNext)
      ImportStep.RUNNING -> runningStep(state)
      ImportStep.DONE -> doneStep(state, onClose, onOpenNote)
    }
  }

  if (confirmingLeave) {
    FluidAlert(
      onDismissRequest = { confirmingLeave = false },
      title = stringResource(R.string.import_leave_title),
      message = stringResource(R.string.import_leave_message),
      actions = listOf(
        FluidAlertAction(label = stringResource(R.string.import_leave_stay), onClick = { confirmingLeave = false }, emphasis = FluidAlertAction.Emphasis.Preferred),
        FluidAlertAction(
          label = stringResource(R.string.import_leave_anyway),
          emphasis = FluidAlertAction.Emphasis.Destructive,
          onClick = {
            confirmingLeave = false
            onClose()
          },
        ),
      ),
    )
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
  onUpdateExisting: (Boolean) -> Unit,
  onNext: () -> Unit,
  onCreateFolder: () -> Unit,
) {
  val doc = candidate.sdocx ?: return
  val updating = candidate.canUpdate && state.updateExisting

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

  if (candidate.canUpdate) {
    // La stessa nota, una versione dopo: e' il caso normale di chi prende appunti in Samsung Notes.
    // Aggiornare e' la prima scelta; una seconda nota con lo stesso titolo si sceglie apposta.
    item { FluidSectionHeader(title = stringResource(R.string.import_samsung_update_header)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.import_samsung_update, candidate.updateOfNoteTitle.orEmpty()),
          subtitle = stringResource(R.string.import_samsung_update_detail),
          onClick = { onUpdateExisting(true) },
          badge = if (state.updateExisting) {
            { FluidStatusBadge(label = stringResource(R.string.import_chosen), tone = FluidTone.Primary) }
          } else {
            null
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.import_samsung_new),
          subtitle = stringResource(R.string.import_samsung_new_detail),
          onClick = { onUpdateExisting(false) },
          badge = if (!state.updateExisting) {
            { FluidStatusBadge(label = stringResource(R.string.import_chosen), tone = FluidTone.Primary) }
          } else {
            null
          },
        )
      }
    }
  }

  if (!updating) item {
    FluidTextField(
      value = state.newNoteTitle,
      onValueChange = onTitleChange,
      label = stringResource(R.string.import_samsung_title),
      modifier = Modifier.fillMaxWidth(),
    )
  }

  if (!updating) item { FluidSectionHeader(title = stringResource(R.string.import_samsung_folder)) }
  if (updating) {
    // Niente da scegliere: la nota e la sua cartella ci sono gia'.
  } else if (state.folders.isEmpty()) {
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
  if (!updating) item {
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
      text = stringResource(if (updating) R.string.action_update else R.string.action_import),
      onClick = onNext,
      enabled = updating || (state.selectedFolderId != null && state.newNoteTitle.isNotBlank()),
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
    if (doc.handwrittenPages > 0) add(pluralStringResource(R.plurals.note_handwriting_done, doc.handwrittenPages, doc.handwrittenPages))
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
  onToggleGroupStart: (String) -> Unit,
  onSplitAll: () -> Unit,
  onJoinAll: () -> Unit,
  onGroupTitle: (String, String) -> Unit,
  onGroupsAsNotes: (Boolean) -> Unit,
  onNext: () -> Unit,
) {
  // La domanda «in coda a quale sessione» ha senso solo se una sessione c'e'. In una nota nuova
  // l'unica risposta sarebbe «una nuova», e una domanda con una risposta sola non e' una domanda.
  if (state.existingSessions.isNotEmpty()) {
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
  }

  if (state.canGroup) groupingSection(state, onToggleGroupStart, onSplitAll, onJoinAll, onGroupTitle, onGroupsAsNotes)

  item {
    FluidButton(
      text = stringResource(R.string.action_import),
      onClick = onNext,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/**
 * Dove staccare, fra piu' registrazioni importate insieme.
 *
 * Un interruttore per registrazione, dalla seconda in poi: acceso, da li' comincia una nota (o una
 * lezione) nuova. L'etichetta sopra ogni riga dice a colpo d'occhio in quale gruppo e' finita, che
 * e' l'unica cosa da controllare prima di premere Importa. Le due scorciatoie in cima coprono i
 * casi che non si vogliono fare a mano: dieci lezioni distinte, o dieci pezzi della stessa.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.groupingSection(
  state: ImportUiState,
  onToggleGroupStart: (String) -> Unit,
  onSplitAll: () -> Unit,
  onJoinAll: () -> Unit,
  onGroupTitle: (String, String) -> Unit,
  onGroupsAsNotes: (Boolean) -> Unit,
) {
  val grouping = state.grouping ?: return
  val groups = state.groupsView
  val asNotes = state.selectedNoteId == null && grouping.asNotes

  item { FluidSectionHeader(title = stringResource(R.string.import_group_header), detail = stringResource(R.string.import_group_detail)) }

  item {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      FluidButton(
        text = stringResource(R.string.import_group_all),
        onClick = onJoinAll,
        style = if (grouping.isSplit) FluidButtonStyle.Tinted else FluidButtonStyle.Plain,
        enabled = grouping.isSplit,
        modifier = Modifier.weight(1f),
      )
      FluidButton(
        text = stringResource(R.string.import_group_each),
        onClick = onSplitAll,
        style = if (groups.size < state.audioInOrder.size) FluidButtonStyle.Tinted else FluidButtonStyle.Plain,
        enabled = groups.size < state.audioInOrder.size,
        modifier = Modifier.weight(1f),
      )
    }
  }

  // Note separate o lezioni della stessa nota: solo per una nota nuova, e solo se si e' diviso
  // qualcosa. Dentro una nota che c'e' gia' non si creano note, quindi non c'e' niente da scegliere.
  if (state.selectedNoteId == null && grouping.isSplit) {
    item {
      val labels = mapOf(
        true to stringResource(R.string.import_group_as_notes),
        false to stringResource(R.string.import_group_as_sessions),
      )
      FluidSegmentedControl(
        options = listOf(true, false),
        selected = grouping.asNotes,
        onSelect = onGroupsAsNotes,
        label = { labels.getValue(it) },
      )
    }
  }

  item {
    FluidListGroup {
      state.audioInOrder.forEachIndexed { index, candidate ->
        if (index > 0) FluidListDivider()
        val group = grouping.groupOf(candidate.id) + 1
        val startsNew = candidate.id in grouping.startsNew
        FluidListRow(
          title = candidate.displayName,
          subtitle = candidateSubtitle(candidate),
          eyebrow = stringResource(if (asNotes) R.string.import_group_note_n else R.string.import_group_session_n, group),
          tone = if (index == 0 || startsNew) FluidTone.Primary else FluidTone.Neutral,
          badge = if (index == 0) null else {
            { FluidSwitch(checked = startsNew, onCheckedChange = { onToggleGroupStart(candidate.id) }) }
          },
        )
      }
    }
  }
  item { FluidSectionFootnote(text = stringResource(R.string.import_group_switch_note)) }

  if (grouping.isSplit) {
    item { FluidSectionHeader(title = stringResource(R.string.import_group_titles)) }
    groups.forEach { group ->
      item(key = "title-${group.firstId}") {
        FluidTextField(
          value = group.title,
          onValueChange = { onGroupTitle(group.firstId, it) },
          label = stringResource(if (asNotes) R.string.import_group_note_n else R.string.import_group_session_n, group.index + 1),
          placeholder = group.defaultTitle.ifBlank { null },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
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
    // Piu' note create: si elencano, e ognuna si apre da qui. Un solo tasto «Apri la nota» non
    // saprebbe quale.
    val manyNotes = state.createdNotes.size > 1
    if (manyNotes) {
      item { FluidSectionHeader(title = stringResource(R.string.import_done_notes)) }
      item {
        FluidListGroup {
          state.createdNotes.forEachIndexed { index, note ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = note.title,
              subtitle = pluralStringResource(R.plurals.import_samsung_recordings, note.recordings, note.recordings),
              tone = FluidTone.Primary,
              onClick = { onOpenNote(note.id) },
            )
          }
        }
      }
    }
    item {
      FluidListGroup {
        outcome.imported.forEachIndexed { index, item ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = item.displayName,
            subtitle = item.summary?.let { importSummaryText(it) } ?: item.detail ?: importedSubtitle(item),
            tone = when (item.status) {
              SourceStatus.OK -> FluidTone.Success
              SourceStatus.PARTIAL -> FluidTone.Warning
              SourceStatus.FAILED -> FluidTone.Danger
            },
          )
        }
      }
    }
    if (!manyNotes) {
      item {
        FluidButton(
          text = stringResource(R.string.import_open_note),
          onClick = { onOpenNote(outcome.noteId) },
          fillWidth = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
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
private fun importSummaryText(summary: ImportSummary): String = when (summary) {
  ImportSummary.FileUnavailable -> stringResource(R.string.import_summary_unavailable)
  ImportSummary.Unreadable -> stringResource(R.string.import_summary_unreadable)
  ImportSummary.Unsupported -> stringResource(R.string.import_summary_unsupported)
  ImportSummary.SamsungEmpty -> stringResource(R.string.import_summary_samsung_empty)
  is ImportSummary.SamsungPages -> pluralStringResource(R.plurals.import_summary_pages, summary.pages, summary.pages)
  is ImportSummary.SamsungUpdated -> {
    val updated = stringResource(R.string.import_summary_updated, summary.newRecordings, summary.kept)
    if (summary.pages > 0) updated + " · " + pluralStringResource(R.plurals.note_handwriting_done, summary.pages, summary.pages) else updated
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
