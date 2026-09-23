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
import dev.pampa.pampanotes.core.archive.ArchiveRepository
import dev.pampa.pampanotes.core.repo.StorageRepository
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider

/**
 * Il giro di archiviazione: manda al computer di casa quello che ancora non ha.
 *
 * In primo piano, come la trascrizione: un `.sdocx` da mezzo giga sale in minuti, e un worker
 * normale il sistema lo ferma dopo dieci. La notifica dice quale file sta salendo e ha il tasto per
 * annullare. Il progresso lo pubblica anche come dato del lavoro, cosi' la pagina Archiviazione lo
 * legge senza un canale suo.
 */
@HiltWorker
class ArchiveWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val archive: ArchiveRepository,
  private val settingsStore: PampaSettingsStore,
  private val transcription: TranscriptionRepository,
  private val scheduler: WorkScheduler,
  private val storage: StorageRepository,
) : CoroutineWorker(context, params) {

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(text = null, progress = null)

  override suspend fun doWork(): Result {
    // Spento nel frattempo: un giro periodico rimasto in coda non deve caricare lo stesso.
    if (!settingsStore.current().archiveEnabled) return Result.success()
    // Da Android 12 il primo piano si nega a un'app in background: e' un «non adesso», e il giro
    // si riprova col ritardo che cresce invece di finire in errore.
    try {
      setForeground(getForegroundInfo())
    } catch (refused: IllegalStateException) {
      return Result.retry()
    }

    // Il progresso arriva ogni 256 kB caricati, cioe' decine di volte al secondo: la notifica e il
    // dato del lavoro si aggiornano al massimo due volte al secondo, o si passa il tempo a
    // ridisegnare una barra che l'occhio non segue. Le versioni `Async` perche' la lambda non e'
    // una coroutine: viene chiamata dal ciclo di scrittura sulla socket.
    var lastPublished = 0L
    val outcome = archive.archiveAll { progress ->
      val now = System.currentTimeMillis()
      val boundary = progress.done == progress.total || progress.fraction == 0f
      if (!boundary && now - lastPublished < PUBLISH_EVERY_MS) return@archiveAll
      lastPublished = now
      setProgressAsync(workDataOf(KEY_DONE to progress.done, KEY_TOTAL to progress.total, KEY_LABEL to progress.label))
      runCatching { setForegroundAsync(foregroundInfo(text = progress.label.ifBlank { null }, progress = progress.fraction)) }
    }

    val data = workDataOf(
      KEY_UPLOADED to outcome.uploaded,
      KEY_ALREADY to outcome.alreadyThere,
      KEY_FAILED to outcome.failed,
      KEY_MISSING to outcome.missing,
      KEY_BYTES to outcome.bytes,
      KEY_ERROR to outcome.lastError,
      KEY_SKIPPED to outcome.skipped,
    )
    // Il computer ha risposto: se c'e' una trascrizione in fila che lo aspettava, e' il momento.
    val answered = outcome.uploaded + outcome.alreadyThere > 0
    if (answered && transcription.queuedCount(OpenAiCompatProvider.ID) > 0) scheduler.wake(OpenAiCompatProvider.ID)
    // «Solo sul computer»: quello che il giro ha appena portato sul PC, e che una regola tiene
    // lontano da qui, se ne va adesso. Solo se il computer ha risposto: senza, il giro non ha
    // cambiato niente, e la pulizia puo' aspettare quello dopo.
    if (!outcome.unreachable) runCatching { storage.evictComputerOnly() }
    // Il server non c'era: si riprova con l'attesa che cresce. Un server che c'e' e rifiuta un file
    // invece chiude bene: sara' il prossimo giro a riprovare quello rimasto indietro.
    return if (outcome.unreachable) Result.retry() else Result.success(data)
  }

  private fun foregroundInfo(text: String?, progress: Float?): ForegroundInfo {
    val notification = AppNotifications.buildProgress(
      context = applicationContext,
      title = applicationContext.getString(R.string.notification_archiving),
      text = text,
      progress = progress,
      workId = id,
    )
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(AppNotifications.ID_ARCHIVE_FOREGROUND, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(AppNotifications.ID_ARCHIVE_FOREGROUND, notification)
    }
  }

  companion object {
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    const val KEY_LABEL = "label"
    const val KEY_UPLOADED = "uploaded"
    const val KEY_ALREADY = "already"
    const val KEY_FAILED = "failed"
    const val KEY_MISSING = "missing"
    const val KEY_BYTES = "bytes"
    const val KEY_ERROR = "error"
    const val KEY_SKIPPED = "skipped"

    private const val PUBLISH_EVERY_MS = 500L
  }
}
