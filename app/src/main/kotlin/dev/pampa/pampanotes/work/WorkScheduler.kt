package dev.pampa.pampanotes.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chi sveglia le code.
 *
 * Una coda per provider, con `KEEP`: chiedere due volte non fa partire due worker, fa trovare il
 * lavoro nuovo a quello che sta gia' girando. E' il modo in cui la concorrenza resta uno per
 * provider senza un semaforo scritto a mano.
 */
@Singleton
class WorkScheduler @Inject constructor(
  @ApplicationContext private val context: Context,
) {

  fun kick(providerId: String) {
    val request = OneTimeWorkRequestBuilder<TranscriptionQueueWorker>()
      .setConstraints(
        Constraints.Builder()
          .setRequiredNetworkType(NetworkType.CONNECTED)
          .build(),
      )
      .setInputData(workDataOf(TranscriptionQueueWorker.KEY_PROVIDER to providerId))
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
      .addTag(TAG)
      .build()

    WorkManager.getInstance(context).enqueueUniqueWork(workName(providerId), ExistingWorkPolicy.KEEP, request)
  }

  /** Ferma la coda di un provider. I lavori restano dove sono: li rimette in fila l'avvio successivo. */
  fun stop(providerId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(workName(providerId))
  }

  /**
   * Ferma tutte le code.
   *
   * Serve al ripristino: un worker che sta scrivendo il risultato di una trascrizione dentro il
   * database che si sta per sostituire lo riscriverebbe un secondo dopo, sopra quello ripristinato.
   */
  fun stopAll() {
    WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
  }

  private fun workName(providerId: String) = "$WORK_PREFIX$providerId"

  companion object {
    const val TAG = "transcription"
    private const val WORK_PREFIX = "transcription-queue-"
  }
}
