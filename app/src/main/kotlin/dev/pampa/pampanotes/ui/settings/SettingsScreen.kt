package dev.pampa.pampanotes.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.pampa.pampanotes.core.audio.ChunkPolicy
import dev.pampa.pampanotes.core.transcription.CompanionSaveResult
import dev.pampa.pampanotes.core.transcription.CompanionStatus
import dev.pampa.pampanotes.core.transcription.VramMode
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.foundation.EngineBuild
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.BuildConfig
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.export.ExportOptions
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.RefinementPreset
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.ui.common.jobErrorText
import dev.pampa.pampanotes.ui.export.ExportChoices
import dev.pampa.pampanotes.ui.nav.SettingsSection

/**
 * Le sezioni, nell'ordine in cui si leggono.
 *
 * Prima quello che riguarda il testo che entra — servizi, trascrizione, raffinamento — poi quello
 * che esce, poi l'app. Non e' l'ordine dell'enum: quello e' l'ordine in cui sono state scritte.
 */
private val settingsSections = listOf(
  SettingsSection.SERVICES,
  SettingsSection.TRANSCRIPTION,
  SettingsSection.REFINEMENT,
  SettingsSection.EXPORT,
  SettingsSection.BACKUP,
  SettingsSection.STORAGE,
  SettingsSection.SYNC,
  SettingsSection.SHARES,
  SettingsSection.GUESTS,
  SettingsSection.APPEARANCE,
  SettingsSection.ABOUT,
)

/**
 * L'indice delle impostazioni: una riga per sezione, e la sezione e' una pagina sua.
 *
 * Era una pagina sola con trentasei voci di fila, e si leggeva come un muro. Cinque righe si
 * riconoscono; sul tablet l'indice sta a sinistra e la sezione a destra.
 */
@Composable
fun SettingsRoute(
  onBack: () -> Unit,
  onOpenSection: (SettingsSection) -> Unit,
) {
  FluidScreen(
    title = stringResource(R.string.settings_title),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Bars),
  ) {
    item {
      FluidListGroup {
        settingsSections.forEachIndexed { index, section ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = section.label(),
            subtitle = section.detail(),
            meta = if (section == SettingsSection.ABOUT) BuildConfig.VERSION_NAME else null,
            onClick = { onOpenSection(section) },
          )
        }
      }
    }
  }
}

@Composable
fun SettingsSection.label(): String = stringResource(
  when (this) {
    SettingsSection.SERVICES -> R.string.settings_section_services
    SettingsSection.TRANSCRIPTION -> R.string.settings_section_transcription
    SettingsSection.REFINEMENT -> R.string.settings_section_refinement
    SettingsSection.EXPORT -> R.string.settings_section_export
    SettingsSection.BACKUP -> R.string.settings_section_backup
    SettingsSection.APPEARANCE -> R.string.settings_section_appearance
    SettingsSection.STORAGE -> R.string.settings_section_storage
    SettingsSection.SYNC -> R.string.settings_section_sync
    SettingsSection.SHARES -> R.string.settings_section_shares
    SettingsSection.GUESTS -> R.string.settings_section_guests
    SettingsSection.ABOUT -> R.string.settings_section_about
  },
)

@Composable
private fun SettingsSection.detail(): String = stringResource(
  when (this) {
    SettingsSection.SERVICES -> R.string.settings_section_services_detail
    SettingsSection.TRANSCRIPTION -> R.string.settings_section_transcription_detail
    SettingsSection.REFINEMENT -> R.string.settings_section_refinement_detail
    SettingsSection.APPEARANCE -> R.string.settings_section_appearance_detail
    SettingsSection.ABOUT -> R.string.settings_section_about_detail
    SettingsSection.EXPORT -> R.string.settings_section_export_detail
    SettingsSection.BACKUP -> R.string.settings_section_backup_detail
    SettingsSection.STORAGE -> R.string.settings_section_storage_detail
    SettingsSection.SYNC -> R.string.settings_section_sync_detail
    SettingsSection.SHARES -> R.string.settings_section_shares_detail
    SettingsSection.GUESTS -> R.string.settings_section_guests_detail
  },
)

/**
 * Una sezione delle impostazioni: la sua pagina, con dentro solo le sue voci.
 *
 * Backup e archiviazione hanno un ViewModel loro e stanno in un file loro: scrivono su disco e
 * cancellano file, cioe' fanno un mestiere diverso dal salvare una preferenza, e tenerli qui
 * avrebbe fatto di [SettingsViewModel] il posto da cui passa tutto.
 */
@Composable
fun SettingsSectionRoute(
  section: SettingsSection,
  onBack: () -> Unit,
  /** Da una sezione a un'altra: Archiviazione manda a Servizi chi non ha ancora collegato il computer. */
  onOpenSection: (SettingsSection) -> Unit = {},
) {
  when (section) {
    SettingsSection.BACKUP -> BackupSectionRoute(onBack = onBack)
    SettingsSection.STORAGE -> StorageSectionRoute(onBack = onBack, onOpenServices = { onOpenSection(SettingsSection.SERVICES) })
    SettingsSection.SYNC -> SyncSectionRoute(onBack = onBack)
    SettingsSection.SHARES -> SharesSectionRoute(onBack = onBack)
    SettingsSection.GUESTS -> GuestsSectionRoute(onBack = onBack)
    else -> PreferencesSectionRoute(section = section, onBack = onBack)
  }
}

@Composable
private fun PreferencesSectionRoute(
  section: SettingsSection,
  onBack: () -> Unit,
  viewModel: SettingsViewModel = hiltViewModel(),
) {
  val engine by viewModel.engineSettings.collectAsStateWithLifecycle()
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val services by viewModel.services.collectAsStateWithLifecycle()
  val exportDefaults by viewModel.exportDefaults.collectAsStateWithLifecycle()

  var groqKey by remember { mutableStateOf("") }
  var endpointUrl by remember(settings.endpointUrl) { mutableStateOf(settings.endpointUrl) }
  var endpointRemoteUrl by remember(settings.endpointRemoteUrl) { mutableStateOf(settings.endpointRemoteUrl) }
  var endpointToken by remember { mutableStateOf("") }

  val companion by viewModel.companionState.collectAsStateWithLifecycle()

  if (section == SettingsSection.REFINEMENT) {
    LaunchedEffect(Unit) { viewModel.loadRefinementModels() }
  }
  // La memoria video si chiede al computer entrando in Trascrizione, e solo se un computer c'e':
  // le impostazioni arrivano da DataStore un attimo dopo la pagina, da qui la chiave.
  if (section == SettingsSection.TRANSCRIPTION) {
    LaunchedEffect(settings.hasEndpoint) { if (settings.hasEndpoint) viewModel.loadCompanion() }
  }

  FluidScreen(
    title = section.label(),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Bars),
  ) {
    when (section) {
      SettingsSection.SERVICES -> servicesSection(
        services = services,
        groqKey = groqKey,
        onGroqKeyChange = { groqKey = it },
        onVerifyGroq = {
          viewModel.saveGroqKey(groqKey)
          groqKey = ""
        },
        endpointUrl = endpointUrl,
        onEndpointUrlChange = { endpointUrl = it },
        endpointRemoteUrl = endpointRemoteUrl,
        onEndpointRemoteUrlChange = { endpointRemoteUrl = it },
        endpointToken = endpointToken,
        onEndpointTokenChange = { endpointToken = it },
        onTestEndpoint = {
          viewModel.setEndpoint(endpointUrl, settings.endpointModel)
          viewModel.setEndpointRemoteUrl(endpointRemoteUrl)
          if (endpointToken.isNotBlank()) viewModel.setEndpointToken(endpointToken)
          viewModel.testEndpoint(endpointUrl, endpointRemoteUrl)
        },
      )

      SettingsSection.TRANSCRIPTION -> transcriptionSection(settings = settings, companion = companion, viewModel = viewModel)
      SettingsSection.REFINEMENT -> refinementSection(settings = settings, services = services, viewModel = viewModel)
      SettingsSection.APPEARANCE -> appearanceSection(engine = engine, viewModel = viewModel)
      SettingsSection.ABOUT -> aboutSection()
      SettingsSection.EXPORT -> exportSection(defaults = exportDefaults, viewModel = viewModel)
      // Hanno una pagina loro, e qui non ci si arriva mai.
      SettingsSection.BACKUP, SettingsSection.STORAGE, SettingsSection.SYNC, SettingsSection.SHARES, SettingsSection.GUESTS -> Unit
    }
  }
}

// -------------------------------------------------------------------------------------------------
// Servizi
// -------------------------------------------------------------------------------------------------

private fun LazyListScope.servicesSection(
  services: ServicesUiState,
  groqKey: String,
  onGroqKeyChange: (String) -> Unit,
  onVerifyGroq: () -> Unit,
  endpointUrl: String,
  onEndpointUrlChange: (String) -> Unit,
  endpointRemoteUrl: String,
  onEndpointRemoteUrlChange: (String) -> Unit,
  endpointToken: String,
  onEndpointTokenChange: (String) -> Unit,
  onTestEndpoint: () -> Unit,
) {
  item {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.settings_groq),
        subtitle = when {
          services.groqVerified -> stringResource(R.string.settings_groq_verified)
          services.groqKeyPresent -> stringResource(R.string.settings_groq_unverified)
          else -> stringResource(R.string.settings_groq_missing)
        },
        badge = if (services.groqVerified) {
          { FluidStatusBadge(label = stringResource(R.string.settings_ok), tone = FluidTone.Success) }
        } else {
          null
        },
      )
    }
  }

  item {
    FluidTextField(
      value = groqKey,
      onValueChange = onGroqKeyChange,
      label = stringResource(R.string.settings_groq_key),
      placeholder = "gsk_…",
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
      supportingText = stringResource(R.string.settings_groq_key_hint),
      modifier = Modifier.fillMaxWidth(),
    )
  }

  item {
    FluidButton(
      text = stringResource(R.string.settings_verify),
      onClick = onVerifyGroq,
      enabled = groqKey.isNotBlank() && services.groqCheck !is CheckState.Running,
      loading = services.groqCheck is CheckState.Running,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }

  (services.groqCheck as? CheckState.Failed)?.let { failed ->
    item {
      FluidInlineMessage(
        message = jobErrorText(failed.reason, null),
        title = stringResource(R.string.settings_groq),
        tone = FluidTone.Danger,
      )
    }
  }
  (services.groqCheck as? CheckState.Ok)?.let { ok ->
    item {
      FluidInlineMessage(
        message = stringResource(R.string.settings_groq_ok, ok.detail),
        title = stringResource(R.string.settings_groq),
        tone = FluidTone.Success,
      )
    }
  }

  item {
    FluidSectionHeader(
      title = stringResource(R.string.settings_endpoint),
      detail = stringResource(R.string.settings_endpoint_detail),
    )
  }

  item {
    FluidTextField(
      value = endpointUrl,
      onValueChange = onEndpointUrlChange,
      label = stringResource(R.string.settings_endpoint_url),
      placeholder = "192.168.1.10:8765",
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
      modifier = Modifier.fillMaxWidth(),
    )
  }
  item {
    FluidTextField(
      value = endpointRemoteUrl,
      onValueChange = onEndpointRemoteUrlChange,
      label = stringResource(R.string.settings_endpoint_remote_url),
      placeholder = "100.x.y.z:8765",
      supportingText = stringResource(R.string.settings_endpoint_remote_hint),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
      modifier = Modifier.fillMaxWidth(),
    )
  }
  item {
    FluidTextField(
      value = endpointToken,
      onValueChange = onEndpointTokenChange,
      label = stringResource(R.string.settings_endpoint_token),
      placeholder = stringResource(R.string.settings_endpoint_token_hint),
      visualTransformation = PasswordVisualTransformation(),
      modifier = Modifier.fillMaxWidth(),
    )
  }
  item {
    FluidButton(
      text = stringResource(R.string.settings_test_connection),
      onClick = onTestEndpoint,
      enabled = (endpointUrl.isNotBlank() || endpointRemoteUrl.isNotBlank()) && services.endpointCheck !is CheckState.Running,
      loading = services.endpointCheck is CheckState.Running,
      style = FluidButtonStyle.Tinted,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
  (services.endpointCheck as? CheckState.Ok)?.let { ok ->
    item {
      FluidInlineMessage(
        message = stringResource(if (ok.detail == SettingsViewModel.ENDPOINT_VIA_REMOTE) R.string.settings_endpoint_ok_remote else R.string.settings_endpoint_ok_lan, ok.latencyMs) + " · " +
          pluralStringResource(R.plurals.settings_endpoint_models, ok.modelCount, ok.modelCount),
        title = stringResource(R.string.settings_endpoint),
        tone = FluidTone.Success,
      )
    }
  }
  (services.endpointCheck as? CheckState.Failed)?.let { failed ->
    item {
      FluidInlineMessage(
        message = jobErrorText(failed.reason, null),
        title = stringResource(R.string.settings_endpoint),
        tone = FluidTone.Danger,
      )
    }
  }
}

// -------------------------------------------------------------------------------------------------
// Trascrizione
// -------------------------------------------------------------------------------------------------

private fun LazyListScope.transcriptionSection(settings: PampaSettings, companion: CompanionUiState, viewModel: SettingsViewModel) {
  // Con «solo il computer di casa» la scelta non esiste piu': il selettore sparisce invece di
  // restare li' a proporre Groq come se contasse.
  if (!settings.customOnly) {
    item {
      val providerLabels = mapOf(
        TranscriptionProviderId.GROQ to stringResource(R.string.provider_groq),
        TranscriptionProviderId.CUSTOM to stringResource(R.string.provider_custom),
      )
      FluidSegmentedControl(
        options = TranscriptionProviderId.entries.toList(),
        selected = settings.preferredProvider,
        onSelect = viewModel::setPreferredProvider,
        label = { providerLabels.getValue(it) },
      )
    }
  }

  item {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.settings_custom_only),
        subtitle = stringResource(if (settings.hasEndpoint || settings.customOnly) R.string.settings_custom_only_detail else R.string.storage_archive_no_endpoint),
        badge = {
          FluidSwitch(checked = settings.customOnly, onCheckedChange = viewModel::setCustomOnly, enabled = settings.hasEndpoint || settings.customOnly)
        },
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.settings_auto_transcribe),
        subtitle = stringResource(R.string.settings_auto_transcribe_detail),
        badge = {
          FluidSwitch(checked = settings.autoTranscribeOnImport, onCheckedChange = viewModel::setAutoTranscribe)
        },
      )
    }
  }

  // I pezzi, un servizio per volta: Groq li vuole per forza (il tetto per richiesta), il computer di
  // casa solo se glieli si chiede. Con «solo il computer di casa» Groq non si usa mai, e le sue
  // scelte spariscono invece di restare li' a sembrare importanti.
  if (!settings.customOnly) {
    item { FluidSectionHeader(title = stringResource(R.string.provider_groq)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_chunk_minutes),
          subtitle = stringResource(R.string.settings_chunk_minutes_detail),
          meta = "${settings.chunkMinutes} min",
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_groq_limit),
          subtitle = stringResource(R.string.settings_groq_limit_detail),
          meta = "${settings.groqMaxUploadMb} MB",
        )
      }
    }

    item {
      FluidSegmentedControl(
        options = listOf(5, 10, 15),
        selected = settings.chunkMinutes,
        onSelect = viewModel::setChunkMinutes,
        label = { "$it min" },
      )
    }

    item {
      FluidSegmentedControl(
        options = listOf(25, 100),
        selected = settings.groqMaxUploadMb,
        onSelect = viewModel::setGroqMaxUploadMb,
        label = { "$it MB" },
      )
    }
    item { FluidSectionFootnote(text = stringResource(R.string.settings_groq_chunk_rule)) }
  }

  item { FluidSectionHeader(title = stringResource(R.string.provider_custom), detail = stringResource(R.string.settings_custom_chunk_detail)) }
  // Una riga per scelta, con quello che vuol dire: un segmentato con «Intero · 30 · 60 · 120» non
  // diceva ne' che intero e' il meglio per Whisper, ne' che un tetto lascia intera una lezione
  // appena piu' lunga.
  item {
    val chosen: @Composable () -> Unit = {
      FluidStatusBadge(label = stringResource(R.string.settings_model_chosen), tone = FluidTone.Success)
    }
    FluidListGroup {
      listOf<Int?>(null, 30, 60, 120).forEachIndexed { index, minutes ->
        if (index > 0) FluidListDivider()
        FluidListRow(
          title = minutes?.let { stringResource(R.string.settings_custom_chunk_title, it) } ?: stringResource(R.string.settings_custom_chunk_whole),
          subtitle = minutes?.let {
            stringResource(R.string.settings_custom_chunk_capped, it + (ChunkPolicy.COMPUTER_TOLERANCE_MS / 60_000L).toInt(), it)
          } ?: stringResource(R.string.settings_custom_chunk_whole_detail),
          onClick = { viewModel.setCustomMaxMinutes(minutes) },
          badge = if (settings.customMaxMinutes == minutes) chosen else null,
        )
      }
    }
  }

  if (settings.hasEndpoint) vramSection(companion, viewModel)

  item {
    val autoLabel = stringResource(R.string.language_auto)
    FluidSegmentedControl(
      options = listOf("auto", "it", "en", "fr", "de", "es", "la"),
      selected = settings.language,
      onSelect = viewModel::setLanguage,
      label = { if (it == "auto") autoLabel else it.uppercase() },
    )
  }

  item {
    FluidTextField(
      value = settings.vocabulary,
      onValueChange = viewModel::setVocabulary,
      label = stringResource(R.string.settings_vocabulary),
      placeholder = stringResource(R.string.settings_vocabulary_hint),
      singleLine = false,
      minLines = 3,
      showClearButton = false,
      modifier = Modifier.fillMaxWidth(),
    )
  }
  item { FluidSectionFootnote(text = stringResource(R.string.settings_vocabulary_detail)) }
}

/** I modelli che la pagina propone, dal piu' preciso al piu' leggero, con la riga che li distingue. */
private val vramModels = listOf(
  "large-v3" to R.string.settings_vram_model_large,
  "medium" to R.string.settings_vram_model_medium,
  "small" to R.string.settings_vram_model_small,
)

/** I tetti che si possono indicare a mano, fino alla scheda che c'e'. */
private val vramChoicesGb = listOf(2.0, 3.0, 4.0, 6.0, 8.0, 10.0, 12.0, 16.0, 20.0, 24.0, 32.0, 48.0)

/**
 * La memoria video del computer di casa: che scheda c'e', quanto si pensa di usare, e le due scelte
 * che la cambiano — il tetto e il modello.
 *
 * Tutto quello che arriva dal companion e' facoltativo: se non risponde, o e' di prima, una riga lo
 * dice e la sezione finisce li'. Le scelte si provano con la stima del companion prima di salvarle,
 * perche' «large-v3 non ci sta» e' una cosa da sapere prima, non alla prossima lezione.
 */
private fun LazyListScope.vramSection(companion: CompanionUiState, viewModel: SettingsViewModel) {
  item { FluidSectionHeader(title = stringResource(R.string.settings_vram_header), detail = stringResource(R.string.settings_vram_length)) }

  val status = companion.status
  if (companion.loading || status == null) {
    item { FluidSectionFootnote(text = stringResource(R.string.settings_vram_loading)) }
    return
  }
  when (status) {
    CompanionStatus.Unconfigured -> return
    CompanionStatus.Unreachable -> {
      item { FluidSectionFootnote(text = stringResource(R.string.settings_vram_unreachable)) }
      return
    }
    CompanionStatus.Unsupported -> {
      item { FluidSectionFootnote(text = stringResource(R.string.settings_vram_unsupported)) }
      return
    }
    is CompanionStatus.Ready -> Unit
  }
  val ready = status as CompanionStatus.Ready
  val estimate = companion.shownEstimate
  val draft = companion.draft

  item {
    FluidListGroup {
      val gpu = ready.gpu
      FluidListRow(
        title = if (gpu != null) {
          stringResource(R.string.settings_vram_card, listOfNotNull(gpuShortName(gpu.name), gpu.totalGb?.let { gigabytes(it) }).joinToString(" · "))
        } else {
          stringResource(R.string.settings_vram_no_gpu)
        },
        subtitle = when {
          gpu == null -> stringResource(R.string.settings_vram_cpu)
          gpu.freeGb != null -> stringResource(R.string.settings_vram_free, gigabytes(gpu.freeGb!!))
          else -> gpu.name
        },
      )
      if (estimate != null) {
        FluidListDivider()
        val model = draft?.model ?: estimate.model
        val detail = buildList {
          if (model != null && estimate.batchSize != null) add(stringResource(R.string.settings_vram_estimate_detail, model, estimate.batchSize!!))
          else if (model != null) add(model)
          estimate.budgetGb?.let { add(stringResource(R.string.settings_vram_estimate_budget, gigabytes(it))) }
        }.joinToString(" · ")
        FluidListRow(
          title = stringResource(R.string.settings_vram_estimate, gigabytes(estimate.estimateGb)),
          subtitle = detail.ifBlank { stringResource(R.string.settings_vram_header) },
          tone = if (estimate.exceedsBudget) FluidTone.Warning else FluidTone.Neutral,
        )
      }
    }
  }

  if (estimate?.exceedsBudget == true) {
    item {
      FluidInlineMessage(
        message = stringResource(R.string.settings_vram_over),
        title = stringResource(R.string.settings_vram_header),
        tone = FluidTone.Warning,
      )
    }
  }

  if (!ready.canEdit || draft == null) {
    item { FluidSectionFootnote(text = stringResource(R.string.settings_vram_read_only)) }
    return
  }

  item {
    val labels = mapOf(
      VramMode.AUTO to stringResource(R.string.settings_vram_mode_auto),
      VramMode.MANUAL to stringResource(R.string.settings_vram_mode_manual),
    )
    FluidSegmentedControl(
      options = VramMode.entries.toList(),
      selected = draft.vramMode,
      onSelect = { mode ->
        viewModel.updateCompanionDraft { current ->
          // Passando a mano si parte da un numero sensato: quello di prima, o quanto la scheda ha.
          val gb = current.vramGb ?: ready.vram?.budgetGb?.let { kotlin.math.floor(it) } ?: ready.gpu?.totalGb?.let { kotlin.math.floor(it) }
          current.copy(vramMode = mode, vramGb = if (mode == VramMode.MANUAL) gb else current.vramGb)
        }
      },
      label = { labels.getValue(it) },
    )
  }
  if (draft.vramMode == VramMode.AUTO) {
    item { FluidSectionFootnote(text = stringResource(R.string.settings_vram_mode_auto_detail)) }
  } else {
    item { FluidSectionFootnote(text = stringResource(R.string.settings_vram_mode_manual_detail)) }
    item {
      val total = ready.gpu?.totalGb
      val choices = (vramChoicesGb.filter { total == null || it <= total + 0.5 } + listOfNotNull(draft.vramGb)).distinct().sorted()
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        choices.forEach { gb ->
          FluidChip(
            label = gigabytes(gb),
            selected = draft.vramGb == gb,
            onClick = { viewModel.updateCompanionDraft { it.copy(vramGb = gb) } },
          )
        }
      }
    }
  }

  item { FluidSectionHeader(title = stringResource(R.string.settings_vram_model)) }
  item {
    val chosen: @Composable () -> Unit = {
      FluidStatusBadge(label = stringResource(R.string.settings_model_chosen), tone = FluidTone.Success)
    }
    // Un modello scelto sul computer che non e' fra i tre (un «large-v3-turbo») resta visibile:
    // sparire vorrebbe dire far credere che sia stato cambiato.
    val known = vramModels.map { it.first }
    val models = vramModels + listOfNotNull(companion.saved?.model?.takeIf { it !in known }?.let { it to R.string.settings_vram_model_other })
    FluidListGroup {
      models.forEachIndexed { index, (model, detail) ->
        if (index > 0) FluidListDivider()
        FluidListRow(
          title = model,
          subtitle = stringResource(detail),
          onClick = { viewModel.updateCompanionDraft { it.copy(model = model) } },
          badge = if (draft.model == model) chosen else null,
        )
      }
    }
  }

  item {
    FluidButton(
      text = stringResource(R.string.settings_vram_save),
      onClick = viewModel::saveCompanion,
      enabled = companion.dirty && !companion.saving,
      loading = companion.saving,
      style = FluidButtonStyle.Tinted,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
  when (val result = companion.saveResult) {
    is CompanionSaveResult.Saved -> item {
      FluidInlineMessage(message = stringResource(R.string.settings_vram_saved), title = stringResource(R.string.settings_vram_header), tone = FluidTone.Success)
    }
    CompanionSaveResult.Forbidden -> item {
      FluidInlineMessage(message = stringResource(R.string.settings_vram_forbidden), title = stringResource(R.string.settings_vram_header), tone = FluidTone.Danger)
    }
    is CompanionSaveResult.Failed -> item {
      FluidInlineMessage(
        message = stringResource(R.string.settings_vram_failed, jobErrorText(result.code, null)),
        title = stringResource(R.string.settings_vram_header),
        tone = FluidTone.Danger,
      )
    }
    null -> Unit
  }
}

/** «NVIDIA GeForce RTX 4070 Ti» → «RTX 4070 Ti»: la marca non aiuta a riconoscere la scheda. */
private fun gpuShortName(name: String): String =
  name.replace(Regex("^(NVIDIA\\s+)?(GeForce\\s+)?|^AMD\\s+(Radeon\\s+)?", RegexOption.IGNORE_CASE), "").trim().ifEmpty { name }

/** «12 GB», «5,9 GB»: un decimale solo quando dice qualcosa, nella lingua del telefono. */
@Composable
private fun gigabytes(value: Double): String {
  val rounded = kotlin.math.round(value)
  val number = if (kotlin.math.abs(value - rounded) < 0.05) {
    rounded.toLong().toString()
  } else {
    String.format(LocalConfiguration.current.locales[0], "%.1f", value)
  }
  return stringResource(R.string.settings_gb, number)
}

// -------------------------------------------------------------------------------------------------
// Raffinamento
// -------------------------------------------------------------------------------------------------

private fun LazyListScope.refinementSection(
  settings: PampaSettings,
  services: ServicesUiState,
  viewModel: SettingsViewModel,
) {
  item { FluidSectionFootnote(text = stringResource(R.string.refine_explain)) }
  item {
    val presetLabels = mapOf(
      RefinementPreset.CLEAN to stringResource(R.string.refine_preset_clean),
      RefinementPreset.STRUCTURED to stringResource(R.string.refine_preset_structured),
      RefinementPreset.CUSTOM to stringResource(R.string.refine_preset_custom),
    )
    FluidSegmentedControl(
      options = RefinementPreset.entries.toList(),
      selected = settings.refinementPreset,
      onSelect = viewModel::setRefinementPreset,
      label = { presetLabels.getValue(it) },
    )
  }

  item { FluidSectionHeader(title = stringResource(R.string.settings_refinement_model)) }

  if (services.refinementModels.isEmpty()) {
    // Senza catalogo resta il campo libero: e' meglio di niente, e la riga sotto dice come avere l'elenco.
    item {
      FluidTextField(
        value = settings.refinementModel,
        onValueChange = viewModel::setRefinementModel,
        label = stringResource(R.string.settings_refinement_model),
        placeholder = "openai/gpt-oss-120b",
        modifier = Modifier.fillMaxWidth(),
      )
    }
    item { FluidSectionFootnote(text = stringResource(R.string.settings_models_missing)) }
    return
  }

  item {
    val chosen: @Composable () -> Unit = {
      FluidStatusBadge(label = stringResource(R.string.settings_model_chosen), tone = FluidTone.Success)
    }
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.settings_model_auto),
        subtitle = services.refinementAuto?.let { stringResource(R.string.settings_model_auto_detail, it) }
          ?: stringResource(R.string.settings_refinement_model_detail),
        onClick = { viewModel.setRefinementModel("") },
        badge = if (settings.refinementModel.isBlank()) chosen else null,
      )
      services.refinementModels.forEach { model ->
        FluidListDivider()
        FluidListRow(
          // Il nome del modello e' gia' tutto quello che c'e' da dire: «openai/gpt-oss-120b» non
          // ha un sottotitolo che non sia una ripetizione.
          title = model.substringAfterLast('/'),
          subtitle = model,
          onClick = { viewModel.setRefinementModel(model) },
          badge = if (settings.refinementModel == model) chosen else null,
        )
      }
    }
  }
}

// -------------------------------------------------------------------------------------------------
// Esportazione
// -------------------------------------------------------------------------------------------------

/**
 * Da dove parte il pannello di export.
 *
 * Le stesse scelte del pannello, con le stesse parole: «Dove lo usi?» e «Personalizza». Erano
 * quattro interruttori e un formato senza spiegazione, e chi apriva la pagina non sapeva quale
 * combinazione serviva a cosa. Si riscrivono da sole dopo ogni export riuscito: questa pagina serve
 * a cambiarle senza dover esportare qualcosa per farlo.
 */
private fun LazyListScope.exportSection(defaults: ExportOptions, viewModel: SettingsViewModel) {
  item { FluidSectionFootnote(text = stringResource(R.string.settings_export_explain)) }
  item { ExportChoices(options = defaults, onOptions = viewModel::setExportDefaults) }
}

// -------------------------------------------------------------------------------------------------
// Aspetto
// -------------------------------------------------------------------------------------------------

private fun LazyListScope.appearanceSection(engine: EngineSettings, viewModel: SettingsViewModel) {
  item {
    val themeLabels = mapOf(
      ThemeMode.SYSTEM to stringResource(R.string.theme_system),
      ThemeMode.LIGHT to stringResource(R.string.theme_light),
      ThemeMode.DARK to stringResource(R.string.theme_dark),
      ThemeMode.AMOLED to stringResource(R.string.theme_amoled),
    )
    FluidSegmentedControl(
      options = ThemeMode.entries.toList(),
      selected = engine.themeMode,
      onSelect = viewModel::setThemeMode,
      label = { themeLabels.getValue(it) },
    )
  }
  item {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.settings_dynamic_color),
        subtitle = stringResource(R.string.settings_dynamic_color_detail),
        badge = { FluidSwitch(checked = engine.dynamicColorEnabled, onCheckedChange = viewModel::setDynamicColor) },
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.settings_amoled),
        subtitle = stringResource(R.string.settings_amoled_detail),
        badge = { FluidSwitch(checked = engine.amoledEnabled, onCheckedChange = viewModel::setAmoled) },
      )
      FluidListDivider()
      FluidListRow(
        title = stringResource(R.string.settings_haptics),
        subtitle = stringResource(R.string.settings_haptics_detail),
        badge = { FluidSwitch(checked = engine.hapticsEnabled, onCheckedChange = viewModel::setHaptics) },
      )
    }
  }
  item { FluidSectionFootnote(text = stringResource(R.string.settings_subject_accent_detail)) }
}

// -------------------------------------------------------------------------------------------------
// Informazioni
// -------------------------------------------------------------------------------------------------

private fun LazyListScope.aboutSection() {
  item {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.settings_version),
        subtitle = BuildConfig.VERSION_NAME,
        meta = EngineBuild.VERSION,
      )
    }
  }
}
