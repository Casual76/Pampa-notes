package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.repo.RawTimelinePlan
import dev.pampa.pampanotes.core.repo.RawTimelineSnapshot
import org.junit.Assert.*
import org.junit.Test

class CloudPortableProjectionTest {
  private val a = "  Selected,\t😀!\n"
  private val b = "UNSELECTED_SIBLING_SECRET"
  private fun part(id: String, position: Int, session: String = "s") = AudioPartEntity(id, session, position, "$id.wav", "$id.wav", "audio/wav", 1, 3000, AlignmentProtocol.sha256(id), 1)
  private fun session(id: String, active: String?) = SessionEntity(id, "n", date = "2026-11-08", position = if (id == "s") 0 else 1, activeTranscriptId = active, createdAt = 1, updatedAt = 1)
  private val parts = listOf(part("a", 0), part("secret-audio-id", 1))
  private val raw = TranscriptEntity("original-raw", "s", TranscriptKind.RAW, "groq", "whisper", "it", a + "\n\n" + b, wordCount = 4, createdAt = 42).let { raw ->
    raw.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(raw, raw.text, listOf(
      CloudTextPart("a", parts[0].sha256, a, "it", "groq", "whisper"),
      CloudTextPart("secret-audio-id", parts[1].sha256, b, "en", "openrouter", "source-model")), "REPORTED_SECRET_WHOLE_RESPONSE")))
  }
  @Test fun `single split session projection excludes sibling text audio id hash and full reported text`() {
    val moved = listOf(parts[0], parts[1].copy(sessionId = "t", position = 0))
    val before = RawTimelineSnapshot(listOf(session("s", raw.id)), parts, listOf(raw), emptyList())
    val split = RawTimelinePlan.build(before, before.sessions + session("t", null), moved, 100)
    val selected = split.transcripts.single { it.id == split.active["s"] }
    val localJson = selected.cloudSourceJson!!
    assertTrue(localJson.contains(b)) // Local recovery still has the complete immutable original.
    val portable = CloudSources.portableProjection(selected, moved.filter { it.sessionId == "s" })
    assertEquals(a, portable.text); assertFalse(portable.alignmentApplicable)
    val json = portable.cloudSourceJson!!
    for (secret in listOf(b, "secret-audio-id", parts[1].sha256, "REPORTED_SECRET_WHOLE_RESPONSE", AlignmentProtocol.sha256(raw.text))) assertFalse(secret, json.contains(secret))
    assertEquals(localJson, selected.cloudSourceJson)
    val doc = CloudSources.decode(json)!!
    assertEquals("portable_projection", doc.originals.single().contentScope)
    assertEquals(a, doc.originals.single().text)
    assertTrue(CloudSources.validatePortable(selected.copy(text = portable.text, cloudSourceJson = json), moved.take(1)))
    assertNull(CloudSources.bindSource(doc, moved.take(1)))
  }
  @Test fun `copies and aliases rewrite only selected identities and remain explicitly unbound`() {
    val projection = CloudSources.portableProjection(raw, parts.take(1), "copied-raw", mapOf("a" to "copied-a"), 77)
    val copied = raw.copy(id = "copied-raw", createdAt = 77, text = projection.text, cloudSourceJson = projection.cloudSourceJson)
    val copiedParts = listOf(parts[0].copy(id = "copied-a"))
    assertEquals(a, copied.text)
    assertTrue(CloudSources.validatePortable(copied, copiedParts))
    assertNull(CloudSources.bindSource(CloudSources.decode(copied.cloudSourceJson)!!, copiedParts))
    assertFalse(copied.cloudSourceJson!!.contains("original-raw"))
    assertFalse(CloudSources.validatePortable(copied.copy(id = "stale-raw-alias"), copiedParts))
    assertFalse(CloudSources.validatePortable(copied, listOf(copiedParts.single().copy(id = "stale-part-alias"))))
    assertFalse(CloudSources.validatePortable(copied, listOf(copiedParts.single().copy(sha256 = AlignmentProtocol.sha256("replacement")))))
  }
  @Test fun `portable unbound state survives local reorder and combination without accidental attestation`() {
    val p = CloudSources.portableProjection(raw, parts)
    val imported = raw.copy(cloudSourceJson = p.cloudSourceJson)
    val doc = CloudSources.decode(imported.cloudSourceJson)!!
    val reordered = parts.reversed().mapIndexed { i, part -> part.copy(position = i) }
    val projected = CloudSources.project(doc, imported, reordered.map { it.id }, emptyList())
    assertNull(CloudSources.bindSource(projected.second, reordered))
    val combined = CloudSources.combine(imported, parts, listOf(doc), emptyList()).second
    assertEquals("portable_unbound", combined.alignmentBinding)
    assertNull(CloudSources.bindSource(combined, parts))
  }
  @Test fun `unknown opaque JSON is safely replaced without hidden sibling fields or legacy auto adoption`() {
    val opaque = raw.copy(text = a, cloudSourceJson = "{\"future\":\"$b\"}")
    val projection = CloudSources.portableProjection(opaque, parts.take(1), "copy", mapOf("a" to "copy-a"))
    assertEquals(a, projection.text)
    assertFalse(projection.cloudSourceJson!!.contains(b))
    val doc = CloudSources.decode(projection.cloudSourceJson)!!
    assertEquals("portable_unbound", doc.alignmentBinding)
    assertTrue(doc.parts.all { it.sha256.isEmpty() && it.text == null })
    assertNull(CloudSources.bindSource(doc, listOf(parts[0].copy(id = "copy-a"))))
  }
  @Test fun `ambiguous legacy multipart subsets and identity collisions fail closed`() {
    val legacy = raw.copy(cloudSourceJson = null)
    val doc = CloudSources.legacy(legacy, parts)!!
    try { CloudSources.portableProjection(raw.copy(cloudSourceJson = CloudSources.encode(doc)), parts.take(1)); fail("Must not guess multipart text") } catch (_: IllegalArgumentException) { }
    try { CloudSources.portableProjection(raw, parts, partIdRemap = mapOf("a" to "collision", "secret-audio-id" to "collision")); fail("Must reject aliases") } catch (_: IllegalArgumentException) { }
    assertNull(CloudSources.portableProjection(raw.copy(cloudSourceJson = null), parts).cloudSourceJson)
    assertEquals("", CloudSources.portableProjection(raw.copy(cloudSourceJson = ""), parts).cloudSourceJson)
  }
  @Test fun `replaced selected audio never gains a borrowed source hash in portable metadata`() {
    val replaced = listOf(parts[0].copy(sha256 = AlignmentProtocol.sha256("replacement")))
    val projection = CloudSources.portableProjection(raw, replaced)
    val json = projection.cloudSourceJson!!
    val doc = CloudSources.decode(json)!!
    assertEquals(a, projection.text)
    assertEquals("", doc.parts.single().sha256)
    assertFalse(json.contains(replaced.single().sha256))
    assertFalse(json.contains(parts[0].sha256))
    assertNull(CloudSources.bindSource(doc, replaced))
  }
}
