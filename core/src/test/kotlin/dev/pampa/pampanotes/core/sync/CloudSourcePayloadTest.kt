package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CloudSourcePayloadTest {
  private val raw = TranscriptEntity(
    id = "raw", sessionId = "s", kind = TranscriptKind.RAW, provider = "mistral",
    model = "voxtral-mini-transcribe-3", language = "it", text = "Ciao, mondo!", wordCount = 2, createdAt = 1,
  )
  private fun encode(value: TranscriptEntity) = SyncPayloads.encode(TranscriptEntity.serializer(), value, value.createdAt)

  @Test fun `old raw hash remains unchanged when source provenance is absent`() {
    val encoded = encode(raw)
    val old = JsonObject(encoded.payload.jsonObject - "cloudSourceJson")
    assertEquals(SyncCodec.hash(old), encoded.hash)
    assertEquals(raw, SyncCodec.json.decodeFromJsonElement(TranscriptEntity.serializer(), old))
  }

  @Test fun `source whitespace punctuation and language survive serialization exactly`() {
    val source = "{\"language\":\"it\",\"text\":\"  Ciao,  mondo!\\nÈ così… 😀\\t\"}"
    val sourced = raw.copy(cloudSourceJson = source)
    val decoded = SyncCodec.json.decodeFromJsonElement(TranscriptEntity.serializer(), encode(sourced).payload)
    assertEquals(source, decoded.cloudSourceJson)
    assertEquals(raw.text, decoded.text)
    assertNotEquals(encode(raw).hash, encode(sourced).hash)
    assertNotEquals(encode(sourced).hash, encode(sourced.copy(cloudSourceJson = source + " ")).hash)
  }

  @Test fun `null and explicitly empty public source have different meanings`() {
    val empty = raw.copy(cloudSourceJson = "")
    assertEquals("", SyncCodec.json.decodeFromJsonElement(TranscriptEntity.serializer(), encode(empty).payload).cloudSourceJson)
    assertNotEquals(encode(raw).hash, encode(empty).hash)
  }
}
