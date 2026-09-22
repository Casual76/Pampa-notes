package dev.pampa.pampanotes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.repo.StorageRepository
import dev.pampa.pampanotes.core.repo.StorageUsage
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.work.ArchiveWorker
import dev.pampa.pampanotes.work.FetchWorker
import dev.pampa.pampanotes.work.WorkScheduler
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
  data class Evicted(val files: Int, val bytes: Long) : StorageEvent
}

/** Un giro di archiviazione in corso, come lo pubblica il worker. */
data class ArchiveRun(val done: Int, val total: Int, val label: String)

/** L'ultimo giro finito, come l'ha lasciato il worker. */
data class ArchiveLast(val uploaded: Int, val alreadyThere: Int, val failed: Int, val missing: Int, val bytes: Long, val error: String?)

/** Il verso opposto: un giro di scarico in corso, e l'ultimo finito. */
data class FetchRun(val done: Int, val total: Int, val label: String)
data class FetchLast(val downloaded: Int, val failed: Int, val bytes: Long, val error: String?)

data class StorageUiState(
  val usage: StorageUsage? = null,
  val working: Boolean = false,
  val event: StorageEvent? = null,
  val archiveEnabled: Boolean = false,
  val archiveOnlyUnmetered: Boolean = true,
  /** Un server personale configurato: senza, l'archivio non ha dove andare. */
  val hasEndpoint: Boolean = false,
  val lastArchiveAt: Long = 0L,
  val archiveRun: ArchiveRun? = null,
  val archiveLast: ArchiveLast? = null,
  /** Tieni tutto anche qui: i file degli altri dispositivi si scaricano appena l'indice li porta. */
  val mirrorEnabled: Boolean = false,
  val fetchRun: FetchRun? = null,
  val fetchLast: FetchLast? = null,
) {
  val archiving: Boolean get() = archiveRun != null
  val fetching: Boolean get() = fetchRun != null
}

/**
 * Quanto spazio occupa l'app, come liberarne, e cosa ne ha gia' una copia sul computer di casa.
 *
 * Si rilegge dopo ogni pulizia e non si osserva di continuo: camminare le cartelle costa, e un
 * numero che cambia da solo mentre lo si guarda non serve a nessuno. Il giro di archiviazione
 * invece si osserva, perche' e' l'unica cosa qui che dura minuti.
 */
@HiltViewModel
class StorageViewModel @Inject constructor(
  private val storage: StorageRepository,
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
) : ViewModel() {

  private val _uiState = MutableStateFlow(StorageUiState())
  val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

  init {
    refresh()
    viewModelScope.launch {
      settingsStore.settings.collect { settings ->
        _uiState.update {
          it.copy(
            archiveEnabled = settings.archiveEnabled,
            archiveOnlyUnmetered = settings.archiveOnlyUnmetered,
            hasEndpoint = settings.hasEndpoint,
            lastArchiveAt = settings.lastArchiveAt,
            mirrorEnabled = settings.mirrorEnabled,
          )
        }
      }
    }
    viewModelScope.launch {
      var wasRunning = false
      scheduler.observeFetch().collect { infos ->
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        val run = running?.let {
          FetchRun(
            done = it.progress.getInt(FetchWorker.KEY_DONE, 0),
            total = it.progress.getInt(FetchWorker.KEY_TOTAL, 0),
            label = it.progress.getString(FetchWorker.KEY_LABEL).orEmpty(),
          )
        }
        val last = infos.filter { it.state == WorkInfo.State.SUCCEEDED && it.outputData.keyValueMap.isNotEmpty() }
          .maxByOrNull { it.outputData.getLong(FetchWorker.KEY_BYTES, 0) }
          ?.outputData?.let {
            FetchLast(
              downloaded = it.getInt(FetchWorker.KEY_DOWNLOADED, 0),
              failed = it.getInt(FetchWorker.KEY_FAILED, 0),
              bytes = it.getLong(FetchWorker.KEY_BYTES, 0),
              error = it.getString(FetchWorker.KEY_ERROR),
            )
          }
        _uiState.update { it.copy(fetchRun = run, fetchLast = last) }
        if (wasRunning && run == null) refresh()
        wasRunning = run != null
      }
    }
    viewModelScope.launch {
      var wasRunning = false
      scheduler.observeArchive().collect { infos ->
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        val run = running?.let {
          ArchiveRun(
            done = it.progress.getInt(ArchiveWorker.KEY_DONE, 0),
            total = it.progress.getInt(ArchiveWorker.KEY_TOTAL, 0),
            label = it.progress.getString(ArchiveWorker.KEY_LABEL).orEmpty(),
          )
        }
        // L'esito piu' recente fra quelli finiti bene: e' quello che si mostra sotto il tasto.
        val last = infos.filter { it.state == WorkInfo.State.SUCCEEDED && it.outputData.keyValueMap.isNotEmpty() }
          .maxByOrNull { it.outputData.getLong(ArchiveWorker.KEY_BYTES, 0) }
          ?.outputData?.let {
            ArchiveLast(
              uploaded = it.getInt(ArchiveWorker.KEY_UPLOADED, 0),
              alreadyThere = it.getInt(ArchiveWorker.KEY_ALREADY, 0),
              failed = it.getInt(ArchiveWorker.KEY_FAILED, 0),
              missing = it.getInt(ArchiveWorker.KEY_MISSING, 0),
              bytes = it.getLong(ArchiveWorker.KEY_BYTES, 0),
              error = it.getString(ArchiveWorker.KEY_ERROR),
            )
          }
        _uiState.update { it.copy(archiveRun = run, archiveLast = last) }
        // Finito un giro, i totali sono cambiati: si rileggono una volta, non a ogni progresso.
        if (wasRunning && run == null) refresh()
        wasRunning = run != null
      }
    }
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

  /** Via da qui quello che il computer ha gia': originali, registrazioni, o tutti e due. */
  fun evict(sources: Boolean, audio: Boolean) {
    viewModelScope.launch {
      _uiState.update { it.copy(working = true, event = null) }
      val gone = runCatching { storage.evictArchived(sources, audio) }.getOrNull()
      _uiState.update { it.copy(working = false, event = StorageEvent.Evicted(gone?.count ?: 0, gone?.bytes ?: 0L)) }
      refresh()
    }
  }

  fun dismissEvent() {
    _uiState.update { it.copy(event = null) }
  }

  // --- l'archivio ---

  fun setArchiveEnabled(enabled: Boolean) {
    viewModelScope.launch {
      settingsStore.setArchiveEnabled(enabled)
      scheduler.setPeriodicArchive(enabled, settingsStore.current().archiveOnlyUnmetered)
      // Acceso adesso: si parte subito, non fra sei ore.
      if (enabled) scheduler.archiveNow(settingsStore.current().archiveOnlyUnmetered)
    }
  }

  fun setArchiveOnlyUnmetered(only: Boolean) {
    viewModelScope.launch {
      settingsStore.setArchiveOnlyUnmetered(only)
      scheduler.setPeriodicArchive(settingsStore.current().archiveEnabled, only)
    }
  }

  fun archiveNow() {
    viewModelScope.launch { scheduler.archiveNow(settingsStore.current().archiveOnlyUnmetered) }
  }

  // --- tieni tutto anche qui ---

  fun setMirrorEnabled(enabled: Boolean) {
    viewModelScope.launch {
      settingsStore.setMirrorEnabled(enabled)
      // Acceso adesso: quello che gia' manca si va a prendere subito, non al prossimo giro di sync.
      if (enabled) scheduler.fetchNow(settingsStore.current().archiveOnlyUnmetered)
    }
  }

  /** Una volta sola, anche con l'interruttore spento. */
  fun fetchNow() {
    viewModelScope.launch { scheduler.fetchNow(settingsStore.current().archiveOnlyUnmetered, force = true) }
  }
}
