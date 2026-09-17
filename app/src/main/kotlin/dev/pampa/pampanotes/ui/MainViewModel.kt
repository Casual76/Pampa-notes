package dev.pampa.pampanotes.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.ui.importing.ImportRequest
import dev.pampa.pampanotes.ui.importing.ImportRequestHolder
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MainViewModel @Inject constructor(
  engineSettingsStore: EngineSettingsStore,
  settingsStore: PampaSettingsStore,
  private val importRequests: ImportRequestHolder,
) : ViewModel() {

  /** Il tema. Parte dai default compilati: un fotogramma con l'accento giusto vale piu' di uno vuoto. */
  val engineSettings: StateFlow<EngineSettings> = engineSettingsStore.settings
    .stateIn(viewModelScope, SharingStarted.Eagerly, EngineSettings())

  val settings: StateFlow<PampaSettings> = settingsStore.settings
    .stateIn(viewModelScope, SharingStarted.Eagerly, PampaSettings())

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
