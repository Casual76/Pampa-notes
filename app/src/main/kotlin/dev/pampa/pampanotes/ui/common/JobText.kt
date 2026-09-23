package dev.pampa.pampanotes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.work.JobPhaseText

/**
 * Le frasi che descrivono un lavoro.
 *
 * Il worker salva la fase come un codice compatto (`uploading:2/6:40`) invece che come frase gia'
 * fatta: una frase salvata nel database sarebbe nella lingua in cui girava l'app quando il lavoro e'
 * partito, e resterebbe li' anche dopo che l'utente ha cambiato lingua.
 */
@Composable
fun jobStateLabel(state: JobState): String = stringResource(
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

/** La riga che dice a che punto e': "Pezzo 2 di 6", "Preparazione 40%". */
@Composable
fun jobPhaseText(job: JobEntity): String {
  val phase = job.phase ?: return jobStateLabel(job.state)
  val parts = phase.split(':')
  return when (parts.firstOrNull()) {
    "preparing" -> {
      val percent = parts.getOrNull(2)?.toIntOrNull() ?: 0
      stringResource(R.string.job_phase_preparing, percent)
    }

    "uploading" -> {
      val position = parts.getOrNull(1).orEmpty().split('/')
      stringResource(
        R.string.job_phase_uploading,
        position.getOrNull(0)?.toIntOrNull() ?: 1,
        position.getOrNull(1)?.toIntOrNull() ?: 1,
        parts.getOrNull(2)?.toIntOrNull() ?: 0,
      )
    }

    "transcribing" -> {
      val position = parts.getOrNull(1).orEmpty().split('/')
      stringResource(
        R.string.job_phase_transcribing,
        position.getOrNull(0)?.toIntOrNull() ?: 1,
        position.getOrNull(1)?.toIntOrNull() ?: 1,
      )
    }

    "refining" -> {
      val position = parts.getOrNull(1).orEmpty().split('/')
      stringResource(
        R.string.job_phase_refining,
        position.getOrNull(0)?.toIntOrNull() ?: 1,
        position.getOrNull(1)?.toIntOrNull() ?: 1,
      )
    }

    "waiting" -> stringResource(R.string.job_phase_waiting, parts.getOrNull(1)?.toIntOrNull() ?: 0)
    "endpoint" -> stringResource(R.string.job_phase_endpoint)
    // Groq ha chiesto di aspettare piu' di quanto valga la pena tenere il lavoro aperto: e' tornato
    // in coda, e riparte da solo a quell'ora.
    "until" -> JobPhaseText.untilText(LocalContext.current, parts.getOrNull(1)?.toLongOrNull()) ?: jobStateLabel(job.state)
    "stitching" -> stringResource(R.string.job_state_stitching)
    else -> jobStateLabel(job.state)
  }
}

/**
 * Cosa e' andato storto, detto a chi deve rimediarci.
 *
 * Il messaggio del server resta fuori: e' in inglese, e per un 401 dice "Invalid API Key" quando
 * quello che serve sapere e' "vai nelle impostazioni e rimetti la chiave".
 */
@Composable
fun jobErrorText(code: String, rawMessage: String?): String = stringResource(
  when (code) {
    "unauthorized" -> R.string.error_unauthorized
    "rate_limited" -> R.string.error_rate_limited
    "file_too_large" -> R.string.error_file_too_large
    "server" -> R.string.error_server
    "network" -> R.string.error_network
    "timeout" -> R.string.error_timeout
    "decode" -> R.string.error_decode
    "parse" -> R.string.error_parse
    "no_speech" -> R.string.error_no_speech
    "unknown_model" -> R.string.error_unknown_model
    "cancelled" -> R.string.job_state_cancelled
    else -> R.string.error_generic
  },
)
