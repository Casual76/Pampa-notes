package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PampaSyncMetadataMergeTest {
  private val audio = AudioPartEntity("p", "s", 0, "local.m4a", "Lecture.m4a", "audio/mp4", 10, 1000, "a".repeat(64), 1,
    recordedAtEpochMs = 1_700_000_000_000, recordedAtLocal = "2023-11-14T23:13:20", recordedAtZoneId = "Europe/Rome",
    recordedAtSource = "media_metadata", recordedAtPrecision = "second")
  private val transcript = TranscriptEntity("t", "s", TranscriptKind.RAW, "openrouter", "model", text = "Exact words", wordCount = 2,
    createdAt = 1, cloudSourceJson = "immutable cloud source")
  private fun olderAudio() = SyncCodec.json.decodeFromJsonElement(AudioPartEntity.serializer(), JsonObject(
    SyncCodec.json.encodeToJsonElement(AudioPartEntity.serializer(), audio).jsonObject -
      setOf("recordedAtEpochMs", "recordedAtLocal", "recordedAtZoneId", "recordedAtSource", "recordedAtPrecision")))

  @Test fun `older audio payload cannot erase known origin and its metadata edits still apply`() {
    val incoming = olderAudio().copy(fileName = "remote.bin", position = 2)
    val merged = SyncMetadataMerge.audio(audio, incoming)
    assertEquals(audio.copy(fileName = "remote.bin", position = 2), merged)
    assertNotEquals(SyncPayloads.encode(AudioPartEntity.serializer(), incoming, 1).hash,
      SyncPayloads.encode(AudioPartEntity.serializer(), merged, 1).hash)
    assertEquals(merged, SyncMetadataMerge.audio(incoming, merged)) // Local-wins direction.
  }

  @Test fun `different content or identity cannot inherit another recording origin`() {
    for (incoming in listOf(olderAudio().copy(sha256 = "b".repeat(64)), olderAudio().copy(sizeBytes = 11), olderAudio().copy(id = "other"))) {
      assertEquals(incoming, SyncMetadataMerge.audio(audio, incoming))
    }
  }

  @Test fun `weaker day provenance cannot downgrade an instant and conflicting origins fail safely`() {
    val day = olderAudio().copy(recordedAtLocal = "2023-11-14", recordedAtPrecision = "day")
    assertEquals(audio, SyncMetadataMerge.audio(audio, day))
    assertThrows(IllegalArgumentException::class.java) { SyncMetadataMerge.audio(audio, audio.copy(recordedAtEpochMs = 123)) }
    assertThrows(IllegalArgumentException::class.java) { SyncMetadataMerge.audio(audio, day.copy(recordedAtLocal = "2026-11-08")) }
  }

  @Test fun `older omitted or null cloud source retains exact public evidence without hiding text edits`() {
    val omitted = JsonObject(SyncCodec.json.encodeToJsonElement(TranscriptEntity.serializer(), transcript).jsonObject - "cloudSourceJson")
    val incoming = SyncCodec.json.decodeFromJsonElement(TranscriptEntity.serializer(), omitted).copy(text = "Remote text")
    assertEquals(transcript.copy(text = "Remote text"), SyncMetadataMerge.transcript(transcript, incoming))
    assertEquals(transcript, SyncMetadataMerge.transcript(incoming, transcript))
    assertEquals("", SyncMetadataMerge.transcript(transcript.copy(cloudSourceJson = ""), incoming).cloudSourceJson)
    assertEquals("", SyncMetadataMerge.transcript(null, incoming.copy(cloudSourceJson = "")).cloudSourceJson)
  }

  @Test fun `explicit empty and different nonnull cloud sources remain conflicts`() {
    for (source in listOf("", "different immutable source")) {
      assertThrows(IllegalArgumentException::class.java) { SyncMetadataMerge.transcript(transcript, transcript.copy(cloudSourceJson = source)) }
    }
  }

  @Test fun `null legacy audio and transcript hashes remain exact`() {
    val oldAudio = olderAudio()
    val oldTranscript = transcript.copy(cloudSourceJson = null)
    val a = SyncPayloads.encode(AudioPartEntity.serializer(), SyncMetadataMerge.audio(null, oldAudio), 1)
    val t = SyncPayloads.encode(TranscriptEntity.serializer(), SyncMetadataMerge.transcript(null, oldTranscript), 1)
    assertEquals(a.hash, SyncCodec.hash(JsonObject(a.payload.jsonObject -
      setOf("recordedAtEpochMs", "recordedAtLocal", "recordedAtZoneId", "recordedAtSource", "recordedAtPrecision"))))
    assertEquals(t.hash, SyncCodec.hash(JsonObject(t.payload.jsonObject - "cloudSourceJson")))
  }
}
