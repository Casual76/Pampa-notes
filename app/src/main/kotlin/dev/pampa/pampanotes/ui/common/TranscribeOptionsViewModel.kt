package dev.pampa.pampanotes.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.core.transcription.CompanionSettings
import dev.pampa.pampanotes.core.transcription.CompanionSettingsApi
import dev.pampa.pampanotes.core.transcription.CompanionStatus
import dev.pampa.pampanotes.core.transcription.ComputerOverrides
import dev.pampa.pampanotes.core.transcription.VramEstimate
import dev.pampa.pampanotes.core.transcription.VramMode
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cosa il pannello «Ritrascrivi con le tue impostazioni» sa del telefono e del computer di casa. */
data class TranscribeOptionsUiState(
  /** Null finche' le impostazioni non sono lette. */
  val settings: PampaSettings? = null,
  /** Chi trascrive adesso: e' il computer di casa, o Groq? Cambia quali scelte hanno senso. */
  val onComputer: Boolean = false,
  /** Come sta il computer di casa. Null mentre lo si chiede (solo se [onComputer]). */
  val companion: CompanionStatus? = null,
  /** La stima del computer per le scelte di modello, memoria e lotto fatte nel pannello. */
  val estimate: VramEstimate? = null,
) {
  val ready: CompanionStatus.Ready? get() = companion as? CompanionStatus.Ready

  /** Si possono scegliere modello, memoria e lotto: il computer lo sa fare e chi chiede e' il proprietario. */
  val canTuneComputer: Boolean get() = onComputer && ready?.let { it.jobOptions && it.canEdit && it.settings != null } == true
}

/**
 * Dati e stime per il pannello delle impostazioni di una sola trascrizione.
 *
 * Il pannello non salva niente qui: le scelte tornano a chi l'ha aperto, che le mette nella riga del
 * lavoro. Questo ViewModel legge soltanto — le preferenze dell'app, lo stato del computer — e fa la
 * stima della memoria per le scelte non ancora fatte, senza toccare le impostazioni del computer.
 */
@HiltViewModel
class TranscribeOptionsViewModel @Inject constructor(
  private val settingsStore: PampaSettingsStore,
  private val companionApi: CompanionSettingsApi,
) : ViewModel() {

  private val _state = MutableStateFlow(TranscribeOptionsUiState())
  val state: StateFlow<TranscribeOptionsUiState> = _state.asStateFlow()

  private var loaded = false
  private var estimating: Job? = null

  /** Una volta per pannello: legge le preferenze e, se trascrive il computer, gli chiede come sta. */
  fun load() {
    if (loaded) return
    loaded = true
    viewModelScope.launch {
      val settings = settingsStore.current()
      val onComputer = settings.transcriptionProvider == TranscriptionProviderId.CUSTOM
      _state.update { it.copy(settings = settings, onComputer = onComputer) }
      if (onComputer) {
        val status = companionApi.status()
        _state.update { it.copy(companion = status) }
      }
    }
  }

  /**
   * La stima per queste scelte, chiesta al computer. Una scelta nuova annulla la richiesta di
   * prima: chi passa da «large-v3» a «small» passando per «medium» vuole la stima di «small».
   */
  fun estimate(choice: ComputerOverrides?) {
    estimating?.cancel()
    val base = _state.value.ready?.settings
    if (choice == null || choice.isEmpty || base == null || !_state.value.canTuneComputer) {
      _state.update { it.copy(estimate = null) }
      return
    }
    estimating = viewModelScope.launch {
      // Chi trascina o tocca piu' scelte di fila non fa una domanda al computer per ognuna.
      delay(ESTIMATE_DEBOUNCE_MS)
      val estimate = companionApi.estimate(draftFor(base, choice))
      _state.update { it.copy(estimate = estimate) }
    }
  }

  internal companion object {
    const val ESTIMATE_DEBOUNCE_MS = 250L

    /**
     * Le impostazioni del computer come sarebbero con le scelte di questo lavoro sopra. In memoria:
     * la stima non salva niente. Una memoria indicata vuol dire «la indico io» per questa stima.
     */
    fun draftFor(base: CompanionSettings, choice: ComputerOverrides): CompanionSettings = base.copy(
      model = choice.model ?: base.model,
      vramMode = if (choice.vramGb != null) VramMode.MANUAL else base.vramMode,
      vramGb = choice.vramGb ?: base.vramGb,
      batchSizeMax = choice.batchMax ?: base.batchSizeMax,
    )
  }
}
