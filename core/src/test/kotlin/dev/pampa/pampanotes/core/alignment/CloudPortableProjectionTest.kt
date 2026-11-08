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
    assertEquals("copied-a", CloudSources.decode(copied.cloudSourceJson)!!.parts.single().id)
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
  @Test fun `unsupported nonempty metadata fails without downgrade loss or legacy adoption`() {
    for (value in listOf("{\"future\":\"secret\"}", "opaque legacy text", "null", raw.cloudSourceJson!!.dropLast(1) + ",\"jobId\":\"private\"}")) {
      val opaque = raw.copy(cloudSourceJson = value)
      try { CloudSources.portableProjection(opaque, parts); fail("Must preserve unsupported original by rejecting transfer") } catch (_: IllegalArgumentException) { }
      assertEquals(value, opaque.cloudSourceJson)
      assertNull(CloudSources.legacy(opaque, parts))
      assertFalse(CloudSources.validatePortable(opaque, parts))
    }
  }
  @Test fun `ambiguous legacy multipart subsets and identity collisions fail closed`() {
    val legacy = raw.copy(cloudSourceJson = null)
    val doc = CloudSources.legacy(legacy, parts)!!
    try { CloudSources.portableProjection(raw.copy(cloudSourceJson = CloudSources.encode(doc)), parts.take(1)); fail("Must not guess multipart text") } catch (_: IllegalArgumentException) { }
    try { CloudSources.portableProjection(raw, parts, partIdRemap = mapOf("a" to "collision", "secret-audio-id" to "collision")); fail("Must reject aliases") } catch (_: IllegalArgumentException) { }
    assertNull(CloudSources.portableProjection(raw.copy(cloudSourceJson = null), parts).cloudSourceJson)
    assertEquals("", CloudSources.portableProjection(raw.copy(cloudSourceJson = ""), parts).cloudSourceJson)
    val discovered = CloudSources.legacy(legacy, parts)!!
    assertEquals("portable_unbound", discovered.alignmentBinding)
    assertTrue(discovered.parts.all { it.sha256.isEmpty() })
    assertNull(CloudSources.bindSource(discovered, parts))
  }
  @Test fun `replaced selected audio never gains a borrowed source hash or retained timing`() {
    val replaced = listOf(parts[0].copy(sha256 = AlignmentProtocol.sha256("replacement")))
    try { CloudSources.portableProjection(raw, replaced); fail("Must reject replacement audio") } catch (_: IllegalArgumentException) { }
    assertEquals(parts[0].sha256, CloudSources.decode(raw.cloudSourceJson)!!.parts[0].sha256)
  }

  private fun overlaid(): TranscriptEntity {
    val doc = CloudSources.decode(raw.cloudSourceJson)!!
    val tokens = AlignmentProtocol.tokens(a)
    val overlay = CloudOverlay("a", AlignmentProtocol.sha256(a), "it", tokens.mapIndexed { i, t ->
      AlignmentTiming(t.first, t.last + 1, if (i == 0) 100L else null, if (i == 0) 500L else null,
        if (i == 0) "measured" else "missing", if (i == 0) null else "unreliable")
    })
    return raw.copy(cloudSourceJson = CloudSources.encode(doc.copy(overlays = listOf(overlay))))
  }
  @Test fun `measured and missing overlays survive selected remap with exact UTF16 text`() {
    val source = overlaid()
    val p = CloudSources.portableProjection(source, parts.take(1), "copy", mapOf("a" to "copy-a"), 77)
    val dest = source.copy(id = "copy", createdAt = 77, text = p.text, cloudSourceJson = p.cloudSourceJson)
    val destParts = listOf(parts[0].copy(id = "copy-a"))
    assertArrayEquals(a.toByteArray(), p.text.toByteArray())
    assertTrue(CloudSources.validatePortable(dest, destParts))
    val doc = CloudSources.decode(p.cloudSourceJson)!!
    assertEquals("copy-a", doc.overlays.single().partId)
    assertEquals(listOf("measured", "missing"), doc.overlays.single().timings.map { it.status })
    assertEquals(a, CloudSources.display(doc.parts.single(), doc.overlays.single()).joinToString("") { it.text })
    assertNull(CloudSources.bindSource(doc, destParts))
    assertFalse(CloudSources.validatePortable(dest, destParts.map { it.copy(durationMs = 499) }))
    assertFalse(CloudSources.validatePortable(dest.copy(createdAt = 78), destParts))
    assertEquals(source.cloudSourceJson, overlaid().cloudSourceJson)
  }
  @Test fun `full authorized exports retain original history and runner report including deleted parts`() {
    val source = overlaid()
    val projectedLocal = CloudSources.project(CloudSources.decode(source.cloudSourceJson)!!, source, listOf("a"), emptyList())
    val local = source.copy(text = projectedLocal.first, cloudSourceJson = CloudSources.encode(projectedLocal.second))
    val p = CloudSources.portableProjection(local, parts.take(1), retainOriginals = true)
    val doc = CloudSources.decode(p.cloudSourceJson)!!
    assertEquals(CloudSources.decode(source.cloudSourceJson)!!.originals, doc.originals)
    assertEquals("REPORTED_SECRET_WHOLE_RESPONSE", doc.originals.single().reportedText)
    assertTrue(CloudSources.validatePortable(local.copy(cloudSourceJson = p.cloudSourceJson), parts.take(1)))
    val restricted = CloudSources.portableProjection(local, parts.take(1))
    assertFalse(restricted.cloudSourceJson!!.contains(b))
    assertEquals("portable_projection", CloudSources.decode(restricted.cloudSourceJson)!!.originals.single().contentScope)
  }
  @Test fun `malformed hash unicode offsets timing and languages fail closed`() {
    val source = overlaid()
    val p = CloudSources.portableProjection(source, parts)
    val dest = source.copy(cloudSourceJson = p.cloudSourceJson)
    val doc = CloudSources.decode(p.cloudSourceJson)!!
    fun rejects(changed: CloudSourceDocument) {
      val encoded = AlignmentProtocol.json.encodeToString(CloudSourceDocument.serializer(), changed)
      assertFalse(CloudSources.validatePortable(dest.copy(cloudSourceJson = encoded), parts))
    }
    rejects(doc.copy(parts = doc.parts.mapIndexed { i, p -> if (i == 0) p.copy(sha256 = "invalid") else p }))
    rejects(doc.copy(overlays = doc.overlays.map { it.copy(language = "IT") }))
    rejects(doc.copy(overlays = doc.overlays.map { it.copy(timings = it.timings.mapIndexed { i, t -> if (i == 0) t.copy(endMs = 3001) else t }) }))
    rejects(doc.copy(overlays = doc.overlays.map { it.copy(timings = it.timings.mapIndexed { i, t -> if (i == 1) t.copy(textEnd = t.textEnd - 1) else t }) }))
    val broken = source.copy(text = "bad\uD800")
    try { CloudSources.portableProjection(broken, parts); fail("Invalid UTF16") } catch (_: IllegalArgumentException) { }
  }
  @Test fun `projected original provenance describes only selected providers models and languages`() {
    val doc = CloudSources.decode(raw.cloudSourceJson)!!
    val source = raw.copy(cloudSourceJson = CloudSources.encode(doc.copy(originals = doc.originals.map {
      it.copy(provider = "groq + SIBLING_PROVIDER", model = "whisper + SIBLING_MODEL", language = "en")
    })))
    val p = CloudSources.portableProjection(source, parts.take(1))
    val original = CloudSources.decode(p.cloudSourceJson)!!.originals.single()
    assertEquals("groq", original.provider)
    assertEquals("whisper", original.model)
    assertEquals("it", original.language)
    assertFalse(p.cloudSourceJson!!.contains("SIBLING_PROVIDER"))
    assertFalse(p.cloudSourceJson.contains("SIBLING_MODEL"))
    val whole = CloudSources.portableProjection(source, parts, retainOriginals = true)
    assertEquals(doc.originals.single().copy(provider = "groq + SIBLING_PROVIDER", model = "whisper + SIBLING_MODEL", language = "en"),
      CloudSources.decode(whole.cloudSourceJson)!!.originals.single())
  }
}
