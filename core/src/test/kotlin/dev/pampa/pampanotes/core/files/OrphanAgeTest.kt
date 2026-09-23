package dev.pampa.pampanotes.core.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanAgeTest {

  private val now = 1_760_000_000_000L

  @Test
  fun `un file appena scritto non e' un orfano, anche se nessuna riga lo cita ancora`() {
    // E' il file di un import in corso: copiato al suo posto, con la riga che arriva fra un attimo.
    assertFalse(AppFiles.isOldEnough(now - 60_000, now))
    assertFalse(AppFiles.isOldEnough(now - AppFiles.ORPHAN_MIN_AGE_MS + 1, now))
  }

  @Test
  fun `passato il quarto d'ora si puo' togliere`() {
    assertTrue(AppFiles.isOldEnough(now - AppFiles.ORPHAN_MIN_AGE_MS, now))
    assertTrue(AppFiles.isOldEnough(now - 86_400_000, now))
  }

  @Test
  fun `una data nel futuro o illeggibile vale come giovane`() {
    assertFalse(AppFiles.isOldEnough(now + 3_600_000, now))
    assertFalse(AppFiles.isOldEnough(0, now))
  }
}
