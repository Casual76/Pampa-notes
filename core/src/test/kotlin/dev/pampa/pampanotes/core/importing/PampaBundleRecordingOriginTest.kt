package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PampaBundleRecordingOriginTest {
  private val note = NoteEntity("n", "f", "Recording", createdAt = 1, updatedAt = 2)
  private val session = SessionEntity("s", "n", date = "2026-11-01", position = 0, createdAt = 1, updatedAt = 2)
  private val unknown = AudioPartEntity("p", "s", 0, "p.wav", "p.wav", "audio/wav", 7, 1000, "a".repeat(64), 1)
  private val exact = unknown.copy(recordedAtEpochMs = 1793523600000, recordedAtLocal = "2026-11-01T10:00",
    recordedAtZoneId = "+01:00", recordedAtSource = "media_metadata", recordedAtPrecision = "second")
  private fun graph(part: AudioPartEntity) = PampaBundleNote(note, sessions = listOf(session), parts = listOf(part))
  private fun origin(part: AudioPartEntity) = listOf(part.recordedAtEpochMs, part.recordedAtLocal,
    part.recordedAtZoneId, part.recordedAtSource, part.recordedAtPrecision)

  @Test fun `older native payload defaults stay null and merge retains all five known fields exactly`() {
    val json = PampaBundleArchive.json
    val tree = json.encodeToJsonElement(AudioPartEntity.serializer(), exact).jsonObject
    val older = json.decodeFromJsonElement(AudioPartEntity.serializer(), JsonObject(tree.filterKeys { !it.startsWith("recordedAt") }))
    assertEquals(origin(unknown), origin(older))
    val merged = PampaBundleMerge.merge(graph(exact), graph(older)).parts.single()
    assertEquals(origin(exact), origin(merged))
    assertEquals(origin(exact), origin(PampaBundleCloudSources.merge(graph(exact), graph(older), false).parts.single()))
    assertEquals(origin(unknown), origin(PampaBundleMerge.merge(null, graph(older)).parts.single()))
  }

  @Test fun `unknown partial day minute and unzoned origins cannot downgrade coherent precise evidence`() {
    val lessKnown = listOf(unknown, unknown.copy(recordedAtSource = "media_metadata", recordedAtPrecision = "second"),
      unknown.copy(recordedAtLocal = "2026-11-01", recordedAtSource = "filename", recordedAtPrecision = "day"),
      unknown.copy(recordedAtLocal = "2026-11-01T10:00", recordedAtSource = "filename", recordedAtPrecision = "minute"),
      exact.copy(recordedAtEpochMs = null, recordedAtZoneId = null),
      exact.copy(recordedAtEpochMs = exact.recordedAtEpochMs!! + 3_600_000))
    lessKnown.forEach { incoming ->
      assertEquals(origin(exact), origin(PampaBundleMerge.merge(graph(exact), graph(incoming)).parts.single()))
    }
  }

  @Test fun `precision upgrades and equally precise conflicting corrections choose one whole tuple`() {
    val day = unknown.copy(recordedAtLocal = "2026-11-01", recordedAtSource = "file_modified", recordedAtPrecision = "day")
    assertEquals(origin(exact), origin(PampaBundleMerge.merge(graph(day), graph(exact)).parts.single()))
    val correction = exact.copy(recordedAtEpochMs = exact.recordedAtEpochMs!! + 3_600_000,
      recordedAtLocal = "2026-11-01T11:00", recordedAtSource = "corrected_metadata")
    assertEquals(origin(correction), origin(PampaBundleMerge.merge(graph(exact), graph(correction)).parts.single()))
    // A local-only precise correction must not borrow an unrelated epoch/zone from an older day.
    val unzoned = unknown.copy(recordedAtLocal = "2026-11-02T16:20", recordedAtSource = "filename", recordedAtPrecision = "minute")
    val merged = PampaBundleMerge.merge(graph(day), graph(unzoned)).parts.single()
    assertEquals(origin(unzoned), origin(merged))
    assertNull(merged.recordedAtEpochMs)
    assertNull(merged.recordedAtZoneId)
  }

  @Test fun `no origin transfer across changed blank hashes or unrelated IDs`() {
    listOf(unknown.copy(sha256 = "b".repeat(64)), unknown.copy(sha256 = ""), unknown.copy(id = "other")).forEach { incoming ->
      assertEquals(origin(unknown), origin(PampaBundleRecordingOrigin.retain(exact, incoming)))
    }
    assertEquals(origin(unknown), origin(PampaBundleRecordingOrigin.retain(exact.copy(sha256 = ""), unknown)))
    assertEquals(origin(exact), origin(PampaBundleRecordingOrigin.retain(exact, unknown.copy(sha256 = unknown.sha256.uppercase()))))
    val changed = PampaBundleMerge.merge(graph(exact), graph(unknown.copy(sha256 = "b".repeat(64)))).parts.single()
    assertEquals(origin(unknown), origin(changed))
  }

  @Test fun `legacy aliases preserve the reconciled recording origin and copies retain only their own evidence`() {
    val legacySession = session.copy(id = PampaBundleLegacyReconcile.id("session", "s"))
    val legacyPart = exact.copy(id = PampaBundleLegacyReconcile.id("part", "p"), sessionId = legacySession.id)
    val local = graph(legacyPart).copy(sessions = listOf(legacySession))
    val plan = PampaBundleLegacyReconcile.plan(local, graph(unknown), publicLegacy = false)
    assertEquals(unknown.id, plan.parts[legacyPart.id])
    val merged = PampaBundleMerge.merge(plan.local, plan.incoming).parts.single()
    assertEquals(unknown.id, merged.id)
    assertEquals(origin(exact), origin(merged))
    val data = PampaBundleData(folders = listOf(FolderEntity("f", "Folder", createdAt = 1, updatedAt = 2)), notes = listOf(graph(unknown)))
    val copied = PampaBundleImporter.remap(data, "independent-copy").notes.single()
    assertNotEquals(unknown.id, copied.parts.single().id)
    assertEquals(origin(unknown), origin(PampaBundleMerge.merge(null, copied).parts.single()))
  }
}
