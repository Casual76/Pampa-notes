package dev.pampa.pampanotes.ui.cloudrelay

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.relay.*
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.work.CloudRelayScheduler
import dev.pampa.pampanotes.work.CloudRelayWorker
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RelayJobUi(val result: RelayResult, val name: String, val noteId: String?)
data class CloudRelayUiState(
  val configured: Boolean = false,
  val automaticReceive: Boolean = false,
  val queueing: Boolean = false,
  val working: Boolean = false,
  val cancelled: Boolean = false,
  val checked: Boolean = false,
  val jobs: List<RelayJobUi> = emptyList(),
  val results: List<RelayResult> = emptyList(),
  val progress: RelayResult? = null,
  val fileNames: Map<Pair<RelayKind, String>, String> = emptyMap(),
)

@HiltViewModel
class CloudRelayViewModel @Inject constructor(
  savedState: SavedStateHandle,
  private val relay: RelayRepository,
  private val preferences: RelaySettingsStore,
  private val settings: PampaSettingsStore,
  private val sources: dev.pampa.pampanotes.core.db.SourceDao,
  private val parts: dev.pampa.pampanotes.core.db.AudioPartDao,
  private val sessions: dev.pampa.pampanotes.core.db.SessionDao,
  private val scheduler: CloudRelayScheduler,
) : ViewModel() {
  val noteId: String? = savedState.get<String>("noteId")
  private val mutable = MutableStateFlow(CloudRelayUiState())
  val state = mutable.asStateFlow()
  private var operation: Job? = null
  private var reload: Job? = null
  private var requestedWork: java.util.UUID? = null
  private var expiryRefresh: Job? = null

  init {
    viewModelScope.launch { preferences.automaticReceive.collect { enabled -> mutable.update { it.copy(automaticReceive = enabled) } } }
    viewModelScope.launch {
      settings.settings.collect { value ->
        mutable.update { it.copy(configured = value.syncEnabled && value.syncHasToken && value.syncDeviceId.isNotBlank() && value.syncServerUrl.isNotBlank()) }
        reloadJobs()
      }
    }
    viewModelScope.launch {
      scheduler.observe().collect { infos ->
        val active = infos.any { !it.state.isFinished }
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        val data = running?.progress
        val progress = data?.getString("state")?.let { name ->
          RelayState.entries.firstOrNull { it.name == name }?.let { status ->
            RelayResult(status, jobId = data.getString("job"), entityId = data.getString("entity"),
              kind = RelayKind.entries.firstOrNull { it.wire == data.getString("kind") },
              bytes = data.getLong("bytes", 0), totalBytes = data.getLong("total", 0),
              expiresAt = data.getLong("expiresAt", 0).takeIf { it > 0 })
          }
        }
        mutable.update { it.copy(working = active, progress = progress) }
        if (!active) {
          val last = infos.firstOrNull { it.id == requestedWork && it.state == WorkInfo.State.SUCCEEDED }
          val states = last?.outputData?.getString(CloudRelayWorker.KEY_STATES).orEmpty()
            .split(',').mapNotNull { name -> RelayState.entries.firstOrNull { it.name == name } }
          if (last != null) mutable.update { it.copy(checked = true,
            results = it.results.filter { result -> result.jobId == null && result.entityId != null } + states.map { status ->
              RelayResult(status, code = last.outputData.getString(CloudRelayWorker.KEY_CODE).takeIf { relayIsProblem(status) })
            }) }
          reloadJobs()
        }
      }
    }
  }

  fun reloadJobs() {
    reload?.cancel()
    reload = viewModelScope.launch {
      try {
        val owners = sessions.all().associate { it.id to it.noteId }
        val rows = buildMap<Pair<RelayKind, String>, Pair<String, String>> {
          sources.all().forEach { put(RelayKind.SOURCE to it.id, it.originalName to it.noteId) }
          parts.all().forEach { part -> owners[part.sessionId]?.let { put(RelayKind.AUDIO to part.id, part.originalName to it) } }
        }
        val jobs = relay.jobs().mapNotNull { result ->
          val row = result.kind?.let { kind -> result.entityId?.let { rows[kind to it] } }
          if (noteId != null && row?.second != noteId) null else RelayJobUi(result, row?.first.orEmpty(), row?.second)
        }
        mutable.update { it.copy(jobs = jobs, fileNames = rows.mapValues { entry -> entry.value.first }) }
        expiryRefresh?.cancel()
        jobs.mapNotNull { it.result.remainingMillis?.takeIf { ms -> ms > 0 && it.result.state in setOf(RelayState.READY, RelayState.QUEUED, RelayState.RETRY) } }
          .minOrNull()?.let { remaining ->
            expiryRefresh = viewModelScope.launch { kotlinx.coroutines.delay(remaining); reloadJobs() }
          }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (error: Exception) { mutable.update { it.copy(jobs = emptyList(), results = listOf(error.result())) } }
    }
  }

  fun setAutomaticReceive(enabled: Boolean) = viewModelScope.launch {
    preferences.setAutomaticReceive(enabled)
    if (enabled) receive()
  }

  fun receive() {
    mutable.update { it.copy(cancelled = false, checked = false, results = emptyList()) }
    requestedWork = scheduler.receiveNow()
  }

  fun resume() { mutable.update { it.copy(cancelled = false) }; requestedWork = scheduler.kick() }

  fun send(resend: Boolean = false, targetNote: String? = noteId) {
    if (operation?.isActive == true) return
    operation = viewModelScope.launch {
      mutable.update { it.copy(queueing = true, cancelled = false, results = emptyList(), checked = false) }
      try {
        val report = if (targetNote == null) scheduler.uploadAllLocalFiles(resend) else scheduler.uploadNote(targetNote, resend)
        mutable.update { it.copy(results = report.results, checked = true) }
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (error: Exception) { mutable.update { it.copy(results = listOf(error.result())) }
      } finally { mutable.update { it.copy(queueing = false) }; reloadJobs() }
    }
  }

  fun cancel() {
    operation?.cancel()
    scheduler.cancel()
    mutable.update { it.copy(cancelled = true, queueing = false) }
    reloadJobs()
  }

  private fun Exception.result() = if (this is RelayFailure) RelayResult(state, code = code) else RelayResult(RelayState.FAILED)
}
