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
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.export.ExportEstimate
import dev.pampa.pampanotes.core.export.ExportFailure
import dev.pampa.pampanotes.core.export.ExportFileKind
import dev.pampa.pampanotes.core.export.ExportScope
import dev.pampa.pampanotes.core.export.ExportTarget
import dev.pampa.pampanotes.core.export.ExportWarning
import dev.pampa.pampanotes.core.export.MissingGroup
import dev.pampa.pampanotes.core.export.MissingReason
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.PageActions
import dev.pampa.pampanotes.ui.common.SheetBody
import java.util.Locale

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

  val busy = state.stage == ExportStage.RUNNING || state.stage == ExportStage.FETCHING
  FluidGlassModalPortal(
    visible = true,
    // Chiudere a meta' lavoro lascerebbe un file rotto, o un download a meta': finche' lavora, il
    // pannello resta, e «Annulla» e' il modo di uscire.
    onDismissRequest = { if (!busy) onDismiss() },
    // Una pagina intera: le scelte sono piu' di tre, e i tasti in fondo restano fermi mentre
    // le scelte scorrono.
    presentation = FluidGlassModalPresentation.FullScreen,
    paneTitle = stringResource(R.string.action_export),
    footer = {
      PageActions {
        when (state.stage) {
          ExportStage.RUNNING, ExportStage.FETCHING -> FluidButton(
            text = stringResource(R.string.action_cancel),
            onClick = viewModel::cancel,
            style = FluidButtonStyle.Plain,
            fillWidth = true,
            modifier = Modifier.fillMaxWidth(),
          )

          ExportStage.MISSING -> MissingActions(onExportWithout = viewModel::exportWithoutMissing, onCancel = viewModel::cancel)

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
        ExportStage.FETCHING -> FetchingBody(state)
        ExportStage.MISSING -> MissingBody(state)
        ExportStage.RUNNING -> RunningBody(state)
        ExportStage.DONE -> DoneBody(state)
        else -> ConfiguringBody(state, viewModel)
      }
    }
  }
}

// -------------------------------------------------------------------------------------------------
// Le scelte
// -------------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.ConfiguringBody(state: ExportUiState, viewModel: ExportViewModel) {
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

  ExportChoices(
    options = state.options,
    onOptions = viewModel::setOptions,
    audioDurationMs = set?.audioDurationMs,
  )

  // Il peso prima di esportare: e' qui che si capisce se una chat lo prendera' o no.
  state.estimate?.let { estimate ->
    Text(
      text = stringResource(R.string.export_estimate, Formats.bytes(estimate.bytes), tokens(estimate.tokens)),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  state.warnings.forEach { warning -> WarningMessage(warning, state.estimate) }

  if (state.stage == ExportStage.FAILED) {
    val fallback = stringResource(R.string.export_failure_write)
    FluidInlineMessage(
      title = stringResource(R.string.export_failed),
      message = state.failure?.let { exportFailureText(it) } ?: state.error?.takeIf { it.isNotBlank() } ?: fallback,
      tone = FluidTone.Danger,
    )
  }
}

@Composable
private fun WarningMessage(warning: ExportWarning, estimate: ExportEstimate?) {
  val tokenText = tokens(estimate?.tokens ?: 0)
  val (title, message) = when (warning) {
    ExportWarning.CHAT_TOO_HEAVY -> stringResource(R.string.export_warn_heavy_title) to
      stringResource(R.string.export_warn_heavy)
    ExportWarning.CHAT_TOO_LONG -> stringResource(R.string.export_warn_long_title) to
      stringResource(R.string.export_warn_long, tokenText)
    ExportWarning.PASTE_TOO_LONG -> stringResource(R.string.export_warn_long_title) to
      stringResource(R.string.export_warn_paste_long, tokenText)
    ExportWarning.AUDIO_HEAVY -> stringResource(R.string.export_warn_audio_title) to
      stringResource(R.string.export_warn_audio, Formats.bytes(estimate?.audioBytes ?: 0))
  }
  // Informazioni, non allarmi: il pacchetto si puo' fare lo stesso, e la persona decide.
  FluidInlineMessage(title = title, message = message, tone = FluidTone.Info)
}

@Composable
private fun exportFailureText(reason: ExportFailure.Reason): String = stringResource(
  when (reason) {
    ExportFailure.Reason.WRITE -> R.string.export_failure_write
    ExportFailure.Reason.CREATE -> R.string.export_failure_create
    ExportFailure.Reason.NOT_WRITABLE -> R.string.export_failure_not_writable
    ExportFailure.Reason.FOLDER_GONE -> R.string.export_failure_folder_gone
    ExportFailure.Reason.INTERRUPTED -> R.string.export_failure_interrupted
  },
)

@Composable
private fun ColumnScope.ConfiguringActions(
  state: ExportUiState,
  onShare: () -> Unit,
  onSaveHere: () -> Unit,
  onPickFolder: () -> Unit,
) {
  val enabled = state.ready && state.noteCount > 0
  val saveText = if (state.folderName.isNotBlank()) {
    stringResource(R.string.export_save_in, state.folderName)
  } else {
    stringResource(R.string.export_save_folder)
  }
  val onSave = if (state.folderName.isNotBlank()) onSaveHere else onPickFolder
  // Per un Progetto si salva: venti file condivisi insieme a un'app di chat arrivano spesso a meta',
  // e da una cartella (Drive, il telefono) si caricano tutti e si ricaricano quando serve.
  val saveFirst = state.options.target == ExportTarget.PROJECT
  if (saveFirst) FluidSectionFootnote(text = stringResource(R.string.export_project_save_hint))
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    if (saveFirst) {
      FluidButton(text = saveText, onClick = onSave, style = FluidButtonStyle.Tinted, enabled = enabled, fillWidth = true, modifier = Modifier.weight(1f))
      FluidButton(text = stringResource(R.string.export_share), onClick = onShare, enabled = enabled, fillWidth = true, modifier = Modifier.weight(1f))
    } else {
      FluidButton(text = stringResource(R.string.export_share), onClick = onShare, style = FluidButtonStyle.Tinted, enabled = enabled, fillWidth = true, modifier = Modifier.weight(1f))
      FluidButton(text = saveText, onClick = onSave, enabled = enabled, fillWidth = true, modifier = Modifier.weight(1f))
    }
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
// I file dal computer di casa
// -------------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.FetchingBody(state: ExportUiState) {
  val fetch = state.fetch
  val total = fetch?.total ?: 0
  // Il file che sta arrivando, contato da uno: «1 di 12» mentre scarica il primo.
  val current = ((fetch?.done ?: 0) + 1).coerceAtMost(total.coerceAtLeast(1))
  Text(text = stringResource(R.string.export_fetching, current, total), style = MaterialTheme.typography.titleLarge)
  fetch?.label?.takeIf { it.isNotBlank() }?.let { name ->
    Text(text = name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
  FluidProgressBar(progress = { fetch?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
  FluidSectionFootnote(text = stringResource(R.string.export_fetching_detail))
}

@Composable
private fun ColumnScope.MissingBody(state: ExportUiState) {
  Text(text = stringResource(R.string.export_missing_title), style = MaterialTheme.typography.titleLarge)
  Text(
    text = stringResource(R.string.export_missing_detail),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  MissingList(state.missing)
  if (state.fetched > 0) {
    FluidSectionFootnote(text = pluralStringResource(R.plurals.export_fetched, state.fetched, state.fetched))
  }
  FluidSectionFootnote(
    text = stringResource(if (state.computerSilent) R.string.export_missing_retry else R.string.export_missing_without),
  )
}

@Composable
private fun MissingList(groups: List<MissingGroup>) {
  FluidListGroup {
    groups.forEachIndexed { index, group ->
      if (index > 0) FluidListDivider()
      FluidListRow(
        title = kindCount(group.kind, group.count),
        subtitle = reasonText(group.reason),
        tone = if (group.reason == MissingReason.NOT_ARCHIVED) FluidTone.Neutral else FluidTone.Warning,
      )
    }
  }
}

@Composable
private fun ColumnScope.MissingActions(onExportWithout: () -> Unit, onCancel: () -> Unit) {
  FluidButton(
    text = stringResource(R.string.export_missing_continue),
    onClick = onExportWithout,
    style = FluidButtonStyle.Tinted,
    fillWidth = true,
    modifier = Modifier.fillMaxWidth(),
  )
  FluidButton(
    text = stringResource(R.string.action_cancel),
    onClick = onCancel,
    style = FluidButtonStyle.Plain,
    fillWidth = true,
    modifier = Modifier.fillMaxWidth(),
  )
}

@Composable
private fun kindCount(kind: ExportFileKind, count: Int): String = pluralStringResource(
  when (kind) {
    ExportFileKind.AUDIO -> R.plurals.export_recordings
    ExportFileKind.SOURCE -> R.plurals.export_originals
    ExportFileKind.PAGE -> R.plurals.export_handwritten_pages
  },
  count,
  count,
)

@Composable
private fun reasonText(reason: MissingReason): String = stringResource(
  when (reason) {
    MissingReason.NOT_ARCHIVED -> R.string.export_missing_not_archived
    MissingReason.UNREACHABLE -> R.string.export_missing_unreachable
    MissingReason.FAILED -> R.string.export_missing_failed
  },
)

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
  val result = state.result ?: return
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

  // Cosa c'e' dentro, contato sul pacchetto scritto: e' la risposta a «li ha messi o no?».
  val inside = buildList {
    if (result.includedAudio > 0) add(kindCount(ExportFileKind.AUDIO, result.includedAudio))
    if (result.includedSources > 0) add(kindCount(ExportFileKind.SOURCE, result.includedSources))
    if (result.includedPages > 0) add(kindCount(ExportFileKind.PAGE, result.includedPages))
  }
  if (inside.isNotEmpty()) {
    Text(
      text = stringResource(R.string.export_done_inside, inside.joinToString(" · ")),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  if (state.fetched > 0) {
    Text(
      text = pluralStringResource(R.plurals.export_fetched, state.fetched, state.fetched),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }

  if (state.missing.isNotEmpty()) {
    FluidSectionFootnote(text = stringResource(R.string.export_done_missing))
    MissingList(state.missing)
  } else {
    // Mancanti scoperti solo scrivendo (un file sparito fra il controllo e la scrittura): si dicono
    // lo stesso, con le frasi di prima.
    if (result.skippedAudio > 0) {
      Text(
        text = pluralStringResource(R.plurals.export_skipped_audio, result.skippedAudio, result.skippedAudio),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
    if (result.skippedSources > 0) {
      Text(
        text = pluralStringResource(R.plurals.export_skipped_sources, result.skippedSources, result.skippedSources),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
  }
}

@Composable
private fun ColumnScope.DoneActions(
  state: ExportUiState,
  onShare: () -> Unit,
  onOpen: () -> Unit,
  onClose: () -> Unit,
) {
  // Il file in cache si consegna a un'altra app; quello gia' salvato in una cartella si apre. Una
  // cartella di file sciolti salvata non si apre con niente: e' gia' dove l'utente l'ha messa.
  if (state.result?.file != null) {
    FluidButton(
      text = stringResource(R.string.export_share),
      onClick = onShare,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  } else if (state.result?.isDirectory != true) {
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
private fun summaryOf(notes: Int, sessions: Int, audioMs: Long): String {
  val pieces = buildList {
    add(pluralStringResource(R.plurals.export_notes, notes, notes))
    if (sessions > 0) add(pluralStringResource(R.plurals.export_recordings, sessions, sessions))
    if (audioMs > 0) add(Formats.durationShort(audioMs))
  }
  return pieces.joinToString(" · ")
}

/**
 * «90k», «1,2M»: un conto di token si legge a occhio, e la precisione sarebbe finta — e' una stima
 * fatta dalle parole.
 */
private fun tokens(value: Long): String = when {
  value < 1_000 -> value.toString()
  value < 1_000_000 -> "${(value + 500) / 1_000}k"
  else -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000.0)
}
