package dev.pampa.pampanotes.core.files

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Prepared source coverage for owner identity and release generations, with no wall-clock wait. */
class AppFileWriteProtectionRoundThreeTest {
  @get:Rule val temp = TemporaryFolder()

  @Test fun anotherOwnerCannotReleaseACanonicalPathAndLastReleaseKeepsAnOldSnapshotProtected() {
    val directory = temp.newFolder("audio")
    val file = File(directory, "shared.bin").also { it.writeText("verified bytes") }
    val alias = File(directory, "../audio/shared.bin")
    // Equal-valued owners still have separate lifetimes.
    val a = String(charArrayOf('a'))
    val b = String(charArrayOf('a'))
    val stale = AppFileWriteProtection.beginSweep()
    try {
      AppFileWriteProtection.hold(file, a)
      AppFileWriteProtection.hold(alias, b)
      AppFileWriteProtection.release(Any())
      AppFileWriteProtection.release(a)
      assertFalse(AppFileWriteProtection.heldBy(file, a))
      assertTrue(AppFileWriteProtection.heldBy(file, b))
      assertFalse(AppFileWriteProtection.deleteIfUnprotected(file, stale) { true })
      AppFileWriteProtection.release(b)
      assertFalse(AppFileWriteProtection.deleteIfUnprotected(file, stale) { true })
      assertEquals("verified bytes", file.readText())
    } finally {
      AppFileWriteProtection.release(a)
      AppFileWriteProtection.release(b)
      stale.close()
    }
    AppFileWriteProtection.beginSweep().use { fresh ->
      assertTrue(AppFileWriteProtection.deleteIfUnprotected(file, fresh) { true })
    }
  }

  @Test fun aClosedSnapshotCannotBeUsedToDeleteAndCloseIsIdempotent() {
    val file = temp.newFile().also { it.writeText("keep") }
    val snapshot = AppFileWriteProtection.beginSweep()
    snapshot.close()
    snapshot.close()
    try {
      AppFileWriteProtection.deleteIfUnprotected(file, snapshot) { true }
      fail("Expected closed snapshot rejection")
    } catch (_: IllegalStateException) { }
    assertEquals("keep", file.readText())
  }
}
