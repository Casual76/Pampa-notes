package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.*
import org.junit.Assert.*
import org.junit.Test

class RawTimelinePlanTest {
  private fun session(id: String, position: Int, active: String?) = SessionEntity(id, "n", date = "2026-11-05", position = position, activeTranscriptId = active, createdAt = 0, updatedAt = 0)
  private fun part(id: String, session: String, position: Int) = AudioPartEntity(id, session, position, "$id.wav", "$id.wav", "audio/wav", 1, 10000, "", 0)
  private fun raw(id: String, session: String, stamp: Long, words: String) = TranscriptEntity(id, session, TranscriptKind.RAW, "custom", "test", text = words, wordCount = 4, createdAt = stamp)
  private fun segment(id: String, part: AudioPartEntity, text: String) = SegmentEntity(transcriptId = id, partId = part.id, indexInPart = 3, partStartMs = 100, partEndMs = 500,
    sessionStartMs = part.position * 10000L + 100, sessionEndMs = part.position * 10000L + 500, text = text, wordsJson = "0,400,$text", speaker = "SPEAKER_00")
  private fun seed(refined: Boolean = false): RawTimelineSnapshot {
    val parts = listOf(part("A", "s", 0), part("B", "s", 1))
    val raws = listOf(raw("R", "s", 100, "author A\n\nauthor B"), raw("L", "s", 200, "local A\n\nlocal B"))
    val refinement = raws.first().copy(id = "F", kind = TranscriptKind.REFINED, parentId = "R", text = "refined author")
    return RawTimelineSnapshot(listOf(session("s", 0, if (refined) "F" else "R")), parts, raws + if (refined) listOf(refinement) else emptyList(),
      raws.flatMap { r -> parts.map { segment(r.id, it, "${if (r.id == "R") "author" else "local"} ${it.id}") } })
  }
  private fun validate(plan: RawTimelinePlan.Result, parts: List<AudioPartEntity>) {
    plan.segments.forEach { s ->
      val p = parts.single { it.id == s.partId }
      assertEquals(p.sessionId, plan.transcripts.single { it.id == s.transcriptId }.sessionId)
      val offset = parts.filter { it.sessionId == p.sessionId && it.position < p.position }.sumOf { it.durationMs }
      assertEquals(offset + s.partStartMs, s.sessionStartMs)
      assertEquals(offset + s.partEndMs, s.sessionEndMs)
      assertEquals(3, s.indexInPart)
      assertEquals("0,400,${s.text}", s.wordsJson)
      assertEquals("SPEAKER_00", s.speaker)
    }
    plan.transcripts.filter { it.parentId != null }.forEach { child -> assertEquals(child.sessionId, plan.transcripts.single { it.id == child.parentId }.sessionId) }
    plan.active.forEach { (session, id) -> if (id != null) assertEquals(session, plan.transcripts.single { it.id == id }.sessionId) }
  }

  @Test fun `reorder never mixes R100 with L200 and selected refinement falls back only to R`() {
    val before = seed(true)
    val parts = before.parts.reversed().mapIndexed { index, p -> p.copy(position = index) }
    val result = RawTimelinePlan.build(before, before.sessions, parts, 500)
    assertEquals("R", result.active["s"])
    assertEquals("author B\n\nauthor A", result.transcripts.single { it.id == "R" }.text)
    assertEquals("local B\n\nlocal A", result.transcripts.single { it.id == "L" }.text)
    assertEquals(before.transcripts.single { it.id == "F" }, result.transcripts.single { it.id == "F" })
    assertEquals(mapOf("R" to 2, "L" to 2), result.segments.groupingBy { it.transcriptId }.eachCount())
    validate(result, parts)
  }

  @Test fun `split creates separate R and L branches without merging alternatives`() {
    val before = seed(true)
    val created = session("t", 1, null)
    val parts = before.parts.map { if (it.id == "B") it.copy(sessionId = "t", position = 0) else it }
    val result = RawTimelinePlan.build(before, before.sessions + created, parts, 500)
    assertEquals("author A", result.transcripts.single { it.id == "R" }.text)
    assertEquals("local A", result.transcripts.single { it.id == "L" }.text)
    assertEquals("author B", result.transcripts.single { it.id == result.active["t"] }.text)
    assertEquals(4, result.segments.size)
    assertEquals(4, result.transcripts.count { it.kind == TranscriptKind.RAW })
    assertEquals("R", result.transcripts.single { it.id == "F" }.parentId)
    validate(result, parts)
  }

  @Test fun `partial selected RAW never incorporates a disjoint alternative during rebuild or reorder`() {
    for (refined in listOf(false, true)) {
      val before = seed(refined).let { b -> b.copy(
        transcripts = b.transcripts.map { when (it.id) {
          "R" -> it.copy(text = "author A")
          "L" -> it.copy(text = "local B")
          else -> it
        } },
        segments = b.segments.filter { it.transcriptId == "R" && it.partId == "A" || it.transcriptId == "L" && it.partId == "B" }
      ) }
      for (order in listOf(before.parts, before.parts.reversed().mapIndexed { index, p -> p.copy(position = index) })) {
        val result = RawTimelinePlan.build(before, before.sessions, order, 500)
        assertEquals(if (refined) "F" else "R", result.active["s"])
        assertEquals(setOf("R", "L") + if (refined) setOf("F") else emptySet<String>(), result.transcripts.map { it.id }.toSet())
        assertEquals("author A", result.transcripts.single { it.id == "R" }.text)
        assertEquals("local B", result.transcripts.single { it.id == "L" }.text)
        assertEquals(listOf("A"), result.segments.filter { it.transcriptId == "R" }.map { it.partId })
        assertEquals(listOf("B"), result.segments.filter { it.transcriptId == "L" }.map { it.partId })
        validate(result, order)
      }
    }
  }

  @Test fun `whole move preserves RAW ids and refinement before source cascade`() {
    val before = seed(true)
    val target = session("t", 1, null)
    val parts = before.parts.map { it.copy(sessionId = "t") }
    val result = RawTimelinePlan.build(before, before.sessions + target, parts, 500)
    assertEquals(setOf("R", "L", "F"), result.transcripts.map { it.id }.toSet())
    assertTrue(result.transcripts.all { it.sessionId == "t" })
    assertEquals("F", result.active["t"])
    assertNull(result.active["s"])
    assertEquals(4, result.segments.size)
    validate(result, parts)
  }

  @Test fun `independent C composes with selected author B and leaves alternative local B intact`() {
    val before = seed()
    val target = session("t", 1, "Q")
    val c = part("C", "t", 0)
    val q = raw("Q", "t", 300, "independent C")
    val snapshot = before.copy(sessions = before.sessions + target, parts = before.parts + c, transcripts = before.transcripts + q, segments = before.segments + segment("Q", c, "independent C"))
    val parts = snapshot.parts.map { if (it.id == "B") it.copy(sessionId = "t", position = 1) else it }
    val result = RawTimelinePlan.build(snapshot, snapshot.sessions, parts, 500)
    val active = result.transcripts.single { it.id == result.active["t"] }
    assertEquals("independent C\n\nauthor B", active.text)
    assertEquals("R", result.active["s"])
    assertEquals("author A", result.transcripts.single { it.id == "R" }.text)
    assertEquals("local A", result.transcripts.single { it.id == "L" }.text)
    assertTrue(result.transcripts.any { it.sessionId == "t" && it.text == "local B" })
    assertEquals(2, result.segments.count { it.transcriptId == active.id })
    assertEquals(7, result.segments.size)
    validate(result, parts)
    val next = RawTimelineSnapshot(snapshot.sessions.map { it.copy(activeTranscriptId = result.active[it.id]) }, parts, result.transcripts, result.segments)
    val repeated = RawTimelinePlan.build(next, next.sessions, parts, 600)
    assertEquals(result, repeated)
  }

  @Test fun `merge rehomes all histories and refinements even with no part metadata`() {
    val before = seed(true)
    val target = session("t", 0, null)
    val timeless = before.copy(parts = emptyList(), segments = emptyList())
    val result = RawTimelinePlan.build(timeless, timeless.sessions + target, emptyList(), 500, relocatedSessions = mapOf("s" to "t"))
    assertTrue(result.transcripts.all { it.sessionId == "t" })
    assertEquals("F", result.active["t"])
    assertEquals("refined author", result.transcripts.single { it.id == "F" }.text)
  }

  @Test fun `invalid selected refinement never switches to an unrelated newest history`() {
    val before = seed(true).let { b -> b.copy(transcripts = b.transcripts.map { if (it.id == "F") it.copy(parentId = null) else it }) }
    val result = RawTimelinePlan.build(before, before.sessions, before.parts, 500)
    assertEquals("F", result.active["s"])
    assertEquals(4, result.segments.size)
  }

  @Test fun `new transcription with initially empty text is assembled even without reordering`() {
    val before = seed().let { b -> b.copy(transcripts = b.transcripts.map { if (it.id == "R") it.copy(text = "", wordCount = 0) else it }) }
    val result = RawTimelinePlan.build(before, before.sessions, before.parts, 500)
    assertEquals("author A\n\nauthor B", result.transcripts.single { it.id == "R" }.text)
    assertEquals("R", result.active["s"])
    assertEquals("local A\n\nlocal B", result.transcripts.single { it.id == "L" }.text)
  }

  @Test fun `moving all selected words leaves explicit empty related branch for untranscribed remainder`() {
    val before = seed().let { b -> b.copy(transcripts = b.transcripts.map { if (it.id == "R") it.copy(text = "author A") else it },
      segments = b.segments.filterNot { it.transcriptId == "R" && it.partId == "B" }) }
    val target = session("t", 1, null)
    val parts = before.parts.map { if (it.id == "A") it.copy(sessionId = "t", position = 0) else it.copy(position = 0) }
    val result = RawTimelinePlan.build(before, before.sessions + target, parts, 500)
    assertNotEquals("L", result.active["s"])
    assertEquals("", result.transcripts.single { it.id == result.active["s"] }.text)
    assertTrue(result.segments.none { it.transcriptId == result.active["s"] })
    assertEquals("R", result.active["t"])
    assertEquals("author A", result.transcripts.single { it.id == "R" }.text)
    validate(result, parts)
  }

  @Test fun `all permutations and simultaneous session swaps retain every version payload and ownership`() {
    val source = seed(true)
    val c = part("C", "t", 0)
    val q = raw("Q", "t", 300, "independent C")
    val before = source.copy(sessions = source.sessions + session("t", 1, "Q"), parts = source.parts + c,
      transcripts = source.transcripts + q, segments = source.segments + segment("Q", c, "independent C"))
    val orders = listOf(listOf("A", "B", "C"), listOf("A", "C", "B"), listOf("B", "A", "C"), listOf("B", "C", "A"), listOf("C", "A", "B"), listOf("C", "B", "A"))
    for (order in orders) for (mask in 0..7) {
      val positions = mutableMapOf<String, Int>()
      val parts = order.map { id ->
        val destination = if (mask and (1 shl listOf("A", "B", "C").indexOf(id)) == 0) "s" else "t"
        val index = positions.getOrDefault(destination, 0); positions[destination] = index + 1
        before.parts.single { it.id == id }.copy(sessionId = destination, position = index)
      }
      val result = RawTimelinePlan.build(before, before.sessions, parts, 500)
      validate(result, parts)
      before.segments.forEach { original -> assertTrue(result.segments.any { it.partId == original.partId && it.text == original.text && it.wordsJson == original.wordsJson }) }
      assertEquals(result.segments.size, result.segments.map { it.transcriptId to it.partId }.distinct().size)
      val next = RawTimelineSnapshot(before.sessions.map { it.copy(activeTranscriptId = result.active[it.id]) }, parts, result.transcripts, result.segments)
      assertEquals(result, RawTimelinePlan.build(next, next.sessions, parts, 600))
    }
  }

  @Test fun `repair alias preserves untimed legacy text and existing refinement selection`() {
    val before = seed(true).copy(parts = emptyList(), segments = emptyList())
    val target = session("t", 1, null)
    val result = RawTimelinePlan.build(before, before.sessions + target, emptyList(), 500,
      newRawIds = mapOf("t" to "repair"), relocatedSessions = mapOf("s" to "t"))
    assertEquals("author A\n\nauthor B", result.transcripts.single { it.id == "repair" }.text)
    assertEquals("F", result.active["t"])
    assertEquals("R", result.transcripts.single { it.id == "F" }.parentId)
    assertTrue(result.segments.isEmpty())
  }

  @Test fun `deleting a part rebases each remaining history without deleting the other version`() {
    val before = seed(true)
    val remaining = before.parts.filter { it.id == "B" }.map { it.copy(position = 0) }
    val result = RawTimelinePlan.build(before, before.sessions, remaining, 500)
    assertEquals("author B", result.transcripts.single { it.id == "R" }.text)
    assertEquals("local B", result.transcripts.single { it.id == "L" }.text)
    assertEquals(2, result.segments.size)
    assertEquals("R", result.active["s"])
    assertEquals("refined author", result.transcripts.single { it.id == "F" }.text)
    validate(result, remaining)
  }
}
