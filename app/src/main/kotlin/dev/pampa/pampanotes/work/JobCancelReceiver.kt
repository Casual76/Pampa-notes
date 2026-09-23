package dev.pampa.pampanotes.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Il tasto «Annulla» della notifica di una trascrizione.
 *
 * Prima era il `PendingIntent` di WorkManager, che ferma il **worker**, non il lavoro: da Android 12
 * il worker capiva chi l'aveva fermato e chiudeva il lavoro annullato, ma su Android 8–11 il motivo
 * non si sa, il lavoro tornava in fila come dopo un'interruzione del sistema, e la coda — fermata
 * dall'app, non dal sistema — non ripartiva da sola. Qui si chiede di annullare quel lavoro, come fa
 * il tasto dentro l'app: la riga diventa `CANCEL_REQUESTED` (o `CANCELLED`, se era ancora in fila),
 * il worker che la guarda smette e passa al successivo.
 *
 * Non esportato: lo manda solo la notifica dell'app.
 */
@AndroidEntryPoint
class JobCancelReceiver : BroadcastReceiver() {

  @Inject lateinit var repository: TranscriptionRepository

  override fun onReceive(context: Context, intent: Intent) {
    val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return
    // Una scrittura sul database non si fa sul thread principale: `goAsync` tiene vivo il
    // ricevitore finche' non ha finito (pochi millisecondi, ben dentro i dieci secondi concessi).
    val pending = goAsync()
    scope.launch {
      try {
        runCatching { repository.requestCancel(jobId) }
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    private const val ACTION_CANCEL = "dev.pampa.pampanotes.action.CANCEL_JOB"
    private const val EXTRA_JOB_ID = "jobId"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Il tasto per annullare [jobId]: uno per lavoro, cosi' la notifica del successivo non annulla il primo. */
    fun pendingIntent(context: Context, jobId: String): PendingIntent {
      val intent = Intent(context, JobCancelReceiver::class.java)
        .setAction(ACTION_CANCEL)
        .putExtra(EXTRA_JOB_ID, jobId)
      return PendingIntent.getBroadcast(
        context,
        jobId.hashCode(),
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    }
  }
}
