package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.export.*
import dev.pampa.pampanotes.core.sync.SyncCodec
import dev.pampa.pampanotes.core.transcription.VoiceNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Prepared regression sources. They must run against freshly compiled production classes. */
class PampaBundleClockAndRawRoundThreeTest {
  @get:Rule val temp = TemporaryFolder()
  private val folder = FolderEntity("folder", "Folder", createdAt = 1, updatedAt = 1)
  private val note = NoteEntity("note", folder.id, "Note", "Body", createdAt = 1, updatedAt = 1)
  private fun session(id: String, time: Long, names: Map<String, String> = emptyMap(), active: String? = null) =
    SessionEntity(id, note.id, "Title $time", "2026-11-01", 0, active, 1, time, voiceNames = VoiceNames.encode(names))
  private fun part(id: String, owner: String, position: Int = 0) =
    AudioPartEntity(id, owner, position, "$id.m4a", "$id.m4a", "audio/mp4", 0, 1000, "a".repeat(64), 1)
  private fun raw(id: String, owner: String, text: String = "Exact $id\n", cloud: String? = null) =
    TranscriptEntity(id, owner, TranscriptKind.RAW, "custom", "test", text = text, wordCount = 2, createdAt = 1, cloudSourceJson = cloud)
  private fun names(value: PampaBundleNote, owner: String = "destination") = VoiceNames.decode(value.sessions.single { it.id == owner }.voiceNames)
  private fun clocks(value: PampaBundleNote, owner: String = "destination") = VoiceNames.clocks(value.sessions.single { it.id == owner }.voiceNames)

  private fun nativeRoundtrip(value: PampaBundleNote): PampaBundleData {
    // PN uses the entire native graph, including unselected independent histories.
    val export = ExportNote(value.note, listOf(folder.name), value.tags, emptyList(), emptyList(), native = value)
    val set = ExportSet("Folder", "folder", listOf(export), "Round three test", 1,
      folders = listOf(folder.copy(id = value.note.folderId)))
    val directory = temp.newFolder()
    val file = File(directory, "note.pn")
    file.outputStream().use { PampaNoteWriter(directory, directory).write(set, it) }
    return PampaBundleArchive.read(file).data
  }

  private fun beforeMove(removed: Boolean): PampaBundleNote {
    val sourceNames = if (removed) emptyMap() else mapOf("A|SPEAKER_00" to "A at 100")
    return PampaBundleNote(note,
      sessions = listOf(session("origin", 100, sourceNames, "raw-A"),
        session("destination", 10, mapOf("B|SPEAKER_00" to "B at 10", "L|SPEAKER_00" to "Local only"), "raw-local").copy(position = 1)),
      parts = listOf(part("A", "origin"), part("B", "destination"), part("L", "destination", 1)),
      transcripts = listOf(raw("raw-A", "origin", cloud = "{\"text\":\" source A\\n\"}"),
        raw("raw-local", "destination", cloud = """{"text":" local source "}""")))
  }

  private fun movedSnapshot(time: Long): PampaBundleNote = PampaBundleNote(note,
    sessions = listOf(session("destination", time,
      mapOf("A|SPEAKER_00" to "Stale A", "B|SPEAKER_00" to "B at $time", "N|SPEAKER_00" to "New recording"), "raw-$time")
      .copy(date = if (time == 20L) "2026-11-02" else "2026-11-05")),
    parts = listOf(part("A", "destination"), part("B", "destination", 1), part("N", "destination", 2)),
    transcripts = listOf(raw("raw-$time", "destination")))

  private fun assertIntermediateSnapshot(removed: Boolean) {
    val local = beforeMove(removed)
    val at20 = PampaBundleMerge.merge(local, movedSnapshot(20))
    val destination20 = at20.sessions.single { it.id == "destination" }
    assertEquals("Title 20", destination20.title)
    assertEquals("2026-11-02", destination20.date)
    assertEquals(20L, destination20.updatedAt)
    assertEquals("raw-20", destination20.activeTranscriptId)
    assertEquals(100L, clocks(at20).getValue("A"))
    assertEquals(20L, clocks(at20).getValue("B"))
    assertEquals(if (removed) null else "A at 100", names(at20)["A|SPEAKER_00"])

    // Real persistence boundary: clock/tombstone must survive a PN export/read, not just memory.
    val persisted = nativeRoundtrip(at20).notes.single()
    assertEquals(clocks(at20), clocks(persisted))
    val at50 = PampaBundleMerge.merge(persisted, movedSnapshot(50))
    val destination50 = at50.sessions.single { it.id == "destination" }
    assertEquals("Title 50", destination50.title)
    assertEquals("2026-11-05", destination50.date)
    assertEquals(50L, destination50.updatedAt)
    assertEquals("raw-50", destination50.activeTranscriptId)
    assertEquals("B at 50", names(at50)["B|SPEAKER_00"])
    assertEquals(if (removed) null else "A at 100", names(at50)["A|SPEAKER_00"])
    assertEquals("Local only", names(at50)["L|SPEAKER_00"])
    assertEquals("New recording", names(at50)["N|SPEAKER_00"])
    assertEquals(mapOf("A" to 100L, "B" to 50L, "L" to 10L, "N" to 50L), clocks(at50))
    assertEquals(local.transcripts, at50.transcripts.filter { it.id in local.transcripts.map { t -> t.id } })
    assertEquals(setOf("raw-A", "raw-local", "raw-20", "raw-50"), at50.transcripts.map { it.id }.toSet())
    assertEquals(setOf("A", "B", "L", "N"), at50.parts.map { it.id }.toSet())
    assertEquals(at50, PampaBundleMerge.merge(at50, movedSnapshot(50)))

    // A later title/selection edit must not make B's older voice clock become 80.
    val local80 = at50.copy(sessions = at50.sessions.map { s -> if (s.id != "destination") s else
      s.copy(title = "My title at 80", updatedAt = 80, activeTranscriptId = "raw-local") })
    val at70 = PampaBundleMerge.merge(local80, movedSnapshot(70))
    assertEquals("My title at 80", at70.sessions.single { it.id == "destination" }.title)
    assertEquals("raw-local", at70.sessions.single { it.id == "destination" }.activeTranscriptId)
    assertEquals("B at 70", names(at70)["B|SPEAKER_00"])
    assertEquals(if (removed) null else "A at 100", names(at70)["A|SPEAKER_00"])
    assertEquals(80L, at70.sessions.single { it.id == "destination" }.updatedAt)
    assertEquals(70L, clocks(at70).getValue("B"))
  }

  @Test fun movedRenameAt100DoesNotBlockDestinationMetadataAndBAt50() = assertIntermediateSnapshot(removed = false)
  @Test fun movedRemovalAt100DoesNotBlockDestinationMetadataAndBAt50() = assertIntermediateSnapshot(removed = true)

  @Test fun oldNullVoiceColumnDoesNotChangeSessionHashWithoutNewAuthorityEvidence() {
    val value = PampaBundleNote(note, sessions = listOf(session("session", 10)), parts = listOf(part("A", "session")))
    val merged = PampaBundleMerge.merge(value, value)
    assertEquals(value.sessions, merged.sessions)
    assertNull(merged.sessions.single().voiceNames)
    val before = SyncCodec.json.encodeToJsonElement(SessionEntity.serializer(), value.sessions.single())
    val after = SyncCodec.json.encodeToJsonElement(SessionEntity.serializer(), merged.sessions.single())
    assertEquals(SyncCodec.hash(before), SyncCodec.hash(after))
    assertEquals(SyncCodec.hash(JsonObject(before.jsonObject - "voiceNames")), SyncCodec.hash(after))
  }

  @Test fun nativeCopyAndAliasRemappingRetainClockOnlyRecordingIdentities() {
    val value = PampaBundleMerge.merge(beforeMove(removed = true), movedSnapshot(20))
    val copied = PampaBundleImporter.remap(nativeRoundtrip(value), "round-three-clock-copy").notes.single()
    val destination = copied.sessions.single { it.title == "Title 20" }
    val a = copied.parts.single { it.originalName == "A.m4a" }
    val b = copied.parts.single { it.originalName == "B.m4a" }
    assertNotEquals("A", a.id)
    assertEquals(100L, VoiceNames.clocks(destination.voiceNames).getValue(a.id))
    assertEquals(20L, VoiceNames.clocks(destination.voiceNames).getValue(b.id))
    assertFalse(VoiceNames.decode(destination.voiceNames).keys.any { it.startsWith(a.id + "|") })
    assertTrue(VoiceNames.clocks(destination.voiceNames).keys.all { key -> copied.parts.any { it.id == key && it.sessionId == destination.id } })
    assertEquals(copied.sessions, nativeRoundtrip(copied).notes.single().sessions)
  }

  @Test fun legacyAliasCarriesClockOnlyRemovalIntoNativeIdentityAcrossAnIntermediateSnapshot() {
    val sid = PampaBundleLegacyReconcile.id("session", "round-three-session")
    val pid = PampaBundleLegacyReconcile.id("part", "round-three-part")
    val legacy = PampaBundleNote(note.copy(folderId = PampaBundleLegacyReconcile.id("folder", "round-three-folder"), bundleNativeAdopted = false),
      sessions = listOf(session(sid, 20).copy(voiceNames = VoiceNames.encode(emptyMap(), mapOf(pid to 100L)))),
      parts = listOf(part(pid, sid).copy(originalName = "A.m4a")))
    fun snapshot(time: Long) = PampaBundleNote(note,
      sessions = listOf(session("destination", time, mapOf("A|SPEAKER_00" to "Removed alias"))),
      parts = listOf(part("A", "destination")))
    val plan = PampaBundleLegacyReconcile.plan(legacy, snapshot(20), false)
    val adopted = PampaBundleMerge.merge(plan.local, plan.incoming)
    assertEquals(setOf("A"), adopted.parts.map { it.id }.toSet())
    assertEquals(mapOf("A" to 100L), clocks(adopted))
    assertTrue(names(adopted).isEmpty())
    val after = PampaBundleMerge.merge(nativeRoundtrip(adopted).notes.single(), snapshot(50))
    assertTrue(names(after).isEmpty())
    assertEquals(mapOf("A" to 100L), clocks(after))
    assertEquals(50L, after.sessions.single().updatedAt)
  }

  @Test fun rawTiePermutationsAndDatabaseIdsKeepExactTextEveryHistoryAndCloudSourceThroughPnCopyMergeReexport() {
    val first = raw("raw", "session", "First.\n\nSecond!\n", "{\"text\":\" First.\\n\\nSecond!\\n \"}")
    val second = raw("alternate", "session", "Other first.\n  Other second!\n", "{\"text\":\" alternative source \"}")
    val refined = first.copy(id = "refined", kind = TranscriptKind.REFINED, parentId = first.id, text = "Refined\n\nexact\n")
    val segments = listOf(first, second).flatMap { t -> listOf(
      SegmentEntity(id = 1, transcriptId = t.id, partId = "part", indexInPart = 1, partStartMs = 0, partEndMs = 100,
        sessionStartMs = 0, sessionEndMs = 100, text = "Second!", wordsJson = "0,100,Second!", speaker = "SPEAKER_01"),
      SegmentEntity(id = 2, transcriptId = t.id, partId = "part", indexInPart = 0, partStartMs = 0, partEndMs = 100,
        sessionStartMs = 0, sessionEndMs = 100, text = "First.", wordsJson = "0,100,First.", speaker = "SPEAKER_00")) }
    val value = PampaBundleNote(note, sessions = listOf(session("session", 10, active = first.id)),
      parts = listOf(part("part", "session")), transcripts = listOf(first, second, refined), segments = segments)
    val read = nativeRoundtrip(value)
    assertEquals(value.transcripts, read.notes.single().transcripts)
    val copied = PampaBundleImporter.remap(read, "round-three-raw-copy").notes.single()
    val merged = PampaBundleMerge.merge(null, copied)
    assertEquals(copied.transcripts, merged.transcripts)
    val reexported = nativeRoundtrip(merged).notes.single()
    assertEquals(copied.transcripts, reexported.transcripts)
    assertEquals(copied.segments.map { it.copy(id = 0) }.toSet(), reexported.segments.toSet())
    val permuted = copied.copy(segments = copied.segments.reversed().mapIndexed { i, s -> s.copy(id = 100L + i) })
    assertEquals(merged.transcripts, PampaBundleMerge.merge(merged, permuted).transcripts)
    assertEquals(merged, PampaBundleMerge.merge(merged, copied))
  }

  @Test fun realPartReorderStillRebuildsAllRawHistoriesButLeavesCloudSourcesAndWordsImmutable() {
    val first = raw("raw", "session", "First.\n\nSecond!\n", "{\"text\":\" immutable source \"}")
    val second = raw("alternate", "session", "Alternative exact\n", "{\"text\":\" other immutable source \"}")
    val parts = listOf(part("A", "session"), part("B", "session", 1))
    val segments = listOf(first, second).flatMap { t -> parts.map { p ->
      SegmentEntity(transcriptId = t.id, partId = p.id, indexInPart = 0, partStartMs = 0, partEndMs = 100,
        sessionStartMs = p.position * 1000L, sessionEndMs = p.position * 1000L + 100,
        text = if (p.id == "A") "First." else "Second!", wordsJson = "0,100,${p.id}", speaker = "SPEAKER_00") } }
    val value = PampaBundleNote(note, sessions = listOf(session("session", 10, active = first.id)), parts = parts,
      transcripts = listOf(first, second), segments = segments)
    val reordered = value.copy(parts = listOf(parts[1].copy(position = 0), parts[0].copy(position = 1)),
      transcripts = emptyList(), segments = emptyList(), sessions = value.sessions.map { it.copy(activeTranscriptId = null) })
    val merged = PampaBundleMerge.merge(value, reordered)
    assertEquals(listOf("Second! First.", "Second! First."), merged.transcripts.map { it.text })
    assertEquals(value.transcripts.map { it.cloudSourceJson }, merged.transcripts.map { it.cloudSourceJson })
    assertEquals(value.segments.map { Triple(it.transcriptId, it.partId, it.wordsJson) }.toSet(),
      merged.segments.map { Triple(it.transcriptId, it.partId, it.wordsJson) }.toSet())
    assertEquals(setOf(0L), merged.segments.filter { it.partId == "B" }.map { it.sessionStartMs }.toSet())
    assertEquals(setOf(1000L), merged.segments.filter { it.partId == "A" }.map { it.sessionStartMs }.toSet())
    assertEquals(merged.transcripts, nativeRoundtrip(merged).notes.single().transcripts)
  }
}
