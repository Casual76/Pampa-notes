package dev.pampa.pampanotes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.archive.ArchiveRepository
import dev.pampa.pampanotes.core.files.ArchiveOutlook
import dev.pampa.pampanotes.core.repo.StorageRepository
import dev.pampa.pampanotes.core.repo.StorageUsage
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.work.ArchiveWorker
import dev.pampa.pampanotes.work.FetchWorker
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

/** Il computer di casa, visto da qui adesso. */
enum class ComputerState { UNCONFIGURED, CHECKING, REACHABLE, UNREACHABLE }

/**
 * I file che il computer ha rifiutato: quanti, e di questi quanti sono messi da parte per qualche
 * giorno (tre rifiuti di fila), con il momento in cui si riprova il primo.
 */
data class ArchiveRejections(val files: Int = 0, val parked: Int = 0, val retryAt: Long = 0L)

/** I lavori di archiviazione in coda, come li dice WorkManager: da qui si ricava [ArchiveOutlook]. */
private data class ArchiveQueue(
  val running: Boolean = false,
  val oneShotNextAt: Long? = null,
  val oneShotAttempts: Int = 0,
  val periodicNextAt: Long? = null,
)

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
  /** Uno scarico in coda che aspetta la rete (o il Wi-Fi). */
  val fetchQueued: Boolean = false,
  val computer: ComputerState = ComputerState.CHECKING,
  /** Il nome che il computer ha dato di se', se l'ha dato. */
  val computerName: String = "",
  /** Senza sincronizzazione non arrivano righe dagli altri dispositivi, quindi niente da scaricare. */
  val syncEnabled: Boolean = false,
  val rejections: ArchiveRejections = ArchiveRejections(),
  /** Quando salira' quello che sta solo qui, con le impostazioni di adesso. */
  val archiveOutlook: ArchiveOutlook = ArchiveOutlook.Unknown,
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
  private val transcription: TranscriptionRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(StorageUiState())
  val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

  private var archiveQueue = ArchiveQueue()

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
            computerName = settings.endpointName,
            syncEnabled = settings.syncEnabled,
          )
        }
        updateOutlook()
      }
    }
    // Il computer si interroga quando si apre la pagina e quando ne cambiano gli indirizzi: e' una
    // domanda di rete da due secondi, non qualcosa da ripetere a ogni ridisegno.
    viewModelScope.launch {
      settingsStore.settings
        .map { it.endpointUrl to it.endpointRemoteUrl }
        .distinctUntilChanged()
        .collect { checkComputer() }
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
        val queued = run == null && infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        _uiState.update { it.copy(fetchRun = run, fetchLast = last, fetchQueued = queued) }
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
        // Il giro «adesso» in coda (anche un nuovo tentativo) e il prossimo periodico: da qui la
        // frase «quando sale quello che sta solo qui», che e' la domanda vera dietro l'interruttore.
        val waiting = infos.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        val oneShot = waiting.firstOrNull { it.periodicityInfo == null }
        val periodic = waiting.firstOrNull { it.periodicityInfo != null }
        archiveQueue = ArchiveQueue(
          running = running != null,
          oneShotNextAt = oneShot?.nextScheduleTimeMillis,
          oneShotAttempts = oneShot?.runAttemptCount ?: 0,
          periodicNextAt = periodic?.nextScheduleTimeMillis,
        )
        _uiState.update { it.copy(archiveRun = run, archiveLast = last) }
        updateOutlook()
        // Finito un giro, i totali sono cambiati: si rileggono una volta, non a ogni progresso.
        if (wasRunning && run == null) refresh()
        wasRunning = run != null
      }
    }
  }

  fun refresh() {
    viewModelScope.launch {
      val usage = runCatching { storage.usage() }.getOrNull()
      val rejections = runCatching { rejections() }.getOrDefault(ArchiveRejections())
      _uiState.update { it.copy(usage = usage, rejections = rejections) }
    }
  }

  /** La domanda al computer: all'apertura, e di nuovo da un tocco sulla sua riga. */
  fun checkComputer() {
    viewModelScope.launch {
      val configured = settingsStore.current().hasEndpoint
      _uiState.update { it.copy(computer = if (configured) ComputerState.CHECKING else ComputerState.UNCONFIGURED) }
      if (!configured) return@launch
      val state = runCatching { transcription.endpointState() }.getOrNull()
      _uiState.update {
        it.copy(
          computer = when (state) {
            TranscriptionRepository.EndpointState.UNCONFIGURED -> ComputerState.UNCONFIGURED
            TranscriptionRepository.EndpointState.REACHABLE -> ComputerState.REACHABLE
            TranscriptionRepository.EndpointState.UNREACHABLE, null -> ComputerState.UNREACHABLE
          },
        )
      }
    }
  }

  /** I rifiuti che l'archivio si ricorda fra un giro e l'altro (`ArchiveFailures` nel DataStore). */
  private suspend fun rejections(): ArchiveRejections {
    val failures = settingsStore.archiveFailures().values
    val now = System.currentTimeMillis()
    val parked = failures.filter {
      it.count >= ArchiveRepository.MAX_FILE_FAILURES && now - it.lastAt < ArchiveRepository.FAILURE_COOLDOWN_MS
    }
    return ArchiveRejections(
      files = failures.size,
      parked = parked.size,
      retryAt = parked.minOfOrNull { it.lastAt + ArchiveRepository.FAILURE_COOLDOWN_MS } ?: 0L,
    )
  }

  private fun updateOutlook() {
    _uiState.update {
      it.copy(
        archiveOutlook = ArchiveOutlook.of(
          hasComputer = it.hasEndpoint,
          enabled = it.archiveEnabled,
          running = archiveQueue.running,
          oneShotNextAt = archiveQueue.oneShotNextAt,
          oneShotAttempts = archiveQueue.oneShotAttempts,
          periodicNextAt = archiveQueue.periodicNextAt,
          now = System.currentTimeMillis(),
        ),
      )
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
