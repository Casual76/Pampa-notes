package dev.pampa.pampanotes.ui.note

import dev.pampa.pampanotes.ui.share.ShareSheet
import android.widget.Toast
import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import dev.pampa.pampanotes.ui.common.SelectionMark
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidPillTabs
import dev.antigravity.fluidengine.ui.theme.FluidQuickAction
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.export.ExportScope
import dev.pampa.pampanotes.ui.export.ExportSheet
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.CloseWhenGone
import dev.pampa.pampanotes.ui.common.JobProgressBars
import dev.pampa.pampanotes.ui.common.jobErrorText
import dev.pampa.pampanotes.ui.common.jobPhaseText
import dev.pampa.pampanotes.ui.common.MarkdownText
import dev.pampa.pampanotes.ui.common.OverflowMenuButton
import dev.pampa.pampanotes.ui.common.rememberComputerOnly
import dev.pampa.pampanotes.ui.common.rememberPullToSync
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.pampanotes.ui.common.ReportSubject
import dev.pampa.pampanotes.ui.common.asSubject
import dev.pampa.pampanotes.ui.common.openWithSystem

@Composable
fun NoteRoute(
  initialTab: String?,
  onBack: () -> Unit,
  onEdit: (String) -> Unit,
  onOpenSession: (String) -> Unit,
  onImportInto: (String) -> Unit,
  viewModel: NoteViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  // Le stringhe dalle risorse osservabili, non dal contesto: cambiano con la lingua e il tema.
  val resources = LocalResources.current
  NoteScreen(
    state = state,
    initialTab = tabFromRoute(initialTab),
    onBack = onBack,
    onEdit = { state.note?.let { onEdit(it.id) } },
    onImport = { state.note?.let { onImportInto(it.id) } },
    onTogglePinned = viewModel::togglePinned,
    onDelete = { viewModel.delete(onBack) },
    onTranscribe = viewModel::transcribe,
    onRetranscribe = viewModel::retranscribe,
    onDeleteSessions = viewModel::deleteSessions,
    onCancelJob = viewModel::cancelJob,
    onOpenSession = onOpenSession,
    onOpenSource = { source ->
      viewModel.openSource(
        source,
        onReady = { openWithSystem(context, it, source.mime) },
        onError = { Toast.makeText(context, resources.getString(R.string.note_source_fetch_failed, it), Toast.LENGTH_LONG).show() },
        // Una pagina a mano e' sempre toccabile — e' una scheda, non una riga spenta — quindi un
        // tocco che non puo' fare niente deve almeno dire perche'.
        onUnavailable = { Toast.makeText(context, resources.getString(R.string.note_handwriting_unavailable), Toast.LENGTH_LONG).show() },
      )
    },
    onRederiveHandwriting = {
      Toast.makeText(context, resources.getString(R.string.note_handwriting_working), Toast.LENGTH_SHORT).show()
      viewModel.rederiveHandwriting(
        onDone = { count ->
          val message = if (count == 0) resources.getString(R.string.note_handwriting_none)
          else resources.getQuantityString(R.plurals.note_handwriting_done, count, count)
          Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        },
        onError = { Toast.makeText(context, resources.getString(R.string.note_source_fetch_failed, it), Toast.LENGTH_LONG).show() },
      )
    },
  )
}

private fun tabFromRoute(value: String?): NoteTab = when (value) {
  "audio" -> NoteTab.AUDIO
  "fonti", "sources" -> NoteTab.SOURCES
  else -> NoteTab.TEXT
}

@Composable
private fun NoteScreen(
  state: NoteUiState,
  initialTab: NoteTab,
  onBack: () -> Unit,
  onEdit: () -> Unit,
  onImport: () -> Unit,
  onTogglePinned: () -> Unit,
  onDelete: () -> Unit,
  onTranscribe: (String) -> Unit,
  onRetranscribe: (Collection<String>) -> Unit,
  onDeleteSessions: (Collection<String>) -> Unit,
  onCancelJob: (String) -> Unit,
  onOpenSession: (String) -> Unit,
  onOpenSource: (SourceEntity) -> Unit,
  onRederiveHandwriting: () -> Unit,
) {
  var tab by rememberSaveable { mutableStateOf(initialTab) }
  var confirmingDelete by remember { mutableStateOf(false) }
  var exporting by remember { mutableStateOf(false) }
  var sharing by remember { mutableStateOf(false) }
  val computerOnly = rememberComputerOnly()
  // Tirando giu' la nota si sincronizza: la trascrizione fatta sull'altro dispositivo arriva adesso.
  val pull = rememberPullToSync()
  // La selezione delle sessioni: la barra in alto diventa quella della selezione, le schede
  // spariscono e ogni sessione e' una riga con il suo segno. Indietro la chiude.
  var selecting by remember { mutableStateOf(false) }
  var selected by remember { mutableStateOf(emptySet<String>()) }
  var confirmingRetranscribe by remember { mutableStateOf(false) }
  var confirmingDeleteSessions by remember { mutableStateOf(false) }
  val exitSelection = {
    selecting = false
    selected = emptySet()
  }
  BackHandler(enabled = selecting) { exitSelection() }
  // La nota e' della sua materia: l'app prende quel colore.
  ReportSubject(state.folder?.asSubject())

  val tabText = stringResource(R.string.note_tab_text)
  val tabAudio = stringResource(R.string.note_tab_audio)
  val tabSources = stringResource(R.string.note_tab_sources)
  val pinLabel = stringResource(R.string.note_pin)
  val unpinLabel = stringResource(R.string.note_unpin)
  val deleteLabel = stringResource(R.string.action_delete)
  val importLabel = stringResource(R.string.action_import)
  val exportLabel = stringResource(R.string.action_export)
  val editLabel = stringResource(R.string.note_edit)
  val shareLabel = stringResource(R.string.note_share)
  val selectLabel = stringResource(R.string.action_select)
  val retranscribeLabel = stringResource(R.string.session_retranscribe)
  val rederiveLabel = stringResource(R.string.note_handwriting_rederive)
  val addAudioLabel = stringResource(R.string.note_add_audio)
  val retranscribeAllLabel = stringResource(R.string.note_retranscribe_all)
  val chooseSessionsLabel = stringResource(R.string.note_choose_sessions)

  CloseWhenGone(gone = !state.loading && state.note == null, onBack = onBack)

  val tabLabels = listOf(tabText, tabAudio, tabSources)
  val selectedLabel = when (tab) {
    NoteTab.TEXT -> tabText
    NoteTab.AUDIO -> tabAudio
    NoteTab.SOURCES -> tabSources
  }

  FluidScreen(
    title = if (selecting) pluralStringResource(R.plurals.selection_count, selected.size, selected.size) else state.note?.title ?: stringResource(R.string.note_loading),
    subtitle = if (selecting) null else state.folderPath.takeIf { it.isNotBlank() },
    onBack = if (selecting) exitSelection else onBack,
    // Primary, cioe' la materia: il fondale della nota e' dello stesso colore della sua tessera.
    ambient = FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Cards),
    isRefreshing = pull.isRefreshing,
    onRefresh = pull.onRefresh.takeUnless { selecting },
    titleFacets = if (selecting) emptyList() else buildList {
      if (state.partCount > 0) add(Formats.durationShort(state.audioDurationMs))
      if (state.sources.isNotEmpty()) add("${state.sources.size}")
    },
    actions = {
      if (selecting) {
        FluidBarAction(
          icon = Icons.Rounded.Refresh,
          contentDescription = retranscribeLabel,
          enabled = selected.isNotEmpty(),
          onClick = { confirmingRetranscribe = true },
        )
        FluidBarAction(
          icon = Icons.Rounded.Delete,
          contentDescription = deleteLabel,
          enabled = selected.isNotEmpty(),
          onClick = { confirmingDeleteSessions = true },
        )
      } else {
        // La matita solo dove c'e' qualcosa da scrivere: nella scheda Audio e in Fonti modificare gli
        // appunti non c'entra, e il tasto piu' in vista deve essere quello che serve li'.
        if (tab == NoteTab.TEXT) {
          FluidBarAction(icon = Icons.Rounded.Edit, contentDescription = editLabel, onClick = onEdit)
        }
        OverflowMenuButton(
          actions = {
            buildList {
              when (tab) {
                NoteTab.TEXT -> {
                  add(FluidContextAction(label = editLabel) { onEdit() })
                  add(FluidContextAction(label = importLabel) { onImport() })
                  if (state.sources.any { it.kind == SourceKind.SDOCX }) add(FluidContextAction(label = rederiveLabel) { onRederiveHandwriting() })
                }
                NoteTab.AUDIO -> {
                  add(FluidContextAction(label = addAudioLabel) { onImport() })
                  // Ne' quelle in lavoro qui, ne' quelle che un altro dispositivo sta gia' trascrivendo.
                  val transcribable = state.sessions.filter { it.parts.isNotEmpty() && state.activeJobs[it.session.id] == null && it.session.id !in state.elsewhere }
                  // Rifare da capo quello che c'e': tutto in un colpo, o scegliendo sessione per sessione.
                  if (transcribable.any { state.transcripts[it.session.id] != null }) {
                    add(
                      FluidContextAction(label = retranscribeAllLabel) {
                        selected = transcribable.map { it.session.id }.toSet()
                        confirmingRetranscribe = true
                      },
                    )
                  }
                  if (state.sessions.isNotEmpty()) add(FluidContextAction(label = chooseSessionsLabel) { selecting = true })
                }
                NoteTab.SOURCES -> add(FluidContextAction(label = importLabel) { onImport() })
              }
              add(FluidContextAction(label = exportLabel) { exporting = true })
              add(FluidContextAction(label = shareLabel) { sharing = true })
              state.note?.let { note -> add(computerOnly.noteAction(note.id, note.folderId, note.title)) }
              add(FluidContextAction(label = if (state.note?.pinned == true) unpinLabel else pinLabel) { onTogglePinned() })
              add(FluidContextAction(label = deleteLabel, destructive = true) { confirmingDelete = true })
            }
          },
        )
      }
    },
  ) {
    if (selecting) {
      sessionSelection(state, selected) { id -> selected = if (id in selected) selected - id else selected + id }
      return@FluidScreen
    }

    item {
      FluidPillTabs(
        options = tabLabels,
        selected = selectedLabel,
        onSelect = { label ->
          tab = when (label) {
            tabAudio -> NoteTab.AUDIO
            tabSources -> NoteTab.SOURCES
            else -> NoteTab.TEXT
          }
        },
      )
    }

    when (tab) {
      NoteTab.TEXT -> textTab(state, onEdit, onOpenSource)
      NoteTab.AUDIO -> audioTab(state, onImport, onTranscribe, onCancelJob, onOpenSession) { sessionId ->
        selected = setOf(sessionId)
        confirmingRetranscribe = true
      }
      NoteTab.SOURCES -> sourcesTab(state, onImport, onOpenSource)
    }
  }

  if (confirmingRetranscribe) {
    FluidAlert(
      onDismissRequest = { confirmingRetranscribe = false },
      title = stringResource(R.string.session_retranscribe_title),
      message = pluralStringResource(R.plurals.selection_retranscribe_message, selected.size, selected.size),
      actions = listOf(
        FluidAlertAction(
          label = retranscribeLabel,
          emphasis = FluidAlertAction.Emphasis.Preferred,
          onClick = {
            confirmingRetranscribe = false
            onRetranscribe(selected)
            exitSelection()
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { confirmingRetranscribe = false }),
      ),
    )
  }

  if (confirmingDeleteSessions) {
    FluidAlert(
      onDismissRequest = { confirmingDeleteSessions = false },
      title = stringResource(R.string.selection_delete_title),
      message = pluralStringResource(R.plurals.selection_delete_sessions_message, selected.size, selected.size),
      actions = listOf(
        FluidAlertAction(
          label = deleteLabel,
          emphasis = FluidAlertAction.Emphasis.Destructive,
          onClick = {
            confirmingDeleteSessions = false
            onDeleteSessions(selected)
            exitSelection()
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { confirmingDeleteSessions = false }),
      ),
    )
  }

  state.note?.let { note ->
    if (exporting) ExportSheet(scope = ExportScope.Note(note.id), onDismiss = { exporting = false })
    if (sharing) ShareSheet(noteId = note.id, onDismiss = { sharing = false })
  }

  if (confirmingDelete) {
    FluidAlert(
      onDismissRequest = { confirmingDelete = false },
      title = stringResource(R.string.note_delete_title, state.note?.title.orEmpty()),
      message = stringResource(R.string.note_delete_message),
      actions = listOf(
        FluidAlertAction(
          label = deleteLabel,
          emphasis = FluidAlertAction.Emphasis.Destructive,
          onClick = {
            confirmingDelete = false
            onDelete()
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { confirmingDelete = false }),
      ),
    )
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.textTab(
  state: NoteUiState,
  onEdit: () -> Unit,
  onOpenPage: (SourceEntity) -> Unit,
) {
  val body = state.note?.body.orEmpty()
  if (body.isBlank() && state.handwriting.isEmpty()) {
    item {
      FluidEmptyState(
        title = stringResource(R.string.note_empty_title),
        detail = stringResource(R.string.note_empty_detail),
      )
    }
    item {
      FluidQuickAction(
        label = stringResource(R.string.note_edit),
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  } else if (body.isNotBlank()) {
    item {
      FluidCard {
        MarkdownText(markdown = body, modifier = Modifier.fillMaxWidth())
      }
    }
  }

  // Le pagine scritte a mano stanno qui e non fra le fonti: sono appunti, come il testo sopra.
  if (state.handwriting.isNotEmpty()) {
    item { FluidSectionFootnote(text = stringResource(R.string.note_handwriting_title)) }
    state.handwriting.forEachIndexed { index, page ->
      item(key = page.id) {
        val file = page.storedFileName?.let { File(LocalContext.current.filesDir, "sources/$it") }
        if (file != null) {
          HandwritingPageCard(
            label = stringResource(R.string.note_handwriting_page, index + 1),
            file = file,
            missing = page.id in state.missingSources,
            archived = page.archivedAt > 0,
            onOpen = { onOpenPage(page) },
          )
        }
      }
    }
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.audioTab(
  state: NoteUiState,
  onImport: () -> Unit,
  onTranscribe: (String) -> Unit,
  onCancelJob: (String) -> Unit,
  onOpenSession: (String) -> Unit,
  onRetranscribe: (String) -> Unit,
) {
  if (state.sessions.isEmpty()) {
    item {
      FluidEmptyState(
        title = stringResource(R.string.note_audio_empty_title),
        detail = stringResource(R.string.note_audio_empty_detail),
      )
    }
    item {
      FluidQuickAction(
        label = stringResource(R.string.note_add_audio),
        onClick = onImport,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    return
  }

  state.sessions.forEachIndexed { index, session ->
    item(key = "session-${session.session.id}") {
      val sessionId = session.session.id
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = sessionTitle(index, session.session.title, session.session.date),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 4.dp),
        )
        FluidListGroup {
          session.partsSorted.forEachIndexed { partIndex, part ->
            if (partIndex > 0) FluidListDivider()
            FluidListRow(
              title = part.originalName,
              subtitle = Formats.duration(part.durationMs) + " · " + partLocation(part.archivedAt > 0, part.id in state.missingParts),
              eyebrow = stringResource(R.string.note_part_number, partIndex + 1),
              meta = Formats.bytes(part.sizeBytes),
              onClick = { onOpenSession(sessionId) },
            )
          }
        }

        val job = state.activeJobs[sessionId]
        val transcript = state.transcripts[sessionId]
        val remote = state.elsewhere[sessionId]

        // Un assaggio, non il testo intero. Una lezione da un'ora sono tremila parole, e stamparle
        // qui vorrebbe dire una nota in cui per arrivare alla seconda sessione si scorre un minuto.
        transcript?.let {
          FluidCard(onClick = { onOpenSession(sessionId) }) {
            Text(
              text = stringResource(R.string.note_transcript_meta, it.wordCount, it.model),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = preview(it.text),
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }

        when {
          job != null -> {
            // Cosa sta succedendo, in parole, sopra le barre: la barra da sola non distingueva «il
            // computer carica il modello» da «il computer si e' spento».
            Text(
              text = jobPhaseText(job),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = 4.dp),
            )
            JobProgressBars(job)
            FluidButton(
              text = stringResource(R.string.action_cancel),
              onClick = { onCancelJob(job.id) },
              style = FluidButtonStyle.Plain,
              fillWidth = true,
              modifier = Modifier.fillMaxWidth(),
            )
          }

          // Un altro dispositivo la sta trascrivendo: niente «Trascrivi», che la manderebbe al
          // computer (o a Groq) una seconda volta. Il testo arriva col sync; se c'e' gia' una
          // trascrizione — la si sta rifacendo — resta apribile.
          remote != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FluidInlineMessage(
              title = stringResource(R.string.transcribing_elsewhere, remote.device),
              message = stringResource(R.string.transcribing_elsewhere_detail),
              tone = FluidTone.Info,
            )
            if (transcript != null) {
              FluidButton(
                text = stringResource(R.string.note_open_session),
                onClick = { onOpenSession(sessionId) },
                style = FluidButtonStyle.Plain,
                fillWidth = true,
                modifier = Modifier.fillMaxWidth(),
              )
            }
          }

          // Gia' trascritta: aprirla, o rifarla da capo — col computer di casa invece di Groq, o
          // dopo che l'allineamento delle parole e' stato sistemato. La conferma c'e' perche' la
          // nuova grezza si porta via anche le versioni ripulite.
          transcript != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FluidButton(
              text = stringResource(R.string.note_open_session),
              onClick = { onOpenSession(sessionId) },
              style = FluidButtonStyle.Plain,
              fillWidth = true,
              modifier = Modifier.weight(1f),
            )
            FluidButton(
              text = stringResource(R.string.session_retranscribe),
              onClick = { onRetranscribe(sessionId) },
              style = FluidButtonStyle.Plain,
              fillWidth = true,
              modifier = Modifier.weight(1f),
            )
          }

          else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // L'ultimo tentativo e' fallito: il perche', e lo stesso tasto che diventa «Riprova».
            val failed = state.failedJobs[sessionId]
            if (failed != null) {
              FluidInlineMessage(
                title = stringResource(R.string.job_failed_transcribe),
                message = jobErrorText(failed.errorCode ?: "unknown", failed.errorMessage, failed.provider),
                tone = FluidTone.Danger,
              )
            }
            FluidButton(
              text = stringResource(if (failed != null) R.string.job_retry else R.string.note_transcribe),
              onClick = { onTranscribe(sessionId) },
              style = FluidButtonStyle.Tinted,
              fillWidth = true,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }
    }
  }
  item {
    FluidQuickAction(
      label = stringResource(R.string.note_add_audio),
      onClick = onImport,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/**
 * Le sessioni come righe da scegliere: una per sessione, con il segno davanti. Una sessione con un
 * lavoro in corso non si sceglie — cancellarla sotto al worker o metterla in coda due volte non ha
 * senso — e la riga lo dice al posto dello stato della trascrizione.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.sessionSelection(
  state: NoteUiState,
  selected: Set<String>,
  onToggle: (String) -> Unit,
) {
  item(key = "selection") {
    FluidListGroup {
      state.sessions.forEachIndexed { index, session ->
        if (index > 0) FluidListDivider()
        val id = session.session.id
        val remote = state.elsewhere[id]
        val busy = state.activeJobs[id] != null || remote != null
        val checked = id in selected
        FluidListRow(
          title = sessionTitle(index, session.session.title, session.session.date),
          subtitle = pluralStringResource(R.plurals.session_part_count, session.parts.size, session.parts.size) + " · " + Formats.duration(session.durationMs),
          meta = when {
            remote != null -> stringResource(R.string.transcribing_elsewhere, remote.device)
            busy -> stringResource(R.string.jobs_section_active)
            state.transcripts[id] != null -> stringResource(R.string.note_transcribed)
            else -> stringResource(R.string.note_to_transcribe)
          },
          tone = if (checked) FluidTone.Primary else FluidTone.Neutral,
          leading = { SelectionMark(checked) },
          onClick = if (busy) null else ({ onToggle(id) }),
        )
      }
    }
  }
}

/** Le prime righe di una trascrizione, tagliate a fine parola. */
private fun preview(text: String, maxChars: Int = 220): String {
  val flat = text.replace(Regex("""\s+"""), " ").trim()
  if (flat.length <= maxChars) return flat
  val cut = flat.take(maxChars)
  return cut.substringBeforeLast(' ', cut).trimEnd(',', ';', ':', '.') + "…"
}

/**
 * Le fonti: da dove viene il testo, e la strada per riaprire l'originale.
 *
 * E' l'unica utilita' vera di questa scheda, e va detta in cima: un elenco di nomi di file con
 * accanto dei megabyte non dice a nessuno cosa farci.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.sourcesTab(
  state: NoteUiState,
  onImport: () -> Unit,
  onOpenSource: (SourceEntity) -> Unit,
) {
  if (state.sources.isEmpty()) {
    item {
      FluidEmptyState(
        title = stringResource(R.string.note_sources_empty_title),
        detail = stringResource(R.string.note_sources_empty_detail),
      )
    }
    item {
      FluidQuickAction(
        label = stringResource(R.string.action_import),
        onClick = onImport,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    return
  }

  item { FluidSectionFootnote(text = stringResource(R.string.note_sources_hint)) }
  item {
    FluidListGroup {
      state.sources.forEachIndexed { index, source ->
        if (index > 0) FluidListDivider()
        FluidListRow(
          title = source.originalName,
          subtitle = sourceSubtitle(source, missing = source.id in state.missingSources),
          eyebrow = sourceKindLabel(source.kind),
          meta = Formats.bytes(source.sizeBytes),
          tone = sourceTone(source.status),
          // Solo chi ha un file conservato si riapre: il testo incollato non ha un originale. E un
          // file che sta su un altro dispositivo, non ancora sul computer, non si puo' chiedere a nessuno.
          onClick = if (source.storedFileName != null && (source.id !in state.missingSources || source.archivedAt > 0)) { { onOpenSource(source) } } else null,
          badge = if (source.status != SourceStatus.OK) {
            { FluidStatusBadge(label = sourceStatusLabel(source.status), tone = sourceTone(source.status)) }
          } else {
            null
          },
        )
      }
    }
  }
  item {
    FluidQuickAction(
      label = stringResource(R.string.action_import),
      onClick = onImport,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/**
 * Dove sta il file di una registrazione, in due parole. Con l'indice in cloud «il file non c'e'» e'
 * normale, e senza dirlo non si capisce perche' una lezione chiede di scaricare o non si trascrive.
 */
@Composable
private fun partLocation(archived: Boolean, missing: Boolean): String = stringResource(
  when {
    !missing && archived -> R.string.note_part_here_and_pc
    !missing -> R.string.note_part_here_only
    archived -> R.string.note_part_on_pc
    else -> R.string.note_part_elsewhere
  },
)

private fun sourceTone(status: SourceStatus): FluidTone = when (status) {
  SourceStatus.OK -> FluidTone.Neutral
  SourceStatus.PARTIAL -> FluidTone.Warning
  SourceStatus.FAILED -> FluidTone.Danger
}

@Composable
private fun sourceSubtitle(source: SourceEntity, missing: Boolean): String {
  val detail = source.detail
  return when {
    // Il file non e' qui: e' la prima cosa da dire, prima di quante lettere ne sono uscite.
    missing && source.archivedAt > 0 -> stringResource(R.string.note_source_remote)
    missing -> stringResource(R.string.note_source_elsewhere)
    detail != null -> detail
    source.extractedChars > 0 -> pluralStringResource(R.plurals.note_source_chars, source.extractedChars, source.extractedChars)
    else -> stringResource(R.string.note_source_attached)
  }
}

@Composable
private fun sourceKindLabel(kind: SourceKind): String = stringResource(
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

@Composable
private fun sourceStatusLabel(status: SourceStatus): String = stringResource(
  when (status) {
    SourceStatus.OK -> R.string.source_status_ok
    SourceStatus.PARTIAL -> R.string.source_status_partial
    SourceStatus.FAILED -> R.string.source_status_failed
  },
)

@Composable
private fun sessionTitle(index: Int, title: String, date: String): String {
  val number = stringResource(R.string.note_session_number, index + 1)
  val prettyDate = dev.pampa.pampanotes.core.model.Dates.parseOrNull(date)?.let { Formats.relativeDate(it) } ?: date
  return if (title.isBlank()) "$number — $prettyDate" else "$number — $prettyDate · $title"
}
