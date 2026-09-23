package dev.pampa.pampanotes.work

import android.content.Context
import android.text.format.DateFormat
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.transcription.GroqWhisperProvider
import dev.pampa.pampanotes.core.transcription.JobPhase
import dev.pampa.pampanotes.core.transcription.RemoteStage
import java.util.Date

/**
 * La fase di un lavoro in parole: una lettura sola, per la notifica e per le schermate.
 *
 * Il codice salvato (`remote:transcribing:2/6:70`, vedi [JobPhase]) e' compatto apposta — una frase
 * nel database resterebbe nella lingua in cui l'app girava quando il lavoro e' partito — e qui
 * diventa «Registrazione 2 di 6 · trascrivo · 70% · ancora 3 min». La schermata Lavori, la nota e
 * la sessione passano da `jobPhaseText`, che chiama questa: prima erano due copie, e la notifica
 * mostrava il codice cosi' com'era.
 *
 * La frase si compone a pezzi separati da « · »: quale registrazione (solo se sono piu' d'una),
 * quale pezzo (solo se la registrazione e' stata tagliata), cosa si sta facendo, quanto manca. Un
 * pezzo che non dice niente non si scrive: «Registrazione 1 di 1» e' rumore.
 */
object JobPhaseText {

  fun describe(context: Context, job: JobEntity): String {
    // «Annulla» toccato: la fase di prima resta scritta finche' il worker non se ne accorge, ma
    // quello che conta adesso e' che si sta fermando.
    if (job.state == JobState.CANCEL_REQUESTED) return stateLabel(context, job.state)
    val phase = JobPhase.parse(job.phase) ?: return stateLabel(context, job.state)
    return when (phase) {
      is JobPhase.Preparing -> join(
        partLabel(context, phase.part, phase.parts),
        context.getString(R.string.job_phase_step_preparing, phase.percent),
      )

      is JobPhase.Uploading -> uploading(context, job, phase)

      is JobPhase.Transcribing -> join(
        partLabel(context, phase.part, phase.parts),
        if (phase.chunks > 1) {
          context.getString(R.string.job_phase_step_chunk_done, phase.chunk, phase.chunks)
        } else {
          context.getString(R.string.job_phase_step_part_done)
        },
      )

      is JobPhase.Remote -> remote(context, phase)

      is JobPhase.Refining -> context.getString(R.string.job_phase_refining, phase.chunk, phase.chunks)
      is JobPhase.Waiting -> context.getString(R.string.job_phase_waiting, phase.seconds)
      JobPhase.Endpoint -> context.getString(R.string.job_phase_endpoint)
      is JobPhase.Until -> untilText(context, phase.atMillis) ?: stateLabel(context, job.state)
      JobPhase.Stitching -> context.getString(R.string.job_phase_stitching)
    }
  }

  /**
   * «Carico la registrazione 2 di 6 · 45%»; a caricamento finito, se il servizio non racconta
   * niente (Groq, un companion vecchio), «Registrazione 2 di 6 · Groq trascrive»: una barra ferma
   * al 100% del caricamento sembrava un'app bloccata.
   */
  private fun uploading(context: Context, job: JobEntity, phase: JobPhase.Uploading): String {
    val chunk = chunkLabel(context, phase.chunk, phase.chunks)
    if (phase.awaiting) {
      val who = if (job.provider == GroqWhisperProvider.ID) R.string.job_phase_awaiting_groq else R.string.job_phase_awaiting_computer
      return join(partLabel(context, phase.part, phase.parts), chunk, context.getString(who))
    }
    val percent = context.getString(R.string.job_phase_percent, phase.percent)
    return when {
      phase.parts > 1 -> join(context.getString(R.string.job_phase_upload_part, phase.part, phase.parts), chunk, percent)
      phase.chunks > 1 -> join(context.getString(R.string.job_phase_upload_chunk, phase.chunk, phase.chunks), percent)
      else -> join(context.getString(R.string.job_phase_upload_audio), percent)
    }
  }

  private fun remote(context: Context, phase: JobPhase.Remote): String {
    val step = when (phase.stage) {
      RemoteStage.RECEIVED -> context.getString(R.string.job_phase_remote_received)
      RemoteStage.QUEUED -> phase.position?.takeIf { it > 1 }
        ?.let { context.getString(R.string.job_phase_remote_queued_position, it) }
        ?: context.getString(R.string.job_phase_remote_queued)
      RemoteStage.DECODING -> context.getString(R.string.job_phase_remote_decoding)
      RemoteStage.LOADING_MODEL -> context.getString(R.string.job_phase_remote_loading)
      RemoteStage.TRANSCRIBING -> context.getString(R.string.job_phase_remote_transcribing, phase.percent)
      RemoteStage.ALIGNING -> context.getString(R.string.job_phase_remote_aligning, phase.percent)
      RemoteStage.DONE -> context.getString(R.string.job_phase_remote_done)
      RemoteStage.FAILED -> context.getString(R.string.job_phase_remote_failed)
    }
    // Sul processore la stessa lezione dura dieci volte tanto: se l'attesa e' lunga, si sa perche'.
    val cpu = context.getString(R.string.job_phase_remote_cpu)
      .takeIf { phase.device == "cpu" && phase.stage in WORKING }
    val eta = phase.etaSeconds?.takeIf { phase.stage in WORKING }?.let { etaText(context, it) }
    return join(partLabel(context, phase.part, phase.parts), chunkLabel(context, phase.chunk, phase.chunks), step, cpu, eta)
  }

  private val WORKING = setOf(RemoteStage.LOADING_MODEL, RemoteStage.TRANSCRIBING, RemoteStage.ALIGNING)

  /** «ancora 40 s», «ancora 3 min»: arrotondato in su, perche' «ancora 0 min» con la barra ferma e' peggio. */
  fun etaText(context: Context, seconds: Int): String = if (seconds < 60) {
    context.getString(R.string.job_phase_eta_seconds, seconds.coerceAtLeast(1))
  } else {
    context.getString(R.string.job_phase_eta_minutes, (seconds + 59) / 60)
  }

  private fun partLabel(context: Context, part: Int, parts: Int): String? =
    if (parts > 1) context.getString(R.string.job_phase_part, part, parts) else null

  private fun chunkLabel(context: Context, chunk: Int, chunks: Int): String? =
    if (chunks > 1) context.getString(R.string.job_phase_chunk, chunk, chunks) else null

  /** I pezzi in fila, e la frase che comincia con la maiuscola qualunque pezzo arrivi primo. */
  private fun join(vararg pieces: String?): String =
    pieces.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ").replaceFirstChar { it.uppercaseChar() }

  /**
   * «In attesa del limite di Groq, fino alle 17:40», nell'ora di chi legge (12 o 24 ore come il
   * telefono). E' pubblica perche' la schermata Lavori la puo' usare cosi' com'e'.
   */
  fun untilText(context: Context, atMillis: Long?): String? {
    if (atMillis == null || atMillis <= 0) return null
    return context.getString(R.string.job_phase_until, DateFormat.getTimeFormat(context).format(Date(atMillis)))
  }

  fun stateLabel(context: Context, state: JobState): String = context.getString(
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
