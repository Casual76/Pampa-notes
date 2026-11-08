package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.AudioPartEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioPartOriginPayloadTest {
  private val part = AudioPartEntity("p", "s", 0, "p.m4a", "original.m4a", "audio/mp4", 4, 1000, "sha", 999)
  private val fields = setOf("recordedAtEpochMs", "recordedAtLocal", "recordedAtZoneId", "recordedAtSource", "recordedAtPrecision")
  private fun encode(value: AudioPartEntity) = SyncPayloads.encode(AudioPartEntity.serializer(), value, value.createdAt)

  @Test fun `unknown origin keeps legacy hash and reads legacy payload`() {
    val encoded = encode(part)
    val legacy = JsonObject(encoded.payload.jsonObject.filterKeys { it !in fields })
    assertEquals(SyncCodec.hash(legacy), encoded.hash)
    assertEquals(part, SyncCodec.json.decodeFromJsonElement(AudioPartEntity.serializer(), legacy))
    assertNull(part.recordedAtEpochMs)
    assertNull(part.recordedAtZoneId)
  }

  @Test fun `complete source origin round trips without using import date`() {
    val sourced = part.copy(
      recordedAtEpochMs = 1_758_573_599_000,
      recordedAtLocal = "2025-09-22T23:59:59",
      recordedAtZoneId = "+02:00",
      recordedAtSource = "media_metadata",
      recordedAtPrecision = "second",
    )
    assertEquals(sourced, SyncCodec.json.decodeFromJsonElement(AudioPartEntity.serializer(), encode(sourced).payload))
    assertNotEquals(encode(part).hash, encode(sourced).hash)
  }

  @Test fun `every public origin field participates in sync identity`() {
    listOf(
      part.copy(recordedAtEpochMs = 123),
      part.copy(recordedAtLocal = "2025-09-22"),
      part.copy(recordedAtZoneId = "Europe/Rome"),
      part.copy(recordedAtSource = "filename"),
      part.copy(recordedAtPrecision = "day"),
    ).forEach { assertNotEquals(encode(part).hash, encode(it).hash) }
  }

  @Test fun `date only provenance never manufactures an instant or zone`() {
    val dated = part.copy(recordedAtLocal = "2025-09-22", recordedAtSource = "filename", recordedAtPrecision = "day")
    val restored = SyncCodec.json.decodeFromJsonElement(AudioPartEntity.serializer(), encode(dated).payload)
    assertEquals(dated, restored)
    assertNull(restored.recordedAtEpochMs)
    assertNull(restored.recordedAtZoneId)
  }

}
