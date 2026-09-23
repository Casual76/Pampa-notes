package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.sync.ComputerMerge.Decision
import dev.pampa.pampanotes.core.sync.ComputerMerge.Local
import org.junit.Assert.assertEquals
import org.junit.Test

class ComputerMergeTest {

  private fun remote(at: Long, url: String = "http://192.168.1.10:8765") = AccountComputer(url = url, updatedAt = at)

  @Test
  fun `niente da nessuna parte, niente da fare`() {
    assertEquals(Decision.NOTHING, ComputerMerge.decide(Local(hasEndpoint = false, updatedAt = 0, dirty = false), null))
  }

  @Test
  fun `un telefono nuovo senza computer prende quello dell'account`() {
    assertEquals(Decision.APPLY, ComputerMerge.decide(Local(hasEndpoint = false, updatedAt = 0, dirty = false), remote(at = 1_000)))
  }

  @Test
  fun `un computer configurato prima che seguisse l'account sale, anche senza una data`() {
    assertEquals(Decision.PUSH, ComputerMerge.decide(Local(hasEndpoint = true, updatedAt = 0, dirty = false), null))
  }

  @Test
  fun `ma se l'account ne ha gia' uno, vince l'account`() {
    assertEquals(Decision.APPLY, ComputerMerge.decide(Local(hasEndpoint = true, updatedAt = 0, dirty = false), remote(at = 1_000)))
  }

  @Test
  fun `una modifica nata qui sale se e' la piu' recente`() {
    assertEquals(Decision.PUSH, ComputerMerge.decide(Local(hasEndpoint = true, updatedAt = 2_000, dirty = true), remote(at = 1_000)))
    assertEquals(Decision.PUSH, ComputerMerge.decide(Local(hasEndpoint = true, updatedAt = 2_000, dirty = true), null))
  }

  @Test
  fun `una modifica nata qui perde contro una piu' recente dell'account, e a pari orologio`() {
    assertEquals(Decision.APPLY, ComputerMerge.decide(Local(hasEndpoint = true, updatedAt = 1_000, dirty = true), remote(at = 2_000)))
    assertEquals(Decision.APPLY, ComputerMerge.decide(Local(hasEndpoint = true, updatedAt = 2_000, dirty = true), remote(at = 2_000)))
  }

  @Test
  fun `una cancellazione nata qui sale come riga vuota, e scende sugli altri`() {
    assertEquals(Decision.PUSH, ComputerMerge.decide(Local(hasEndpoint = false, updatedAt = 3_000, dirty = true), remote(at = 1_000)))
    assertEquals(Decision.PUSH, ComputerMerge.decide(Local(hasEndpoint = false, updatedAt = 3_000, dirty = true), null))
    assertEquals(Decision.APPLY, ComputerMerge.decide(Local(hasEndpoint = true, updatedAt = 1_000, dirty = false), remote(at = 3_000, url = "")))
  }

  @Test
  fun `pulito e allineato non si muove, pulito e piu' nuovo dell'account si rimanda`() {
    assertEquals(Decision.NOTHING, ComputerMerge.decide(Local(hasEndpoint = true, updatedAt = 2_000, dirty = false), remote(at = 2_000)))
    assertEquals(Decision.PUSH, ComputerMerge.decide(Local(hasEndpoint = true, updatedAt = 3_000, dirty = false), remote(at = 2_000)))
  }
}
