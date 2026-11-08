package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.transcription.VoiceNames
import org.junit.Assert.*
import org.junit.Test

class PampaBundleSessionAuthorityRoundTwoTest {
  private val note = NoteEntity("note", "folder", "Note", "Body", createdAt = 1, updatedAt = 1)
  private fun session(id: String = "session", time: Long = 10, names: Map<String, String> = emptyMap()) =
    SessionEntity(id, note.id, "Sender title", "2026-11-01", 0, createdAt = 1, updatedAt = time, voiceNames = VoiceNames.encode(names))
  private fun part(id: String = "part", owner: String = "session") =
    AudioPartEntity(id, owner, 0, "$id.bin", "$id.m4a", "audio/mp4", 3, 1000, "a".repeat(64), 1)
  private fun raw(id: String, owner: String = "session", time: Long = 1) =
    TranscriptEntity(id, owner, TranscriptKind.RAW, "local", "test", text = "Exact $id text", wordCount = 3, createdAt = time)
  private fun graph(s: SessionEntity = session(), parts: List<AudioPartEntity> = listOf(part())) =
    PampaBundleNote(note, sessions = listOf(s), parts = parts)
  private fun names(graph: PampaBundleNote, owner: String = "session") = VoiceNames.decode(graph.sessions.single { it.id == owner }.voiceNames)

  @Test fun staleSnapshotKeepsTitleAndDateWithoutAnyActiveSelection() {
    val incoming = graph()
    val localSession = incoming.sessions.single().copy(title = "My title", date = "2024-02-03", updatedAt = 20)
    val merged = PampaBundleMerge.merge(incoming.copy(sessions = listOf(localSession)), incoming)
    assertEquals(localSession, merged.sessions.single())
    assertNull(merged.sessions.single().activeTranscriptId)
  }

  @Test fun staleSnapshotKeepsScalarsAndValidLocalSelectionWhileImportingNewAudioAndDistinctHistories() {
    val incomingRaw = raw("sender-history")
    val localRaw = raw("local-history", time = 30)
    val localSession = session(time = 20, names = mapOf("part|SPEAKER_00" to "Local name"))
      .copy(title = "My title", date = "2025-03-04", activeTranscriptId = localRaw.id)
    val local = graph(localSession).copy(transcripts = listOf(localRaw), segments = listOf(
      SegmentEntity(transcriptId = localRaw.id, partId = "part", indexInPart = 0, partStartMs = 0, partEndMs = 100,
        sessionStartMs = 0, sessionEndMs = 100, text = localRaw.text, wordsJson = "0,100,Exact", speaker = "SPEAKER_00")))
    val incoming = graph(session(names = mapOf("part|SPEAKER_00" to "Old name")).copy(activeTranscriptId = incomingRaw.id),
      listOf(part(), part("new").copy(position = 1))).copy(transcripts = listOf(incomingRaw))
    val merged = PampaBundleMerge.merge(local, incoming)
    assertEquals(localSession, merged.sessions.single())
    assertEquals(setOf("part", "new"), merged.parts.map { it.id }.toSet())
    assertEquals(setOf(localRaw.id, incomingRaw.id), merged.transcripts.map { it.id }.toSet())
    assertEquals(local.segments, merged.segments)
    assertEquals("Local name", names(merged)["part|SPEAKER_00"])
  }

  @Test fun newerLocalRemovalIsNotUndoneByAnOldNonemptyMap() {
    val incoming = graph(session(names = mapOf("part|SPEAKER_00" to "Deleted name")))
    var local = incoming.copy(sessions = listOf(session(time = 20)))
    repeat(2) {
      local = PampaBundleMerge.merge(local, incoming)
      assertTrue(names(local).isEmpty())
      assertEquals(20L, local.sessions.single().updatedAt)
    }
  }

  @Test fun newerSenderMetadataAndRemovalWinWhileLocalOnlyRecordingNamesRemain() {
    val local = graph(session(names = mapOf("part|SPEAKER_00" to "Removed", "local|SPEAKER_01" to "Local recording")),
      listOf(part(), part("local").copy(position = 1)))
    val receivedSession = session(time = 30).copy(title = "Fresh sender title", date = "2026-11-08")
    val incoming = graph(receivedSession)
    val merged = PampaBundleMerge.merge(local, incoming)
    assertEquals(receivedSession.copy(voiceNames = VoiceNames.encode(mapOf("local|SPEAKER_01" to "Local recording"))), merged.sessions.single())
    assertEquals(mapOf("local|SPEAKER_01" to "Local recording"), names(merged))
    assertEquals(merged, PampaBundleMerge.merge(merged, incoming))
  }

  @Test fun aliasesFollowMovedPartAndNewerEmptyMapStillRemovesThem() {
    val local = graph(session(time = 20, names = mapOf("part|SPEAKER_00" to "Person")))
    val received = graph(session("destination", time = 10), listOf(part(owner = "destination")))
      .let { it.copy(sessions = listOf(session(), it.sessions.single().copy(position = 1))) }
    val moved = PampaBundleMerge.merge(local, received)
    assertTrue(names(moved).isEmpty())
    assertEquals(mapOf("part|SPEAKER_00" to "Person"), names(moved, "destination"))
    assertEquals(20L, moved.sessions.single { it.id == "destination" }.updatedAt)
    val stillStale = received.copy(sessions = received.sessions.map { it.copy(updatedAt = 15) })
    assertEquals(mapOf("part|SPEAKER_00" to "Person"), names(PampaBundleMerge.merge(moved, stillStale), "destination"))
    val removal = received.copy(sessions = received.sessions.map { it.copy(updatedAt = 30) })
    assertTrue(names(PampaBundleMerge.merge(moved, removal), "destination").isEmpty())
  }

  @Test fun invalidLocalSelectionFallsBackToValidSenderWithoutLosingNewerScalars() {
    val local = graph(session(time = 20).copy(title = "Local", activeTranscriptId = "absent"))
    val incoming = graph(session().copy(activeTranscriptId = "received")).copy(transcripts = listOf(raw("received")))
    val merged = PampaBundleMerge.merge(local, incoming)
    assertEquals("Local", merged.sessions.single().title)
    assertEquals("received", merged.sessions.single().activeTranscriptId)
  }

  @Test fun newerSenderSelectionAndVoiceRenameWinWithoutDiscardingLocalHistory() {
    val local = graph(session(time = 10, names = mapOf("part|SPEAKER_00" to "Old"))
      .copy(activeTranscriptId = "old-history")).copy(transcripts = listOf(raw("old-history")))
    val incoming = graph(session(time = 30, names = mapOf("part|SPEAKER_00" to "Fresh"))
      .copy(activeTranscriptId = "new-history")).copy(transcripts = listOf(raw("new-history")))
    val merged = PampaBundleMerge.merge(local, incoming)
    assertEquals("new-history", merged.sessions.single().activeTranscriptId)
    assertEquals(mapOf("part|SPEAKER_00" to "Fresh"), names(merged))
    assertEquals(setOf("old-history", "new-history"), merged.transcripts.map { it.id }.toSet())
  }

  @Test fun removedAliasAndItsClockSurviveMoveAndAnIntermediateStaleSnapshot() {
    val local = graph(session(time = 20))
    val received = graph(session("destination", time = 10, names = mapOf("part|SPEAKER_00" to "Deleted")),
      listOf(part(owner = "destination")))
    val moved = PampaBundleMerge.merge(local, received)
    assertTrue(names(moved, "destination").isEmpty())
    assertEquals(20L, moved.sessions.single { it.id == "destination" }.updatedAt)
    val intermediate = received.copy(sessions = received.sessions.map { it.copy(updatedAt = 15) })
    assertTrue(names(PampaBundleMerge.merge(moved, intermediate), "destination").isEmpty())
  }

  @Test fun legacyMappingThenNativeUpdatesHonorTimestampedRenamesAndRemovals() {
    val sid = PampaBundleLegacyReconcile.id("session", "old-session")
    val pid = PampaBundleLegacyReconcile.id("part", "old-part")
    val legacy = graph(session(sid, time = 20, names = mapOf("$pid|SPEAKER_00" to "Local")), listOf(part(pid, sid)))
      .copy(note = note.copy(folderId = PampaBundleLegacyReconcile.id("folder", "old-folder"), bundleNativeAdopted = false))
    for (local in listOf(legacy, legacy.copy(sessions = legacy.sessions.map { it.copy(voiceNames = null) }))) {
      val target = graph(session(time = 10, names = mapOf("part|SPEAKER_00" to "Stale")))
      val plan = PampaBundleLegacyReconcile.plan(local, target, false)
      val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
      assertEquals(VoiceNames.decode(local.sessions.single().voiceNames).values.toSet(), names(merged).values.toSet())
      assertTrue(names(merged).keys.all { it.startsWith("part|") })
      val newer = target.copy(sessions = listOf(session(time = 30)))
      val updated = PampaBundleLegacyReconcile.plan(merged, newer, false)
      assertTrue(names(PampaBundleMerge.merge(updated.local, updated.incoming)).isEmpty())
    }
  }
}
