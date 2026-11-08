package dev.pampa.pampanotes.core.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.transcription.VoiceNames
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Prepared real Room/applier regressions. Not run by this implementation round. */
@RunWith(AndroidJUnit4::class)
class PampaSessionVoiceRoundFourRoomTest {
  private lateinit var db: PampaDatabase
  private lateinit var payloads: SyncPayloads
  private lateinit var applier: SyncApplier
  private var seq = 10L

  @Before fun setup() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    db = PampaDatabase.inMemory(context)
    payloads = SyncPayloads(db.folders(), db.notes(), db.tags(), db.sessions(), db.audioParts(),
      db.transcripts(), db.segments(), db.sources(), db.exportPresets(), db.stats(), db.sync())
    applier = SyncApplier(db, AppFiles(context), payloads)
  }
  @After fun close() = db.close()

  private fun names(p: String?, q: String?, pc: Long, qc: Long) = VoiceNames.encode(
    buildMap { p?.let { put("p|SPEAKER_00", it) }; q?.let { put("q|SPEAKER_00", it) } }, mapOf("p" to pc, "q" to qc))
  private fun session(id: String = "s", at: Long = 10, raw: String? = null) = SessionEntity(
    id = id, noteId = "n", title = "Title-$at", date = "2026-11-08", position = 0,
    activeTranscriptId = "selection-$at", createdAt = 1, updatedAt = at, voiceNames = raw)
  private fun part(id: String, owner: String, at: Long = 1) = AudioPartEntity(
    id, owner, 0, "pn-round-four-$id.m4a", "$id.m4a", "audio/mp4", 0, 1000, "a".repeat(64), at)
  private fun wire(row: SessionEntity) = SyncPayloads.encode(SessionEntity.serializer(), row, row.updatedAt).let {
    WireChange("sessions", row.id, WireChange.OP_UPSERT, row.updatedAt, it.hash, payload = it.payload, seq = seq++, deviceId = "remote")
  }
  private fun wire(row: AudioPartEntity) = SyncPayloads.encode(AudioPartEntity.serializer(), row, row.createdAt).let {
    WireChange("audio_parts", row.id, WireChange.OP_UPSERT, row.createdAt, it.hash, payload = it.payload, seq = seq++, deviceId = "remote")
  }
  private suspend fun apply(vararg changes: WireChange) = applier.apply(changes.toList(), "owner", "this-device")
  private suspend fun seed(rows: List<SessionEntity>, parts: List<AudioPartEntity>) {
    db.folders().upsert(FolderEntity("f", name = "Folder", createdAt = 1, updatedAt = 1))
    db.notes().upsert(NoteEntity("n", "f", "Note", createdAt = 1, updatedAt = 1))
    rows.forEach { db.sessions().upsert(it) }
    parts.forEach { db.audioParts().upsert(it) }
    db.sync().clearAllOutbox()
    for (row in rows) db.sync().upsertMeta(SyncMetaEntity("sessions", row.id, 1, "agreed-${row.id}", 1))
  }
  private suspend fun dirty(row: SessionEntity) {
    db.sync().markDirty(SyncOutboxEntity(tbl = "sessions", rowId = row.id, op = WireChange.OP_UPSERT))
  }
  private suspend fun assertBaseAndConverge(remote: WireChange, expected: SessionEntity) {
    assertEquals(expected, db.sessions().get(expected.id))
    val meta = db.sync().meta("sessions", expected.id)!!
    assertEquals(remote.hash, meta.hash); assertEquals(remote.seq, meta.serverSeq); assertEquals(remote.updatedAt, meta.updatedAt)
    assertNotNull(db.sync().outboxEntry("sessions", expected.id))
    // Simulate the parent's ordinary successful push/ack, then a canonical server echo.
    val encoded = payloads.encode("sessions", expected.id)!!
    db.sync().clearAllOutbox()
    db.sync().upsertMeta(meta.copy(hash = encoded.hash))
    apply(wire(expected))
    assertEquals(expected, db.sessions().get(expected.id))
    assertNull(db.sync().outboxEntry("sessions", expected.id))
  }

  @Test fun applyKeepsAlice100WhileTitleDateAndSelectionUseTheRemoteSessionClock() = runBlocking {
    val local = session(raw = names("Alice", "Anna", 100, 10))
    seed(listOf(local), listOf(part("p", "s"), part("q", "s"))); dirty(local)
    val remote = session(at = 50, raw = names("Stale Alice", "Anna", 20, 10)).copy(date = "2026-11-09")
    val change = wire(remote)
    assertEquals(1, apply(change).applied)
    assertBaseAndConverge(change, remote.copy(voiceNames = local.voiceNames))
  }

  @Test fun applyMergesNewClientDistinctRecordingEditsAndDoesNotResurrectATombstone() = runBlocking {
    for (name in listOf("Alice", null)) {
      val local = session(raw = names(name, "Anna", 200, 10))
      seed(listOf(local), listOf(part("p", "s"), part("q", "s"))); dirty(local)
      val remote = session(at = 300, raw = names("Stale Marco", "Giulia", 100, 300))
      val change = wire(remote)
      assertEquals(1, apply(change).applied)
      assertBaseAndConverge(change, remote.copy(voiceNames = names(name, "Giulia", 200, 300)))
    }
  }

  @Test fun skipLearnsRemoteRecordingAuthorityAndMarkerWithoutChangingLocalMetadata() = runBlocking {
    val local = session(at = 900, raw = names("Alice", "Anna", 100, 10))
    seed(listOf(local), listOf(part("p", "s"), part("q", "s"))); dirty(local)
    val remote = session(at = 300, raw = names("Stale Alice", "Giulia", 20, 300))
      .copy(transcribingOn = "other-device", transcribingSince = 123)
    val change = wire(remote)
    assertEquals(1, apply(change).skipped)
    assertBaseAndConverge(change, local.copy(voiceNames = names("Alice", "Giulia", 100, 300),
      transcribingOn = remote.transcribingOn, transcribingSince = remote.transcribingSince))
  }

  @Test fun applyRetainsThisDevicesTranscribingMarkerWhileMergingRecordingMaps() = runBlocking {
    val local = session(raw = names("Alice", "Anna", 100, 10)).copy(transcribingOn = "this-device", transcribingSince = 123)
    seed(listOf(local), listOf(part("p", "s"), part("q", "s"))); dirty(local)
    val remote = session(at = 300, raw = names("Stale Alice", "Giulia", 20, 300))
    val change = wire(remote)
    apply(change)
    assertBaseAndConverge(change, remote.copy(voiceNames = names("Alice", "Giulia", 100, 300),
      transcribingOn = local.transcribingOn, transcribingSince = local.transcribingSince))
  }

  @Test fun newerLegacyNullRemovalIsACompleteOwnedMapWithItsOwnClock() = runBlocking {
    val local = session(raw = names("Alice", "Anna", 100, 10))
    seed(listOf(local), listOf(part("p", "s"), part("q", "s")))
    val remote = session(at = 400)
    val change = wire(remote)
    apply(change)
    assertBaseAndConverge(change, remote.copy(voiceNames = names(null, null, 400, 400)))
  }

  @Test fun samePageMoveTransfersCompleteSourceAuthorityAfterTheActualAudioApply() = runBlocking {
    val source = session("source", raw = VoiceNames.encode(emptyMap(), mapOf("p" to 200L)))
    val target = session("target", at = 50)
    val audio = part("p", source.id)
    seed(listOf(source, target), listOf(audio))
    val remoteTarget = target.copy(updatedAt = 300, voiceNames = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Stale Marco"), mapOf("p" to 100L)))
    // Session order deliberately follows seq, not the order of ownership changes.
    apply(wire(remoteTarget), wire(source.copy(updatedAt = 300, voiceNames = null)), wire(audio.copy(sessionId = target.id)))
    assertEquals(target.id, db.audioParts().get("p")!!.sessionId)
    assertNull(db.sessions().get(source.id)!!.voiceNames)
    val moved = db.sessions().get(target.id)!!
    assertTrue(VoiceNames.decode(moved.voiceNames).isEmpty())
    assertEquals(mapOf("p" to 200L), VoiceNames.clocks(moved.voiceNames))
    assertEquals(300L, moved.updatedAt)
    assertNotNull(db.sync().outboxEntry("sessions", target.id))
  }

  @Test fun splitPagesConvergeForSessionBeforeMoveAndMoveBeforeSession() = runBlocking {
    for (sessionFirst in listOf(true, false)) {
      val source = session("source", raw = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Alice"), mapOf("p" to 200L)))
      val target = session("target", at = 50)
      val audio = part("p", source.id)
      seed(listOf(source, target), listOf(audio))
      val remote = target.copy(updatedAt = 300, voiceNames = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Stale Alice"), mapOf("p" to 100L)))
      val sessionChange = wire(remote); val move = wire(audio.copy(sessionId = target.id))
      if (sessionFirst) { apply(sessionChange); apply(move) } else { apply(move); apply(sessionChange) }
      assertEquals(target.id, db.audioParts().get("p")!!.sessionId)
      assertNull(db.sessions().get(source.id)!!.voiceNames)
      assertEquals(mapOf("p|SPEAKER_00" to "Alice"), VoiceNames.decode(db.sessions().get(target.id)!!.voiceNames))
      assertEquals(mapOf("p" to 200L), VoiceNames.clocks(db.sessions().get(target.id)!!.voiceNames))
    }
  }

  @Test fun skippedMoveDoesNotGiveTheDestinationAuthorityOverTheCurrentOwner() = runBlocking {
    val source = session("source", raw = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Alice"), mapOf("p" to 200L)))
    val target = session("target", at = 50)
    val localAudio = part("p", source.id, at = 1000)
    seed(listOf(source, target), listOf(localAudio))
    db.sync().upsertMeta(SyncMetaEntity("audio_parts", "p", 1, "agreed-audio", 1))
    db.sync().markDirty(SyncOutboxEntity(tbl = "audio_parts", rowId = "p", op = WireChange.OP_UPSERT))
    val incomingTarget = target.copy(updatedAt = 300, voiceNames = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Other"), mapOf("p" to 900L)))
    val result = apply(wire(incomingTarget), wire(localAudio.copy(sessionId = target.id, createdAt = 1)))
    assertEquals(1, result.skipped)
    assertEquals(source.id, db.audioParts().get("p")!!.sessionId)
    assertEquals(source.voiceNames, db.sessions().get(source.id)!!.voiceNames)
  }

  @Test fun workerSourcePageBeforeAudioRetainsAuthorityThenRequeuesOnlyOwnershipCleanup() = runBlocking {
    val source = session("source", raw = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Alice"), mapOf("p" to 100L)))
    val target = session("target", at = 50)
    val audio = part("p", source.id)
    seed(listOf(source, target), listOf(audio))
    // This is the production worker's retained source snapshot for a same-push move.
    val publishedSource = source.copy(updatedAt = 600, title = "Moved source")
    val sourceChange = wire(publishedSource)
    apply(sourceChange)
    assertEquals(source.voiceNames, db.sessions().get(source.id)!!.voiceNames)
    assertNull(db.sync().outboxEntry("sessions", source.id))
    val publishedTarget = target.copy(updatedAt = 600, voiceNames = source.voiceNames)
    apply(wire(publishedTarget), wire(audio.copy(sessionId = target.id)))
    assertEquals(source.voiceNames, db.sessions().get(target.id)!!.voiceNames)
    assertNull(db.sync().outboxEntry("sessions", target.id))
    assertBaseAndConverge(sourceChange, publishedSource.copy(voiceNames = null))
  }
}
