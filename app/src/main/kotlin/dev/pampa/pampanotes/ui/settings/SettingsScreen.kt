package dev.pampa.pampanotes.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.foundation.EngineBuild
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
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.ui.common.jobErrorText

/**
 * Le impostazioni: servizi, trascrizione, aspetto.
 *
 * Una pagina sola e non una gerarchia: sono tre gruppi, e una pagina che si scorre si legge meglio
 * di tre che si aprono.
 */
@Composable
fun SettingsRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
  val engine by viewModel.engineSettings.collectAsStateWithLifecycle()
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val services by viewModel.services.collectAsStateWithLifecycle()

  var groqKey by remember { mutableStateOf("") }
  var endpointUrl by remember(settings.endpointUrl) { mutableStateOf(settings.endpointUrl) }
  var endpointToken by remember { mutableStateOf("") }

  val themeLabels = mapOf(
    ThemeMode.SYSTEM to stringResource(R.string.theme_system),
    ThemeMode.LIGHT to stringResource(R.string.theme_light),
    ThemeMode.DARK to stringResource(R.string.theme_dark),
    ThemeMode.AMOLED to stringResource(R.string.theme_amoled),
  )
  val providerLabels = mapOf(
    TranscriptionProviderId.GROQ to stringResource(R.string.provider_groq),
    TranscriptionProviderId.CUSTOM to stringResource(R.string.provider_custom),
  )
  val autoLabel = stringResource(R.string.language_auto)
  val languages = listOf("auto", "it", "en", "fr", "de", "es", "la")

  FluidScreen(
    title = stringResource(R.string.settings_title),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Bars),
  ) {
    // --- Servizi ---
    item { FluidSectionHeader(title = stringResource(R.string.settings_section_services)) }

    item {
      FluidListGroup(glass = true) {
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
        onValueChange = { groqKey = it },
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
        onClick = {
          viewModel.saveGroqKey(groqKey)
          groqKey = ""
        },
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
        onValueChange = { endpointUrl = it },
        label = stringResource(R.string.settings_endpoint_url),
        placeholder = "192.168.1.10:8765",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
      )
    }
    item {
      FluidTextField(
        value = endpointToken,
        onValueChange = { endpointToken = it },
        label = stringResource(R.string.settings_endpoint_token),
        placeholder = stringResource(R.string.settings_endpoint_token_hint),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
      )
    }
    item {
      FluidButton(
        text = stringResource(R.string.settings_test_connection),
        onClick = {
          viewModel.setEndpoint(endpointUrl, settings.endpointModel)
          if (endpointToken.isNotBlank()) viewModel.setEndpointToken(endpointToken)
          viewModel.testEndpoint(endpointUrl)
        },
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
          message = stringResource(R.string.settings_endpoint_ok, ok.detail),
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

    // --- Trascrizione ---
    item { FluidSectionHeader(title = stringResource(R.string.settings_section_transcription)) }

    item {
      FluidSegmentedControl(
        options = TranscriptionProviderId.entries.toList(),
        selected = settings.preferredProvider,
        onSelect = viewModel::setPreferredProvider,
        label = { providerLabels.getValue(it) },
      )
    }

    item {
      FluidListGroup(glass = true) {
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
      FluidSegmentedControl(
        options = languages,
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

    // --- Aspetto ---
    item { FluidSectionHeader(title = stringResource(R.string.settings_section_appearance)) }
    item {
      FluidSegmentedControl(
        options = ThemeMode.entries.toList(),
        selected = engine.themeMode,
        onSelect = viewModel::setThemeMode,
        label = { themeLabels.getValue(it) },
      )
    }
    item {
      FluidListGroup(glass = true) {
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

    // --- Informazioni ---
    item { FluidSectionHeader(title = stringResource(R.string.settings_section_about)) }
    item {
      FluidListGroup(glass = true) {
        FluidListRow(
          title = stringResource(R.string.settings_version),
          subtitle = BuildConfig.VERSION_NAME,
          meta = EngineBuild.VERSION,
        )
      }
    }
  }
}
