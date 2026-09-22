package dev.pampa.pampanotes.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import java.util.UUID
import dev.pampa.pampanotes.MainActivity
import dev.pampa.pampanotes.R

/** I canali di notifica: uno per i lavori in corso (silenzioso), uno per gli esiti. */
object AppNotifications {
  const val CHANNEL_JOBS = "jobs"
  const val CHANNEL_RESULTS = "results"

  const val ID_TRANSCRIPTION_FOREGROUND = 1001
  const val ID_REFINEMENT_FOREGROUND = 1002
  const val ID_ARCHIVE_FOREGROUND = 1003
  const val ID_FETCH_FOREGROUND = 1004
  private const val ID_RESULT_BASE = 2000

  fun createChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
      // Bassa importanza: un lavoro che dura venti minuti non deve suonare, deve solo restare
      // visibile abbastanza da tenere il processo vivo e da farsi annullare.
      NotificationChannel(CHANNEL_JOBS, context.getString(R.string.channel_jobs), NotificationManager.IMPORTANCE_LOW).apply {
        description = context.getString(R.string.channel_jobs_detail)
        setShowBadge(false)
      },
    )
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = context.getString(R.string.channel_results_detail)
      },
    )
  }

  /**
   * La notifica del lavoro in corso.
   *
   * Porta il tasto per annullare: senza, l'unico modo di fermare una trascrizione partita per
   * sbaglio su un file da un'ora e' aprire l'app e cercarla.
   */
  fun buildProgress(
    context: Context,
    title: String,
    text: String?,
    progress: Float?,
    workId: UUID,
  ): Notification {
    val builder = NotificationCompat.Builder(context, CHANNEL_JOBS)
      .setContentTitle(title)
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setOngoing(true)
      .setSilent(true)
      .setContentIntent(openApp(context))
      .addAction(
        0,
        context.getString(R.string.action_cancel),
        WorkManager.getInstance(context).createCancelPendingIntent(workId),
      )
    text?.let(builder::setContentText)
    if (progress != null) {
      builder.setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
    } else {
      builder.setProgress(0, 0, true)
    }
    return builder.build()
  }

  fun notifyDone(context: Context, jobId: String, wordCount: Int) {
    notify(
      context = context,
      jobId = jobId,
      title = context.getString(R.string.notification_done_title),
      text = context.resources.getQuantityString(R.plurals.notification_done_words, wordCount, wordCount),
    )
  }

  /**
   * Il testo ripulito e' pronto.
   *
   * Quando il conto delle parole si discosta troppo dal grezzo la notifica lo dice: un testo
   * accorciato del quaranta per cento puo' essere un testo pieno di "ehm" ripulito bene, oppure un
   * riassunto, e la differenza la vede solo chi guarda.
   */
  fun notifyRefined(context: Context, jobId: String, wordCount: Int, suspicious: Boolean) {
    notify(
      context = context,
      jobId = jobId,
      title = context.getString(
        if (suspicious) R.string.notification_refined_check else R.string.notification_refined_title,
      ),
      text = context.resources.getQuantityString(R.plurals.notification_done_words, wordCount, wordCount),
    )
  }

  fun notifyFailed(context: Context, jobId: String, errorCode: String) {
    notify(
      context = context,
      jobId = jobId,
      title = context.getString(R.string.notification_failed_title),
      text = context.getString(R.string.notification_failed_text),
    )
  }

  private fun notify(context: Context, jobId: String, title: String, text: String) {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return
    val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
      .setContentTitle(title)
      .setContentText(text)
      .setSmallIcon(android.R.drawable.stat_notify_chat)
      .setAutoCancel(true)
      .setContentIntent(openApp(context))
      .build()
    runCatching { manager.notify(ID_RESULT_BASE + jobId.hashCode().and(0xFFF), notification) }
  }

  private fun openApp(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }
}
