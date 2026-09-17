package dev.pampa.pampanotes.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.pampa.pampanotes.R

/** I canali di notifica: uno per i lavori in corso (silenzioso), uno per gli esiti. */
object AppNotifications {
  const val CHANNEL_JOBS = "jobs"
  const val CHANNEL_RESULTS = "results"

  const val ID_TRANSCRIPTION_FOREGROUND = 1001
  const val ID_REFINEMENT_FOREGROUND = 1002
  const val ID_RESULT_BASE = 2000

  fun createChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(
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
}
