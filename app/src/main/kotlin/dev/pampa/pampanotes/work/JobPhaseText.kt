package dev.pampa.pampanotes.work

import android.content.Context
import android.text.format.DateFormat
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import java.util.Date

/**
 * La fase di un lavoro in parole, fuori da Compose: per la notifica.
 *
 * La stessa lettura di `jobPhaseText` in `ui/common/JobText.kt`, che la schermata Lavori usa. La
 * notifica mostrava il codice cosi' com'e' salvato (`uploading:2/6:40`) — compatto apposta, perche'
 * una frase nel database resterebbe nella lingua in cui l'app girava quando il lavoro e' partito — e
 * sotto «Trascrizione in corso» si leggeva un pezzo di protocollo.
 */
object JobPhaseText {

  fun describe(context: Context, job: JobEntity): String {
    val phase = job.phase ?: return stateLabel(context, job.state)
    val parts = phase.split(':')
    return when (parts.firstOrNull()) {
      "preparing" -> context.getString(R.string.job_phase_preparing, parts.getOrNull(2)?.toIntOrNull() ?: 0)

      "uploading" -> {
        val position = parts.getOrNull(1).orEmpty().split('/')
        context.getString(
          R.string.job_phase_uploading,
          position.getOrNull(0)?.toIntOrNull() ?: 1,
          position.getOrNull(1)?.toIntOrNull() ?: 1,
          parts.getOrNull(2)?.toIntOrNull() ?: 0,
        )
      }

      "transcribing" -> {
        val position = parts.getOrNull(1).orEmpty().split('/')
        context.getString(
          R.string.job_phase_transcribing,
          position.getOrNull(0)?.toIntOrNull() ?: 1,
          position.getOrNull(1)?.toIntOrNull() ?: 1,
        )
      }

      "refining" -> {
        val position = parts.getOrNull(1).orEmpty().split('/')
        context.getString(
          R.string.job_phase_refining,
          position.getOrNull(0)?.toIntOrNull() ?: 1,
          position.getOrNull(1)?.toIntOrNull() ?: 1,
        )
      }

      "waiting" -> context.getString(R.string.job_phase_waiting, parts.getOrNull(1)?.toIntOrNull() ?: 0)
      TranscriptionRepository.PHASE_WAITING_ENDPOINT -> context.getString(R.string.job_phase_endpoint)
      TranscriptionRepository.PHASE_RETRY_AT -> untilText(context, parts.getOrNull(1)?.toLongOrNull())
        ?: stateLabel(context, job.state)
      "stitching" -> context.getString(R.string.job_state_stitching)
      else -> stateLabel(context, job.state)
    }
  }

  /**
   * «In attesa del limite di Groq, fino alle 17:40», nell'ora di chi legge (12 o 24 ore come il
   * telefono). E' pubblica perche' la schermata Lavori la puo' usare cosi' com'e'.
   */
  fun untilText(context: Context, atMillis: Long?): String? {
    if (atMillis == null || atMillis <= 0) return null
    return context.getString(R.string.job_phase_until, DateFormat.getTimeFormat(context).format(Date(atMillis)))
  }

  private fun stateLabel(context: Context, state: JobState): String = context.getString(
    when (state) {
      JobState.QUEUED -> R.string.job_state_queued
      JobState.PREPARING -> R.string.job_state_preparing
      JobState.UPLOADING -> R.string.job_state_uploading
      JobState.TRANSCRIBING -> R.string.job_state_transcribing
      JobState.STITCHING -> R.string.job_state_stitching
      JobState.DONE -> R.string.job_state_done
      JobState.FAILED -> R.string.job_state_failed
      JobState.CANCELLED -> R.string.job_state_cancelled
      JobState.CANCEL_REQUESTED -> R.string.job_state_cancelling
    },
  )
}
