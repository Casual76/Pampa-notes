package dev.pampa.pampanotes.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.backup.BackupManifest
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.nav.SettingsSection

/**
 * Backup e ripristino.
 *
 * Due mestieri opposti nella stessa pagina, e per questo separati da un titolo: uno scrive un file
 * e non tocca niente, l'altro sostituisce tutto l'archivio. Quello che sostituisce chiede conferma
 * **dopo** aver letto cosa c'e' dentro il file scelto, perche' "3 note del 12 marzo" e' l'unica
 * cosa che permette di accorgersi di aver preso il backup sbagliato mentre si puo' ancora dire no.
 */
@Composable
fun BackupSectionRoute(
  onBack: () -> Unit,
  viewModel: BackupViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current

  // Il permesso sulla cartella va reso permanente subito: senza, alla prossima apertura dell'app
  // l'URI salvato non vale piu' e il backup fallisce senza spiegazioni.
  val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
      }
      viewModel.setFolder(uri)
    }
  }

  // Tipi larghi apposta: Drive e OneDrive dichiarano uno zip in modi diversi, e un selettore che
  // non mostra il file che l'utente vede nella cartella e' peggio di uno permissivo.
  val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) viewModel.inspect(uri)
  }

  FluidScreen(
    title = SettingsSection.BACKUP.label(),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Bars),
  ) {
    item { FluidSectionFootnote(text = stringResource(R.string.backup_explain)) }

    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.backup_folder),
          subtitle = state.folderName.ifBlank { stringResource(R.string.backup_folder_missing) },
          meta = if (state.folderUri.isNotBlank()) stringResource(R.string.backup_folder_change) else null,
          onClick = { pickFolder.launch(null) },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.backup_include_audio),
          subtitle = stringResource(R.string.backup_include_audio_detail),
          badge = {
            FluidSwitch(checked = state.includeAudio, onCheckedChange = viewModel::setIncludeAudio)
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.backup_include_sources),
          subtitle = stringResource(R.string.backup_include_sources_detail),
          badge = {
            FluidSwitch(checked = state.includeSources, onCheckedChange = viewModel::setIncludeSources)
          },
        )
      }
    }

    state.preview?.let { preview ->
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.backup_contents),
            subtitle = contentsOf(preview),
            meta = Formats.bytes(preview.totalBytes),
          )
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.backup_last),
            subtitle = if (state.lastBackupAt > 0) {
              Formats.relativeDate(state.lastBackupAt)
            } else {
              stringResource(R.string.backup_never)
            },
          )
        }
      }
    }

    item {
      FluidButton(
        text = stringResource(R.string.backup_now),
        onClick = viewModel::writeBackup,
        enabled = state.canWrite,
        loading = state.busy,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    if (state.busy) {
      item { FluidProgressBar(progress = { state.progress }, modifier = Modifier.fillMaxWidth()) }
    }

    state.written?.let { result ->
      item {
        FluidInlineMessage(
          title = stringResource(R.string.backup_done),
          message = stringResource(R.string.backup_done_detail, result.displayName, Formats.bytes(result.sizeBytes)),
          tone = FluidTone.Success,
          onDismiss = viewModel::dismissMessage,
        )
      }
    }

    state.error?.let { message ->
      item {
        FluidInlineMessage(
          title = stringResource(R.string.backup_failed),
          message = message,
          tone = FluidTone.Danger,
          onDismiss = viewModel::dismissMessage,
        )
      }
    }

    item {
      FluidSectionHeader(
        title = stringResource(R.string.backup_restore_title),
        detail = stringResource(R.string.backup_restore_detail),
      )
    }
    item {
      FluidButton(
        text = stringResource(R.string.backup_restore_action),
        onClick = { pickFile.launch(arrayOf("application/zip", "application/octet-stream")) },
        enabled = !state.busy,
        style = FluidButtonStyle.Tinted,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    item { FluidSectionFootnote(text = stringResource(R.string.backup_secrets_note)) }
  }

  state.pending?.let { pending ->
    FluidAlert(
      onDismissRequest = viewModel::cancelRestore,
      title = stringResource(R.string.backup_restore_confirm_title),
      message = stringResource(
        R.string.backup_restore_confirm_message,
        contentsOf(pending),
        Formats.relativeDate(pending.createdAt),
      ),
      actions = listOf(
        FluidAlertAction(
          label = stringResource(R.string.backup_restore_confirm_action),
          emphasis = FluidAlertAction.Emphasis.Destructive,
          onClick = viewModel::confirmRestore,
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = viewModel::cancelRestore),
      ),
    )
  }

  state.restored?.let {
    FluidAlert(
      // Nessun modo di chiuderlo senza riavviare: da qui in poi il processo non ha piu' un
      // database aperto, e ogni schermata dietro mostrerebbe i dati di prima.
      onDismissRequest = { restart(context) },
      title = stringResource(R.string.backup_restored_title),
      message = stringResource(R.string.backup_restored_message),
      actions = listOf(
        FluidAlertAction(
          label = stringResource(R.string.backup_restart),
          emphasis = FluidAlertAction.Emphasis.Preferred,
          onClick = { restart(context) },
        ),
      ),
    )
  }
}

/** "2 cartelle · 7 note · 3 registrazioni": cosa c'e' dentro, nell'ordine in cui lo si cerca. */
@Composable
private fun contentsOf(manifest: BackupManifest): String {
  val counts = manifest.counts
  val notes = pluralStringResource(R.plurals.export_notes, counts.notes, counts.notes)
  val parts = pluralStringResource(R.plurals.export_recordings, counts.parts, counts.parts)
  val audio = if (manifest.includesAudio) "" else " " + stringResource(R.string.backup_without_audio)
  return "$notes · $parts$audio"
}

/**
 * Riavvia l'app.
 *
 * Non e' una scorciatoia: dopo un ripristino il database e' un altro file, e ogni ViewModel vivo
 * tiene ancora in mano le righe di prima. Ripartire da zero e' l'unico stato di cui ci si possa
 * fidare, e farlo subito evita che qualcuno scriva sopra quello appena ripristinato.
 */
private fun restart(context: Context) {
  val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
  if (intent != null) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
  }
  Runtime.getRuntime().exit(0)
}
