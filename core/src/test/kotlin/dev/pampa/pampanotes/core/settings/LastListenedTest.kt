package dev.pampa.pampanotes.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastListenedTest {

  @Test
  fun `si legge com'e' stata scritta`() {
    val value = LastListened(sessionId = "abc-123", positionMs = 1_930_000, at = 1_790_164_800_000, durationMs = 3_900_000)

    assertEquals(value, LastListened.decode(value.encode()))
  }

  @Test
  fun `una voce rotta non e' una voce`() {
    assertNull(LastListened.decode(null))
    assertNull(LastListened.decode(""))
    assertNull(LastListened.decode("abc|non-un-numero|1"))
    assertNull(LastListened.decode("|10|20"))
    // Senza durata (una versione con tre campi): si legge, con la durata a zero.
    assertEquals(LastListened("abc", 10, 20, 0), LastListened.decode("abc|10|20"))
  }

  @Test
  fun `finita oltre il 95 per cento`() {
    assertTrue(LastListened("s", positionMs = 960, at = 0, durationMs = 1_000).finished)
    assertFalse(LastListened("s", positionMs = 940, at = 0, durationMs = 1_000).finished)
    // Senza durata non si sa: non e' finita.
    assertFalse(LastListened("s", positionMs = 5_000, at = 0, durationMs = 0).finished)
  }
}
