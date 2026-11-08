package dev.pampa.pampanotes.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.pampanotes.core.alignment.AlignmentRepository
import kotlinx.coroutines.CancellationException

/** One part/POST per work slice, finite network timeout, durable deadline rather than WM retry. */
@HiltWorker
class AlignmentWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val repository: AlignmentRepository,
  private val scheduler: AlignmentScheduler,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result = try {
    repository.discover()
    scheduler.scheduleAt(repository.runNext())
    Result.success()
  } catch (cancel: CancellationException) { throw cancel }
  catch (_: Exception) {
    // Corrupt/private storage is a terminal slice failure. No hot retries or paid fallback.
    Result.failure()
  }
}

/** A single replaceable timer plus a periodic restart safety net; neither resets retry budgets. */
@HiltWorker
class AlignmentWakeWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val scheduler: AlignmentScheduler,
  private val repository: AlignmentRepository,
) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result { repository.onEndpointProbe(); scheduler.kick(); return Result.success() }
}
