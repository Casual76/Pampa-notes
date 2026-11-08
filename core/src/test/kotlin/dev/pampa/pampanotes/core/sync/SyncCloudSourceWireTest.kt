package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.alignment.AlignmentProtocol
import dev.pampa.pampanotes.core.alignment.AlignmentTiming
import dev.pampa.pampanotes.core.alignment.CloudOriginal
import dev.pampa.pampanotes.core.alignment.CloudOverlay
import dev.pampa.pampanotes.core.alignment.CloudSources
import dev.pampa.pampanotes.core.alignment.CloudTextPart
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.files.Hashing
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.Random
import java.util.zip.DeflaterOutputStream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SyncCloudSourceWireTest {
  private fun raw(text: String) = TranscriptEntity(id = "raw", sessionId = "s", kind = TranscriptKind.RAW,
    provider = "mistral", model = "voxtral-mini-transcribe-3", language = "it", text = text, wordCount = 1, createdAt = 42)
  private fun part(id: String, text: String) = CloudTextPart(id, Hashing.sha256("audio-$id"), text, "it", "mistral", "voxtral-mini-transcribe-3")
  private fun sourced(text: String): TranscriptEntity {
    val row = raw(text)
    return row.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(row, text, listOf(part("a", text)))))
  }
  private fun entityPayload(row: TranscriptEntity) = SyncCodec.json.encodeToJsonElement(TranscriptEntity.serializer(), row)
  private fun encode(row: TranscriptEntity) = SyncPayloads.encode(TranscriptEntity.serializer(), row, row.createdAt)
  private fun bytes(value: JsonElement) = value.toString().toByteArray(Charsets.UTF_8).size

  private fun roundTrip(row: TranscriptEntity, label: String, segments: List<SegmentEntity> = emptyList()) {
    val before = entityPayload(row)
    val encoded = encode(row)
    assertTrue("Fixture must actually exercise the envelope", encoded.payload.jsonObject.getValue("cloudSourceJson").toString().contains(SyncCloudSourceWire.PREFIX))
    assertTrue("$label must fit the unchanged server row limit", bytes(encoded.payload) <= SyncSizeFailures.MAX_ROW_BYTES)
    assertEquals(SyncCodec.hash(before), encoded.hash)
    assertEquals(encoded.hash, SyncCodec.hash(encoded.payload))
    val hash = SyncCodec.transcriptHash(encoded.hash, segments)
    val change = WireChange("transcripts", row.id, "U", row.createdAt, hash, encoded.payload, segments, seq = 12, deviceId = "other")
    val pullJson = SyncCodec.json.encodeToString(PullResponse.serializer(), PullResponse(listOf(change), 12, false))
    val received = SyncCodec.json.decodeFromString(PullResponse.serializer(), pullJson).changes.single()
    val expanded = SyncCloudSourceWire.toLocal(received)
    val entity = SyncCodec.json.decodeFromJsonElement(TranscriptEntity.serializer(), expanded.payload!!)
    // This is the precise entity handed to Room by SyncApplier; Room execution is a separate gate.
    assertEquals(row, entity)
    assertArrayEquals(row.cloudSourceJson!!.toByteArray(), entity.cloudSourceJson!!.toByteArray())
    assertEquals(before, expanded.payload)
    assertEquals(encoded.payload, encode(entity).payload)
    assertEquals(hash, SyncCodec.transcriptHash(encode(entity).hash, segments))
    assertEquals(SyncMerge.Decision.APPLY, SyncMerge.decide("transcripts",
      SyncMerge.LocalView(true, true, hash, 42, hash), expanded))
    assertEquals(SyncMerge.Decision.SKIP, SyncMerge.decide("transcripts",
      SyncMerge.LocalView(true, true, "new-local", 43, hash), expanded))
    assertEquals(hash, expanded.hash)
    println("$label plain=${bytes(before)} wire=${bytes(encoded.payload)} exact=true stableHash=true")
  }

  @Test fun `R4 03 normal 500000 char source now fits without losing provenance`() {
    val row = sourced("voce ".repeat(100_000))
    assertTrue(bytes(entityPayload(row)) > SyncSizeFailures.MAX_ROW_BYTES)
    assertTrue(bytes(entityPayload(row.copy(cloudSourceJson = null))) < SyncSizeFailures.MAX_ROW_BYTES)
    roundTrip(row, "R4-03-500k")
  }

  @Test fun `Unicode CRLF punctuation and language survive exactly`() {
    roundTrip(sourced("  Caffè, 東京! 😀\r\n\t\"sì\"\\ ".repeat(20_000)), "unicode-crlf")
  }

  private fun entropy(size: Int, seed: Long): String {
    val random = Random(seed)
    return buildString(size) { repeat(size) { append((33 + random.nextInt(94)).toChar()) } }
  }

  @Test fun `high entropy text beyond the deflate window is deduplicated losslessly`() {
    roundTrip(sourced(entropy(500_000, 76)), "entropy-500k")
  }

  @Test fun `existing near limit raw is not duplicated by provenance on the wire`() {
    roundTrip(sourced(entropy(1_700_000, 77)), "entropy-1700k")
  }

  @Test fun `genuinely independent large histories remain complete and explicitly unsupported by row capacity`() {
    val row = raw(entropy(500_000, 10))
    val historical = entropy(1_400_000, 11)
    val doc = CloudSources.create(row, row.text, listOf(part("a", row.text)))
    val original = CloudOriginal("historical", 1, historical, Hashing.sha256(historical), "groq", "whisper-large-v3", "en",
      listOf(part("historical-a", historical).copy(language = "en", provider = "groq", model = "whisper-large-v3")))
    val sourced = row.copy(cloudSourceJson = CloudSources.encode(doc.copy(originals = doc.originals + original)))
    val encoded = encode(sourced)
    assertEquals(entityPayload(sourced), SyncCloudSourceWire.toLocal(encoded.payload))
    assertTrue(SyncSizeFailures.exceedsLimits(WireChange("transcripts", row.id, "U", 42, encoded.hash, encoded.payload)))
    println("independent-history plain=${bytes(entityPayload(sourced))} wire=${bytes(encoded.payload)} explicitTooLarge=true exact=true")
  }

  @Test fun `multipart original report full histories overlay and unbound metadata survive`() {
    val a = part("a", entropy(200_000, 1)); val b = part("b", entropy(300_000, 2)); val c = part("c", "Ciao, mondo!")
    val text = listOf(a, b, c).joinToString("\n\n") { it.text!! }
    val row = raw(text)
    val historical = entropy(300_000, 3)
    val original = CloudOriginal("earlier", 1, historical, Hashing.sha256(historical), "groq", "whisper-large-v3", "en",
      listOf(part("removed", historical).copy(language = "en", provider = "groq", model = "whisper-large-v3")),
      reportedText = "  distinct provider report\r\n", contentScope = "complete_original")
    val timings = AlignmentProtocol.tokens(c.text!!).mapIndexed { index, span ->
      AlignmentTiming(span.first, span.last + 1, if (index == 0) 1 else null, if (index == 0) 10 else null,
        if (index == 0) "measured" else "missing", if (index == 0) null else "unreliable")
    }
    val doc = CloudSources.create(row, text, listOf(a, b, c), "Reported separately: $text").let {
      it.copy(originals = it.originals + original, overlays = listOf(CloudOverlay(c.id, Hashing.sha256(c.text!!), "it", timings)), alignmentBinding = "portable_unbound")
    }
    val source = CloudSources.encode(doc)
    // The transport also preserves lexical formatting and alternate JSON escapes, not just a tree.
    val formatted = " \r\n" + source.replace("\"rawId\":", "\"rawId\" : ") + "\n"
    val segment = SegmentEntity(transcriptId = row.id, partId = c.id, indexInPart = 0, partStartMs = 1, partEndMs = 10,
      sessionStartMs = 5001, sessionEndMs = 5010, text = c.text!!)
    roundTrip(row.copy(cloudSourceJson = formatted), "multipart-history-overlay", listOf(segment))
    assertEquals(doc, CloudSources.decode(formatted))
  }

  @Test fun `small plain absent null and empty formats keep the legacy hashes`() {
    val plain = entityPayload(raw("hello"))
    assertEquals(plain, SyncCloudSourceWire.toWire(plain))
    assertEquals(plain, SyncCloudSourceWire.toLocal(plain))
    val old = JsonObject(plain.jsonObject - "cloudSourceJson")
    assertEquals(SyncCodec.hash(old), SyncCodec.hash(plain))
    val empty = entityPayload(raw("hello").copy(cloudSourceJson = ""))
    assertNotEquals(SyncCodec.hash(empty), SyncCodec.hash(plain))
    assertEquals(empty, SyncCloudSourceWire.toLocal(SyncCloudSourceWire.toWire(empty)))
    val legacySource = sourced("hello")
    assertEquals(entityPayload(legacySource), encode(legacySource).payload)
  }

  private fun envelope(value: String, text: String = "body") = JsonObject(entityPayload(raw(text)).jsonObject + ("cloudSourceJson" to JsonPrimitive(value)))
  private fun bad(value: JsonElement) {
    try { SyncCloudSourceWire.toLocal(value); fail("Must reject malformed/unsupported transport") }
    catch (expected: IllegalArgumentException) { assertEquals("Invalid or unsupported cloud source sync encoding", expected.message) }
  }
  private fun crafted(length: Int = 100, tail: DataOutputStream.() -> Unit): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(DeflaterOutputStream(bytes)).use { out ->
      out.writeInt(length); out.writeUTF(Hashing.sha256("unused")); out.writeUTF(Hashing.sha256("body")); out.tail()
    }
    return SyncCloudSourceWire.PREFIX + "1:" + Base64.getEncoder().encodeToString(bytes.toByteArray())
  }

  @Test fun `unknown versions and malformed base64 fail explicitly`() {
    bad(envelope(SyncCloudSourceWire.PREFIX + "2:AAAA"))
    bad(envelope(SyncCloudSourceWire.PREFIX + "1:!invalid!"))
    bad(envelope(SyncCloudSourceWire.PREFIX + "1:" + "A".repeat(1_900_000)))
  }

  @Test fun `truncation trailing bytes wrong raw and tampered content fail explicitly`() {
    val row = sourced("voce ".repeat(100_000)); val wire = encode(row).payload.jsonObject
    val source = (wire.getValue("cloudSourceJson") as JsonPrimitive).content
    bad(JsonObject(wire + ("text" to JsonPrimitive(row.text + "changed"))))
    bad(envelope(source.dropLast(8), row.text))
    val prefix = SyncCloudSourceWire.PREFIX + "1:"
    val bytes = Base64.getDecoder().decode(source.removePrefix(prefix))
    bad(envelope(prefix + Base64.getEncoder().encodeToString(bytes + ByteArray(9000)), row.text))
    bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 32).toByte()
    bad(envelope(prefix + Base64.getEncoder().encodeToString(bytes), row.text))
  }

  @Test fun `bomb headers invalid lengths references invalid utf8 and opcodes are rejected`() {
    bad(envelope(crafted(SyncCloudSourceWire.MAX_SOURCE_CHARS + 1) { writeByte(3) }))
    bad(envelope(crafted { writeByte(0); writeInt(Int.MAX_VALUE) }))
    bad(envelope(crafted { writeByte(0); writeInt(-1) }))
    bad(envelope(crafted { writeByte(1); writeInt(Int.MAX_VALUE); writeInt(2) }))
    bad(envelope(crafted { writeByte(2); writeInt(0); writeInt(100) }))
    bad(envelope(crafted { writeByte(0); writeInt(2); write(byteArrayOf(0xc0.toByte(), 0xaf.toByte())); writeByte(3) }))
    bad(envelope(crafted { writeByte(123) }))
    bad(envelope(crafted { writeByte(3) })) // source length/hash mismatch
  }

  @Test fun `unrelated tables deletes and unknown source fields are never silently stripped`() {
    val row = sourced("voce ".repeat(100_000)).let { it.copy(cloudSourceJson = it.cloudSourceJson!!.dropLast(1) + ",\"future\":\"preserve exactly\"}") }
    roundTrip(row, "opaque-future-source")
    val delete = WireChange("transcripts", "raw", "D", 42, payload = envelope(SyncCloudSourceWire.PREFIX + "99:bad"))
    assertEquals(delete, SyncCloudSourceWire.toLocal(delete))
    val unrelated = delete.copy(tbl = "notes", op = "U")
    assertEquals(unrelated, SyncCloudSourceWire.toLocal(unrelated))
    try { SyncCloudSourceWire.toWire(envelope(SyncCloudSourceWire.PREFIX + "1:opaque")); fail() }
    catch (_: IllegalArgumentException) { }
  }
}
