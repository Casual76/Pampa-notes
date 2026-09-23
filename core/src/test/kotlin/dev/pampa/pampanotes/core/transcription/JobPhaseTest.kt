package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il codice della fase: quello che il worker scrive e' quello che notifica e schermate leggono. */
class JobPhaseTest {

  private fun roundTrip(phase: JobPhase) = assertEquals(phase, JobPhase.parse(phase.encode()))

  @Test
  fun `ogni fase si rilegge com'era`() {
    roundTrip(JobPhase.Preparing(2, 6, 40))
    roundTrip(JobPhase.Uploading(1, 3, 45, part = 2, parts = 6))
    roundTrip(JobPhase.Transcribing(3, 3, part = 4, parts = 6))
    roundTrip(JobPhase.Remote(RemoteStage.QUEUED, 2, 6, 0, position = 2))
    roundTrip(JobPhase.Remote(RemoteStage.TRANSCRIBING, 2, 6, 70, chunk = 1, chunks = 3, etaSeconds = 185, device = "cuda"))
    roundTrip(JobPhase.Remote(RemoteStage.ALIGNING, 1, 1, 32))
    roundTrip(JobPhase.Refining(2, 5))
    roundTrip(JobPhase.Waiting(42))
    roundTrip(JobPhase.Endpoint)
    roundTrip(JobPhase.Until(1_758_634_800_000))
    roundTrip(JobPhase.Stitching)
    roundTrip(JobPhase.NeedsApp)
    roundTrip(JobPhase.Elsewhere("Tab S9"))
  }

  @Test
  fun `il nome del dispositivo altrove si rilegge intero, due punti compresi`() {
    assertEquals(JobPhase.Elsewhere("PC: studio"), JobPhase.parse(JobPhase.Elsewhere("PC: studio").encode()))
    assertNull(JobPhase.parse("elsewhere:"))
  }

  @Test
  fun `il formato del computer e' quello concordato`() {
    assertEquals(
      "remote:transcribing:2/6:70::1/1::",
      JobPhase.Remote(RemoteStage.TRANSCRIBING, 2, 6, 70).encode(),
    )
    assertEquals(
      JobPhase.Remote(RemoteStage.QUEUED, 1, 1, 0, position = 2),
      JobPhase.parse("remote:queued:1/1:0:2"),
    )
  }

  @Test
  fun `le fasi scritte dalla versione di prima si leggono ancora`() {
    // Un lavoro partito prima dell'aggiornamento ha ancora la fase vecchia nella riga.
    assertEquals(JobPhase.Uploading(2, 6, 40), JobPhase.parse("uploading:2/6:40"))
    assertEquals(JobPhase.Transcribing(3, 7), JobPhase.parse("transcribing:3/7"))
    assertEquals(JobPhase.Preparing(1, 2, 30), JobPhase.parse("preparing:1/2:30"))
    // Il vecchio «pezzo 0 di 1» della via breve diventa 1 di 1, non uno zero sullo schermo.
    assertEquals(JobPhase.Uploading(1, 1, 80), JobPhase.parse("uploading:0/1:80"))
  }

  @Test
  fun `quello che non si capisce e' null, e chi legge ripiega sullo stato`() {
    assertNull(JobPhase.parse(null))
    assertNull(JobPhase.parse(""))
    assertNull(JobPhase.parse("boh:1"))
    assertNull(JobPhase.parse("remote:sta-ballando:1/1:0"))
    assertNull(JobPhase.parse("until:domani"))
  }

  @Test
  fun `le fasi del repository sono le stesse`() {
    assertEquals(TranscriptionRepository.PHASE_WAITING_ENDPOINT, JobPhase.Endpoint.encode())
    assertTrue(JobPhase.Until(5).encode().startsWith(TranscriptionRepository.PHASE_RETRY_AT + ":"))
  }

  @Test
  fun `la seconda barra c'e' solo quando il passo si misura`() {
    assertEquals(70, JobPhase.Remote(RemoteStage.TRANSCRIBING, 1, 1, 70).stepPercent)
    assertNull(JobPhase.Remote(RemoteStage.LOADING_MODEL, 1, 1, 0).stepPercent)
    assertNull(JobPhase.Uploading(1, 1, 100).stepPercent)
    assertTrue(JobPhase.Uploading(1, 1, 100).awaiting)
    assertEquals(45, JobPhase.Uploading(1, 1, 45).stepPercent)
  }
}
