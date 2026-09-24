package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.db.JobType
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Quali falliti «Riprova tutti» rimanda: non i superati, non i muti, non quelli senza sessione. */
class FailedJobsTest {

  @Test
  fun `un guasto vero si riprova`() {
    val failed = job("j1", createdAt = 100, errorCode = "network")
    assertEquals(FailureStanding.RETRYABLE, FailedJobs.standing(failed, true, emptyList(), listOf(failed)))
  }

  @Test
  fun `una grezza arrivata dopo lo supera, una di prima no`() {
    val failed = job("j1", createdAt = 100)
    assertEquals(FailureStanding.SUPERSEDED, FailedJobs.standing(failed, true, listOf(transcript(TranscriptKind.RAW, 200)), listOf(failed)))
    assertEquals(FailureStanding.RETRYABLE, FailedJobs.standing(failed, true, listOf(transcript(TranscriptKind.RAW, 50)), listOf(failed)))
    // Una raffinata non supera una trascrizione.
    assertEquals(FailureStanding.RETRYABLE, FailedJobs.standing(failed, true, listOf(transcript(TranscriptKind.REFINED, 200)), listOf(failed)))
  }

  @Test
  fun `un raffinamento lo supera solo una raffinata piu' recente`() {
    val failed = job("r1", createdAt = 100, type = JobType.REFINE)
    assertEquals(FailureStanding.RETRYABLE, FailedJobs.standing(failed, true, listOf(transcript(TranscriptKind.RAW, 200)), listOf(failed)))
    assertEquals(FailureStanding.SUPERSEDED, FailedJobs.standing(failed, true, listOf(transcript(TranscriptKind.REFINED, 200)), listOf(failed)))
  }

  @Test
  fun `un lavoro dello stesso tipo partito dopo, finito o in corso, lo supera`() {
    val failed = job("j1", createdAt = 100)
    val running = job("j2", createdAt = 200, state = JobState.TRANSCRIBING)
    val cancelled = job("j3", createdAt = 300, state = JobState.CANCELLED)
    assertEquals(FailureStanding.SUPERSEDED, FailedJobs.standing(failed, true, emptyList(), listOf(failed, running)))
    assertEquals(FailureStanding.RETRYABLE, FailedJobs.standing(failed, true, emptyList(), listOf(failed, cancelled)))
  }

  @Test
  fun `muta e orfana non si riprovano`() {
    val silent = job("j1", createdAt = 100, errorCode = FailedJobs.NO_SPEECH)
    assertEquals(FailureStanding.NO_SPEECH, FailedJobs.standing(silent, true, emptyList(), listOf(silent)))
    assertEquals(FailureStanding.ORPHANED, FailedJobs.standing(silent, false, emptyList(), listOf(silent)))
  }

  @Test
  fun `riprovare una trascrizione muta la rifa' da capo`() {
    // I pezzi vuoti rimasti nella cartella del lavoro facevano fallire «Riprova» senza chiamare nessuno.
    assertEquals(true, FailedJobs.discardsWorkOnRetry(job("j1", createdAt = 100, errorCode = FailedJobs.NO_SPEECH)))
    // Un guasto vero riparte dai pezzi gia' fatti, e un raffinamento non ha pezzi.
    assertEquals(false, FailedJobs.discardsWorkOnRetry(job("j2", createdAt = 100, errorCode = "network")))
    assertEquals(false, FailedJobs.discardsWorkOnRetry(job("r1", createdAt = 100, type = JobType.REFINE, errorCode = FailedJobs.NO_SPEECH)))
  }

  private fun job(
    id: String,
    createdAt: Long,
    type: JobType = JobType.TRANSCRIBE,
    state: JobState = JobState.FAILED,
    errorCode: String? = "network",
  ) = JobEntity(
    id = id,
    sessionId = "s1",
    type = type,
    provider = "custom",
    state = state,
    errorCode = errorCode.takeIf { state == JobState.FAILED },
    createdAt = createdAt,
    updatedAt = createdAt,
  )

  private fun transcript(kind: TranscriptKind, createdAt: Long) = TranscriptEntity(
    id = "t$createdAt$kind",
    sessionId = "s1",
    kind = kind,
    provider = "custom",
    model = "large-v3",
    text = "",
    wordCount = 0,
    createdAt = createdAt,
  )
}
