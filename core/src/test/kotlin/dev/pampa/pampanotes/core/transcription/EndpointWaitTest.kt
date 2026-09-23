package dev.pampa.pampanotes.core.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il passo con cui la coda riguarda il computer di casa: corto e fisso, mai un'attesa che raddoppia. */
class EndpointWaitTest {

  @Test
  fun `nella prima mezz'ora si riguarda ogni minuto, poi ogni cinque`() {
    assertEquals(EndpointWait.FAST_DELAY_MS, EndpointWait.nextDelayMs(0))
    assertEquals(EndpointWait.FAST_DELAY_MS, EndpointWait.nextDelayMs(29 * 60_000L))
    assertEquals(EndpointWait.SLOW_DELAY_MS, EndpointWait.nextDelayMs(30 * 60_000L))
    assertEquals(EndpointWait.SLOW_DELAY_MS, EndpointWait.nextDelayMs(8 * 60 * 60_000L))
  }

  @Test
  fun `un PC che si riavvia in tre minuti fa aspettare al piu' un minuto in piu'`() {
    // I tentativi come li farebbe la coda: il primo quando il computer sparisce.
    var waited = 0L
    val attempts = mutableListOf<Long>()
    while (waited < 3 * 60_000L) {
      waited += EndpointWait.nextDelayMs(waited)
      attempts += waited
    }
    val firstAfterReboot = attempts.first { it >= 3 * 60_000L }
    assertTrue(firstAfterReboot - 3 * 60_000L <= 60_000L)
  }

  @Test
  fun `da quando si aspetta - quello portato dal tentativo prima, se ha senso`() {
    assertEquals(1_000L, EndpointWait.waitingSince(carried = 1_000L, now = 5_000L))
    assertEquals(5_000L, EndpointWait.waitingSince(carried = 0L, now = 5_000L))
    // Nel futuro: l'orologio e' stato spostato, si riparte da adesso.
    assertEquals(5_000L, EndpointWait.waitingSince(carried = 9_000L, now = 5_000L))
  }
}
