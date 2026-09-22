package dev.pampa.pampanotes.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.sync.SyncRepository

/**
 * Un giro di sincronizzazione con l'indice in cloud.
 *
 * **Non** in primo piano: dura secondi, e' testo. Il worker della trascrizione ha una notifica
 * perche' dura ore; questo no, e una notifica per un lavoro di tre secondi sarebbe rumore.
 * L'esito finisce nei dati del lavoro, da dove la pagina Sincronizzazione lo legge.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val repository: SyncRepository,
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    if (!settingsStore.current().syncEnabled) return Result.success()
    val report = repository.syncNow()
    // Le righe nuove sono arrivate: se si e' chiesto di tenere tutto anche qui, i loro file si
    // vanno a prendere adesso. Il worker si chiude da solo se non c'e' niente da scaricare.
    val settings = settingsStore.current()
    if (report.ok && settings.mirrorEnabled && settings.hasEndpoint) scheduler.fetchNow(settings.archiveOnlyUnmetered)
    val data = workDataOf(
      KEY_PUSHED to report.pushed,
      KEY_PULLED to report.pulled,
      KEY_DELETED to report.deleted,
      KEY_FORKED to report.forked,
      KEY_REJECTED to report.rejected,
      KEY_ERROR to report.error,
    )
    if (report.ok) return Result.success(data)
    // Un token sbagliato o un protocollo diverso non si sistemano riprovando fra un minuto: si
    // chiude con l'errore scritto, e lo si legge in pagina. Il resto — rete, server giu' — si riprova.
    val permanent = report.error?.let { it.contains("401") || it.contains("token") || it.contains("protocollo") } == true
    return if (permanent || runAttemptCount >= MAX_ATTEMPTS) Result.success(data) else Result.retry()
  }

  companion object {
    const val KEY_PUSHED = "pushed"
    const val KEY_PULLED = "pulled"
    const val KEY_DELETED = "deleted"
    const val KEY_FORKED = "forked"
    const val KEY_REJECTED = "rejected"
    const val KEY_ERROR = "error"
    private const val MAX_ATTEMPTS = 4
  }
}
