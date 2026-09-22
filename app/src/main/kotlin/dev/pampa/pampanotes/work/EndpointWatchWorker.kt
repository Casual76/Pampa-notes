package dev.pampa.pampanotes.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider

/**
 * Guarda se il computer di casa e' tornato.
 *
 * Gira ogni quarto d'ora finche' c'e' un lavoro in fila per lui (vedi [WorkScheduler.watchEndpoint]).
 * Non trascrive niente: fa la sonda dei due secondi e, se risponde, sveglia la coda. Con la fila
 * vuota si spegne da solo, cosi' un telefono senza lavori non interroga il PC ogni quindici minuti
 * per sempre.
 */
@HiltWorker
class EndpointWatchWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val repository: TranscriptionRepository,
  private val scheduler: WorkScheduler,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    if (repository.queuedCount(OpenAiCompatProvider.ID) == 0) {
      scheduler.watchEndpoint(false)
      return Result.success()
    }
    if (repository.endpointState() == TranscriptionRepository.EndpointState.REACHABLE) {
      scheduler.wake(OpenAiCompatProvider.ID)
    }
    return Result.success()
  }
}
