package dev.pampa.pampanotes.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.pampanotes.core.relay.RelayProgress
import dev.pampa.pampanotes.core.relay.RelayFailure
import dev.pampa.pampanotes.core.relay.RelayReport
import dev.pampa.pampanotes.core.relay.RelayRepository
import dev.pampa.pampanotes.core.relay.RelaySettingsStore
import dev.pampa.pampanotes.core.relay.RelayState
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Short, cancellable 90-second slices within the normal WorkManager execution budget.
 * No foreground service or new resource is required. Chunk boundaries survive a stopped slice.
 * Background scheduling can miss the ten-minute window; the UI must expose explicit resend.
 */
@HiltWorker
class CloudRelayWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val repository: RelayRepository,
  private val settings: RelaySettingsStore,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val reports = mutableListOf<RelayReport>()
    var lastProgress = 0L
    val progress: (RelayProgress) -> Unit = { value ->
      val now = android.os.SystemClock.elapsedRealtime()
      if (now - lastProgress >= 500 || value.bytes == value.totalBytes) {
        lastProgress = now
        setProgressAsync(workDataOf(
          "job" to value.jobId, "entity" to value.entityId, "kind" to value.kind.wire,
          "state" to value.state.name, "bytes" to value.bytes, "total" to value.totalBytes,
          "expiresAt" to (value.expiresAt ?: 0L),
        ))
      }
    }
    try {
      val finished = withTimeoutOrNull(SLICE_MS) {
        reports += repository.resumeUploads(onProgress = progress)
        if (inputData.getBoolean(KEY_MANUAL_RECEIVE, false) || settings.automaticReceiveEnabled()) {
          reports += repository.receiveReady(progress)
        }
        true
      } ?: false
      val results = reports.flatMap { it.results }
      val data = workDataOf(
        KEY_STATES to results.map { it.state.name }.distinct().joinToString(","),
        KEY_READY to results.count { it.state == RelayState.READY },
        KEY_RECEIVED to results.count { it.state == RelayState.RECEIVED },
        KEY_UNMATCHED to results.count { it.state == RelayState.UNMATCHED },
        KEY_FAILED to results.count { it.state in TERMINAL_ERRORS },
        KEY_BYTES to results.sumOf { it.bytes },
        KEY_CODE to results.firstOrNull { it.state in TERMINAL_ERRORS || it.state == RelayState.RETRY }?.code,
      )
      // Each persisted job also has a six-attempt limit and a server-anchored deadline.
      if (runAttemptCount < 5 && (!finished || reports.any { it.needsRetry })) Result.retry()
      else Result.success(data)
    } catch (error: RelayFailure) {
      Result.success(workDataOf(KEY_STATES to error.state.name, KEY_CODE to error.code))
    } catch (_: IOException) {
      // Pre-job authentication/local-storage errors require an explicit refresh.
      Result.success(workDataOf(KEY_STATES to RelayState.FAILED.name))
    }
  }

  companion object {
    const val KEY_MANUAL_RECEIVE = "manual_receive"
    const val KEY_STATES = "states"
    const val KEY_READY = "ready"
    const val KEY_RECEIVED = "received"
    const val KEY_UNMATCHED = "unmatched"
    const val KEY_FAILED = "failed"
    const val KEY_BYTES = "bytes"
    const val KEY_CODE = "code"
    private const val SLICE_MS = 90_000L
    private val TERMINAL_ERRORS = setOf(RelayState.EXPIRED, RelayState.QUOTA, RelayState.UNSUPPORTED,
      RelayState.ACCOUNT_CHANGED, RelayState.ROW_CHANGED, RelayState.SOURCE_CHANGED,
      RelayState.INTEGRITY_ERROR, RelayState.AUTH_REQUIRED, RelayState.FAILED, RelayState.QUEUE_FULL)
  }
}
