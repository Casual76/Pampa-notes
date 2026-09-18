package dev.pampa.pampanotes.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.BuildConfig
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.RefinementPreset
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.ui.common.jobErrorText
import dev.pampa.pampanotes.ui.nav.SettingsSection

/**
 * Le sezioni che hanno qualcosa dentro, nell'ordine in cui si leggono.
 *
 * Le altre tre dell'enum — export, backup, archiviazione — arrivano con M8: elencare una pagina
 * vuota e' peggio di non elencarla.
 */
private val settingsSections = listOf(
  SettingsSection.SERVICES,
  SettingsSection.TRANSCRIPTION,
  SettingsSection.REFINEMENT,
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
    SettingsSection.EXPORT, SettingsSection.BACKUP, SettingsSection.STORAGE -> R.string.settings_section_soon_detail
  },
)

/** Una sezione delle impostazioni: la sua pagina, con dentro solo le sue voci. */
@Composable
fun SettingsSectionRoute(
  section: SettingsSection,
  onBack: () -> Unit,
  viewModel: SettingsViewModel = hiltViewModel(),
) {
  val engine by viewModel.engineSettings.collectAsStateWithLifecycle()
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val services by viewModel.services.collectAsStateWithLifecycle()

  var groqKey by remember { mutableStateOf("") }
  var endpointUrl by remember(settings.endpointUrl) { mutableStateOf(settings.endpointUrl) }
  var endpointToken by remember { mutableStateOf("") }

  if (section == SettingsSection.REFINEMENT) {
    LaunchedEffect(Unit) { viewModel.loadRefinementModels() }
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
        endpointToken = endpointToken,
        onEndpointTokenChange = { endpointToken = it },
        onTestEndpoint = {
          viewModel.setEndpoint(endpointUrl, settings.endpointModel)
          if (endpointToken.isNotBlank()) viewModel.setEndpointToken(endpointToken)
          viewModel.testEndpoint(endpointUrl)
        },
      )

      SettingsSection.TRANSCRIPTION -> transcriptionSection(settings = settings, viewModel = viewModel)
      SettingsSection.REFINEMENT -> refinementSection(settings = settings, services = services, viewModel = viewModel)
      SettingsSection.APPEARANCE -> appearanceSection(engine = engine, viewModel = viewModel)
      SettingsSection.ABOUT -> aboutSection()
      SettingsSection.EXPORT, SettingsSection.BACKUP, SettingsSection.STORAGE -> item {
        FluidEmptyState(
          title = stringResource(R.string.settings_section_soon_title),
          detail = stringResource(R.string.settings_section_soon_detail),
        )
      }
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
      enabled = endpointUrl.isNotBlank() && services.endpointCheck !is CheckState.Running,
      loading = services.endpointCheck is CheckState.Running,
      style = FluidButtonStyle.Tinted,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
  (services.endpointCheck as? CheckState.Ok)?.let { ok ->
    item {
      FluidInlineMessage(
        message = stringResource(R.string.settings_endpoint_ok, ok.latencyMs) + " · " +
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

private fun LazyListScope.transcriptionSection(settings: PampaSettings, viewModel: SettingsViewModel) {
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

  item {
    FluidListGroup {
      FluidListRow(
        title = stringResource(R.string.settings_auto_transcribe),
        subtitle = stringResource(R.string.settings_auto_transcribe_detail),
        badge = {
          FluidSwitch(checked = settings.autoTranscribeOnImport, onCheckedChange = viewModel::setAutoTranscribe)
        },
      )
      FluidListDivider()
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
