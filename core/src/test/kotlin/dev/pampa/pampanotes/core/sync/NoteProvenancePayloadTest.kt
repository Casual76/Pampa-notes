package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.NoteEntity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NoteProvenancePayloadTest {
  private val note = NoteEntity("n", "f", "Title", "Body", createdAt = 1, updatedAt = 2)

  @Test fun `default provenance preserves old hash and old payload compatibility`() {
    val plain = SyncCodec.json.encodeToJsonElement(NotePayload.serializer(), NotePayload(note))
    val old = JsonObject(plain.jsonObject + ("note" to JsonObject(plain.jsonObject.getValue("note").jsonObject - "bundleNativeAdopted")))
    assertEquals(SyncCodec.hash(old), SyncCodec.hash(plain))
    assertNull(SyncCodec.json.decodeFromJsonElement(NotePayload.serializer(), old).note.bundleNativeAdopted)
    val verifiedLegacy = SyncCodec.json.encodeToJsonElement(NotePayload.serializer(), NotePayload(note.copy(bundleNativeAdopted = false)))
    assertNotEquals(SyncCodec.hash(plain), SyncCodec.hash(verifiedLegacy))
    assertEquals(false, SyncCodec.json.decodeFromJsonElement(NotePayload.serializer(), verifiedLegacy).note.bundleNativeAdopted)
    val known = NotePayload(note.copy(bundleNativeAdopted = true, bundleBodyBase = "Private baseline"))
    val encoded = SyncCodec.json.encodeToJsonElement(NotePayload.serializer(), known)
    assertEquals(known, SyncCodec.json.decodeFromJsonElement(NotePayload.serializer(), encoded))
    assertNotEquals(SyncCodec.hash(plain), SyncCodec.hash(encoded))
  }
}
