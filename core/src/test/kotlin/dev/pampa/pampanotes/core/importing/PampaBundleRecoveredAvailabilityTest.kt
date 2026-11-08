package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import org.junit.Assert.*
import org.junit.Test

class PampaBundleRecoveredAvailabilityTest {
  private val part = AudioPartEntity("p", "s", 0, PampaBundleOmissions.expectedFileName("p"), "Recording.m4a",
    "audio/mp4", 3, 1000, "a".repeat(64), 1)

  @Test fun localAvailabilityOverridesHistoricalOmissionWithoutChangingIdentity() {
    assertTrue(PampaBundleOmissions.isOmitted(part, localAvailable = false))
    assertFalse(PampaBundleOmissions.isOmitted(part, localAvailable = true))
    assertTrue(PampaBundleOmissions.isOmitted(part))
    assertEquals(0L, part.archivedAt)
  }

  @Test fun readyOmissionsParticipateInRecoveryAndExpireBackToMissing() {
    val missing = listOf(part)
    assertEquals(missing, PampaBundleOmissions.omitted(missing))
    assertTrue(PampaBundleOmissions.omitted(missing, setOf(part.id)).isEmpty())
    assertEquals(missing, PampaBundleOmissions.remote(missing, setOf(part.id)))
    assertTrue(PampaBundleOmissions.remote(missing, setOf("different-row")).isEmpty())
    assertTrue(PampaBundleOmissions.omitted(emptyList(), setOf(part.id)).isEmpty())
  }

  @Test fun sourceRecoveryReservesOnlyKnownOriginalsWithSafeStableNames() {
    val source = SourceEntity("../id:unsafe", "note", SourceKind.PDF, "original.pdf", "application/pdf", 3,
      "a".repeat(64), importedAt = 1)
    val name = PampaBundleOmissions.expectedSourceName(source)!!
    assertTrue(name.matches(Regex("missing-[a-f0-9-]{36}")))
    assertEquals(name, PampaBundleOmissions.expectedFileName(source.id))
    assertEquals(name, PampaBundleOmissions.expectedSourceName(source.copy(originalName = "renamed.pdf")))
    for (kind in listOf(SourceKind.CLIPBOARD, SourceKind.SHARE)) {
      assertEquals(name, PampaBundleOmissions.expectedSourceName(source.copy(kind = kind)))
      assertNull(PampaBundleOmissions.expectedSourceName(source.copy(kind = kind, mime = "text/plain")))
      assertEquals(name, PampaBundleOmissions.expectedSourceName(source.copy(kind = kind, mime = "text/plain", archivedAt = 5)))
    }
    assertNull(PampaBundleOmissions.expectedSourceName(source.copy(sha256 = "")))
    assertNull(PampaBundleOmissions.expectedSourceName(source.copy(sha256 = "unknown")))
  }
}
