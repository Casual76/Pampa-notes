package dev.pampa.pampanotes.core.sync

import androidx.room.withTransaction
import dev.pampa.pampanotes.core.alignment.CloudSources
import dev.pampa.pampanotes.core.alignment.CloudTextPart
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SyncDao
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.SyncMetaEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.Hashing
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Production applier and entity serializer, with in-memory DAO ports and transaction execution. */
class SyncCloudSourceApplierTest {
  private val db = mockk<PampaDatabase>(relaxed = true)
  private val sync = mockk<SyncDao>(relaxed = true)
  private val transcripts = mockk<TranscriptDao>(relaxed = true)
  private val payloads = mockk<SyncPayloads>()
  private var saved: TranscriptEntity? = null
  private var meta: SyncMetaEntity? = null
  private fun applier() = SyncApplier(db, mockk<AppFiles>(), payloads)

  @Before fun setup() {
    mockkStatic("androidx.room.RoomDatabaseKt")
    every { db.sync() } returns sync
    every { db.transcripts() } returns transcripts
    coEvery { db.withTransaction<Any?>(any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
    coEvery { payloads.encode("transcripts", "r") } answers {
      saved?.let { SyncPayloads.encode(TranscriptEntity.serializer(), it, it.createdAt) }
    }
    coEvery { db.sync().outboxEntry(any(), any()) } returns null
    coEvery { db.sync().meta("transcripts", "r") } answers { meta }
    coEvery { db.sync().upsertMeta(any()) } answers { meta = firstArg() }
    coEvery { db.sessions().get("s") } returns SessionEntity(id = "s", noteId = "n", date = "2026-11-08", position = 0, createdAt = 1, updatedAt = 1)
    coEvery { db.transcripts().upsert(any()) } answers { saved = firstArg() }
    coEvery { transcripts.get("r") } answers { saved }
  }
  @After fun teardown() { unmockkStatic("androidx.room.RoomDatabaseKt") }

  private fun row(): TranscriptEntity {
    val text = "voce, CRLF\r\n😀! ".repeat(35_000)
    val raw = TranscriptEntity(id = "r", sessionId = "s", kind = TranscriptKind.RAW, provider = "mistral",
      model = "voxtral-mini-transcribe-3", language = "it", text = text, wordCount = 1, createdAt = 42)
    val part = CloudTextPart("p", Hashing.sha256("synthetic audio identity"), text, "it", raw.provider, raw.model)
    return raw.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(raw, text, listOf(part))))
  }

  @Test fun `encode wire pull actual applier writes expanded exact entity and agreed hash without resync`() = runBlocking {
    val row = row()
    val encoded = SyncPayloads.encode(TranscriptEntity.serializer(), row, row.createdAt)
    val wire = WireChange("transcripts", "r", "U", 42, encoded.hash, encoded.payload, seq = 1, deviceId = "other")
    val response = SyncCodec.json.encodeToString(PullResponse.serializer(), PullResponse(listOf(wire), 1, false))
    val pull = SyncCodec.json.decodeFromString(PullResponse.serializer(), response)
    val first = applier().apply(pull.changes, "owner", "fixture")
    assertEquals(1, first.applied)
    assertEquals(0, first.forked)
    assertEquals(row, saved)
    assertEquals(encoded.hash, meta!!.hash)
    assertEquals(encoded.hash, payloads.encode("transcripts", "r")!!.hash)
    val again = applier().apply(pull.changes, "owner", "fixture")
    assertEquals(0, again.forked)
    assertEquals(row, saved)
    coVerify(exactly = 0) { sync.markDirty(any()) }
  }

  @Test fun `unsupported envelope aborts before any database or metadata write`() = runBlocking {
    val encoded = SyncPayloads.encode(TranscriptEntity.serializer(), row(), 42)
    val malformed = JsonObject((encoded.payload as JsonObject) + ("cloudSourceJson" to JsonPrimitive(SyncCloudSourceWire.PREFIX + "99:opaque")))
    try {
      applier().apply(listOf(WireChange("transcripts", "r", "U", 42, "hash", malformed)), "owner", "fixture")
      fail("Unsupported transport must fail before graph use")
    } catch (_: IllegalArgumentException) { }
    assertNull(saved)
    assertNull(meta)
    coVerify(exactly = 0) { transcripts.upsert(any()) }
    coVerify(exactly = 0) { sync.upsertMeta(any()) }
  }
}
