package dev.pampa.pampanotes.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le prese sui file: chi rilascia la sua non libera quella di un altro. */
class FilesInUseTest {

  @Test
  fun `la sessione aperta e l'export tengono lo stesso file ciascuno per se`() {
    val inUse = FilesInUse()
    val session = Any()
    val name = FilesInUse.audio("p1.m4a")
    inUse.hold(listOf(name), ttlMillis = 60_000, now = 0, owner = session)
    inUse.hold(listOf(name), ttlMillis = 60_000, now = 0)
    // L'export finisce: la sessione e' ancora aperta, il file resta tenuto.
    inUse.release(listOf(name))
    assertEquals(setOf(name), inUse.current(now = 1))
    inUse.release(listOf(name), owner = session)
    assertTrue(inUse.current(now = 1).isEmpty())
  }

  @Test
  fun `una presa scade da sola`() {
    val inUse = FilesInUse()
    inUse.hold(listOf("audio/a"), ttlMillis = 10, now = 0, owner = "lettore")
    assertEquals(setOf("audio/a"), inUse.current(now = 5))
    assertTrue(inUse.current(now = 10).isEmpty())
  }
}
