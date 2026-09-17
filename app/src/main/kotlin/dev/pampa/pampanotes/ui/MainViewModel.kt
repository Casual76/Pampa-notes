package dev.pampa.pampanotes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MainViewModel @Inject constructor(
  engineSettingsStore: EngineSettingsStore,
  settingsStore: PampaSettingsStore,
) : ViewModel() {

  /** Il tema. Parte dai default compilati: un fotogramma con l'accento giusto vale piu' di uno vuoto. */
  val engineSettings: StateFlow<EngineSettings> = engineSettingsStore.settings
    .stateIn(viewModelScope, SharingStarted.Eagerly, EngineSettings())

  val settings: StateFlow<PampaSettings> = settingsStore.settings
    .stateIn(viewModelScope, SharingStarted.Eagerly, PampaSettings())
}
