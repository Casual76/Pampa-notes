package dev.pampa.pampanotes.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.settings.PampaSettingsStore

/**
 * «Tieni tutto anche qui»: scarica dal computer di casa i file che l'indice cita e questo
 * dispositivo non ha.
 *
 * E' l'archivio nel verso opposto, e ha la stessa forma: in primo piano con una notifica, perche'
 * un semestre di lezioni sono gigabyte e un worker normale il sistema lo ferma dopo dieci minuti;
 * il progresso pubblicato come dato del lavoro, per la pagina Archiviazione. Parte dopo ogni giro
 * di sincronizzazione — e' il sync che porta le righe nuove — e dal tasto «Scarica adesso».
 */
@HiltWorker
class FetchWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val fetcher: ArchiveFetcher,
  private val settingsStore: PampaSettingsStore,
) : CoroutineWorker(context, params) {

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(text = null, progress = null)

  override suspend fun doWork(): Result {
    val forced = inputData.getBoolean(KEY_FORCE, false)
    // Solo Registrazioni: «Tieni le registrazioni anche qui» appena acceso (vedi `WorkScheduler.fetchPersonal`).
    val onlyPersonal = inputData.getBoolean(KEY_ONLY_PERSONAL, false)
    val settings = settingsStore.current()
    // Spento nel frattempo, o senza un computer da cui prendere: niente da fare.
    if (!settings.hasEndpoint) return Result.success()
    if (!forced && !settings.mirrorEnabled) return Result.success()
    // Niente da scaricare: si chiude senza nemmeno mostrare la notifica.
    if (fetcher.pendingCount(onlyPersonal) == 0) return Result.success(workDataOf(KEY_DOWNLOADED to 0, KEY_FAILED to 0, KEY_BYTES to 0L))
    // Il primo piano negato a un'app in background (Android 12+) e' un «non adesso»: si riprova.
    try {
      setForeground(getForegroundInfo())
    } catch (refused: IllegalStateException) {
      return Result.retry()
    }

    var lastPublished = 0L
    val outcome = fetcher.fetchAll(onlyPersonal) { progress ->
      val now = System.currentTimeMillis()
      val boundary = progress.done == progress.total || progress.fraction == 0f
      if (!boundary && now - lastPublished < PUBLISH_EVERY_MS) return@fetchAll
      lastPublished = now
      setProgressAsync(workDataOf(KEY_DONE to progress.done, KEY_TOTAL to progress.total, KEY_LABEL to progress.label))
      runCatching { setForegroundAsync(foregroundInfo(text = progress.label.ifBlank { null }, progress = progress.fraction)) }
    }

    val data = workDataOf(
      KEY_DOWNLOADED to outcome.downloaded,
      KEY_FAILED to outcome.failed,
      KEY_BYTES to outcome.bytes,
      KEY_ERROR to outcome.lastError,
    )
    // Il computer non c'era: si riprova con l'attesa che cresce, ma non per sempre — il giro di
    // sincronizzazione successivo ne chiede comunque un altro. Chi ha premuto il tasto invece vuole
    // una risposta adesso, e la legge in pagina.
    return if (outcome.unreachable && !forced && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success(data)
  }

  private fun foregroundInfo(text: String?, progress: Float?): ForegroundInfo {
    val notification = AppNotifications.buildProgress(
      context = applicationContext,
      title = applicationContext.getString(R.string.notification_fetching),
      text = text,
      progress = progress,
      workId = id,
    )
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(AppNotifications.ID_FETCH_FOREGROUND, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(AppNotifications.ID_FETCH_FOREGROUND, notification)
    }
  }

  companion object {
    const val KEY_FORCE = "force"
    const val KEY_ONLY_PERSONAL = "onlyPersonal"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    const val KEY_LABEL = "label"
    const val KEY_DOWNLOADED = "downloaded"
    const val KEY_FAILED = "failed"
    const val KEY_BYTES = "bytes"
    const val KEY_ERROR = "error"

    private const val PUBLISH_EVERY_MS = 500L
    private const val MAX_ATTEMPTS = 4
  }
}
