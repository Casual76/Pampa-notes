package dev.pampa.pampanotes.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore

/**
 * Il timer della fila che aspetta il computer di casa: scade, e sveglia la coda.
 *
 * Non trascrive e non guarda il PC — quello lo fa il worker della coda appena parte. Un timer solo
 * per provider (`WorkScheduler.retryForEndpoint`, nome unico e `REPLACE`), con un nome suo: cosi'
 * rimetterlo non tocca mai il worker della coda, nemmeno quello che sta lavorando, e per quanto
 * l'attesa duri ce n'e' sempre uno e uno solo. WorkManager lo tiene su disco: sopravvive al processo
 * ucciso, al riavvio del telefono, e in Doze scade alla prima finestra buona.
 *
 * Con la fila vuota — i lavori annullati o cancellati mentre si aspettava — non sveglia niente.
 */
@HiltWorker
class EndpointRetryWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val repository: TranscriptionRepository,
  private val scheduler: WorkScheduler,
  private val settingsStore: PampaSettingsStore,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val providerId = inputData.getString(TranscriptionQueueWorker.KEY_PROVIDER) ?: return Result.success()
    if (repository.queuedCount(providerId) > 0) {
      scheduler.wake(providerId)
    } else {
      // Nessuno aspetta piu': la prossima attesa riparte dal passo corto.
      settingsStore.setEndpointWaitingSince(providerId, null)
    }
    return Result.success()
  }
}
