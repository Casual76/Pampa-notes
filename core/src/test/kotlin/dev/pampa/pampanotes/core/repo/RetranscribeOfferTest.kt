package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.db.JobType
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class RetranscribeOfferTest {

  private val zone = ZoneId.of("Europe/Rome")
  private val from = RetranscribeOffer.fromMillis(zone)
  private fun at(day: Int, hour: Int = 10) = LocalDateTime.of(2026, 9, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

  /** Il companion nuovo e' stato visto la prima volta il 27/09 alle 18. */
  private val holesSince = at(27, 18)

  private fun raw(session: String, createdAt: Long, provider: String = "custom", id: String = "r-$session-$createdAt") =
    RawStamp(id, session, provider, createdAt)

  private fun job(
    session: String,
    createdAt: Long,
    state: JobState,
    errorCode: String? = null,
    type: JobType = JobType.TRANSCRIBE,
  ) = JobEntity(
    id = "j-$session-$createdAt",
    sessionId = session,
    type = type,
    provider = "custom",
    state = state,
    errorCode = errorCode,
    createdAt = createdAt,
    updatedAt = createdAt,
  )

  private fun candidates(raws: List<RawStamp>, jobs: List<JobEntity> = emptyList(), busy: Set<String> = emptySet(), since: Long = holesSince) =
    RetranscribeOffer.candidates(raws, jobs, busy, from, since)

  @Test
  fun `il confine comincia la mezzanotte del 24 settembre, ora di casa`() {
    assertEquals(LocalDateTime.of(2026, 9, 24, 0, 0).atZone(zone).toInstant().toEpochMilli(), from)
  }

  @Test
  fun `le lezioni del computer fra il 24 e il companion nuovo, in ordine`() {
    val raws = listOf(
      raw("dopo", at(25)),
      raw("prima", at(24, 9)),
      raw("troppo-presto", at(23, 23)),
      raw("gia-nuovo", at(27, 19)),
      raw("groq", at(25), provider = "groq"),
    )
    assertEquals(listOf("prima", "dopo"), candidates(raws))
  }

  @Test
  fun `senza il companion nuovo non c'e' niente da offrire`() {
    assertEquals(emptyList<String>(), candidates(listOf(raw("a", at(25))), since = 0L))
  }

  @Test
  fun `conta la grezza di adesso, la piu' recente`() {
    // Ritrascritta dopo la correzione: la grezza nuova e' quella che vale, e la sessione e' a posto.
    val redone = listOf(raw("a", at(25)), raw("a", at(28)))
    assertEquals(emptyList<String>(), candidates(redone))
    // Ritrascritta con Groq il 26: e' la grezza di Groq quella di adesso, e il computer non c'entra.
    val byGroq = listOf(raw("b", at(25)), raw("b", at(26), provider = "groq"))
    assertEquals(emptyList<String>(), candidates(byGroq))
  }

  @Test
  fun `fuori le mute, quelle in fila o al lavoro, e quelle che un altro dispositivo sta facendo`() {
    val raws = listOf(raw("muta", at(25)), raw("in-fila", at(25)), raw("altrove", at(25)), raw("buona", at(25)))
    val jobs = listOf(
      job("muta", at(25, 9), JobState.DONE),
      job("muta", at(26), JobState.FAILED, errorCode = FailedJobs.NO_SPEECH),
      job("in-fila", at(28), JobState.QUEUED),
      // Un fallimento qualsiasi non la esclude: la grezza di prima e' ancora quella bucata.
      job("buona", at(26), JobState.FAILED, errorCode = "network"),
    )
    assertEquals(listOf("buona"), candidates(raws, jobs, busy = setOf("altrove")))
  }

  @Test
  fun `conta l'ultima trascrizione, non un raffinamento venuto dopo`() {
    val jobs = listOf(
      job("a", at(26), JobState.FAILED, errorCode = FailedJobs.NO_SPEECH),
      job("a", at(27), JobState.FAILED, errorCode = "network", type = JobType.REFINE),
    )
    assertEquals(emptyList<String>(), candidates(listOf(raw("a", at(25))), jobs))
  }
}
