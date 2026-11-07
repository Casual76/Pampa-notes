package dev.pampa.pampanotes.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampanotes.core.relay.RelayReport
import dev.pampa.pampanotes.core.relay.RelayRepository
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** One unique chain for upload and receive; no token, URL or begin body is put in WorkManager Data. */
@Singleton
class CloudRelayScheduler @Inject constructor(
  @ApplicationContext private val context: Context,
  private val repository: RelayRepository,
) {
  /** Persist all exact begin bodies before scheduling. Call from a ViewModel coroutine. */
  suspend fun uploadNote(noteId: String, resend: Boolean = false): RelayReport =
    repository.queueNote(noteId, resend).also { kick() }

  suspend fun uploadAllLocalFiles(resend: Boolean = false): RelayReport =
    repository.queueAllLocalFiles(resend).also { kick() }

  /** Startup / successful text sync: resumes existing uploads and honors receive opt-in. */
  fun kick(): UUID = enqueue(manualReceive = false)

  /** User's explicit receive/refresh action, also allowed with automatic receiving disabled. */
  fun receiveNow(): UUID = enqueue(manualReceive = true)

  fun observe(): Flow<List<WorkInfo>> = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)
  fun cancel() { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }

  private fun enqueue(manualReceive: Boolean): UUID {
    val request = OneTimeWorkRequestBuilder<CloudRelayWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setInputData(workDataOf(CloudRelayWorker.KEY_MANUAL_RECEIVE to manualReceive))
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
      .addTag(WORK_NAME)
      .build()
    // Requests arriving while the worker runs are not lost behind a KEEP of an old snapshot.
    WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    return request.id
  }

  companion object { const val WORK_NAME = "cloud-relay-v1" }
}
