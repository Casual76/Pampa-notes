package dev.pampa.pampanotes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.keys.AiKeyVerifier
import dev.antigravity.fluidengine.ai.keys.AiSettingsStore
import dev.antigravity.fluidengine.ai.keys.VerifyResult
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.core.transcription.EndpointHealth
import dev.pampa.pampanotes.core.transcription.GroqWhisperProvider
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import dev.pampa.pampanotes.core.transcription.TranscriptionHttp
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Com'e' andata l'ultima verifica di una chiave o di un indirizzo. */
sealed interface CheckState {
  data object Idle : CheckState
  data object Running : CheckState

  /**
   * Andata bene.
   *
   * @param detail il modello scelto, per Groq.
   * @param latencyMs e [modelCount] per un endpoint: le parole attorno ai numeri le mette la UI,
   *   perche' scriverle qui vorrebbe dire scriverle in una lingua sola.
   */
  data class Ok(val detail: String = "", val latencyMs: Long = 0, val modelCount: Int = 0) : CheckState

  data class Failed(val reason: String) : CheckState
}

data class ServicesUiState(
  val groqKeyPresent: Boolean = false,
  val groqVerified: Boolean = false,
  val groqModels: List<String> = emptyList(),
  val groqCheck: CheckState = CheckState.Idle,
  val endpointCheck: CheckState = CheckState.Idle,
  val endpointModels: List<String> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
  private val engineSettingsStore: EngineSettingsStore,
  private val settingsStore: PampaSettingsStore,
  private val keys: AiKeyStore,
  private val verifier: AiKeyVerifier,
  private val aiSettings: AiSettingsStore,
  private val http: TranscriptionHttp,
) : ViewModel() {

  val engineSettings: StateFlow<EngineSettings> = engineSettingsStore.settings
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EngineSettings())

  val settings: StateFlow<PampaSettings> = settingsStore.settings
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PampaSettings())

  private val _services = MutableStateFlow(ServicesUiState())
  val services: StateFlow<ServicesUiState> = _services.asStateFlow()

  init {
    viewModelScope.launch {
      keys.states.collect { states ->
        val groq = states[ProviderId.GROQ]
        _services.update { it.copy(groqKeyPresent = groq?.present == true, groqVerified = groq?.verified == true) }
      }
    }
  }

  // --- Aspetto ---
  fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { engineSettingsStore.setThemeMode(mode) }
  fun setDynamicColor(enabled: Boolean) = viewModelScope.launch {
    engineSettingsStore.setDynamicColorEnabled(enabled)
    // L'interruttore da solo non basta: con accentMode BRAND il marchio vince comunque, e
    // l'interruttore sembrerebbe rotto.
    engineSettingsStore.setAccentMode(
      if (enabled) dev.antigravity.fluidengine.foundation.AccentMode.DYNAMIC
      else dev.antigravity.fluidengine.foundation.AccentMode.BRAND,
    )
  }
  fun setAmoled(enabled: Boolean) = viewModelScope.launch { engineSettingsStore.setAmoledEnabled(enabled) }
  fun setHaptics(enabled: Boolean) = viewModelScope.launch { engineSettingsStore.setHapticsEnabled(enabled) }

  // --- Groq ---
  /**
   * Salva la chiave e la prova subito.
   *
   * Provarla adesso e non alla prima trascrizione: scoprire che la chiave e' sbagliata dopo aver
   * aspettato la decodifica di un'ora di audio e' il momento peggiore in cui scoprirlo.
   */
  fun saveGroqKey(key: String) {
    viewModelScope.launch {
      _services.update { it.copy(groqCheck = CheckState.Running) }
      keys.set(ProviderId.GROQ, key)
      when (val result = verifier.verify(ProviderId.GROQ)) {
        is VerifyResult.Ok -> {
          val whisper = result.catalogue.stt.map { it.id }.filter { it.contains("whisper", true) }
          // Il default dell'engine e' whisper-large-v3; qui vogliamo il turbo, che costa meno
          // secondi di quota a parita' di risultato su una lezione.
          val picked = GroqWhisperProvider.pickModel(whisper)
          picked?.let { aiSettings.setSttModel(ProviderId.GROQ, it) }
          _services.update {
            // Il modello *scelto*, non il primo dell'elenco: e' quello che verra' usato davvero, e
            // mostrarne un altro fa credere che la preferenza non sia stata applicata.
            it.copy(groqModels = whisper, groqCheck = CheckState.Ok(picked.orEmpty()))
          }
        }

        VerifyResult.Invalid -> _services.update { it.copy(groqCheck = CheckState.Failed("unauthorized")) }
        is VerifyResult.Failed -> _services.update {
          it.copy(groqCheck = CheckState.Failed(result.error?.let { e -> TranscriptionError.from(e).code } ?: "network"))
        }
      }
    }
  }

  fun clearGroqKey() {
    viewModelScope.launch {
      keys.set(ProviderId.GROQ, null)
      _services.update { it.copy(groqModels = emptyList(), groqCheck = CheckState.Idle) }
    }
  }

  // --- Server personale ---
  fun setEndpoint(url: String, model: String) = viewModelScope.launch {
    settingsStore.setEndpoint(url, name = "", model = model)
  }

  fun setEndpointToken(token: String?) = viewModelScope.launch { settingsStore.setEndpointToken(token) }

  fun testEndpoint(url: String) {
    viewModelScope.launch {
      if (url.isBlank()) {
        _services.update { it.copy(endpointCheck = CheckState.Failed("empty")) }
        return@launch
      }
      _services.update { it.copy(endpointCheck = CheckState.Running) }
      val provider = OpenAiCompatProvider(
        http = http,
        baseUrl = url,
        token = settingsStore.endpointToken(),
        readTimeoutMillis = 15_000,
      )
      val health: EndpointHealth = provider.health()
      _services.update {
        if (health.reachable) {
          it.copy(
            endpointModels = health.models,
            endpointCheck = CheckState.Ok(latencyMs = health.latencyMs, modelCount = health.models.size),
          )
        } else {
          it.copy(endpointCheck = CheckState.Failed(health.detail ?: "network"))
        }
      }
    }
  }

  // --- Trascrizione ---
  fun setPreferredProvider(provider: TranscriptionProviderId) = viewModelScope.launch {
    settingsStore.setPreferredProvider(provider)
  }

  fun setLanguage(language: String) = viewModelScope.launch { settingsStore.setLanguage(language) }
  fun setVocabulary(text: String) = viewModelScope.launch { settingsStore.setVocabulary(text) }
  fun setChunkMinutes(minutes: Int) = viewModelScope.launch { settingsStore.setChunkMinutes(minutes) }
  fun setGroqMaxUploadMb(mb: Int) = viewModelScope.launch { settingsStore.setGroqMaxUploadMb(mb) }
  fun setAutoTranscribe(enabled: Boolean) = viewModelScope.launch { settingsStore.setAutoTranscribeOnImport(enabled) }
}
