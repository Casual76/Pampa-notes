package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.db.JobType
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind

/** Che cosa vale ancora un lavoro fallito. */
enum class FailureStanding {
  /** Un guasto vero, e niente l'ha rimediato: «Riprova» serve. */
  RETRYABLE,

  /**
   * Dopo il fallimento la sessione ha avuto quello che il lavoro doveva darle: una grezza (o una
   * raffinata) piu' recente, arrivata da un altro tentativo o da un altro dispositivo col sync, o un
   * lavoro dello stesso tipo partito dopo e finito o ancora in corso. Riprovarlo rifarebbe una
   * lezione gia' fatta — e per una trascrizione porterebbe via le raffinate della grezza nuova.
   */
  SUPERSEDED,

  /** La registrazione non ha parole: rifarla da' la stessa risposta, non e' un guasto. */
  NO_SPEECH,

  /** La sessione non c'e' piu': non c'e' niente da rifare. */
  ORPHANED,
}

/**
 * Quali falliti meritano «Riprova tutti»: la pagina Lavori li contava tutti, e riprovava anche una
 * lezione ritrascritta nel frattempo, una registrazione muta e il lavoro di una sessione cancellata.
 * Puro, per provarlo in JVM: chi chiama da' i fatti della sessione.
 */
object FailedJobs {
  const val NO_SPEECH = "no_speech"

  /**
   * @param sessionExists la sessione del lavoro c'e' ancora.
   * @param transcripts le trascrizioni della sessione.
   * @param sessionJobs gli altri lavori della sessione (il lavoro stesso puo' esserci o no).
   */
  fun standing(
    job: JobEntity,
    sessionExists: Boolean,
    transcripts: List<TranscriptEntity>,
    sessionJobs: List<JobEntity>,
  ): FailureStanding {
    if (!sessionExists) return FailureStanding.ORPHANED
    val producedKind = if (job.type == JobType.REFINE) TranscriptKind.REFINED else TranscriptKind.RAW
    val newerResult = transcripts.any { it.kind == producedKind && it.createdAt > job.createdAt }
    val newerJob = sessionJobs.any { other ->
      other.id != job.id && other.type == job.type && other.createdAt > job.createdAt &&
        (other.state == JobState.DONE || other.state.isActive)
    }
    if (newerResult || newerJob) return FailureStanding.SUPERSEDED
    if (job.errorCode == NO_SPEECH) return FailureStanding.NO_SPEECH
    return FailureStanding.RETRYABLE
  }
}
