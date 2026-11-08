package dev.pampa.pampanotes.work

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampanotes.core.alignment.AlignmentRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Independent names/tag: paid transcription never waits behind alignment. */
@Singleton
class AlignmentScheduler @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val repository: AlignmentRepository,
) {
  /** Call for every SavedTranscription.sessionIds after the cloud save has committed. */
  suspend fun onCloudSaved(sessionId: String) { repository.onCloudSaved(sessionId); watch(); kick() }
  /** Call from app start and successful owner endpoint discovery/configuration. */
  suspend fun onApplicationOrEndpointWake() { repository.onWake(); watch(); kick() }
  fun kick() {
    WorkManager.getInstance(context).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.KEEP,
      OneTimeWorkRequestBuilder<AlignmentWorker>().setConstraints(constraints()).addTag(TAG).build())
  }
  internal fun scheduleAt(deadline: Long?) {
    val manager = WorkManager.getInstance(context)
    if (deadline == null) { manager.cancelUniqueWork(TIMER); return }
    val delay = (deadline - System.currentTimeMillis()).coerceAtLeast(30_000L)
    manager.enqueueUniqueWork(TIMER, ExistingWorkPolicy.REPLACE,
      OneTimeWorkRequestBuilder<AlignmentWakeWorker>().setConstraints(constraints())
        .setInitialDelay(delay, TimeUnit.MILLISECONDS).addTag(TAG).build())
  }
  private fun watch() {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(WATCH, ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<AlignmentWakeWorker>(15, TimeUnit.MINUTES).setInitialDelay(15, TimeUnit.MINUTES)
        .setConstraints(constraints()).addTag(TAG).build())
  }
  /** Wire alongside existing restore/logout stop hooks. Cancellation survives rediscovery. */
  suspend fun stopAll() {
    try { repository.cancelAll() }
    finally { WorkManager.getInstance(context).cancelAllWorkByTag(TAG) }
  }
  suspend fun cancelSession(sessionId: String) { repository.cancelSession(sessionId) }
  // Conservative home-audio transport policy; never uploads over metered mobile data.
  private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
  companion object {
    const val TAG = "cloud-alignment"
    private const val QUEUE = "cloud-alignment-queue"
    private const val TIMER = "cloud-alignment-timer"
    private const val WATCH = "cloud-alignment-watch"
  }
}
