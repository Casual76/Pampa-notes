package dev.pampa.pampanotes.ui.export

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.export.ExportFormat
import dev.pampa.pampanotes.core.export.ExportOptions
import dev.pampa.pampanotes.core.export.ExportScope
import dev.pampa.pampanotes.core.export.TranscriptChoice
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.PageActions
import dev.pampa.pampanotes.ui.common.SheetBody

/**
 * Il pannello che prepara un pacchetto.
 *
 * L'ambito non si sceglie qui: e' gia' deciso da dove si e' aperto il pannello — da una nota, da una
 * cartella, o da Altro per l'archivio intero. Un selettore di ambito dentro un pannello aperto da
 * una nota sarebbe un modo per uscire dalla nota senza accorgersene.
 */
@Composable
fun ExportSheet(
  scope: ExportScope,
  onDismiss: () -> Unit,
  viewModel: ExportViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val everything = stringResource(R.string.export_scope_everything)
  val labels = exportLabels()

  LaunchedEffect(scope) { viewModel.start(scope, labels, everything) }

  // Il selettore di cartella di sistema. Il permesso va reso permanente subito: senza, alla prossima
  // apertura dell'app l'URI salvato non vale piu' e il salvataggio fallisce senza spiegazioni.
  val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
      }
      viewModel.saveTo(uri)
    }
  }

  fun shareResult() {
    val result = state.result ?: return
    viewModel.shareIntent(result)?.let { context.startActivity(Intent.createChooser(it, null)) }
  }

  FluidGlassModalPortal(
    visible = true,
    // Chiudere a meta' scrittura lascerebbe un file rotto: finche' scrive, il pannello resta.
    onDismissRequest = { if (state.stage != ExportStage.RUNNING) onDismiss() },
    // Una pagina intera: le scelte sono piu' di tre, e i tasti in fondo restano fermi mentre
    // le scelte scorrono.
    presentation = FluidGlassModalPresentation.FullScreen,
    paneTitle = stringResource(R.string.action_export),
    footer = {
      PageActions {
        when (state.stage) {
          ExportStage.RUNNING -> FluidButton(
            text = stringResource(R.string.action_cancel),
            onClick = viewModel::cancel,
            style = FluidButtonStyle.Plain,
            fillWidth = true,
            modifier = Modifier.fillMaxWidth(),
          )

          ExportStage.DONE -> DoneActions(
            state = state,
            onShare = ::shareResult,
            onOpen = { state.result?.let { runCatching { context.startActivity(viewModel.openIntent(it)) } } },
            onClose = {
              viewModel.dismissResult()
              onDismiss()
            },
          )

          else -> ConfiguringActions(
            state = state,
            onShare = viewModel::share,
            onSaveHere = {
              state.folderUri.takeIf { it.isNotBlank() }
                ?.let { viewModel.saveTo(Uri.parse(it), remember = false) }
            },
            onPickFolder = { pickFolder.launch(null) },
          )
        }
      }
    },
  ) {
    SheetBody(scrollable = false) {
      when (state.stage) {
        ExportStage.RUNNING -> RunningBody(state)
        ExportStage.DONE -> DoneBody(state)
        else -> ConfiguringBody(state, viewModel::setOptions)
      }
    }
  }
}

// -------------------------------------------------------------------------------------------------
// Le scelte
// -------------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.ConfiguringBody(state: ExportUiState, onOptions: (ExportOptions) -> Unit) {
  val options = state.options
  val set = state.set

  // Cosa si sta per esportare, in due righe. E' il momento in cui ci si accorge di aver aperto il
  // pannello dalla nota sbagliata.
  Text(text = set?.scopeLabel.orEmpty(), style = MaterialTheme.typography.titleLarge)
  Text(
    text = if (set == null) {
      stringResource(R.string.export_counting)
    } else {
      summaryOf(set.notes.size, set.sessionCount, set.audioDurationMs)
    },
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )

  val bundleLabel = stringResource(R.string.export_format_bundle)
  val singleLabel = stringResource(R.string.export_format_single)
  FluidSectionFootnote(text = stringResource(R.string.export_format_label))
  FluidSegmentedControl(
    options = listOf(ExportFormat.BUNDLE, ExportFormat.SINGLE),
    selected = options.format,
    onSelect = { onOptions(options.copy(format = it)) },
    label = { if (it == ExportFormat.BUNDLE) bundleLabel else singleLabel },
    modifier = Modifier.fillMaxWidth(),
  )
  FluidSectionFootnote(
    text = stringResource(
      if (options.format == ExportFormat.BUNDLE) R.string.export_format_bundle_detail else R.string.export_format_single_detail,
    ),
  )

  val bestLabel = stringResource(R.string.export_transcript_best)
  val rawLabel = stringResource(R.string.export_transcript_raw)
  FluidSectionFootnote(text = stringResource(R.string.export_transcript_label))
  FluidSegmentedControl(
    options = listOf(TranscriptChoice.BEST, TranscriptChoice.RAW),
    selected = options.transcript,
    onSelect = { onOptions(options.copy(transcript = it)) },
    label = { if (it == TranscriptChoice.BEST) bestLabel else rawLabel },
    modifier = Modifier.fillMaxWidth(),
  )

  FluidListGroup(modifier = Modifier.padding(top = 4.dp)) {
    SwitchRow(
      title = stringResource(R.string.export_timestamps),
      subtitle = stringResource(R.string.export_timestamps_detail),
      checked = options.timestamps,
      onChange = { onOptions(options.copy(timestamps = it)) },
    )
    // Un file singolo e' un testo da incollare: non ha una cartella dove mettere un PDF, e le regole
    // ci stanno gia' dentro in cima.
    if (options.format == ExportFormat.BUNDLE) {
      FluidListDivider()
      SwitchRow(
        title = stringResource(R.string.export_skill),
        subtitle = stringResource(R.string.export_skill_detail),
        checked = options.includeSkill,
        onChange = { onOptions(options.copy(includeSkill = it)) },
      )
      FluidListDivider()
      SwitchRow(
        title = stringResource(R.string.export_sources),
        subtitle = stringResource(R.string.export_sources_detail),
        checked = options.includeSources,
        onChange = { onOptions(options.copy(includeSources = it)) },
      )
      FluidListDivider()
      SwitchRow(
        title = stringResource(R.string.export_audio),
        subtitle = if (set != null && set.audioDurationMs > 0) {
          stringResource(R.string.export_audio_detail_size, Formats.durationShort(set.audioDurationMs))
        } else {
          stringResource(R.string.export_audio_detail)
        },
        checked = options.includeAudio,
        onChange = { onOptions(options.copy(includeAudio = it)) },
      )
    }
  }

  state.error?.let { error ->
    FluidInlineMessage(
      title = stringResource(R.string.export_failed),
      message = error,
      tone = FluidTone.Danger,
    )
  }
}

@Composable
private fun ColumnScope.ConfiguringActions(
  state: ExportUiState,
  onShare: () -> Unit,
  onSaveHere: () -> Unit,
  onPickFolder: () -> Unit,
) {
  val enabled = state.ready && state.noteCount > 0
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    FluidButton(
      text = stringResource(R.string.export_share),
      onClick = onShare,
      style = FluidButtonStyle.Tinted,
      enabled = enabled,
      fillWidth = true,
      modifier = Modifier.weight(1f),
    )
    FluidButton(
      text = if (state.folderName.isNotBlank()) {
        stringResource(R.string.export_save_in, state.folderName)
      } else {
        stringResource(R.string.export_save_folder)
      },
      onClick = if (state.folderName.isNotBlank()) onSaveHere else onPickFolder,
      enabled = enabled,
      fillWidth = true,
      modifier = Modifier.weight(1f),
    )
  }
  if (state.folderName.isNotBlank()) {
    FluidButton(
      text = stringResource(R.string.export_change_folder),
      onClick = onPickFolder,
      style = FluidButtonStyle.Plain,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

// -------------------------------------------------------------------------------------------------
// Mentre scrive, e quando ha finito
// -------------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.RunningBody(state: ExportUiState) {
  Text(text = stringResource(R.string.export_running), style = MaterialTheme.typography.titleLarge)
  Text(
    text = stringResource(R.string.export_running_detail),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  FluidProgressBar(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
}

@Composable
private fun ColumnScope.DoneBody(state: ExportUiState) {
  Text(text = stringResource(R.string.export_done), style = MaterialTheme.typography.titleLarge)
  state.result?.let { result ->
    Text(
      text = result.displayName,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = pluralStringResource(R.plurals.export_done_detail, result.noteCount, result.noteCount) +
        " · " + Formats.bytes(result.sizeBytes),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun ColumnScope.DoneActions(
  state: ExportUiState,
  onShare: () -> Unit,
  onOpen: () -> Unit,
  onClose: () -> Unit,
) {
  // Il file in cache si consegna a un'altra app; quello gia' salvato in una cartella si apre.
  if (state.result?.file != null) {
    FluidButton(
      text = stringResource(R.string.export_share),
      onClick = onShare,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  } else {
    FluidButton(
      text = stringResource(R.string.export_open),
      onClick = onOpen,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
  FluidButton(
    text = stringResource(R.string.action_done),
    onClick = onClose,
    style = FluidButtonStyle.Plain,
    fillWidth = true,
    modifier = Modifier.fillMaxWidth(),
  )
}

// -------------------------------------------------------------------------------------------------

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  FluidListRow(
    title = title,
    subtitle = subtitle,
    onClick = { onChange(!checked) },
    badge = { FluidSwitch(checked = checked, onCheckedChange = onChange) },
  )
}

@Composable
private fun summaryOf(notes: Int, sessions: Int, audioMs: Long): String {
  val pieces = buildList {
    add(pluralStringResource(R.plurals.export_notes, notes, notes))
    if (sessions > 0) add(pluralStringResource(R.plurals.export_recordings, sessions, sessions))
    if (audioMs > 0) add(Formats.durationShort(audioMs))
  }
  return pieces.joinToString(" · ")
}
