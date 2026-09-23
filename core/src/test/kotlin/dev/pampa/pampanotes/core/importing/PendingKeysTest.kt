package dev.pampa.pampanotes.core.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Una nota che fa fallire il giro delle date vere si riprova, ma non per sempre. */
class PendingKeysTest {

  @Test
  fun `un fallimento resta in attesa col suo conto`() {
    val first = PendingKeys.retry("n:abc", null)
    assertEquals("!1!n:abc", first)
    assertEquals("n:abc" to 1, PendingKeys.failed(first!!))
    assertEquals("!2!n:abc", PendingKeys.retry("n:abc", 1))
  }

  @Test
  fun `dopo tre giri falliti si lascia stare`() {
    assertNull(PendingKeys.retry("n:abc", PendingKeys.MAX_ATTEMPTS - 1))
  }

  @Test
  fun `una voce che aspetta il computer non e' un fallimento`() {
    assertNull(PendingKeys.failed("n:abc"))
    assertNull(PendingKeys.failed("s:def"))
  }
}
