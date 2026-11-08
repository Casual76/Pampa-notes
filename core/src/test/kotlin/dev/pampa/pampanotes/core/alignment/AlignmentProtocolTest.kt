package dev.pampa.pampanotes.core.alignment

import org.junit.Assert.*
import org.junit.Test

class AlignmentProtocolTest {
  private val audio = AlignmentProtocol.sha256("original fixture bytes")
  private fun request(text: String = "  Ciao,\tmondo!\n") = AlignmentRequest(
    source = AlignmentSource("raw", 27, AlignmentProtocol.sha256(text), listOf(AlignmentPart("a", audio))),
    partId = "a", text = text, textSha256 = AlignmentProtocol.sha256(text), language = "it",
    segments = listOf(AlignmentWindow(0, text.length, 0, 3000)),
  )
  private fun response(r: AlignmentRequest) = AlignmentResponse(1, r.source, r.partId, r.text, r.textSha256, r.language,
    "utf16", "ms", audio, "job-fixture-01", "instance-1", "aligned",
    AlignmentProtocol.tokens(r.text).mapIndexed { i, span -> AlignmentTiming(span.first, span.last + 1, i * 500L, i * 500L + 400, "measured") })
  private fun rejected(block: () -> Unit) { try { block(); fail("Must reject") } catch (_: IllegalArgumentException) { } }

  @Test fun `exact unicode whitespace punctuation hash and request JSON round trip`() {
    val r = request("  Sì,\t😀 e\u0301!\u00a0fine.\r\n")
    assertNotEquals(AlignmentProtocol.sha256(r.text), AlignmentProtocol.sha256(r.text.trim()))
    assertEquals(r, AlignmentProtocol.json.decodeFromString(AlignmentRequest.serializer(), AlignmentProtocol.json.encodeToString(AlignmentRequest.serializer(), r)))
    assertEquals(listOf("Sì,", "😀", "e\u0301!", "fine."), AlignmentProtocol.tokens(r.text).map { r.text.substring(it) })
    AlignmentProtocol.validate(r, 5000)
  }
  @Test fun `accept measured subset without completing missing word`() {
    val r = request(); val measured = response(r)
    val missing = measured.timings.last().copy(startMs = null, endMs = null, status = "missing", reason = "unknown_character")
    val partial = measured.copy(status = "partial", timings = listOf(measured.timings.first(), missing))
    AlignmentProtocol.validate(partial, r, 5000, partial.jobId)
    val part = CloudTextPart("a", audio, r.text, "it", "groq", "whisper")
    val spans = CloudSources.display(part, CloudOverlay("a", r.textSha256, "it", partial.timings))
    assertEquals(r.text, spans.joinToString("") { it.text })
    assertEquals(1, spans.count { it.evidence == "measured" })
    assertTrue(spans.filter { it.evidence != "measured" }.all { it.startMs == null && it.endMs == null })
  }
  @Test fun `all missing stays deferred with null times`() {
    val r = request(); val missing = response(r).copy(status = "deferred", timings = response(r).timings.map { it.copy(startMs = null, endMs = null, status = "missing", reason = "aligner_unavailable") })
    AlignmentProtocol.validate(missing, r, 5000, missing.jobId)
  }
  @Test fun `reject every wrong echo including full source order version hash language and part`() {
    val r = request(); val good = response(r)
    listOf(good.copy(source = r.source.copy(rawId = "other")), good.copy(source = r.source.copy(rawVersion = 28)),
      good.copy(source = r.source.copy(rawSha256 = audio)), good.copy(source = r.source.copy(parts = listOf(AlignmentPart("b", audio), AlignmentPart("a", audio)))),
      good.copy(text = r.text.trim()), good.copy(textSha256 = audio), good.copy(language = "en"), good.copy(partId = "b"),
      good.copy(audioSha256 = AlignmentProtocol.sha256("other")), good.copy(offsetUnit = "codepoint"), good.copy(timeUnit = "seconds"),
      good.copy(jobId = "job-fixture-02"), good.copy(instance = ""), good.copy(protocolVersion = 2)
    ).forEach { bad -> rejected { AlignmentProtocol.validate(bad, r, 5000, good.jobId) } }
  }
  @Test fun `reject omitted extra duplicate and partial token evidence`() {
    val r = request(); val good = response(r)
    listOf(good.timings.drop(1), good.timings + good.timings.last(), listOf(good.timings.first(), good.timings.first()),
      good.timings.mapIndexed { i, t -> if (i == 0) t.copy(textEnd = t.textEnd - 1) else t })
      .forEach { rejected { AlignmentProtocol.validate(good.copy(timings = it), r, 5000, good.jobId) } }
  }
  @Test fun `reject invalid measured and missing time status combinations`() {
    val r = request(); val good = response(r); val t = good.timings.first()
    listOf(t.copy(startMs = null), t.copy(endMs = null), t.copy(startMs = -1), t.copy(endMs = 6000), t.copy(endMs = 0),
      t.copy(status = "missing"), t.copy(status = "estimated"), t.copy(reason = "guessed"))
      .forEach { rejected { AlignmentProtocol.validate(good.copy(timings = listOf(it, good.timings.last())), r, 5000, good.jobId) } }
  }
  @Test fun `reject overlapping backward and out of bounded window evidence`() {
    val r = request(); val good = response(r)
    rejected { AlignmentProtocol.validate(good.copy(timings = listOf(good.timings.first(), good.timings.last().copy(startMs = 100))), r, 5000, good.jobId) }
    rejected { AlignmentProtocol.validate(good.copy(timings = good.timings.map { it.copy(startMs = it.startMs!! + 3500, endMs = it.endMs!! + 3500) }), r, 5000, good.jobId) }
  }
  @Test fun `reject surrogate splits unpaired unicode and invalid source identities`() {
    val r = request("😀 ciao")
    rejected { AlignmentProtocol.validate(r.copy(segments = listOf(AlignmentWindow(1, r.text.length, 0, 3000))), 5000) }
    rejected { AlignmentProtocol.validate(request("\uD800"), 5000) }
    rejected { AlignmentProtocol.validate(r.copy(source = r.source.copy(rawVersion = 0)), 5000) }
    rejected { AlignmentProtocol.validate(r.copy(source = r.source.copy(parts = r.source.parts + r.source.parts)), 5000) }
    rejected { AlignmentProtocol.validate(r.copy(language = "auto"), 5000) }
  }
  @Test fun `map only exact segments with reliable whitespace gaps`() {
    val text = "  Una,\tfrase.\n\nDue! "
    val windows = AlignmentProtocol.windows(text, listOf(AlignmentProtocol.CoarseSegment("Una,\tfrase.", 100, 800), AlignmentProtocol.CoarseSegment("Due!", 1000, 1600)), 2000)
    assertEquals(listOf("Una,\tfrase.", "Due!"), windows.map { text.substring(it.textStart, it.textEnd) })
    assertTrue(AlignmentProtocol.windows(text, listOf(AlignmentProtocol.CoarseSegment("Una, frase.", 100, 800)), 2000).isEmpty())
    assertTrue(AlignmentProtocol.windows(text, listOf(AlignmentProtocol.CoarseSegment("Due!", 1000, 1600)), 2000).isEmpty())
  }
  @Test fun `never distribute long unsegmented text over audio`() {
    val r = request("a".repeat(401)).copy(segments = emptyList())
    AlignmentProtocol.validate(r, 60_000)
    assertTrue(AlignmentProtocol.windows(r.text, emptyList(), 60_000).isEmpty())
    rejected { AlignmentProtocol.validate(response(r), r, 60_000, "job-fixture-01") }
  }
  @Test fun `caps bound matrices windows and total text`() {
    rejected { AlignmentProtocol.validate(request("a".repeat(401)), 5000) }
    rejected { AlignmentProtocol.validate(request().copy(segments = listOf(AlignmentWindow(0, 5, 0, 30_001))), 40_000) }
    rejected { AlignmentProtocol.validate(request().copy(segments = List(1001) { AlignmentWindow(0, 5, 0, 1) }), 5000) }
    rejected { AlignmentProtocol.validate(request("a".repeat(200001)).copy(segments = emptyList()), 5000) }
    assertTrue(AlignmentProtocol.windows("a".repeat(401), listOf(AlignmentProtocol.CoarseSegment("a".repeat(401), 0, 1000)), 1000).isEmpty())
  }
  @Test fun `punctuation only cannot claim measured speech`() {
    val r = request("!!!")
    rejected { AlignmentProtocol.validate(response(r), r, 5000, "job-fixture-01") }
  }
}
