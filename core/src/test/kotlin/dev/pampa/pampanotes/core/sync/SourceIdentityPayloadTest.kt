package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SourceIdentityPayloadTest {
  private val source = SourceEntity("src", "n", SourceKind.SDOCX, "Alpha.sdocx", "application/sdoc", 120, "sha", importedAt = 5)
  private fun payload(value: SourceEntity) = SyncCodec.json.encodeToJsonElement(SourceEntity.serializer(), value)

  @Test fun `null Samsung identity retains old source hash and older payloads decode`() {
    val encoded = payload(source)
    val old = JsonObject(encoded.jsonObject - "sdocxCreatedAtMillis")
    assertEquals(SyncCodec.hash(old), SyncCodec.hash(encoded))
    assertEquals(source, SyncCodec.json.decodeFromJsonElement(SourceEntity.serializer(), old))
  }

  @Test fun `intrinsic identity travels as source content independently of its attachment`() {
    val identified = source.copy(sdocxCreatedAtMillis = 1_700_000_000_000L, archivedAt = 100)
    val wire = SyncCodec.json.encodeToString(SourceEntity.serializer(), identified)
    assertEquals(identified, SyncCodec.json.decodeFromString(SourceEntity.serializer(), wire))
    assertNotEquals(SyncCodec.hash(payload(source)), SyncCodec.hash(payload(identified)))
    assertNotEquals(SyncCodec.hash(payload(identified)), SyncCodec.hash(payload(identified.copy(sdocxCreatedAtMillis = 1_700_000_001_000L))))
  }
  @Test fun `older clients cannot erase identity for the same original`() {
    val known = source.copy(sdocxCreatedAtMillis = 1_700_000_000_000L)
    val remote = source.copy(archivedAt = 100)
    assertEquals(remote.copy(sdocxCreatedAtMillis = known.sdocxCreatedAtMillis), SourceIdentityMerge.into(known, remote))
    assertEquals(remote.copy(sha256 = "different"), SourceIdentityMerge.into(known, remote.copy(sha256 = "different")))
    assertEquals(remote.copy(kind = SourceKind.PDF), SourceIdentityMerge.into(known, remote.copy(kind = SourceKind.PDF)))
    val evidence = remote.copy(sdocxCreatedAtMillis = 1_700_000_001_000L)
    assertEquals(evidence, SourceIdentityMerge.into(known, evidence))
  }

}
