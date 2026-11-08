package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import org.junit.Assert.*
import org.junit.Test

class PampaBundleMetadataMergeTest {
  private val part = AudioPartEntity("part", "session", 0, "local.m4a", "Audio", "audio/mp4", 1, 1000, "a".repeat(64), 1)
  private val rich = part.copy(recordedAtEpochMs = java.time.Instant.parse("2026-11-08T23:00:00.123Z").toEpochMilli(), recordedAtLocal = "2026-11-09T00:00:00.123",
    recordedAtZoneId = "Europe/Rome", recordedAtSource = "media_metadata", recordedAtPrecision = "millisecond")
  private val raw = TranscriptEntity("raw", "session", TranscriptKind.RAW, "cloud", "model", text = "Exact text", wordCount = 2, createdAt = 1)

  @Test fun unknownSnapshotRetainsTheCompleteKnownOriginWithoutChangingIncomingOwnership() {
    val incoming = part.copy(sessionId = "moved", position = 2, fileName = "staged.bin")
    assertEquals(rich.copy(sessionId = "moved", position = 2, fileName = "staged.bin"), PampaBundleMetadataMerge.audio(rich, incoming))
  }

  @Test fun dayOnlySnapshotCannotDowngradeExactOriginAndCompatibleEvidenceCanEnrichIt() {
    val day = part.copy(recordedAtLocal = "2026-11-09", recordedAtSource = "filename", recordedAtPrecision = "day")
    assertEquals(rich, PampaBundleMetadataMerge.audio(rich, day))
    assertEquals(rich, PampaBundleMetadataMerge.audio(day, rich))
    assertEquals(rich, PampaBundleMetadataMerge.audio(part, rich))
    assertEquals(part, PampaBundleMetadataMerge.audio(null, part))
  }

  @Test fun conflictingOriginsFailInsteadOfSynthesizingAnInconsistentClock() {
    assertThrows(IllegalArgumentException::class.java) { PampaBundleMetadataMerge.audio(rich, rich.copy(recordedAtEpochMs = rich.recordedAtEpochMs!! + 1)) }
    assertThrows(IllegalArgumentException::class.java) { PampaBundleMetadataMerge.audio(rich, part.copy(recordedAtLocal = "2026-11-10", recordedAtPrecision = "day")) }
    assertThrows(IllegalArgumentException::class.java) { PampaBundleMetadataMerge.audio(rich.copy(recordedAtEpochMs = null), rich.copy(recordedAtEpochMs = null, recordedAtZoneId = "UTC")) }
  }

  @Test fun sameInstantWithAnotherZoneKeepsOneCoherentRepresentation() {
    val utc = rich.copy(recordedAtLocal = "2026-11-08T23:00:00.123", recordedAtZoneId = "UTC")
    assertEquals(utc, PampaBundleMetadataMerge.audio(rich, utc))
  }

  @Test fun cloudSourceDistinguishesUnknownEmptyAndExactAndRejectsConflictingKnownEvidence() {
    for (source in listOf("", "{\"text\":\"  Caffè,  due spazi!  \"}")) {
      val local = raw.copy(cloudSourceJson = source)
      assertEquals(local, PampaBundleMetadataMerge.transcript(local, raw))
      assertEquals(local, PampaBundleMetadataMerge.transcript(raw, local))
      assertEquals(local, PampaBundleMetadataMerge.transcript(local, local))
      assertThrows(IllegalArgumentException::class.java) { PampaBundleMetadataMerge.transcript(local, raw.copy(cloudSourceJson = "different")) }
    }
    assertNull(PampaBundleMetadataMerge.transcript(null, raw).cloudSourceJson)
  }

  @Test fun staleNoteMetadataIsRetainedWhileIncomingBodyStillMerges() {
    val local = NoteEntity("note", "personal-folder", "My title", "Base\nPersonal", pinned = true,
      language = "it", createdAt = 1, updatedAt = 20, bundleBodyBase = "Base")
    val incoming = local.copy(folderId = "sender-folder", title = "Sender title", body = "Author",
      pinned = false, language = "en", updatedAt = 2, bundleBodyBase = null)
    val merged = PampaBundleMerge.merge(PampaBundleNote(local), PampaBundleNote(incoming)).note
    assertEquals(local.folderId, merged.folderId)
    assertEquals(local.title, merged.title)
    assertEquals(local.pinned, merged.pinned)
    assertEquals(local.language, merged.language)
    assertEquals(20L, merged.updatedAt)
    assertTrue(merged.body.contains("Author"))
    assertTrue(merged.body.contains("Personal"))
    assertEquals("Author", merged.bundleBodyBase)
    val newer = PampaBundleMerge.merge(PampaBundleNote(local), PampaBundleNote(incoming.copy(updatedAt = 21))).note
    assertEquals(incoming.title, newer.title)
    assertEquals(incoming.folderId, newer.folderId)
  }
}
