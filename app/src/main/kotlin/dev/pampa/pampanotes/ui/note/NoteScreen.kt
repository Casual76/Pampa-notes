package dev.pampa.pampanotes.ui.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
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
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
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
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.MarkdownText

@Composable
fun NoteRoute(
  initialTab: String?,
  onBack: () -> Unit,
  onEdit: (String) -> Unit,
  onImportInto: (String) -> Unit,
  viewModel: NoteViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  NoteScreen(
    state = state,
    initialTab = tabFromRoute(initialTab),
    onBack = onBack,
    onEdit = { state.note?.let { onEdit(it.id) } },
    onImport = { state.note?.let { onImportInto(it.id) } },
    onTogglePinned = viewModel::togglePinned,
    onDelete = { viewModel.delete(onBack) },
    onTranscribe = viewModel::transcribe,
    onCancelJob = viewModel::cancelJob,
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
  onCancelJob: (String) -> Unit,
) {
  var tab by rememberSaveable { mutableStateOf(initialTab) }
  var confirmingDelete by remember { mutableStateOf(false) }

  val tabText = stringResource(R.string.note_tab_text)
  val tabAudio = stringResource(R.string.note_tab_audio)
  val tabSources = stringResource(R.string.note_tab_sources)
  val pinLabel = stringResource(R.string.note_pin)
  val unpinLabel = stringResource(R.string.note_unpin)
  val deleteLabel = stringResource(R.string.action_delete)
  val importLabel = stringResource(R.string.action_import)
  val editLabel = stringResource(R.string.note_edit)

  val tabLabels = listOf(tabText, tabAudio, tabSources)
  val selectedLabel = when (tab) {
    NoteTab.TEXT -> tabText
    NoteTab.AUDIO -> tabAudio
    NoteTab.SOURCES -> tabSources
  }

  FluidScreen(
    title = state.note?.title ?: stringResource(R.string.note_loading),
    subtitle = state.folderPath.takeIf { it.isNotBlank() },
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Secondary, motif = FluidHeroMotif.Cards),
    titleFacets = buildList {
      if (state.partCount > 0) add(Formats.durationShort(state.audioDurationMs))
      if (state.sources.isNotEmpty()) add("${state.sources.size}")
    },
    actions = {
      FluidBarAction(
        icon = Icons.Rounded.Edit,
        contentDescription = editLabel,
        onClick = onEdit,
        actions = {
          listOf(
            FluidContextAction(label = editLabel) { onEdit() },
            FluidContextAction(label = importLabel) { onImport() },
            FluidContextAction(label = if (state.note?.pinned == true) unpinLabel else pinLabel) { onTogglePinned() },
            FluidContextAction(label = deleteLabel, destructive = true) { confirmingDelete = true },
          )
        },
      )
    },
  ) {
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
      NoteTab.TEXT -> textTab(state, onEdit)
      NoteTab.AUDIO -> audioTab(state, onImport, onTranscribe, onCancelJob)
      NoteTab.SOURCES -> sourcesTab(state, onImport)
    }
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

private fun androidx.compose.foundation.lazy.LazyListScope.textTab(state: NoteUiState, onEdit: () -> Unit) {
  val body = state.note?.body.orEmpty()
  if (body.isBlank()) {
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
  } else {
    item {
      FluidCard(glass = true) {
        MarkdownText(markdown = body, modifier = Modifier.fillMaxWidth())
      }
    }
  }
}

private fun androidx.compose.foundation.lazy.LazyListScope.audioTab(
  state: NoteUiState,
  onImport: () -> Unit,
  onTranscribe: (String) -> Unit,
  onCancelJob: (String) -> Unit,
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
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = sessionTitle(index, session.session.title, session.session.date),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 4.dp),
        )
        FluidListGroup(glass = true) {
          session.partsSorted.forEachIndexed { partIndex, part ->
            if (partIndex > 0) FluidListDivider()
            FluidListRow(
              title = part.originalName,
              subtitle = Formats.duration(part.durationMs),
              eyebrow = stringResource(R.string.note_part_number, partIndex + 1),
              meta = Formats.bytes(part.sizeBytes),
            )
          }
        }

        val job = state.activeJobs[session.session.id]
        val transcript = state.transcripts[session.session.id]
        val transcribed = transcript != null

        // La trascrizione, quando c'e'. E' il motivo per cui si e' importato l'audio: tenerla dietro
        // un altro tocco vorrebbe dire nascondere il risultato dietro la sua stessa etichetta.
        transcript?.let {
          FluidCard(glass = true) {
            Text(
              text = stringResource(R.string.note_transcript_meta, it.wordCount, it.model),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MarkdownText(markdown = it.text, modifier = Modifier.fillMaxWidth())
          }
        }

        when {
          job != null -> {
            FluidProgressBar(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
            FluidButton(
              text = stringResource(R.string.action_cancel),
              onClick = { onCancelJob(job.id) },
              style = FluidButtonStyle.Plain,
              fillWidth = true,
              modifier = Modifier.fillMaxWidth(),
            )
          }

          else -> FluidButton(
            text = stringResource(if (transcribed) R.string.note_retranscribe else R.string.note_transcribe),
            onClick = { onTranscribe(session.session.id) },
            style = if (transcribed) FluidButtonStyle.Plain else FluidButtonStyle.Tinted,
            fillWidth = true,
            modifier = Modifier.fillMaxWidth(),
          )
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

private fun androidx.compose.foundation.lazy.LazyListScope.sourcesTab(state: NoteUiState, onImport: () -> Unit) {
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

  item {
    FluidListGroup {
      state.sources.forEachIndexed { index, source ->
        if (index > 0) FluidListDivider()
        FluidListRow(
          title = source.originalName,
          subtitle = sourceSubtitle(source),
          eyebrow = sourceKindLabel(source.kind),
          meta = Formats.bytes(source.sizeBytes),
          tone = sourceTone(source.status),
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

private fun sourceTone(status: SourceStatus): FluidTone = when (status) {
  SourceStatus.OK -> FluidTone.Neutral
  SourceStatus.PARTIAL -> FluidTone.Warning
  SourceStatus.FAILED -> FluidTone.Danger
}

@Composable
private fun sourceSubtitle(source: SourceEntity): String {
  val detail = source.detail
  return when {
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
