package dev.pampa.pampanotes.core.transcription

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Il tetto di silenzio: ferma un lavoro muto, non uno lento che continua a dare segni di vita. */
class IdleTimeoutTest {

  private fun TestScope.clock(): () -> Long = { testScheduler.currentTime }

  @Test
  fun `un lavoro lungo che parla arriva in fondo`() = runTest {
    val result = withIdleTimeout(idleMs = 60_000, capMs = 10 * 60 * 60_000L, clock = clock()) { touch ->
      // Tre ore di lavoro, un segno di vita ogni trenta secondi: un tetto fisso di tre ore lo uccideva.
      repeat(3 * 60 * 2) {
        delay(30_000)
        touch()
      }
      "fatto"
    }
    assertEquals("fatto", result)
  }

  @Test
  fun `un lavoro muto si ferma dopo il silenzio`() = runTest {
    var cancelled = false
    try {
      withIdleTimeout(idleMs = 60_000, capMs = 10 * 60 * 60_000L, clock = clock()) { touch ->
        touch()
        try {
          awaitCancellation()
        } finally {
          cancelled = true
        }
      }
      fail("doveva scadere")
    } catch (expired: IdleTimeoutException) {
      assertFalse(expired.capReached)
    }
    assertTrue("il lavoro va annullato, cosi' chiude le sue connessioni", cancelled)
    assertTrue(testScheduler.currentTime in 60_000L..70_000L)
  }

  @Test
  fun `chi parla ma non finisce mai si ferma al tetto`() = runTest {
    try {
      withIdleTimeout(idleMs = 60_000, capMs = 5 * 60_000L, clock = clock()) { touch ->
        while (true) {
          delay(10_000)
          touch()
        }
      }
    } catch (expired: IdleTimeoutException) {
      assertTrue(expired.capReached)
    }
  }

  @Test
  fun `un annullamento da fuori resta un annullamento`() = runTest {
    var outcome: Throwable? = null
    val job = launch {
      try {
        withIdleTimeout(idleMs = 60_000, capMs = 5 * 60_000L, clock = clock()) { awaitCancellation() }
      } catch (error: Throwable) {
        outcome = error
        throw error
      }
    }
    delay(1_000)
    job.cancel()
    job.join()
    assertTrue("era $outcome", outcome is CancellationException)
  }
}
