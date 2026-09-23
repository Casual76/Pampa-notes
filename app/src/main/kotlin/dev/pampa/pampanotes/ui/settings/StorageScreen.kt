package dev.pampa.pampanotes.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
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
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
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
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.SizeTotal
import dev.pampa.pampanotes.core.files.ArchiveOutlook
import dev.pampa.pampanotes.core.files.FileLocations
import dev.pampa.pampanotes.core.files.FilePlace
import dev.pampa.pampanotes.core.files.PlaceSummary
import dev.pampa.pampanotes.ui.common.ComputerOnlyRule
import dev.pampa.pampanotes.ui.common.ComputerOnlySummary
import dev.pampa.pampanotes.ui.common.ComputerOnlyTarget
import dev.pampa.pampanotes.ui.common.ComputerOnlyViewModel
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.nav.SettingsSection
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * Archiviazione: dove stanno i file, cosa fa adesso ogni impostazione, e quanto spazio occupa.
 *
 * La pagina risponde prima alla domanda che l'utente ha davvero — «se perdo il telefono, cosa
 * perdo?» — e solo dopo mostra gli interruttori. Ogni interruttore dice cosa succede **con i valori
 * di adesso** («ogni 6 ore, solo su Wi-Fi»), non cosa fa in generale: una spiegazione generica
 * lascia all'utente il lavoro di combinarla con le altre, ed e' esattamente il lavoro che lo
 * lasciava col dubbio. Quattro gruppi: dove stanno, copia sul computer, copia qui, spazio.
 *
 * Le pulizie non cancellano **mai** una nota o una registrazione citata da una riga: tolgono i file
 * rimasti indietro, i pacchetti gia' condivisi, e — chiedendolo — quello che il computer ha gia'.
 */
@Composable
fun StorageSectionRoute(
  onBack: () -> Unit,
  onOpenServices: () -> Unit = {},
  viewModel: StorageViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val usage = state.usage
  // Cosa si sta per togliere dal dispositivo: "sources" o "audio". Null: nessuna domanda aperta.
  var confirmEvict by remember { mutableStateOf<String?>(null) }
  // «Solo sul computer»: l'elenco aperto, e la regola che si sta per togliere.
  val computerOnly: ComputerOnlyViewModel = hiltViewModel()
  val computerOnlySummary by computerOnly.summary.collectAsStateWithLifecycle()
  var computerOnlyOpen by remember { mutableStateOf(false) }
  var keepingHere by remember { mutableStateOf<ComputerOnlyRule?>(null) }

  FluidScreen(
    title = SettingsSection.STORAGE.label(),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Bars),
  ) {
    whereSection(state)
    toComputerSection(state, viewModel, onOpenServices)
    toHereSection(state, viewModel)
    if (state.hasEndpoint || computerOnlySummary.rules.isNotEmpty()) {
      computerOnlySection(
        summary = computerOnlySummary,
        open = computerOnlyOpen,
        onToggle = { computerOnlyOpen = !computerOnlyOpen },
        onKeep = { keepingHere = it },
      )
    }
    spaceSection(state, viewModel, onAskEvict = { confirmEvict = it })

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
          title = stringResource(R.string.storage_cleanup_header),
          message = message,
          tone = FluidTone.Success,
          onDismiss = viewModel::dismissEvent,
        )
      }
    }

    item { FluidSectionFootnote(text = stringResource(R.string.storage_footnote)) }
  }

  keepingHere?.let { rule ->
    FluidAlert(
      onDismissRequest = { keepingHere = null },
      title = stringResource(R.string.computer_only_keep_title, rule.name),
      message = stringResource(R.string.computer_only_keep_message),
      actions = listOf(
        FluidAlertAction(
          label = stringResource(R.string.computer_only_off),
          emphasis = FluidAlertAction.Emphasis.Preferred,
          onClick = {
            keepingHere = null
            computerOnly.keepHere(
              if (rule.isFolder) ComputerOnlyTarget.Folder(rule.id, rule.name) else ComputerOnlyTarget.Notes(setOf(rule.id), rule.name),
            )
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { keepingHere = null }),
      ),
    )
  }

  confirmEvict?.let { kind ->
    val sources = kind == "sources"
    val total = (if (sources) usage?.evictableSources else usage?.evictableAudio) ?: SizeTotal(0, 0)
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

// --- Dove stanno i tuoi file ---

/**
 * Il riassunto in cima: un verdetto in una frase, poi registrazioni e originali divisi per posto.
 *
 * Il verdetto e' la risposta a «devo preoccuparmi?»: quanti file stanno solo qui e quando saliranno,
 * con le impostazioni di adesso. I posti vuoti non si elencano: una riga «0 file» e' rumore.
 */
private fun LazyListScope.whereSection(state: StorageUiState) {
  val locations = state.usage?.locations ?: return

  item {
    FluidSectionHeader(
      title = stringResource(R.string.storage_where_header),
      detail = stringResource(R.string.storage_where_detail),
    )
  }

  item {
    verdict(state, locations)?.let { (title, message, tone) ->
      FluidInlineMessage(title = title, message = message, tone = tone)
    }
  }

  item {
    placeGroup(
      title = stringResource(R.string.storage_audio),
      detail = stringResource(R.string.storage_kind_recordings_detail),
      summary = locations.recordings,
      countLabel = { pluralStringResource(R.plurals.export_recordings, it, it) },
      state = state,
    )
  }
  item {
    placeGroup(
      title = stringResource(R.string.storage_kind_originals),
      detail = stringResource(R.string.storage_kind_originals_detail),
      summary = locations.originals,
      countLabel = { pluralStringResource(R.plurals.storage_archive_files, it, it) },
      state = state,
    )
  }
}

private data class Verdict(val title: String, val message: String, val tone: FluidTone)

@Composable
private fun verdict(state: StorageUiState, locations: FileLocations): Verdict? {
  val atRisk = locations.atRisk
  return when {
    atRisk.count > 0 -> Verdict(
      title = pluralStringResource(R.plurals.storage_verdict_risk_title, atRisk.count, atRisk.count),
      message = stringResource(R.string.storage_verdict_risk_message, Formats.bytes(atRisk.bytes), outlookSentence(state)),
      tone = FluidTone.Warning,
    )
    locations.recordings.here.count + locations.originals.here.count > 0 -> Verdict(
      title = stringResource(R.string.storage_verdict_safe_title),
      message = stringResource(R.string.storage_verdict_safe_message),
      tone = FluidTone.Success,
    )
    // Niente qui: niente da rassicurare. I gruppi sotto dicono gia' dove sta il resto.
    else -> null
  }
}

@Composable
private fun placeGroup(
  title: String,
  detail: String,
  summary: PlaceSummary,
  countLabel: @Composable (Int) -> String,
  state: StorageUiState,
) {
  val total = summary.total
  FluidListGroup {
    FluidListRow(
      title = title,
      subtitle = if (total.count == 0) stringResource(R.string.storage_kind_empty) else detail,
      meta = if (total.count == 0) null else countAndSize(countLabel(total.count), total.bytes),
    )
    summary.nonEmpty.forEach { place ->
      val part = summary[place]
      FluidListDivider()
      FluidListRow(
        title = stringResource(
          when (place) {
            FilePlace.BOTH -> R.string.storage_place_both
            FilePlace.ONLY_HERE -> R.string.storage_place_only_here
            FilePlace.ONLY_COMPUTER -> R.string.storage_place_only_computer
            FilePlace.ELSEWHERE -> R.string.storage_place_elsewhere
          },
        ),
        subtitle = when (place) {
          FilePlace.BOTH -> stringResource(R.string.storage_place_both_detail)
          FilePlace.ONLY_HERE -> stringResource(R.string.storage_place_only_here_detail, outlookSentence(state))
          FilePlace.ONLY_COMPUTER -> stringResource(
            if (state.mirrorEnabled && state.syncEnabled) R.string.storage_place_only_computer_mirror else R.string.storage_place_only_computer_ondemand,
          )
          FilePlace.ELSEWHERE -> stringResource(R.string.storage_place_elsewhere_detail)
        },
        // Il numero sta nel distintivo, col colore del posto; sotto resta il peso.
        meta = Formats.bytes(part.bytes),
        badge = { FluidStatusBadge(label = part.count.toString(), tone = place.tone()) },
      )
    }
  }
}

/** Il colore dice solo una cosa: dove c'e' un rischio. Il resto e' informazione, non allarme. */
private fun FilePlace.tone(): FluidTone = when (this) {
  FilePlace.BOTH -> FluidTone.Success
  FilePlace.ONLY_HERE -> FluidTone.Warning
  FilePlace.ONLY_COMPUTER -> FluidTone.Info
  FilePlace.ELSEWHERE -> FluidTone.Neutral
}

/**
 * Quando salira' quello che sta solo qui: una frase, letta dai lavori in coda e dalle impostazioni.
 * E' la stessa nel verdetto, nella riga «Solo qui» e nella riga «Prossimo giro», perche' e' la
 * stessa risposta.
 */
@Composable
private fun outlookSentence(state: StorageUiState): String = when (val outlook = state.archiveOutlook) {
  ArchiveOutlook.NoComputer -> stringResource(R.string.storage_outlook_no_computer)
  ArchiveOutlook.Off -> stringResource(R.string.storage_outlook_off)
  ArchiveOutlook.Running -> stringResource(R.string.storage_outlook_running)
  ArchiveOutlook.WaitingNetwork -> stringResource(
    if (state.archiveOnlyUnmetered) R.string.storage_outlook_waiting_wifi else R.string.storage_outlook_waiting_net,
  )
  is ArchiveOutlook.Retry -> stringResource(R.string.storage_outlook_retry, moment(outlook.at))
  is ArchiveOutlook.Scheduled -> stringResource(
    if (state.archiveOnlyUnmetered) R.string.storage_outlook_scheduled_wifi else R.string.storage_outlook_scheduled_net,
    moment(outlook.at),
  )
  ArchiveOutlook.Unknown -> stringResource(R.string.storage_outlook_unknown)
}

// --- Copia sul computer ---

/**
 * Il verso dispositivo → computer: lo stato del computer, l'interruttore, la rete, il prossimo giro.
 *
 * La riga del computer porta a Servizi, che e' dove lo si collega o lo si prova: qui si guarda
 * soltanto, e un tocco deve portare dove si puo' fare qualcosa.
 */
private fun LazyListScope.toComputerSection(
  state: StorageUiState,
  viewModel: StorageViewModel,
  onOpenServices: () -> Unit,
) {
  item {
    FluidSectionHeader(
      title = stringResource(R.string.storage_to_computer_header),
      detail = stringResource(R.string.storage_to_computer_detail),
    )
  }

  item {
    val network = stringResource(if (state.archiveOnlyUnmetered) R.string.storage_net_wifi else R.string.storage_net_any)
    FluidListGroup {
      FluidListRow(
        title = state.computerName.ifBlank { stringResource(R.string.storage_computer_title) },
        subtitle = stringResource(
          when (state.computer) {
            ComputerState.UNCONFIGURED -> R.string.storage_computer_unconfigured
            ComputerState.CHECKING -> R.string.storage_computer_checking
            ComputerState.REACHABLE -> R.string.storage_computer_reachable
            ComputerState.UNREACHABLE -> R.string.storage_computer_unreachable
          },
        ),
        meta = if (state.hasEndpoint) lastSent(state.lastArchiveAt) else null,
        badge = {
          FluidStatusBadge(
            label = stringResource(
              when (state.computer) {
                ComputerState.UNCONFIGURED -> R.string.storage_computer_badge_unconfigured
                ComputerState.CHECKING -> R.string.storage_computer_badge_checking
                ComputerState.REACHABLE -> R.string.storage_computer_badge_reachable
                ComputerState.UNREACHABLE -> R.string.storage_computer_badge_unreachable
              },
            ),
            tone = when (state.computer) {
              ComputerState.REACHABLE -> FluidTone.Success
              ComputerState.UNREACHABLE -> FluidTone.Warning
              else -> FluidTone.Neutral
            },
          )
        },
        onClick = onOpenServices,
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.storage_archive_enable),
        subtitle = when {
          !state.hasEndpoint -> stringResource(R.string.storage_archive_state_no_computer)
          !state.archiveEnabled -> stringResource(R.string.storage_archive_state_off)
          else -> stringResource(R.string.storage_archive_state_on, network)
        },
        badge = { FluidSwitch(checked = state.archiveEnabled, onCheckedChange = viewModel::setArchiveEnabled, enabled = state.hasEndpoint) },
      )
      FluidListDivider()
      // Vale per tutti e due i versi, e lo dice: prima era sotto l'archivio e sembrava solo suo.
      FluidListRow(
        title = stringResource(R.string.storage_archive_unmetered),
        subtitle = stringResource(if (state.archiveOnlyUnmetered) R.string.storage_unmetered_on else R.string.storage_unmetered_off),
        badge = { FluidSwitch(checked = state.archiveOnlyUnmetered, onCheckedChange = viewModel::setArchiveOnlyUnmetered, enabled = state.hasEndpoint) },
      )
      if (state.hasEndpoint && state.archiveEnabled) {
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.storage_next_title),
          subtitle = outlookSentence(state),
        )
      }
      val rejections = state.rejections
      if (rejections.files > 0) {
        FluidListDivider()
        FluidListRow(
          title = pluralStringResource(R.plurals.storage_rejected_title, rejections.files, rejections.files),
          subtitle = if (rejections.parked > 0 && rejections.retryAt > 0) {
            stringResource(R.string.storage_rejected_parked, moment(rejections.retryAt))
          } else {
            stringResource(R.string.storage_rejected_retry)
          },
          tone = FluidTone.Warning,
          badge = { FluidStatusBadge(label = rejections.files.toString(), tone = FluidTone.Warning) },
        )
      }
    }
  }

  if (state.hasEndpoint && state.archiveEnabled) {
    item {
      val run = state.archiveRun
      FluidButton(
        text = if (run != null && run.total > 0) {
          stringResource(R.string.storage_archive_running, run.done, run.total, run.label)
        } else {
          stringResource(R.string.storage_archive_now)
        },
        onClick = viewModel::archiveNow,
        enabled = !state.archiving,
        loading = state.archiving,
        style = FluidButtonStyle.Tinted,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }

  state.archiveLast?.let { last ->
    item {
      val summary = stringResource(R.string.storage_archive_result, last.uploaded, last.alreadyThere, last.failed)
      val detail = last.error?.takeIf { last.failed > 0 }?.let { stringResource(R.string.storage_archive_result_error, it) }
      FluidInlineMessage(
        title = stringResource(R.string.storage_to_computer_header),
        message = if (detail != null) "$summary\n$detail" else summary,
        tone = if (last.failed > 0) FluidTone.Warning else FluidTone.Success,
      )
    }
  }
}

// --- Copia qui ---

/** Il verso computer → dispositivo: un interruttore per farlo sempre, un tasto per farlo adesso. */
private fun LazyListScope.toHereSection(state: StorageUiState, viewModel: StorageViewModel) {
  val remote = state.usage?.locations?.onlyComputer ?: SizeTotal(0, 0)

  item {
    FluidSectionHeader(
      title = stringResource(R.string.storage_to_here_header),
      detail = stringResource(R.string.storage_to_here_detail),
    )
  }

  item {
    val network = stringResource(if (state.archiveOnlyUnmetered) R.string.storage_net_wifi else R.string.storage_net_any)
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.storage_mirror),
        subtitle = when {
          !state.hasEndpoint -> stringResource(R.string.storage_archive_state_no_computer)
          !state.mirrorEnabled -> stringResource(R.string.storage_mirror_state_off)
          // Senza sync non arrivano righe dagli altri: acceso, ma non avrebbe niente da fare.
          !state.syncEnabled -> stringResource(R.string.storage_mirror_state_no_sync)
          else -> stringResource(R.string.storage_mirror_state_on, network)
        },
        badge = { FluidSwitch(checked = state.mirrorEnabled, onCheckedChange = viewModel::setMirrorEnabled, enabled = state.hasEndpoint) },
      )
      if (remote.count > 0) {
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.storage_to_download),
          subtitle = when {
            state.fetchQueued -> stringResource(
              if (state.archiveOnlyUnmetered) R.string.storage_to_download_queued_wifi else R.string.storage_to_download_queued_net,
            )
            else -> stringResource(R.string.storage_to_download_detail)
          },
          meta = countAndSize(pluralStringResource(R.plurals.storage_archive_files, remote.count, remote.count), remote.bytes),
        )
      }
    }
  }

  if (state.hasEndpoint && (remote.count > 0 || state.fetching)) {
    item {
      val run = state.fetchRun
      FluidButton(
        text = if (run != null && run.total > 0) {
          stringResource(R.string.storage_archive_running, run.done, run.total, run.label)
        } else {
          stringResource(R.string.storage_fetch_now_size, Formats.bytes(remote.bytes))
        },
        onClick = viewModel::fetchNow,
        enabled = !state.fetching,
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
        title = stringResource(R.string.storage_to_here_header),
        message = if (detail != null) "$summary\n$detail" else summary,
        tone = if (last.failed > 0) FluidTone.Warning else FluidTone.Success,
      )
    }
  }
}

// --- Solo sul computer ---

/**
 * Le cartelle e le note che su questo dispositivo stanno solo sul computer: quante, quanto pesano,
 * e — aperto — una riga per regola, che si tocca per tenerla anche qui. Le regole si accendono dalle
 * cartelle e dalle note; qui si guardano e si tolgono.
 */
private fun LazyListScope.computerOnlySection(
  summary: ComputerOnlySummary,
  open: Boolean,
  onToggle: () -> Unit,
  onKeep: (ComputerOnlyRule) -> Unit,
) {
  item {
    FluidSectionHeader(
      title = stringResource(R.string.computer_only_header),
      detail = stringResource(R.string.computer_only_header_detail),
    )
  }
  item {
    val empty = summary.rules.isEmpty()
    FluidListGroup {
      FluidListRow(
        title = if (empty) {
          stringResource(R.string.computer_only_row_none)
        } else {
          buildList {
            if (summary.folderCount > 0) add(pluralStringResource(R.plurals.computer_only_folders, summary.folderCount, summary.folderCount))
            if (summary.noteCount > 0) add(pluralStringResource(R.plurals.computer_only_notes, summary.noteCount, summary.noteCount))
          }.joinToString(", ")
        },
        subtitle = stringResource(if (empty) R.string.computer_only_row_empty else R.string.computer_only_row_open),
        meta = if (empty) null else Formats.bytes(summary.bytes),
        onClick = if (empty) null else onToggle,
      )
      if (open) {
        summary.rules.forEach { rule ->
          FluidListDivider()
          FluidListRow(
            title = rule.name,
            subtitle = stringResource(if (rule.isFolder) R.string.computer_only_rule_folder else R.string.computer_only_rule_note),
            onClick = { onKeep(rule) },
          )
        }
      }
    }
  }
}

// --- Spazio ---

/**
 * Quanto occupa l'app, e le tre cose che la fanno occupare meno.
 *
 * «Libera spazio» ha due tasti separati perche' sono due promesse diverse: un PDF si riapre in un
 * secondo, una lezione da un'ora senza il computer non si ascolta. Il tasto dice quanti file e
 * quanti byte: chi tocca deve sapere cosa sta per togliere prima della conferma, non dopo.
 */
private fun LazyListScope.spaceSection(
  state: StorageUiState,
  viewModel: StorageViewModel,
  onAskEvict: (String) -> Unit,
) {
  val usage = state.usage

  item {
    FluidSectionHeader(
      title = stringResource(R.string.storage_space_header),
      detail = stringResource(R.string.storage_space_detail),
    )
  }

  item {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.storage_audio),
        subtitle = pluralStringResource(R.plurals.export_recordings, usage?.audio?.count ?: 0, usage?.audio?.count ?: 0),
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

  // Libera spazio: quello che sta qui *e* sul computer, e solo quello.
  val evictableSources = usage?.evictableSources ?: SizeTotal(0, 0)
  val evictableAudio = usage?.evictableAudio ?: SizeTotal(0, 0)
  val hereCount = usage?.locations?.let { it.recordings.here.count + it.originals.here.count } ?: 0
  if (evictableSources.count > 0 || evictableAudio.count > 0) {
    item {
      FluidSectionHeader(
        title = stringResource(R.string.storage_free_header),
        detail = stringResource(R.string.storage_free_detail),
      )
    }
    if (evictableAudio.count > 0) {
      item {
        FluidButton(
          text = pluralStringResource(R.plurals.storage_free_audio_button, evictableAudio.count, evictableAudio.count, Formats.bytes(evictableAudio.bytes)),
          onClick = { onAskEvict("audio") },
          enabled = !state.working,
          style = FluidButtonStyle.Tinted,
          fillWidth = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
    if (evictableSources.count > 0) {
      item {
        FluidButton(
          text = pluralStringResource(R.plurals.storage_free_sources_button, evictableSources.count, evictableSources.count, Formats.bytes(evictableSources.bytes)),
          onClick = { onAskEvict("sources") },
          enabled = !state.working,
          style = FluidButtonStyle.Plain,
          fillWidth = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.storage_free_footnote)) }
  } else if (state.hasEndpoint && hereCount > 0) {
    item {
      FluidSectionHeader(title = stringResource(R.string.storage_free_header))
    }
    item { FluidSectionFootnote(text = stringResource(R.string.storage_free_nothing)) }
  }

  item {
    FluidSectionHeader(
      title = stringResource(R.string.storage_cleanup_header),
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
}

// --- parole ---

@Composable
private fun countAndSize(count: String, bytes: Long): String = stringResource(R.string.storage_count_size, count, Formats.bytes(bytes))

@Composable
private fun lastSent(at: Long): String =
  if (at > 0) stringResource(R.string.storage_last_sent, moment(at)) else stringResource(R.string.storage_never_sent)

/**
 * «oggi alle 10:18», «domani alle 04:20», «il 12/10/26 alle 09:00»: un momento detto come lo si
 * direbbe, nel passato o nel futuro. `Formats.relativeDate` guarda solo indietro, e qui serve
 * anche il prossimo giro.
 */
@Composable
private fun moment(at: Long): String {
  val date = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
  val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
  return when (ChronoUnit.DAYS.between(LocalDate.now(), date)) {
    0L -> stringResource(R.string.storage_at_today, time)
    1L -> stringResource(R.string.storage_at_tomorrow, time)
    -1L -> stringResource(R.string.storage_at_yesterday, time)
    else -> stringResource(R.string.storage_at_day, DateFormat.getDateInstance(DateFormat.SHORT).format(Date(at)), time)
  }
}
