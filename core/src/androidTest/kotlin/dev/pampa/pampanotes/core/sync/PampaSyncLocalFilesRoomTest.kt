package dev.pampa.pampanotes.core.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SyncMetaEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.Hashing
import dev.pampa.pampanotes.core.repo.SessionRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Compile-only in PN correction round 1: no Android runtime execution was requested. */
@RunWith(AndroidJUnit4::class)
class PampaSyncLocalFilesRoomTest {
  private lateinit var root: File
  private lateinit var a: Device
  private lateinit var b: Device
  private val content = "verified attachment bytes".toByteArray()
  private val sha = Hashing.sha256(content.inputStream())
  private val originalPart get() = AudioPartEntity("p", "session", 0, "p.m4a", "Lecture.m4a", "audio/mp4", content.size.toLong(), 1000, sha, 11)
  private val originalSource get() = SourceEntity("source", "note", SourceKind.SDOCX, "Lecture.sdocx", "application/zip", content.size.toLong(), sha,
    storedFileName = "source.sdocx", importedAt = 12, archivedAt = 40, derivedFromId = "parent", sdocxCreatedAtMillis = 31)

  private class Device(context: Context) {
    val db = PampaDatabase.inMemory(context)
    val files = AppFiles(context)
    fun payloads() = SyncPayloads(db.folders(), db.notes(), db.tags(), db.sessions(), db.audioParts(), db.transcripts(), db.segments(),
      db.sources(), db.exportPresets(), db.stats(), db.sync())
    fun applier() = SyncApplier(db, files, payloads())
    suspend fun apply(vararg changes: WireChange) = applier().apply(changes.toList(), "account", "device")
  }

  @Before fun setUp() = runBlocking {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    root = File(base.cacheDir, "pn-sync-files-${UUID.randomUUID()}").apply { mkdirs() }
    fun context(name: String) = object : ContextWrapper(base) {
      override fun getFilesDir() = File(root, "$name/files").apply { mkdirs() }
      override fun getCacheDir() = File(root, "$name/cache").apply { mkdirs() }
    }
    a = Device(context("A"))
    b = Device(context("B"))
    for (device in listOf(a, b)) {
      device.apply(up("folders", "folder", FolderEntity.serializer(), FolderEntity("folder", "Folder", createdAt = 1, updatedAt = 1)),
        up("notes", "note", NotePayload.serializer(), NotePayload(NoteEntity("note", "folder", "Note", createdAt = 2, updatedAt = 2))),
        up("sessions", "session", SessionEntity.serializer(), SessionEntity("session", "note", date = "2026-11-08", position = 0, createdAt = 3, updatedAt = 3)))
    }
  }

  @After fun tearDown() {
    a.db.close()
    b.db.close()
    root.deleteRecursively()
  }

  private fun <T> up(table: String, id: String, serializer: KSerializer<T>, value: T, seq: Long = 5): WireChange {
    val payload = SyncCodec.json.encodeToJsonElement(serializer, value)
    return WireChange(table, id, "U", 10, SyncCodec.hash(payload), payload, seq = seq, deviceId = "other")
  }

  private suspend fun wire(device: Device, table: String, id: String, seq: Long): WireChange {
    val encoded = device.payloads().encode(table, id)!!
    return WireChange(table, id, "U", encoded.updatedAt, encoded.hash, encoded.payload, seq = seq, deviceId = "other")
  }

  private suspend fun acknowledge(device: Device, change: WireChange) {
    device.db.sync().upsertMeta(SyncMetaEntity(change.tbl, change.id, change.seq, change.hash, change.updatedAt))
    device.db.sync().forgetOutbox(change.tbl, change.id)
  }

  @Test fun B_missing_then_PN_staging_retains_A_references_through_sweep_and_sync_converges() = runBlocking {
    a.files.audioFile(originalPart.fileName).writeBytes(content)
    a.files.sourceFile(originalSource.storedFileName!!).writeBytes(content)
    for (device in listOf(a, b)) device.apply(up("audio_parts", "p", AudioPartEntity.serializer(), originalPart),
      up("sources", "source", SourceEntity.serializer(), originalSource))
    assertFalse(b.files.audioFile("p.m4a").exists())
    assertFalse(b.files.sourceFile("source.sdocx").exists())
    // Accurate PN recovery post-state: synchronized rows existed on B, but their bytes did not.
    // The importer stages UUID.bin only in this missing-file case; with bytes it keeps the name.
    val recoveredPart = originalPart.copy(fileName = "f94ff075-01be-4725-90db-53c24287ed98.bin")
    val recoveredSource = originalSource.copy(storedFileName = "3f20661e-7cf6-48b9-89dc-492fa010487a.bin")
    b.files.audioFile(recoveredPart.fileName).writeBytes(content)
    b.files.sourceFile(recoveredSource.storedFileName!!).writeBytes(content)
    b.db.audioParts().upsert(recoveredPart)
    b.db.sources().upsert(recoveredSource)
    val audioWire = wire(b, "audio_parts", "p", 20)
    val sourceWire = wire(b, "sources", "source", 21)
    a.apply(audioWire, sourceWire)
    acknowledge(b, audioWire)
    acknowledge(b, sourceWire)
    assertEquals(originalPart, a.db.audioParts().get("p"))
    assertEquals(originalSource, a.db.sources().get("source"))
    assertNull(a.db.sync().outboxEntry("audio_parts", "p"))
    assertNull(a.db.sync().outboxEntry("sources", "source"))
    assertEquals(audioWire.hash, a.payloads().encode("audio_parts", "p")!!.hash)
    assertEquals(sourceWire.hash, a.payloads().encode("sources", "source")!!.hash)
    val now = System.currentTimeMillis() + AppFiles.ORPHAN_MIN_AGE_MS + 1000
    assertEquals(0, a.files.sweepOrphans(a.db.audioParts().fileNames().toSet(), a.db.sources().storedFileNames().toSet(), emptySet(), now))
    assertArrayEquals(content, a.files.audioFile("p.m4a").readBytes())
    assertArrayEquals(content, a.files.sourceFile("source.sdocx").readBytes())
    // Real Room triggers enqueue a touch; the actual push's equality branch can discard it.
    a.db.audioParts().upsert(a.db.audioParts().get("p")!!)
    a.db.sources().upsert(a.db.sources().get("source")!!)
    for ((table, id) in listOf("audio_parts" to "p", "sources" to "source")) {
      val entry = a.db.sync().outboxEntry(table, id)!!
      assertEquals(a.db.sync().meta(table, id)!!.hash, a.payloads().encode(table, id)!!.hash)
      a.db.sync().clearOutbox(table, id, entry.id)
      assertNull(a.db.sync().outboxEntry(table, id))
    }
    a.db.audioParts().rename("p", "Renamed lecture.m4a")
    val renamed = wire(a, "audio_parts", "p", 22)
    assertNotEquals(audioWire.hash, renamed.hash)
    b.apply(renamed)
    acknowledge(a, renamed)
    a.apply(renamed.copy(seq = 23))
    assertEquals("p.m4a", a.db.audioParts().get("p")!!.fileName)
    assertEquals(recoveredPart.fileName, b.db.audioParts().get("p")!!.fileName)
    assertEquals(a.payloads().encode("audio_parts", "p")!!.hash, b.payloads().encode("audio_parts", "p")!!.hash)
    assertTrue(a.db.sync().outbox().isEmpty())
    assertTrue(b.db.sync().outbox().isEmpty())
  }

  @Test fun destination_alias_wrong_content_uses_verified_old_alias_without_stealing_provenance() = runBlocking {
    a.apply(up("notes", "old-note", NotePayload.serializer(), NotePayload(NoteEntity("old-note", "folder", "Old note", createdAt = 2, updatedAt = 2))),
      up("sessions", "old-session", SessionEntity.serializer(), SessionEntity("old-session", "old-note", date = "2026-11-08", position = 0, createdAt = 3, updatedAt = 3)))
    val alias = originalPart.copy(id = "old-part", sessionId = "old-session", fileName = "old-part.m4a")
    val aliasSource = originalSource.copy(id = "old-source", noteId = "old-note", storedFileName = "old-source.sdocx", archivedAt = 80, sdocxCreatedAtMillis = 70)
    a.files.audioFile(alias.fileName).writeBytes(content)
    a.files.sourceFile(aliasSource.storedFileName!!).writeBytes(content)
    a.apply(up("audio_parts", alias.id, AudioPartEntity.serializer(), alias), up("sources", aliasSource.id, SourceEntity.serializer(), aliasSource))
    val wrongPart = originalPart.copy(sha256 = "e".repeat(64))
    val wrongSource = originalSource.copy(sha256 = "e".repeat(64), sdocxCreatedAtMillis = 99)
    a.apply(up("audio_parts", "p", AudioPartEntity.serializer(), wrongPart), up("sources", "source", SourceEntity.serializer(), wrongSource))
    a.apply(up("audio_parts", "p", AudioPartEntity.serializer(), originalPart.copy(fileName = "PN.bin"), 30),
      up("sources", "source", SourceEntity.serializer(), originalSource.copy(storedFileName = "PN-source.bin"), 31),
      WireChange("audio_parts", alias.id, "D", 40, seq = 32), WireChange("sources", aliasSource.id, "D", 40, seq = 33))
    val retainedAudio = a.db.audioParts().get("p")!!
    assertNotEquals(alias.fileName, retainedAudio.fileName)
    val retained = a.db.sources().get("source")!!
    assertNotEquals(aliasSource.storedFileName, retained.storedFileName)
    assertEquals(originalSource.archivedAt, retained.archivedAt)
    assertEquals(originalSource.sdocxCreatedAtMillis, retained.sdocxCreatedAtMillis)
    assertArrayEquals(content, a.files.audioFile(retainedAudio.fileName).readBytes())
    assertArrayEquals(content, a.files.sourceFile(retained.storedFileName!!).readBytes())
    assertEquals(0, a.files.sweepOrphans(a.db.audioParts().fileNames().toSet(), a.db.sources().storedFileNames().toSet(), emptySet(),
      System.currentTimeMillis() + AppFiles.ORPHAN_MIN_AGE_MS + 1000))
  }

  @Test fun genuinely_distinct_equal_content_rows_have_independent_file_lifetimes() = runBlocking {
    a.files.audioFile(originalPart.fileName).writeBytes(content)
    a.files.sourceFile(originalSource.storedFileName!!).writeBytes(content)
    a.apply(up("audio_parts", "p", AudioPartEntity.serializer(), originalPart), up("sources", "source", SourceEntity.serializer(), originalSource))
    // Legitimate different native identities can advertise the same remote filename and bytes.
    a.apply(up("audio_parts", "different-part", AudioPartEntity.serializer(), originalPart.copy(id = "different-part", position = 1), 20),
      up("sources", "different-source", SourceEntity.serializer(), originalSource.copy(id = "different-source"), 21))
    val secondPart = a.db.audioParts().get("different-part")!!
    val secondSource = a.db.sources().get("different-source")!!
    assertNotEquals(originalPart.fileName, secondPart.fileName)
    assertNotEquals(originalSource.storedFileName, secondSource.storedFileName)
    SessionRepository(a.db.sessions(), a.db.audioParts(), a.db.transcripts(), a.db.segments(), a.db.notes(), a.files, a.db).deletePart("p")
    assertNull(a.db.audioParts().get("p"))
    assertArrayEquals(content, a.files.audioFile(secondPart.fileName).readBytes())
    // Match source replacement's unconditional unlink after deleting its own source row.
    a.db.sources().delete("source")
    assertTrue(a.files.sourceFile(originalSource.storedFileName!!).delete())
    assertArrayEquals(content, a.files.sourceFile(secondSource.storedFileName!!).readBytes())
    val encoded = a.payloads().encode("audio_parts", secondPart.id)!!
    assertEquals(a.db.sync().meta("audio_parts", secondPart.id)!!.hash, encoded.hash)
  }

  @Test fun older_payloads_keep_public_origin_and_cloud_source_and_queue_only_real_enrichment() = runBlocking {
    val known = originalPart.copy(recordedAtLocal = "2026-11-08", recordedAtSource = "media_metadata", recordedAtPrecision = "day")
    val transcript = TranscriptEntity("t", "session", TranscriptKind.RAW, "openrouter", "model", text = "Original", wordCount = 1,
      createdAt = 11, cloudSourceJson = "exact immutable cloud source")
    a.files.audioFile(known.fileName).writeBytes(content)
    a.apply(up("audio_parts", "p", AudioPartEntity.serializer(), known), up("transcripts", "t", TranscriptEntity.serializer(), transcript))
    fun omit(change: WireChange, keys: Set<String>): WireChange {
      val payload = JsonObject(change.payload!!.jsonObject - keys)
      return change.copy(payload = payload, hash = SyncCodec.hash(payload))
    }
    val oldAudio = omit(up("audio_parts", "p", AudioPartEntity.serializer(), known.copy(originalName = "Remote title", fileName = "PN.bin"), 20),
      setOf("recordedAtEpochMs", "recordedAtLocal", "recordedAtZoneId", "recordedAtSource", "recordedAtPrecision"))
    val oldTranscript = omit(up("transcripts", "t", TranscriptEntity.serializer(), transcript.copy(text = "Remote words"), 21), setOf("cloudSourceJson"))
    a.apply(oldAudio, oldTranscript)
    assertEquals(known.copy(originalName = "Remote title"), a.db.audioParts().get("p"))
    assertEquals(transcript.copy(text = "Remote words"), a.db.transcripts().get("t"))
    assertNotNull(a.db.sync().outboxEntry("audio_parts", "p"))
    assertNotNull(a.db.sync().outboxEntry("transcripts", "t"))
    assertNotEquals(oldAudio.hash, a.payloads().encode("audio_parts", "p")!!.hash)
    assertNotEquals(oldTranscript.hash, a.payloads().encode("transcripts", "t")!!.hash)
  }

  @Test fun immutable_cloud_source_conflict_rolls_back_rows_and_copied_files_together() = runBlocking {
    a.files.audioFile(originalPart.fileName).writeBytes(content)
    a.files.sourceFile(originalSource.storedFileName!!).writeBytes(content)
    val transcript = TranscriptEntity("t", "session", TranscriptKind.RAW, "openrouter", "model", text = "Original", wordCount = 1,
      createdAt = 11, cloudSourceJson = "exact immutable cloud source")
    a.apply(up("audio_parts", "p", AudioPartEntity.serializer(), originalPart), up("sources", "source", SourceEntity.serializer(), originalSource),
      up("transcripts", "t", TranscriptEntity.serializer(), transcript))
    val beforeAudio = a.files.audio.listFiles()!!.map { it.name }.toSet()
    val beforeSources = a.files.sources.listFiles()!!.map { it.name }.toSet()
    try {
      a.apply(up("audio_parts", "copy-part", AudioPartEntity.serializer(), originalPart.copy(id = "copy-part"), 20),
        up("sources", "copy-source", SourceEntity.serializer(), originalSource.copy(id = "copy-source"), 21),
        up("transcripts", "t", TranscriptEntity.serializer(), transcript.copy(cloudSourceJson = ""), 22))
      fail("Explicit empty is conflicting evidence, not an omitted source")
    } catch (_: IllegalArgumentException) {
      assertEquals(transcript, a.db.transcripts().get("t"))
      assertNull(a.db.audioParts().get("copy-part"))
      assertNull(a.db.sources().get("copy-source"))
      assertEquals(beforeAudio, a.files.audio.listFiles()!!.map { it.name }.toSet())
      assertEquals(beforeSources, a.files.sources.listFiles()!!.map { it.name }.toSet())
      assertArrayEquals(content, a.files.audioFile(originalPart.fileName).readBytes())
      assertArrayEquals(content, a.files.sourceFile(originalSource.storedFileName!!).readBytes())
    }
  }

  @Test fun newer_local_edits_also_learn_remote_provenance_without_losing_local_content() = runBlocking {
    val transcript = TranscriptEntity("t", "session", TranscriptKind.RAW, "openrouter", "model", text = "Original", wordCount = 1, createdAt = 11)
    a.files.audioFile(originalPart.fileName).writeBytes(content)
    a.apply(up("audio_parts", "p", AudioPartEntity.serializer(), originalPart), up("transcripts", "t", TranscriptEntity.serializer(), transcript))
    a.db.audioParts().rename("p", "Local title")
    a.db.transcripts().upsert(transcript.copy(text = "Local words"))
    val remoteAudio = originalPart.copy(recordedAtLocal = "2026-11-08", recordedAtPrecision = "day", recordedAtSource = "media_metadata")
    a.apply(up("audio_parts", "p", AudioPartEntity.serializer(), remoteAudio, 20).copy(updatedAt = 1),
      up("transcripts", "t", TranscriptEntity.serializer(), transcript.copy(cloudSourceJson = "immutable remote source"), 21).copy(updatedAt = 1))
    assertEquals(remoteAudio.copy(originalName = "Local title"), a.db.audioParts().get("p"))
    assertEquals(transcript.copy(text = "Local words", cloudSourceJson = "immutable remote source"), a.db.transcripts().get("t"))
    assertNotNull(a.db.sync().outboxEntry("audio_parts", "p"))
    assertNotNull(a.db.sync().outboxEntry("transcripts", "t"))
  }

  @Test fun changed_content_and_wrong_local_bytes_are_never_retained_as_incoming_content() = runBlocking {
    a.files.audioFile("p.m4a").writeBytes(content)
    a.files.sourceFile("source.sdocx").writeBytes(content)
    a.apply(up("audio_parts", "p", AudioPartEntity.serializer(), originalPart), up("sources", "source", SourceEntity.serializer(), originalSource))
    val changedPart = originalPart.copy(fileName = "changed.bin", sha256 = "d".repeat(64))
    val changedSource = originalSource.copy(storedFileName = "changed-source.bin", sha256 = "d".repeat(64), sdocxCreatedAtMillis = null, archivedAt = 0)
    a.apply(up("audio_parts", "p", AudioPartEntity.serializer(), changedPart, 40), up("sources", "source", SourceEntity.serializer(), changedSource, 41))
    assertEquals(changedPart, a.db.audioParts().get("p"))
    assertEquals(changedSource, a.db.sources().get("source"))
    assertFalse(a.files.audioFile(changedPart.fileName).exists())
    assertFalse(a.files.sourceFile(changedSource.storedFileName!!).exists())
    // An incoming safe-looking name can already be occupied by unrelated local bytes.
    a.files.audioFile("changed.bin").writeBytes(content)
    a.files.sourceFile("changed-source.bin").writeBytes(content)
    a.apply(up("audio_parts", "p", AudioPartEntity.serializer(), changedPart, 42),
      up("sources", "source", SourceEntity.serializer(), changedSource, 43))
    val name = a.db.audioParts().get("p")!!.fileName
    assertNotEquals("changed.bin", name)
    assertFalse(a.files.audioFile(name).exists())
    val sourceName = a.db.sources().get("source")!!.storedFileName!!
    assertNotEquals("changed-source.bin", sourceName)
    assertFalse(a.files.sourceFile(sourceName).exists())
  }
}
