package dev.pampa.pampanotes.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.ui.importing.ImportRequest
import dev.pampa.pampanotes.ui.importing.ImportRequestHolder
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
  engineSettingsStore: EngineSettingsStore,
  private val settingsStore: PampaSettingsStore,
  private val importRequests: ImportRequestHolder,
) : ViewModel() {

  /** Il tema. Parte dai default compilati: un fotogramma con l'accento giusto vale piu' di uno vuoto. */
  val engineSettings: StateFlow<EngineSettings> = engineSettingsStore.settings
    .stateIn(viewModelScope, SharingStarted.Eagerly, EngineSettings())

  val settings: StateFlow<PampaSettings> = settingsStore.settings
    .stateIn(viewModelScope, SharingStarted.Eagerly, PampaSettings())

  /**
   * Se il primo avvio e' gia' stato fatto. `null` finche' non si sa.
   *
   * Tre stati e non due: il default compilato di [PampaSettings] dice "non fatto", e partire da
   * quello vorrebbe dire un lampo di benvenuto a ogni apertura per chi l'app ce l'ha da mesi.
   */
  val onboardingDone: StateFlow<Boolean?> = settingsStore.settings
    .map { it.onboardingDone }
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  fun completeOnboarding() {
    viewModelScope.launch { settingsStore.setOnboardingDone(true) }
  }

  /**
   * @return vero quando l'intent portava roba da importare, e la navigazione deve aprire il wizard.
   */
  fun onIntent(intent: Intent): Boolean {
    val request = ImportRequest.fromIntent(intent) ?: return false
    importRequests.offer(request)
    return true
  }

  /** Dal selettore file, o da "importa in questa nota" (dove gli URI arrivano subito dopo). */
  fun onFilesPicked(uris: List<Uri>, intoNoteId: String?) {
    if (uris.isEmpty() && intoNoteId != null) {
      // La nota di destinazione arriva prima del selettore: si ricorda per la chiamata successiva.
      pendingNoteId = intoNoteId
      return
    }
    importRequests.offer(ImportRequest(uris = uris, intoNoteId = intoNoteId ?: pendingNoteId))
    pendingNoteId = null
  }

  private var pendingNoteId: String? = null
}
