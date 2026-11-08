package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.alignment.*
import dev.pampa.pampanotes.core.db.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Prepared Kotlin half of the v1 Worker/Android codec fixtures. Not runtime/Room evidence. */
class CombinedSourceSyncGateBTest {
  private val text = " Exact words. "
  private val part = CloudTextPart("p", AlignmentProtocol.sha256("audio"), text, "Italian", "openrouter", "pinned")
  private val raw = TranscriptEntity("r", "s", TranscriptKind.RAW, "openrouter", "pinned", "Italian", text, wordCount = 2, createdAt = 42)
  private val doc = CloudSources.create(raw, text, listOf(part))
  private val original = raw.copy(cloudSourceJson = CloudSources.encode(doc))
  private fun overlay(measured: Int = 1, start: Long = 100) = CloudOverlay("p", AlignmentProtocol.sha256(text), "it",
    AlignmentProtocol.tokens(text).mapIndexed { i, span -> AlignmentTiming(span.first, span.last + 1,
      if (i < measured) start + i * 200 else null, if (i < measured) start + i * 200 + 100 else null,
      if (i < measured) "measured" else "missing") })
  private fun sourced(overlays: List<CloudOverlay>) = raw.copy(cloudSourceJson = CloudSources.encode(doc.copy(overlays = overlays)))

  @Test fun `supported overlay enrichment converges in both Android merge directions without changing originals`() {
    val partial = sourced(listOf(overlay(1)))
    val measured = sourced(listOf(overlay(2)))
    val forward = SyncMetadataMerge.transcript(partial, measured)
    val reverse = SyncMetadataMerge.transcript(measured, partial)
    assertEquals(measured, forward); assertEquals(forward, reverse)
    assertEquals(forward, SyncMetadataMerge.transcript(forward, reverse))
    assertEquals(doc, CloudSources.decode(forward.cloudSourceJson)!!.copy(overlays = emptyList()))
    // Concurrent different measurements settle with the same ordering as Worker overlayKey.
    val other = sourced(listOf(overlay(1, 200)))
    assertEquals(SyncMetadataMerge.transcript(partial, other), SyncMetadataMerge.transcript(other, partial))
  }
  @Test fun `immutable source and unknown nonempty metadata cannot be stripped or replaced by overlay writes`() {
    val unknown = original.copy(cloudSourceJson = original.cloudSourceJson!!.dropLast(1) + ",\"futureEvidence\":\"PUBLIC_EXACT\"}")
    assertEquals(unknown, SyncMetadataMerge.transcript(unknown, unknown.copy(cloudSourceJson = null)))
    assertThrows(IllegalArgumentException::class.java) { SyncMetadataMerge.transcript(unknown, sourced(listOf(overlay()))) }
    val changed = raw.copy(cloudSourceJson = CloudSources.encode(doc.copy(originals = doc.originals.map { it.copy(reportedText = "different") })))
    assertThrows(IllegalArgumentException::class.java) { SyncMetadataMerge.transcript(original, changed) }
    for (source in listOf(original, unknown)) {
      assertThrows(IllegalArgumentException::class.java) { SyncMetadataMerge.transcript(source, source.copy(text = "changed", cloudSourceJson = null)) }
      assertThrows(IllegalArgumentException::class.java) { SyncMetadataMerge.transcript(source, source.copy(createdAt = 43, cloudSourceJson = null)) }
    }
  }
  @Test fun `unexpanded envelopes are refused before typed merge and shared command fixtures expand to exact local JSON`() {
    val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/sync/combined-source-wire-v1.json"))
      .bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
    val exact = fixture.getValue("sourceJson").jsonPrimitive.content
    for (name in listOf("literalPayload", "referencePayload")) {
      val payload = fixture.getValue(name)
      val unexpanded = SyncCodec.json.decodeFromJsonElement(TranscriptEntity.serializer(), payload)
      assertThrows(IllegalArgumentException::class.java) { SyncMetadataMerge.transcript(null, unexpanded) }
      val expanded = SyncCloudSourceWire.toLocal(payload)
      val entity = SyncCodec.json.decodeFromJsonElement(TranscriptEntity.serializer(), expanded)
      assertEquals(exact, entity.cloudSourceJson)
      assertNotNull(CloudSources.decode(entity.cloudSourceJson)); assertEquals(entity, SyncMetadataMerge.transcript(null, entity))
      assertEquals(SyncCodec.hash(expanded), SyncCodec.hash(payload))
      val changedRaw = JsonObject(payload.jsonObject + ("text" to JsonPrimitive(entity.text + " changed")))
      assertThrows(IllegalArgumentException::class.java) { SyncCloudSourceWire.toLocal(changedRaw) }
      val repacked = SyncCloudSourceWire.toWire(expanded)
      assertTrue(repacked.jsonObject.getValue("cloudSourceJson").jsonPrimitive.content.startsWith(SyncCloudSourceWire.PREFIX))
      assertEquals(expanded, SyncCloudSourceWire.toLocal(repacked))
    }
    val worker = fixture.getValue("workerMergedPayload")
    val merged = SyncCodec.json.decodeFromJsonElement(TranscriptEntity.serializer(), SyncCloudSourceWire.toLocal(worker))
    val typed = CloudSources.decode(merged.cloudSourceJson)!!
    val base = CloudSources.decode(exact)!!
    assertEquals(base, typed.copy(overlays = emptyList()))
    assertEquals(listOf("a", "b"), typed.overlays.map { it.partId })
    assertTrue(typed.overlays.all { it.timings.any { t -> t.status == "measured" } })
    assertEquals(merged, SyncMetadataMerge.transcript(null, merged))
  }
}
