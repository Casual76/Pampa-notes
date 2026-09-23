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
import dev.pampa.pampanotes.core.export.ExportOptions
import dev.pampa.pampanotes.core.export.ExportOptionsCodec
import dev.pampa.pampanotes.core.refinement.RefinementService
import dev.pampa.pampanotes.core.repo.RefinementRepository
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.core.transcription.EndpointHealth
import dev.pampa.pampanotes.core.transcription.EndpointResolver
import dev.pampa.pampanotes.core.transcription.GroqWhisperProvider
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import dev.pampa.pampanotes.core.transcription.TranscriptionHttp
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
  /** I modelli di chat che Groq dichiara, fra cui scegliere quello del raffinamento. */
  val refinementModels: List<String> = emptyList(),
  /** Quello che «automatico» sceglierebbe oggi, per dirlo invece di farlo indovinare. */
  val refinementAuto: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
  private val engineSettingsStore: EngineSettingsStore,
  private val settingsStore: PampaSettingsStore,
  private val keys: AiKeyStore,
  private val verifier: AiKeyVerifier,
  private val aiSettings: AiSettingsStore,
  private val http: TranscriptionHttp,
  private val resolver: EndpointResolver,
  private val refinement: RefinementRepository,
  private val scheduler: dev.pampa.pampanotes.work.WorkScheduler,
) : ViewModel() {

  companion object {
    /** In [CheckState.Ok.detail] per un endpoint: da quale strada e' arrivata la risposta. */
    const val ENDPOINT_VIA_LAN = "lan"
    const val ENDPOINT_VIA_REMOTE = "remote"
  }

  val engineSettings: StateFlow<EngineSettings> = engineSettingsStore.settings
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EngineSettings())

  val settings: StateFlow<PampaSettings> = settingsStore.settings
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PampaSettings())

  /**
   * Le opzioni con cui si esportera' la prossima volta.
   *
   * Vivono nelle preferenze e non in un preset del database: sono una preferenza dell'utente, non
   * un oggetto che si crea, si nomina e si cancella.
   */
  val exportDefaults: StateFlow<ExportOptions> = settingsStore.settings
    .map { ExportOptionsCodec.decode(it.exportDefaultsJson) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportOptions())

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
    // Il nome resta quello che c'e' (arriva dall'account): riscriverlo vuoto sarebbe una modifica,
    // e salirebbe all'account come se l'utente l'avesse cancellato.
    settingsStore.setEndpoint(url, name = settingsStore.current().endpointName, model = model)
  }

  /**
   * I tre campi del computer come li ha lasciati chi li ha scritti, in un colpo solo: dal primo
   * avvio quando si va avanti senza aver premuto «Prova». Un token vuoto lascia quello di prima.
   */
  fun saveEndpoint(url: String, remoteUrl: String, token: String) = viewModelScope.launch {
    val current = settingsStore.current()
    settingsStore.setEndpoint(url, name = current.endpointName, model = current.endpointModel)
    settingsStore.setEndpointRemoteUrl(remoteUrl)
    if (token.isNotBlank()) settingsStore.setEndpointToken(token)
    shareEndpointWithAccount()
  }

  /**
   * Un computer appena cambiato sale all'account adesso, non fra sei ore: chi ricollega il PC sul
   * telefono e poi apre il tablet deve trovarlo gia' li'. `KEEP`: se un giro e' gia' in corso,
   * la modifica resta sporca e sale col prossimo.
   */
  private suspend fun shareEndpointWithAccount() {
    val settings = settingsStore.current()
    if (settings.syncEnabled && settings.endpointDirty) scheduler.syncNow()
  }

  fun setEndpointToken(token: String?) = viewModelScope.launch { settingsStore.setEndpointToken(token) }
  fun setEndpointRemoteUrl(url: String) = viewModelScope.launch { settingsStore.setEndpointRemoteUrl(url) }

  /**
   * Prova i due indirizzi nell'ordine in cui li proverebbe una trascrizione — prima casa, con due
   * secondi di pazienza, poi Tailscale — e dice **quale** ha risposto: e' l'unico modo di sapere
   * se l'indirizzo di fuori funziona mentre si e' ancora a casa a configurarlo.
   */
  fun testEndpoint(lanUrl: String, remoteUrl: String) {
    viewModelScope.launch {
      if (lanUrl.isBlank() && remoteUrl.isBlank()) {
        _services.update { it.copy(endpointCheck = CheckState.Failed("empty")) }
        return@launch
      }
      _services.update { it.copy(endpointCheck = CheckState.Running) }
      // La cache del risolutore va buttata: chi preme «prova» ha appena cambiato qualcosa.
      resolver.invalidate()
      val endpoint = resolver.resolve(lanUrl, remoteUrl) ?: return@launch
      val provider = OpenAiCompatProvider(
        http = http,
        baseUrl = endpoint.url,
        token = settingsStore.endpointToken(),
        readTimeoutMillis = 15_000,
      )
      val health: EndpointHealth = provider.health()
      _services.update {
        if (health.reachable) {
          it.copy(
            endpointModels = health.models,
            endpointCheck = CheckState.Ok(
              detail = if (endpoint.viaLan) ENDPOINT_VIA_LAN else ENDPOINT_VIA_REMOTE,
              latencyMs = health.latencyMs,
              modelCount = health.models.size,
            ),
          )
        } else {
          it.copy(endpointCheck = CheckState.Failed(health.detail ?: "network"))
        }
      }
      // «Prova» viene subito dopo aver salvato i campi: a prova finita sono scritti di sicuro.
      shareEndpointWithAccount()
    }
  }

  // --- Trascrizione ---
  fun setPreferredProvider(provider: TranscriptionProviderId) = viewModelScope.launch {
    settingsStore.setPreferredProvider(provider)
  }

  fun setCustomOnly(only: Boolean) = viewModelScope.launch { settingsStore.setCustomOnly(only) }

  fun setLanguage(language: String) = viewModelScope.launch { settingsStore.setLanguage(language) }
  fun setVocabulary(text: String) = viewModelScope.launch { settingsStore.setVocabulary(text) }

  fun setRefinementPreset(preset: dev.pampa.pampanotes.core.settings.RefinementPreset) =
    viewModelScope.launch { settingsStore.setRefinementPreset(preset) }

  /** Vuoto vuol dire "scegli tu": lo risolve il repository leggendo il catalogo di Groq. */
  fun setRefinementModel(model: String) = viewModelScope.launch { settingsStore.setRefinementModel(model.trim()) }

  /**
   * I modelli fra cui scegliere, dal catalogo di Groq.
   *
   * Un elenco e non un campo di testo: un nome di modello scritto a mano e' un nome sbagliato
   * mezze volte, e l'errore arriva trenta secondi dopo, dal lavoro. Senza chiave l'elenco resta
   * vuoto e la pagina lo dice.
   */
  fun loadRefinementModels() {
    viewModelScope.launch {
      val provider = refinement.provider() ?: return@launch
      val chat = runCatching { provider.listModels().chat.map { it.id } }.getOrDefault(emptyList())
        .filterNot { it.contains("whisper", ignoreCase = true) || it.contains("tts", ignoreCase = true) }
      _services.update { it.copy(refinementModels = chat, refinementAuto = RefinementService.pickModel(chat)) }
    }
  }
  fun setChunkMinutes(minutes: Int) = viewModelScope.launch { settingsStore.setChunkMinutes(minutes) }
  fun setGroqMaxUploadMb(mb: Int) = viewModelScope.launch { settingsStore.setGroqMaxUploadMb(mb) }
  fun setAutoTranscribe(enabled: Boolean) = viewModelScope.launch { settingsStore.setAutoTranscribeOnImport(enabled) }

  /** La cartella dove finiscono backup ed export. La sceglie anche il primo avvio. */
  fun setBackupFolder(uri: android.net.Uri) = viewModelScope.launch {
    settingsStore.setBackupFolderUri(uri.toString())
  }

  // --- Esportazione ---
  fun setExportDefaults(options: ExportOptions) = viewModelScope.launch {
    settingsStore.setExportDefaultsJson(ExportOptionsCodec.encode(options))
  }
}
