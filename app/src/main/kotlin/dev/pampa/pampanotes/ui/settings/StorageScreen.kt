package dev.pampa.pampanotes.ui.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
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
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.nav.SettingsSection

/**
 * Archiviazione: cosa occupa lo spazio, e cosa si puo' togliere senza perdere niente.
 *
 * Le due pulizie che offre non cancellano **mai** una nota o una registrazione citata da una riga:
 * tolgono i file rimasti indietro e i pacchetti gia' condivisi. Una pagina che libera spazio
 * cancellando anche dati e' una pagina che si tocca una volta sola, e con paura.
 */
@Composable
fun StorageSectionRoute(
  onBack: () -> Unit,
  viewModel: StorageViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val usage = state.usage
  // Cosa si sta per togliere dal dispositivo: "sources" o "audio". Null: nessuna domanda aperta.
  var confirmEvict by remember { mutableStateOf<String?>(null) }

  FluidScreen(
    title = SettingsSection.STORAGE.label(),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Bars),
  ) {
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.storage_audio),
          subtitle = pluralStringResource(
            R.plurals.export_recordings,
            usage?.audio?.count ?: 0,
            usage?.audio?.count ?: 0,
          ),
          meta = Formats.bytes(usage?.audio?.bytes ?: 0),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.storage_sources),
          subtitle = stringResource(R.string.storage_sources_detail),
          meta = Formats.bytes(usage?.sources?.bytes ?: 0),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.storage_database),
          subtitle = stringResource(R.string.storage_database_detail),
          meta = Formats.bytes(usage?.databaseBytes ?: 0),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.storage_jobs),
          subtitle = stringResource(R.string.storage_jobs_detail),
          meta = Formats.bytes(usage?.jobsBytes ?: 0),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.storage_exports),
          subtitle = stringResource(R.string.storage_exports_detail),
          meta = Formats.bytes(usage?.exportsBytes ?: 0),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.storage_total),
          subtitle = stringResource(R.string.storage_total_detail),
          meta = Formats.bytes(usage?.totalBytes ?: 0),
        )
      }
    }

    archiveSection(state, viewModel)

    // Quello che sta qui e anche sul computer: si puo' togliere da qui, e tornera' quando servira'.
    // Due tasti separati perche' sono due promesse diverse: un PDF si riapre in un secondo, una
    // lezione da un'ora senza il computer non si ascolta.
    val evictableSources = usage?.evictableSources ?: dev.pampa.pampanotes.core.db.SizeTotal(0, 0)
    val evictableAudio = usage?.evictableAudio ?: dev.pampa.pampanotes.core.db.SizeTotal(0, 0)
    if (evictableSources.count > 0 || evictableAudio.count > 0) {
      item {
        FluidSectionHeader(
          title = stringResource(R.string.storage_evict_header),
          detail = stringResource(R.string.storage_evict_detail),
        )
      }
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.storage_evict_sources),
            subtitle = pluralStringResource(R.plurals.storage_archive_files, evictableSources.count, evictableSources.count),
            meta = Formats.bytes(evictableSources.bytes),
          )
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.storage_evict_audio),
            subtitle = pluralStringResource(R.plurals.storage_archive_files, evictableAudio.count, evictableAudio.count),
            meta = Formats.bytes(evictableAudio.bytes),
          )
        }
      }
      if (evictableSources.count > 0) {
        item {
          FluidButton(
            text = stringResource(R.string.storage_evict_sources_button),
            onClick = { confirmEvict = "sources" },
            enabled = !state.working,
            style = FluidButtonStyle.Tinted,
            fillWidth = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
      if (evictableAudio.count > 0) {
        item {
          FluidButton(
            text = stringResource(R.string.storage_evict_audio_button),
            onClick = { confirmEvict = "audio" },
            enabled = !state.working,
            style = FluidButtonStyle.Plain,
            fillWidth = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }

    item {
      FluidSectionHeader(
        title = stringResource(R.string.storage_cleanup),
        detail = stringResource(R.string.storage_cleanup_detail),
      )
    }

    item {
      FluidButton(
        text = stringResource(R.string.storage_sweep),
        onClick = viewModel::sweep,
        enabled = !state.working,
        loading = state.working,
        style = FluidButtonStyle.Tinted,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    item {
      FluidButton(
        text = stringResource(R.string.storage_clear_exports),
        onClick = viewModel::clearExports,
        enabled = !state.working,
        style = FluidButtonStyle.Plain,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    state.event?.let { event ->
      item {
        val message = when (event) {
          is StorageEvent.Swept -> pluralStringResource(R.plurals.storage_swept, event.files, event.files)
          is StorageEvent.ExportsCleared -> pluralStringResource(
            R.plurals.storage_exports_cleared,
            event.files,
            event.files,
          )
          is StorageEvent.Evicted -> if (event.files == 0) {
            stringResource(R.string.storage_evict_nothing)
          } else {
            pluralStringResource(R.plurals.storage_evicted, event.files, event.files, Formats.bytes(event.bytes))
          }
        }
        FluidInlineMessage(
          title = stringResource(R.string.storage_cleanup),
          message = message,
          tone = FluidTone.Success,
          onDismiss = viewModel::dismissEvent,
        )
      }
    }

    item { FluidSectionFootnote(text = stringResource(R.string.storage_footnote)) }
  }

  confirmEvict?.let { kind ->
    val sources = kind == "sources"
    val total = if (sources) evictableSourcesOf(usage) else evictableAudioOf(usage)
    FluidAlert(
      onDismissRequest = { confirmEvict = null },
      title = stringResource(R.string.storage_evict_confirm_title),
      message = stringResource(
        if (sources) R.string.storage_evict_confirm_sources else R.string.storage_evict_confirm_audio,
        Formats.bytes(total.bytes),
        total.count,
      ),
      actions = listOf(
        FluidAlertAction(
          label = stringResource(R.string.storage_evict_action),
          emphasis = FluidAlertAction.Emphasis.Destructive,
          onClick = {
            confirmEvict = null
            viewModel.evict(sources = sources, audio = !sources)
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { confirmEvict = null }),
      ),
    )
  }
}

private fun evictableSourcesOf(usage: dev.pampa.pampanotes.core.repo.StorageUsage?) = usage?.evictableSources ?: dev.pampa.pampanotes.core.db.SizeTotal(0, 0)
private fun evictableAudioOf(usage: dev.pampa.pampanotes.core.repo.StorageUsage?) = usage?.evictableAudio ?: dev.pampa.pampanotes.core.db.SizeTotal(0, 0)

/**
 * L'archivio sul computer di casa: quanto c'e' gia', quanto aspetta, e il tasto per non aspettare.
 *
 * Due interruttori e basta. Non c'e' un tetto di spazio ne' uno sfratto dei file: si e' deciso che
 * un file arrivato sul dispositivo ci resta, e questa pagina non cancella niente. Sale e basta.
 */
private fun LazyListScope.archiveSection(state: StorageUiState, viewModel: StorageViewModel) {
  val usage = state.usage

  item {
    FluidSectionHeader(
      title = stringResource(R.string.storage_archive_header),
      detail = stringResource(R.string.storage_archive_detail),
    )
  }

  item {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.storage_archive_enable),
        subtitle = stringResource(R.string.storage_archive_enable_detail),
        badge = { FluidSwitch(checked = state.archiveEnabled, onCheckedChange = viewModel::setArchiveEnabled, enabled = state.hasEndpoint) },
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.storage_archive_unmetered),
        subtitle = stringResource(R.string.storage_archive_unmetered_detail),
        badge = { FluidSwitch(checked = state.archiveOnlyUnmetered, onCheckedChange = viewModel::setArchiveOnlyUnmetered, enabled = state.archiveEnabled) },
      )
    }
  }

  if (!state.hasEndpoint) {
    item { FluidSectionFootnote(text = stringResource(R.string.storage_archive_no_endpoint)) }
  }

  item {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.storage_archived),
        subtitle = pluralStringResource(R.plurals.storage_archive_files, usage?.archived?.count ?: 0, usage?.archived?.count ?: 0),
        meta = Formats.bytes(usage?.archived?.bytes ?: 0),
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.storage_pending_archive),
        subtitle = pluralStringResource(R.plurals.storage_archive_files, usage?.pendingArchive?.count ?: 0, usage?.pendingArchive?.count ?: 0),
        meta = Formats.bytes(usage?.pendingArchive?.bytes ?: 0),
        tone = if ((usage?.pendingArchive?.count ?: 0) > 0) FluidTone.Warning else FluidTone.Neutral,
      )
      // Il verso opposto: righe arrivate dagli altri dispositivi, il cui file sta sul computer e
      // non qui. Non e' spazio occupato, e' spazio che si occupera' solo se lo si chiede.
      if ((usage?.remote?.count ?: 0) > 0) {
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.storage_remote),
          subtitle = stringResource(R.string.storage_remote_detail),
          meta = pluralStringResource(R.plurals.storage_archive_files, usage?.remote?.count ?: 0, usage?.remote?.count ?: 0) +
            " · " + Formats.bytes(usage?.remote?.bytes ?: 0),
        )
      }
    }
  }

  item {
    val run = state.archiveRun
    FluidButton(
      text = if (run != null && run.total > 0) {
        stringResource(R.string.storage_archive_running, run.done, run.total, run.label)
      } else {
        stringResource(R.string.storage_archive_now)
      },
      onClick = viewModel::archiveNow,
      enabled = state.archiveEnabled && state.hasEndpoint && !state.archiving,
      loading = state.archiving,
      style = FluidButtonStyle.Tinted,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }

  state.archiveLast?.let { last ->
    item {
      val summary = stringResource(R.string.storage_archive_result, last.uploaded, last.alreadyThere, last.failed)
      val detail = last.error?.takeIf { last.failed > 0 }?.let { stringResource(R.string.storage_archive_result_error, it) }
      FluidInlineMessage(
        title = stringResource(R.string.storage_archive_header),
        message = if (detail != null) "$summary\n$detail" else summary,
        tone = if (last.failed > 0) FluidTone.Warning else FluidTone.Success,
      )
    }
  }

  if (state.lastArchiveAt > 0) {
    item {
      val stamp = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(state.lastArchiveAt))
      FluidSectionFootnote(text = stringResource(R.string.storage_archive_last, stamp))
    }
  }

  // Il verso opposto dell'archivio: quello che sta sul computer e non qui, portato qui. Un
  // interruttore per farlo sempre, un tasto per farlo adesso e una volta sola.
  item {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.storage_mirror),
        subtitle = stringResource(R.string.storage_mirror_detail),
        badge = { FluidSwitch(checked = state.mirrorEnabled, onCheckedChange = viewModel::setMirrorEnabled, enabled = state.hasEndpoint) },
      )
    }
  }
  if ((usage?.remote?.count ?: 0) > 0 || state.fetching) {
    item {
      val run = state.fetchRun
      FluidButton(
        text = if (run != null && run.total > 0) {
          stringResource(R.string.storage_archive_running, run.done, run.total, run.label)
        } else {
          stringResource(R.string.storage_fetch_now)
        },
        onClick = viewModel::fetchNow,
        enabled = state.hasEndpoint && !state.fetching,
        loading = state.fetching,
        style = FluidButtonStyle.Tinted,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
  state.fetchLast?.let { last ->
    item {
      val summary = if (last.downloaded == 0 && last.failed == 0) {
        stringResource(R.string.storage_fetch_nothing)
      } else {
        stringResource(R.string.storage_fetch_result, last.downloaded, last.failed, Formats.bytes(last.bytes))
      }
      val detail = last.error?.takeIf { last.failed > 0 }?.let { stringResource(R.string.storage_archive_result_error, it) }
      FluidInlineMessage(
        title = stringResource(R.string.storage_mirror),
        message = if (detail != null) "$summary\n$detail" else summary,
        tone = if (last.failed > 0) FluidTone.Warning else FluidTone.Success,
      )
    }
  }
}
