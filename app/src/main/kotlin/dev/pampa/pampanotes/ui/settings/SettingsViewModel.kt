package dev.pampa.pampanotes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
  private val engineSettingsStore: EngineSettingsStore,
  private val settingsStore: PampaSettingsStore,
) : ViewModel() {

  val engineSettings: StateFlow<EngineSettings> = engineSettingsStore.settings
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EngineSettings())

  val settings: StateFlow<PampaSettings> = settingsStore.settings
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PampaSettings())

  fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { engineSettingsStore.setThemeMode(mode) }
  fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { engineSettingsStore.setDynamicColorEnabled(enabled) }
  fun setAmoled(enabled: Boolean) = viewModelScope.launch { engineSettingsStore.setAmoledEnabled(enabled) }
  fun setHaptics(enabled: Boolean) = viewModelScope.launch { engineSettingsStore.setHapticsEnabled(enabled) }
}
