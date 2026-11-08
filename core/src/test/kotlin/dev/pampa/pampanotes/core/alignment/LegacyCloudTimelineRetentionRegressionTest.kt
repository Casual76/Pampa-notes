package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.repo.RawTimelinePlan
import dev.pampa.pampanotes.core.repo.RawTimelineSnapshot
import org.junit.Assert.*
import org.junit.Test

/** Executes the production planner with deliberately incomplete, different coarse segment text. */
class LegacyCloudTimelineRetentionRegressionTest {
  private val first = "  Exact\tprovider, \uD83D\uDE00!\r\n  repeated   spaces  "
  private val second = "\nSIBLING_ORIGINAL_WITHOUT_COARSE_COVERAGE\t\r\n"
  private val exact = first + "\n\n" + second
  private fun session(id: String = "s", active: String? = "r") = SessionEntity(id, "n", date = "2026-11-08",
    position = if (id == "s") 0 else 1, activeTranscriptId = active, createdAt = 1, updatedAt = 1)
  private fun seed(source: String? = "", provider: String = "groq", typed: Boolean = false): RawTimelineSnapshot {
    val parts = listOf("a", "b").mapIndexed { i, id -> AudioPartEntity(id, "s", i, "$id.wav", "$id.wav", "audio/wav",
      4, 5000, AlignmentProtocol.sha256("replacement-row-$id"), 1) }
    val raw = TranscriptEntity("r", "s", TranscriptKind.RAW, provider, "original-model", "Italian", exact, wordCount = 9,
      createdAt = 42, cloudSourceJson = source).let { row ->
      if (!typed) row else row.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(row, exact,
        parts.mapIndexed { i, p -> CloudTextPart(p.id, p.sha256, if (i == 0) first else second, "Italian", provider, row.model) })))
    }
    val segments = parts.map { p -> SegmentEntity(transcriptId = "r", partId = p.id, indexInPart = 0,
      partStartMs = 100, partEndMs = 200, sessionStartMs = p.position * 5000L + 100, sessionEndMs = p.position * 5000L + 200,
      text = "COARSE_ONLY_${p.id}", wordsEstimated = true) }
    return RawTimelineSnapshot(listOf(session()), parts, listOf(raw), segments)
  }
  private fun document(raw: TranscriptEntity): CloudSourceDocument {
    val doc = CloudSources.decode(raw.cloudSourceJson)
    assertNotNull("Exact legacy original needs recoverable typed history", doc)
    assertTrue(CloudSources.matches(doc!!, raw))
    return doc
  }
  private fun original(raw: TranscriptEntity): CloudOriginal = document(raw).originals.single { it.rawId == "r" && it.rawVersion == 42L }
  private fun assertLegacy(raw: TranscriptEntity, parts: List<AudioPartEntity>, expected: CloudOriginal? = null) {
    val doc = document(raw)
    val original = original(raw)
    assertArrayEquals(exact.toByteArray(Charsets.UTF_8), original.text.toByteArray(Charsets.UTF_8))
    assertEquals(AlignmentProtocol.sha256(exact), original.textSha256)
    assertEquals("original-model", original.model)
    assertEquals("Italian", original.language)
    assertEquals(listOf("a", "b"), original.parts.map { it.id })
    assertTrue(original.parts.all { it.text == null && it.sha256.isEmpty() && it.language == null && it.responses == null })
    if (expected != null) assertEquals(expected, original)
    assertEquals("portable_unbound", doc.alignmentBinding)
    assertTrue(doc.parts.all { it.text == null && it.sha256.isEmpty() && it.language == null })
    assertTrue(doc.overlays.isEmpty())
    assertNull(CloudSources.bindSource(doc, parts))
  }
  private fun snapshot(sessions: List<SessionEntity>, parts: List<AudioPartEntity>, result: RawTimelinePlan.Result) =
    RawTimelineSnapshot(sessions.map { it.copy(activeTranscriptId = result.active[it.id]) }, parts, result.transcripts, result.segments)

  @Test fun `empty provenance reorder retains the only exact original instead of replacing it with coarse words`() {
    for (provider in listOf("groq", "openrouter", "mistral")) {
      val before = seed(provider = provider)
      val parts = before.parts.reversed().mapIndexed { i, p -> p.copy(position = i) }
      val plan = RawTimelinePlan.build(before, before.sessions, parts, 100)
      val raw = plan.transcripts.single()
      assertEquals("COARSE_ONLY_b\n\nCOARSE_ONLY_a", raw.text)
      assertEquals("r", raw.id)
      assertEquals(42L, raw.createdAt)
      assertEquals("r", plan.active["s"])
      assertLegacy(raw, parts)
      assertEquals(provider, original(raw).provider)
      assertEquals(listOf("b", "a"), document(raw).parts.map { it.id })
      assertEquals("", before.transcripts.single().cloudSourceJson)
      val next = snapshot(before.sessions, parts, plan)
      assertEquals(plan, RawTimelinePlan.build(next, next.sessions, next.parts, 200))
    }
  }

  @Test fun `null and typed reorder controls preserve original identity and their actual source evidence`() {
    val legacy = seed(source = null)
    val parts = legacy.parts.reversed().mapIndexed { i, p -> p.copy(position = i) }
    val nullRaw = RawTimelinePlan.build(legacy, legacy.sessions, parts, 100).transcripts.single()
    assertEquals(exact, original(nullRaw).text)
    assertEquals(CloudSources.legacy(legacy.transcripts.single(), legacy.parts)!!.originals, document(nullRaw).originals)
    assertEquals("portable_unbound", document(nullRaw).alignmentBinding)
    assertNull(CloudSources.bindSource(document(nullRaw), parts))
    val typed = seed(typed = true)
    val raw = RawTimelinePlan.build(typed, typed.sessions, parts, 100).transcripts.single()
    assertEquals(second + "\n\n" + first, raw.text)
    assertEquals(document(typed.transcripts.single()).originals, document(raw).originals)
    assertEquals("source_snapshot", document(raw).alignmentBinding)
    assertEquals(parts.map { it.sha256 }, document(raw).parts.map { it.sha256 })
  }

  @Test fun `split merge reorder and delete keep the same immutable legacy original in every history`() {
    val before = seed()
    val sessions = before.sessions + session("t", null)
    val splitParts = before.parts.map { if (it.id == "b") it.copy(sessionId = "t", position = 0) else it }
    val split = RawTimelinePlan.build(before, sessions, splitParts, 100)
    assertEquals(setOf("COARSE_ONLY_a", "COARSE_ONLY_b"), split.transcripts.map { it.text }.toSet())
    val expected = original(split.transcripts.first())
    split.transcripts.forEach { assertLegacy(it, splitParts.filter { p -> p.sessionId == it.sessionId }, expected) }
    val next = snapshot(sessions, splitParts, split)
    val merged = RawTimelinePlan.build(next, sessions, before.parts, 200, relocatedSessions = mapOf("t" to "s"))
    assertEquals("COARSE_ONLY_a\n\nCOARSE_ONLY_b", merged.transcripts.single { it.id == merged.active["s"] }.text)
    merged.transcripts.forEach { assertLegacy(it, before.parts, expected) }
    val reversed = before.parts.reversed().mapIndexed { i, p -> p.copy(position = i) }
    val mergedBefore = snapshot(sessions, before.parts, merged)
    val reordered = RawTimelinePlan.build(mergedBefore, sessions, reversed, 300)
    reordered.transcripts.forEach { assertLegacy(it, reversed, expected) }
    val reorderedBefore = snapshot(sessions, reversed, reordered)
    for (remaining in listOf(reversed.take(1), emptyList())) {
      val deleted = RawTimelinePlan.build(reorderedBefore, sessions, remaining, 400)
      deleted.transcripts.forEach { assertLegacy(it, remaining, expected) }
    }
  }

  @Test fun `all split destinations and missing segment coverage retain originals without inventing boundaries`() {
    for (source in listOf(null, "")) for (mask in 0..3) for (timed in listOf(true, false)) {
      val before = seed(source).let { if (timed) it else it.copy(segments = emptyList()) }
      val sessions = before.sessions + session("t", null)
      val parts = before.parts.reversed().mapIndexed { i, p -> p.copy(sessionId = if (mask and (1 shl i) == 0) "s" else "t", position = i) }
      val result = RawTimelinePlan.build(before, sessions, parts, 100)
      result.transcripts.forEach { assertLegacy(it, parts.filter { p -> p.sessionId == it.sessionId }) }
      val next = snapshot(sessions, parts, result)
      assertEquals(result, RawTimelinePlan.build(next, sessions, parts, 200))
    }
  }

  @Test fun `whole authorized copy retains unknown multipart originals while restricted split refuses to leak siblings`() {
    val before = seed()
    val sessions = before.sessions + session("t", null)
    val parts = before.parts.map { if (it.id == "b") it.copy(sessionId = "t", position = 0) else it }
    val plan = RawTimelinePlan.build(before, sessions, parts, 100)
    for (raw in plan.transcripts) {
      val selected = parts.filter { it.sessionId == raw.sessionId }
      val source = raw.cloudSourceJson
      assertThrows(IllegalArgumentException::class.java) { CloudSources.portableProjection(raw, selected) }
      val aliases = before.parts.associate { it.id to "copy-${it.id}" }
      val p = CloudSources.portableProjection(raw, selected, "copy-${raw.id}", aliases, 99, retainOriginals = true)
      val copied = raw.copy(id = "copy-${raw.id}", createdAt = 99, text = p.text, cloudSourceJson = p.cloudSourceJson)
      val copiedParts = selected.map { it.copy(id = aliases.getValue(it.id)) }
      assertTrue(CloudSources.validatePortable(copied, copiedParts))
      val doc = document(copied)
      assertEquals(listOf("copy-a", "copy-b"), doc.originals.single().parts.map { it.id })
      assertEquals(exact, doc.originals.single().text)
      assertEquals("r", doc.originals.single().rawId)
      assertEquals(42L, doc.originals.single().rawVersion)
      assertEquals(listOf(aliases.getValue(selected.single().id)), doc.parts.map { it.id })
      assertEquals("portable_unbound", doc.alignmentBinding)
      assertNull(CloudSources.bindSource(doc, copiedParts))
      assertThrows(IllegalArgumentException::class.java) { CloudSources.portableProjection(copied, copiedParts) }
      assertEquals(source, raw.cloudSourceJson)
    }
  }

  @Test fun `restricted deletion with unknown boundaries fails while full authorized history remains recoverable`() {
    val before = seed()
    for (remaining in listOf(before.parts.take(1), emptyList())) {
      val raw = RawTimelinePlan.build(before, before.sessions, remaining, 100).transcripts.single()
      if (remaining.isNotEmpty()) {
        assertThrows(IllegalArgumentException::class.java) { CloudSources.portableProjection(raw, remaining) }
      } else {
        val restricted = CloudSources.portableProjection(raw, remaining)
        assertEquals("", restricted.text)
        assertTrue(CloudSources.decode(restricted.cloudSourceJson)!!.originals.all { it.text.isEmpty() && it.parts.isEmpty() })
        assertFalse(restricted.cloudSourceJson!!.contains(second))
      }
      val p = CloudSources.portableProjection(raw, remaining, retainOriginals = true)
      assertLegacy(raw.copy(text = p.text, cloudSourceJson = p.cloudSourceJson), remaining)
      assertEquals(remaining.map { it.id }, document(raw.copy(cloudSourceJson = p.cloudSourceJson)).parts.map { it.id })
    }
  }

  @Test fun `unchanged null and empty portable serialization roundtrips stay distinct and exact`() {
    for (source in listOf(null, "")) {
      val before = seed(source)
      val raw = before.transcripts.single()
      val p = CloudSources.portableProjection(raw, before.parts, "copy-r", mapOf("a" to "copy-a", "b" to "copy-b"), 99)
      assertEquals(source, p.cloudSourceJson)
      assertEquals(exact, p.text)
      val copied = raw.copy(id = "copy-r", createdAt = 99, text = p.text, cloudSourceJson = p.cloudSourceJson)
      val json = AlignmentProtocol.json.encodeToString(TranscriptEntity.serializer(), copied)
      assertEquals(copied, AlignmentProtocol.json.decodeFromString(TranscriptEntity.serializer(), json))
      assertEquals(source, CloudSources.portableProjection(copied, before.parts).cloudSourceJson)
      assertNull(CloudSources.decode(source))
    }
  }

  @Test fun `opaque malformed unsupported and stale nonempty metadata reject timeline mutation without downgrade`() {
    val typed = seed(typed = true)
    val valid = typed.transcripts.single().cloudSourceJson!!
    val invalid = listOf("opaque", " ", "null", "{}", valid.replace("\"version\":1", "\"version\":2"),
      valid.dropLast(1) + ",\"futureEvidence\":\"must survive\"}", valid.replace("\"rawId\":\"r\"", "\"rawId\":\"stale\""),
      CloudSources.encode(document(typed.transcripts.single()).copy(textSha256 = AlignmentProtocol.sha256("different text"))))
    for (value in invalid) {
      val before = typed.copy(transcripts = typed.transcripts.map { it.copy(cloudSourceJson = value) })
      val reversed = before.parts.reversed().mapIndexed { i, p -> p.copy(position = i) }
      for (parts in listOf(before.parts, reversed, before.parts.take(1), emptyList())) {
        assertThrows(IllegalArgumentException::class.java) { RawTimelinePlan.build(before, before.sessions, parts, 100) }
      }
      assertEquals(value, before.transcripts.single().cloudSourceJson)
      assertEquals(exact, before.transcripts.single().text)
      assertNull(CloudSources.legacy(before.transcripts.single(), before.parts))
    }
  }

  @Test fun `missing original part graph stays unbound and cannot be scoped by guessing from coarse segments`() {
    val before = seed().copy(parts = emptyList(), segments = emptyList())
    val raw = RawTimelinePlan.build(before, before.sessions, emptyList(), 100).transcripts.single()
    assertEquals(exact, original(raw).text)
    assertTrue(original(raw).parts.isEmpty())
    assertTrue(document(raw).parts.isEmpty())
    assertEquals("portable_unbound", document(raw).alignmentBinding)
    assertThrows(IllegalArgumentException::class.java) { CloudSources.portableProjection(raw, emptyList()) }
    assertEquals(exact, CloudSources.decode(CloudSources.portableProjection(raw, emptyList(), retainOriginals = true).cloudSourceJson)!!.originals.single().text)
  }

  @Test fun `single part legacy keeps exact text but never borrows replacement hash for alignment`() {
    val seed = seed()
    val before = seed.copy(parts = seed.parts.take(1), segments = seed.segments.take(1))
    val raw = RawTimelinePlan.build(before, before.sessions, before.parts, 100).transcripts.single()
    val doc = document(raw)
    assertEquals(exact, raw.text)
    assertEquals(exact, doc.parts.single().text)
    assertEquals("Italian", doc.parts.single().language)
    assertEquals("", doc.parts.single().sha256)
    assertNull(CloudSources.bindSource(doc, before.parts))
    val p = CloudSources.portableProjection(raw, before.parts)
    assertEquals(exact, p.text)
    assertEquals(doc.originals, CloudSources.decode(p.cloudSourceJson)!!.originals)
  }

  @Test fun `independent merge preserves exact legacy history and does not attest segment based companion history`() {
    val before = seed()
    val c = before.parts.first().copy(id = "c", sessionId = "t", position = 0)
    val q = before.transcripts.single().copy(id = "q", sessionId = "t", provider = "custom", text = "EXACT_COMPANION\t\r\n",
      createdAt = 60, cloudSourceJson = null)
    val sessions = before.sessions + session("t", "q")
    val all = before.copy(sessions = sessions, parts = before.parts + c, transcripts = before.transcripts + q,
      segments = before.segments + before.segments.first().copy(transcriptId = "q", partId = "c", text = "COARSE_COMPANION"))
    val parts = all.parts.map { if (it.sessionId == "s") it.copy(sessionId = "t", position = it.position + 1) else it }
    val result = RawTimelinePlan.build(all, sessions, parts, 100, relocatedSessions = mapOf("s" to "t"))
    val active = result.transcripts.single { it.id == result.active["t"] }
    val doc = document(active)
    assertEquals(exact, original(active).text)
    assertEquals(q.text, doc.originals.single { it.rawId == "q" }.text)
    assertEquals("derived_presentation", doc.originals.single { it.rawId == "q" }.contentScope)
    assertTrue(doc.parts.all { it.sha256.isEmpty() })
    assertEquals("portable_unbound", doc.alignmentBinding)
    assertNull(CloudSources.bindSource(doc, parts))
    val p = CloudSources.portableProjection(active, parts, retainOriginals = true)
    assertEquals(doc.originals, CloudSources.decode(p.cloudSourceJson)!!.originals)
  }
}
