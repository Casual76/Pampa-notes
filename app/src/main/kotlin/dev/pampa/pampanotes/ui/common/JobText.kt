package dev.pampa.pampanotes.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidIndeterminateBar
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.core.transcription.JobPhase
import dev.pampa.pampanotes.core.transcription.RemoteStage
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

/**
 * La riga che dice a che punto e': «Registrazione 2 di 6 · trascrivo · 70%», «In coda sul
 * computer: sei il 2°». La lettura e' una sola, in [JobPhaseText], condivisa con la notifica.
 */
@Composable
fun jobPhaseText(job: JobEntity): String {
  // Letta per ricomporre quando cambia la lingua, come fa `stringResource`.
  LocalConfiguration.current
  return JobPhaseText.describe(LocalContext.current, job)
}

/**
 * Le barre di un lavoro in corso: sopra l'intera sessione, sotto il passo che si sta facendo.
 *
 * Il passo ha una barra sua perche' la barra della sessione, su sei registrazioni, si muove di un
 * sesto per registrazione: «trascrivo 70%» con la barra grande ferma sembrava un'app bloccata. Dove
 * il passo non si misura — il computer carica il modello, e' in fila, Groq sta rispondendo — la
 * seconda barra scorre invece di fingere un numero. Nessuna seconda barra per un lavoro che non e'
 * ancora partito o che aspetta ore: li' non sta succedendo niente.
 */
@Composable
fun JobProgressBars(job: JobEntity, modifier: Modifier = Modifier) {
  val phase = JobPhase.parse(job.phase)
  val step = phase?.stepPercent
  val running = job.state in RUNNING_STATES
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    FluidProgressBar(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
    when {
      !running -> Unit
      step != null -> FluidProgressBar(
        progress = { step / 100f },
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.fillMaxWidth(),
      )
      phase.isUnmeasuredWork() -> FluidIndeterminateBar(
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

private val RUNNING_STATES = setOf(JobState.PREPARING, JobState.UPLOADING, JobState.TRANSCRIBING, JobState.STITCHING)

/** Qualcuno sta lavorando, ma non dice quanto manca. */
private fun JobPhase?.isUnmeasuredWork(): Boolean = when (this) {
  is JobPhase.Uploading -> awaiting
  is JobPhase.Remote -> stage in setOf(RemoteStage.RECEIVED, RemoteStage.QUEUED, RemoteStage.DECODING, RemoteStage.LOADING_MODEL, RemoteStage.DONE)
  is JobPhase.Transcribing, JobPhase.Stitching -> true
  else -> false
}

/**
 * Cosa e' andato storto, detto a chi deve rimediarci.
 *
 * Il messaggio del server resta fuori: e' in inglese, e per un 401 dice "Invalid API Key" quando
 * quello che serve sapere e' "vai nelle impostazioni e rimetti la chiave".
 */
@Composable
fun jobErrorText(code: String, rawMessage: String?, provider: String? = null): String =
  stringResource(jobErrorRes(code, provider))

/**
 * La frase per un codice d'errore, fuori da Compose (la notifica la usa uguale). `unauthorized`
 * dipende da chi l'ha detto: per Groq e' la chiave, per il computer di casa l'account o il codice —
 * «rimetti la chiave» a chi non ne ha mai messa una manda a cercare la cosa sbagliata.
 */
@StringRes
fun jobErrorRes(code: String, provider: String? = null): Int = when (code) {
  "unauthorized" -> if (provider == TranscriptionProviderId.CUSTOM.id) R.string.error_unauthorized_computer else R.string.error_unauthorized
  "rate_limited" -> R.string.error_rate_limited
  "file_too_large" -> R.string.error_file_too_large
  "server" -> R.string.error_server
  "network" -> R.string.error_network
  "timeout" -> R.string.error_timeout
  "decode" -> R.string.error_decode
  "parse" -> R.string.error_parse
  "no_speech" -> R.string.error_no_speech
  "unknown_model" -> R.string.error_unknown_model
  "computer_lost" -> R.string.error_computer_lost
  "cancelled" -> R.string.job_state_cancelled
  else -> R.string.error_generic
}

/** «In coda», «Trascrizione · 42%»: lo stato, e quanto manca quando lo si sa. */
@Composable
fun jobBadgeLabel(job: JobEntity): String {
  val state = jobStateLabel(job.state)
  val percent = (job.progress * 100).toInt()
  return if (job.state.isRunning && percent in 1..99) stringResource(R.string.home_job_progress, state, percent) else state
}
