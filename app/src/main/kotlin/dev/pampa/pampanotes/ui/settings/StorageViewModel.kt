package dev.pampa.pampanotes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.repo.StorageRepository
import dev.pampa.pampanotes.core.repo.StorageUsage
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cos'e' appena successo a una pulizia: quanti file, o quanti pacchetti. */
sealed interface StorageEvent {
  data class Swept(val files: Int) : StorageEvent
  data class ExportsCleared(val files: Int) : StorageEvent
}

data class StorageUiState(
  val usage: StorageUsage? = null,
  val working: Boolean = false,
  val event: StorageEvent? = null,
)

/**
 * Quanto spazio occupa l'app, e come liberarne.
 *
 * Si rilegge dopo ogni pulizia e non si osserva di continuo: camminare le cartelle costa, e un
 * numero che cambia da solo mentre lo si guarda non serve a nessuno.
 */
@HiltViewModel
class StorageViewModel @Inject constructor(
  private val storage: StorageRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(StorageUiState())
  val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

  init {
    refresh()
  }

  fun refresh() {
    viewModelScope.launch {
      val usage = runCatching { storage.usage() }.getOrNull()
      _uiState.update { it.copy(usage = usage) }
    }
  }

  /** I file che nessuna riga cita piu': la rete di sicurezza fra una cancellazione e il disco. */
  fun sweep() {
    viewModelScope.launch {
      _uiState.update { it.copy(working = true, event = null) }
      val removed = runCatching { storage.sweepOrphans() }.getOrDefault(0)
      _uiState.update { it.copy(working = false, event = StorageEvent.Swept(removed)) }
      refresh()
    }
  }

  fun clearExports() {
    viewModelScope.launch {
      _uiState.update { it.copy(working = true, event = null) }
      val removed = runCatching { storage.clearExports() }.getOrDefault(0)
      _uiState.update { it.copy(working = false, event = StorageEvent.ExportsCleared(removed)) }
      refresh()
    }
  }

  fun dismissEvent() {
    _uiState.update { it.copy(event = null) }
  }
}
