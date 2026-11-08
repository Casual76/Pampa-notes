package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.repo.RawTimelinePlan
import dev.pampa.pampanotes.core.repo.RawTimelineSnapshot
import org.junit.Assert.*
import org.junit.Test

class CloudSourceTimelineTest {
  private fun session(id: String = "s", active: String? = "r", position: Int = 0) = SessionEntity(id, "n", date = "2026-11-08", position = position, activeTranscriptId = active, createdAt = 1, updatedAt = 1)
  private fun part(id: String, position: Int, session: String = "s") = AudioPartEntity(id, session, position, "$id.wav", "$id.wav", "audio/wav", 4, 5000, AlignmentProtocol.sha256(id), 1,
    recordedAtLocal = "2026-11-08", recordedAtPrecision = "day")
  private val a = "  Ciao,\t😀 mondo!\r\n"
  private val b = "\nSeconda  parte.  "
  private fun raw(id: String, text: String, parts: List<AudioPartEntity>, stamp: Long = 2, provider: String = "groq") : TranscriptEntity {
    val raw = TranscriptEntity(id, parts.firstOrNull()?.sessionId ?: "s", TranscriptKind.RAW, provider, "provider-model", "it", text, wordCount = 4, createdAt = stamp)
    val texts = parts.mapIndexed { i, p -> CloudTextPart(p.id, p.sha256, if (i == 0) a else b, if (i == 0) "it" else "en", provider, "provider-model") }
    return raw.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(raw, text, texts)))
  }
  private fun seed() : RawTimelineSnapshot {
    val parts = listOf(part("a", 0), part("b", 1))
    val raw = raw("r", a + "\n\n" + b, parts)
    val segments = parts.mapIndexed { i, p -> SegmentEntity(transcriptId = "r", partId = p.id, indexInPart = 0, partStartMs = 0, partEndMs = 3000,
      sessionStartMs = i * 5000L, sessionEndMs = i * 5000L + 3000, text = if (i == 0) "Ciao, 😀 mondo!" else "Seconda parte.", wordsEstimated = true) }
    return RawTimelineSnapshot(listOf(session()), parts, listOf(raw), segments)
  }
  @Test fun `codec keeps exact source full text parts languages and model immutable`() {
    val seed = seed(); val raw = seed.transcripts.single(); val doc = CloudSources.decode(raw.cloudSourceJson)!!
    assertTrue(CloudSources.matches(doc, raw))
    assertEquals(raw.text, doc.originals.single().text)
    assertEquals(listOf(a, b), doc.parts.map { it.text })
    assertEquals(listOf("it", "en"), doc.parts.map { it.language })
    assertEquals("provider-model", doc.originals.single().model)
    assertEquals(2, doc.rawVersion)
    assertEquals(seed.parts.map { it.sha256 }, doc.parts.map { it.sha256 })
    assertEquals(doc, CloudSources.decode(CloudSources.encode(doc)))
  }
  @Test fun `reorder uses exact parts and retains original full order and hash`() {
    val seed = seed(); val ordered = seed.parts.reversed().mapIndexed { i, p -> p.copy(position = i) }
    val result = RawTimelinePlan.build(seed, seed.sessions, ordered, 10)
    val raw = result.transcripts.single(); val doc = CloudSources.decode(raw.cloudSourceJson)!!
    assertEquals(b + "\n\n" + a, raw.text)
    assertEquals(listOf("b", "a"), doc.parts.map { it.id })
    assertEquals(seed.transcripts.single().text, doc.originals.single().text)
    assertEquals(seed.parts, seed.parts.map { it.copy() })
    assertTrue(result.segments.all { it.wordsEstimated })
  }
  @Test fun `split branches retain every original including the removed part text`() {
    val seed = seed(); val parts = listOf(seed.parts[0], seed.parts[1].copy(sessionId = "t", position = 0))
    val plan = RawTimelinePlan.build(seed, seed.sessions + session("t", null, 1), parts, 10)
    assertEquals(setOf(a, b), plan.transcripts.map { it.text }.toSet())
    plan.transcripts.forEach { raw ->
      val doc = CloudSources.decode(raw.cloudSourceJson)!!
      assertTrue(CloudSources.matches(doc, raw))
      assertEquals(seed.transcripts.single().text, doc.originals.single().text)
      assertEquals(listOf(a, b), doc.originals.single().parts.map { it.text })
    }
  }
  @Test fun `delete one or all parts preserves complete recoverable source originals`() {
    val seed = seed()
    for (parts in listOf(seed.parts.take(1), emptyList())) {
      val result = RawTimelinePlan.build(seed, seed.sessions, parts, 10)
      val doc = CloudSources.decode(result.transcripts.single().cloudSourceJson)!!
      assertEquals(seed.transcripts.single().text, doc.originals.single().text)
      assertEquals(listOf(a, b), doc.originals.single().parts.map { it.text })
      assertEquals(parts.size, doc.parts.size)
    }
  }
  @Test fun `untimed text with no segment rows follows parts across split and merge`() {
    val seed = seed().copy(segments = emptyList())
    val parts = listOf(seed.parts[0], seed.parts[1].copy(sessionId = "t", position = 0))
    val split = RawTimelinePlan.build(seed, seed.sessions + session("t", null, 1), parts, 10)
    assertEquals(setOf(a, b), split.transcripts.map { it.text }.toSet())
    val before = RawTimelineSnapshot((seed.sessions + session("t", null, 1)).map { it.copy(activeTranscriptId = split.active[it.id]) }, parts, split.transcripts, emptyList())
    val merged = RawTimelinePlan.build(before, before.sessions, seed.parts, 20, relocatedSessions = mapOf("t" to "s"))
    val active = merged.transcripts.single { it.id == merged.active["s"] }
    assertEquals(a + "\n\n" + b, active.text)
    assertEquals(1, CloudSources.decode(active.cloudSourceJson)!!.originals.size)
  }
  @Test fun `alternative histories and selected refined parent never mix source text`() {
    val seed = seed()
    val other = seed.transcripts.single().copy(id = "alt", text = "other", cloudSourceJson = null, createdAt = 100)
    val refined = seed.transcripts.single().copy(id = "f", kind = TranscriptKind.REFINED, parentId = "r", text = "clean", cloudSourceJson = null)
    val before = seed.copy(sessions = listOf(session(active = "f")), transcripts = seed.transcripts + other + refined)
    val result = RawTimelinePlan.build(before, before.sessions, before.parts, 10)
    assertEquals("f", result.active["s"])
    assertEquals("other", result.transcripts.single { it.id == "alt" }.text)
    assertEquals(seed.transcripts.single().text, result.transcripts.single { it.id == "r" }.text)
  }
  @Test fun `legacy single snapshots current text while multipart never fabricates originals`() {
    val seed = seed(); val old = seed.transcripts.single().copy(cloudSourceJson = null)
    val one = CloudSources.legacy(old, seed.parts.take(1))!!
    assertEquals(old.text, one.parts.single().text)
    val multi = CloudSources.legacy(old, seed.parts)!!
    assertTrue(multi.parts.all { it.text == null })
    assertEquals(old.text, multi.originals.single().text)
    assertNull(CloudSources.decode(null)); assertNull(CloudSources.decode(""))
    assertNull(CloudSources.legacy(old.copy(cloudSourceJson = ""), seed.parts))
  }
  @Test fun `reject checksum version and malformed codec without overwriting nullable state`() {
    val seed = seed(); val doc = CloudSources.decode(seed.transcripts.single().cloudSourceJson)!!
    assertNull(CloudSources.decode(CloudSources.encode(doc).replace("\"version\":1", "\"version\":2")))
    assertFalse(CloudSources.matches(doc, seed.transcripts.single().copy(text = "changed")))
    assertFalse(CloudSources.matches(doc, seed.transcripts.single().copy(createdAt = 3)))
    assertFalse(CloudSources.matches(doc, seed.transcripts.single().copy(id = "different")))
  }
}
