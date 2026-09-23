package dev.pampa.pampanotes.ui.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Un lavoro con il nome della nota a cui appartiene: "Lavoro 3" non dice niente a nessuno. */
data class JobRow(
  val job: JobEntity,
  val noteTitle: String,
  val sessionDate: String,
  /** La sessione c'e' ancora: la riga si apre su di lei. */
  val sessionExists: Boolean = false,
)

data class JobsUiState(
  val active: List<JobRow> = emptyList(),
  val finished: List<JobRow> = emptyList(),
  val loading: Boolean = true,
) {
  val isEmpty: Boolean get() = !loading && active.isEmpty() && finished.isEmpty()
}

@HiltViewModel
class JobsViewModel @Inject constructor(
  private val repository: TranscriptionRepository,
  private val sessions: SessionDao,
  private val notes: NoteDao,
  private val scheduler: WorkScheduler,
) : ViewModel() {

  /**
   * I titoli si cercano insieme alle righe, non dopo: prima arrivavano un attimo piu' tardi, e ogni
   * lavoro mostrava «Nota rimossa» per un istante a ogni apertura.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  val uiState: StateFlow<JobsUiState> = repository.observeAll().mapLatest { jobs ->
    val lookup = jobs.map { it.sessionId }.distinct().associateWith { sessionId ->
      val session = sessions.get(sessionId)
      val note = session?.let { notes.get(it.noteId) }
      Triple(note?.title.orEmpty(), session?.date.orEmpty(), session != null)
    }
    val rows = jobs.map { job ->
      val (title, date, exists) = lookup[job.sessionId] ?: Triple("", "", false)
      JobRow(job, title, date, exists)
    }
    JobsUiState(
      active = rows.filter { it.job.state.isActive },
      finished = rows.filter { it.job.state.isTerminal },
      loading = false,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JobsUiState())

  fun cancel(jobId: String) = viewModelScope.launch { repository.requestCancel(jobId) }

  fun retry(jobId: String) = viewModelScope.launch {
    repository.retry(jobId)
    repository.get(jobId)?.let { scheduler.kick(it.provider) }
  }

  fun delete(jobId: String) = viewModelScope.launch { repository.delete(jobId) }

  /** Tutti i falliti in fila, e le loro code sveglie: e' quello che serve quando il computer torna. */
  fun retryAllFailed() = viewModelScope.launch { repository.retryAllFailed().forEach { scheduler.kick(it) } }

  fun clearFinished() = viewModelScope.launch { repository.clearFinished() }
}
