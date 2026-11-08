package dev.pampa.pampanotes.core.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.export.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.importing.PampaBundleArchive
import dev.pampa.pampanotes.core.importing.PampaBundleNote
import dev.pampa.pampanotes.core.transcription.VoiceNames
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Prepared only: parent owns AndroidTest compilation; device/runtime execution is waived. */
@RunWith(AndroidJUnit4::class)
class PampaCombinedVoiceOwnershipRoomTest {
  private lateinit var context: Context
  private lateinit var db: PampaDatabase
  private lateinit var payloads: SyncPayloads
  private lateinit var applier: SyncApplier
  private val databaseName = "pn-combined-voice-${UUID.randomUUID()}.db"
  private var seq = 10L
  private val marker = "{\"_pampaVoiceClocks\":{}}"

  @Before fun setup() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    open()
  }
  private fun open() {
    db = PampaDatabase.build(context, databaseName)
    payloads = SyncPayloads(db.folders(), db.notes(), db.tags(), db.sessions(), db.audioParts(),
      db.transcripts(), db.segments(), db.sources(), db.exportPresets(), db.stats(), db.sync())
    applier = SyncApplier(db, AppFiles(context), payloads)
  }
  private fun restart() { db.close(); open() }
  private fun fresh() { db.close(); context.deleteDatabase(databaseName); open() }
  @After fun close() { db.close(); context.deleteDatabase(databaseName) }
  private fun raw(name: String?, clock: Long = 100) = VoiceNames.encode(
    name?.let { mapOf("p|SPEAKER_00" to it) }.orEmpty(), mapOf("p" to clock))!!
  private fun row(id: String, names: String? = null, at: Long = 10) = SessionEntity(
    id, "n", "Title $at", "2026-11-08", if (id == "source") 0 else 1,
    createdAt = 1, updatedAt = at, voiceNames = names)
  private fun part(id: String = "p", owner: String = "source", at: Long = 1) = AudioPartEntity(
    id, owner, 0, "missing-combined-voice-$id.m4a", "$id.m4a", "audio/mp4", 1, 1000, "a".repeat(64), at)
  private fun wire(row: SessionEntity, opaque: Boolean = false): WireChange {
    val encoded = SyncPayloads.encode(SessionEntity.serializer(), row, row.updatedAt)
    return WireChange("sessions", row.id, WireChange.OP_UPSERT, row.updatedAt,
      if (opaque) "opaque-${seq}" else encoded.hash, payload = encoded.payload, seq = seq++, deviceId = "remote")
  }
  private fun wire(row: AudioPartEntity): WireChange {
    val encoded = SyncPayloads.encode(AudioPartEntity.serializer(), row, row.createdAt)
    return WireChange("audio_parts", row.id, WireChange.OP_UPSERT, row.createdAt, encoded.hash,
      payload = encoded.payload, seq = seq++, deviceId = "remote")
  }
  private suspend fun apply(vararg changes: WireChange) = applier.apply(changes.toList(), "account", "this-device")
  private suspend fun pending() = db.withTransaction { SyncPendingVoiceJournal.load(db.sync(), "account") }
  private suspend fun seed(rows: List<SessionEntity>, parts: List<AudioPartEntity>) {
    db.folders().upsert(FolderEntity("f", "Folder", createdAt = 1, updatedAt = 1))
    db.notes().upsert(NoteEntity("n", "f", "Note", createdAt = 1, updatedAt = 1))
    rows.forEach { db.sessions().upsert(it) }
    parts.forEach { db.audioParts().upsert(it) }
    db.sync().clearAllOutbox()
    rows.forEach { db.sync().upsertMeta(SyncMetaEntity("sessions", it.id, 1, "agreed-${it.id}", 1)) }
  }
  private suspend fun assertExportable() {
    val graph = PampaBundleNote(db.notes().get("n")!!, sessions = db.sessions().all(), parts = db.audioParts().all())
    val directory = File(context.cacheDir, "pn-combined-export-${UUID.randomUUID()}").also { it.mkdirs() }
    try {
      val file = File(directory, "fixture.pn")
      val export = ExportNote(graph.note, listOf("Folder"), emptyList(), emptyList(), emptyList(), native = graph)
      file.outputStream().use { PampaNoteWriter(directory, directory).write(ExportSet(
        "Folder", "folder", listOf(export), "Combined integration fixture", 1, folders = listOf(db.folders().get("f")!!)), it) }
      assertEquals(graph.sessions.associate { it.id to it.voiceNames },
        PampaBundleArchive.read(file).data.notes.single().sessions.associate { it.id to it.voiceNames })
    } finally { directory.deleteRecursively() }
  }

  @Test fun destinationEditAndRemovalSurviveCommittedPageRestartCleanupPushAndLaterAudio() = runBlocking {
    for (name in listOf("Giulia", null)) {
      fresh()
      val source = row("source", raw("Alice"))
      val target = row("target", at = 50)
      seed(listOf(source, target), listOf(part()))
      val remote = target.copy(updatedAt = 600, voiceNames = raw(name, 300), title = "Target metadata")
      val change = wire(remote, opaque = true)
      apply(change)
      assertEquals(source, db.sessions().get(source.id))
      assertEquals("{\"_pampaVoiceClocks\":{}}", db.sessions().get(target.id)!!.voiceNames)
      assertEquals(change.hash, db.sync().meta("sessions", target.id)!!.hash)
      assertEquals(change.seq, db.sync().meta("sessions", target.id)!!.serverSeq)
      assertNotNull(db.sync().outboxEntry("sessions", target.id))
      assertFalse(payloads.encode("sessions", target.id)!!.payload.toString().contains("Giulia"))
      assertExportable()
      restart() // A new database connection/applier, not an in-memory helper handoff.
      assertEquals(raw(name, 300), pending().single().voiceNames)
      // Acknowledge the ordinary cleanup push, then pull its canonical echo on a separate page.
      val clean = db.sessions().get(target.id)!!
      db.sync().clearAllOutbox()
      db.sync().upsertMeta(db.sync().meta("sessions", target.id)!!.copy(hash = payloads.encode("sessions", target.id)!!.hash))
      apply(wire(clean))
      assertNull(db.sync().outboxEntry("sessions", target.id))
      assertEquals(raw(name, 300), pending().single().voiceNames)
      apply(wire(part(owner = target.id)))
      assertEquals(remote.copy(voiceNames = raw(name, 300)), db.sessions().get(target.id))
      assertNull(db.sessions().get(source.id)!!.voiceNames)
      assertTrue(pending().isEmpty())
      assertNotNull(db.sync().outboxEntry("sessions", target.id))
      assertExportable()
      // Acknowledge enrichment and verify its echo produces no endless outbox cycle.
      val canonical = db.sessions().get(target.id)!!
      db.sync().clearAllOutbox()
      db.sync().upsertMeta(db.sync().meta("sessions", target.id)!!.copy(hash = payloads.encode("sessions", target.id)!!.hash))
      apply(wire(canonical))
      assertNull(db.sync().outboxEntry("sessions", target.id))
    }
  }

  @Test fun allBoundedPageOrdersRetainNewerDestinationAuthorityAndClearAllExcludedHolders() = runBlocking {
    for (name in listOf("Giulia", null)) for (order in listOf("STA", "SAT", "TSA", "TAS", "AST", "ATS")) {
      fresh()
      val source = row("source", raw("Alice"))
      val target = row("target", at = 50)
      seed(listOf(source, target), listOf(part()))
      for (step in order) {
        when (step) {
          'S' -> apply(wire(source.copy(voiceNames = marker, updatedAt = 600)))
          'T' -> apply(wire(target.copy(voiceNames = raw(name, 300), updatedAt = 600)))
          'A' -> apply(wire(part(owner = target.id)))
        }
        restart()
      }
      assertEquals("$order / $name", raw(name, 300), db.sessions().get(target.id)!!.voiceNames)
      assertTrue(VoiceNames.clocks(db.sessions().get(source.id)!!.voiceNames).isEmpty())
      assertTrue(pending().isEmpty())
      assertExportable()
    }
  }

  @Test fun freshClientFirstAudioCleansNonownerSessionWithStationaryAudioAndOtherEvidenceHolders() = runBlocking {
    val source = row("source", raw("Alice"), 600)
    val target = row("target", raw("Alice"), 600)
    val third = row("third", raw("Stale", 90), 900)
    seed(listOf(source, target, third), listOf(part("q")))
    // No local p row and no previous p owner: this is the confirmed fresh-install reproduction.
    apply(wire(part(owner = target.id)))
    assertNull(db.sessions().get(source.id)!!.voiceNames)
    assertNull(db.sessions().get(third.id)!!.voiceNames)
    assertEquals(raw("Alice"), db.sessions().get(target.id)!!.voiceNames)
    assertTrue(pending().isEmpty())
    assertExportable()
  }

  @Test fun rejectedAudioMoveAndLocalNewerSessionKeepPendingAuthorityWithoutAffectingCurrentOwner() = runBlocking {
    val source = row("source", raw("Alice"))
    val target = row("target", at = 900).copy(transcribingOn = "this-device", transcribingSince = 11)
    val audio = part(at = 1000)
    seed(listOf(source, target), listOf(audio))
    db.sync().markDirty(SyncOutboxEntity(tbl = "sessions", rowId = target.id, op = WireChange.OP_UPSERT))
    db.sync().upsertMeta(SyncMetaEntity("audio_parts", "p", 1, "agreed-audio", 1))
    db.sync().markDirty(SyncOutboxEntity(tbl = "audio_parts", rowId = "p", op = WireChange.OP_UPSERT))
    val remote = target.copy(voiceNames = raw(null, 300), updatedAt = 600, title = "Remote", transcribingOn = null, transcribingSince = null)
    val change = wire(remote, opaque = true)
    val result = apply(change, wire(audio.copy(sessionId = target.id, createdAt = 1)))
    assertEquals(2, result.skipped)
    assertEquals(source, db.sessions().get(source.id))
    assertEquals(target, db.sessions().get(target.id))
    assertEquals(source.id, db.audioParts().get("p")!!.sessionId)
    assertEquals(raw(null, 300), pending().single().voiceNames)
    assertEquals(change.hash, db.sync().meta("sessions", target.id)!!.hash)
    restart()
    db.sync().forgetOutbox("audio_parts", "p")
    apply(wire(audio.copy(sessionId = target.id, createdAt = 1001)))
    assertEquals(target.copy(voiceNames = raw(null, 300)), db.sessions().get(target.id))
    assertTrue(pending().isEmpty())
  }

  @Test fun pendingWriteAndConsumptionRollbackTogetherWithTheRoomPageOnCancellation() = runBlocking {
    val source = row("source", raw("Alice"))
    val target = row("target", at = 50)
    seed(listOf(source, target), listOf(part()))
    val change = wire(target.copy(voiceNames = raw("Giulia", 300), updatedAt = 600))
    try {
      db.withTransaction { apply(change); assertEquals(1, pending().size); throw CancellationException("fixture rollback write") }
      fail("Expected rollback")
    } catch (_: CancellationException) { }
    assertTrue(pending().isEmpty())
    assertEquals(target, db.sessions().get(target.id))
    apply(change)
    restart()
    val move = wire(part(owner = target.id))
    try {
      db.withTransaction { apply(move); assertTrue(pending().isEmpty()); throw CancellationException("fixture rollback consume") }
      fail("Expected rollback")
    } catch (_: CancellationException) { }
    assertEquals(source.id, db.audioParts().get("p")!!.sessionId)
    assertEquals(raw("Giulia", 300), pending().single().voiceNames)
    apply(move)
    assertEquals(raw("Giulia", 300), db.sessions().get(target.id)!!.voiceNames)
    assertTrue(pending().isEmpty())
  }

  @Test fun accountIsolationResetAndCascadedSessionDeletionRemovePrivateJournal() = runBlocking {
    val source = row("source", raw("Alice"))
    val target = row("target")
    seed(listOf(source, target), listOf(part()))
    suspend fun defer() { apply(wire(target.copy(voiceNames = raw("Giulia", 300), updatedAt = 600))) }
    defer()
    assertTrue(db.withTransaction { SyncPendingVoiceJournal.load(db.sync(), "different-account") }.isEmpty())
    assertTrue(pending().isEmpty())
    defer()
    assertEquals(1, pending().size)
    db.sync().clearAllMeta() // Same operation used by switchAccount/ensureIdentity reseeding.
    assertTrue(pending().isEmpty())
    defer()
    db.sync().clearAllOutbox()
    apply(WireChange("notes", "n", WireChange.OP_DELETE, 1000, seq = seq++, deviceId = "remote"))
    assertTrue(db.sessions().all().isEmpty())
    assertTrue(pending().isEmpty())
    assertFalse(db.sync().allMeta().any { it.tbl.startsWith("_private_pending_session_voices") })
  }
}
