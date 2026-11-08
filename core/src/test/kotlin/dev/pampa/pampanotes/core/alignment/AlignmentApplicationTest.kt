package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.*
import org.junit.Assert.*
import org.junit.Test

class AlignmentApplicationTest {
  private val text = "  Exact,\tcloud!\n"
  private val sha = AlignmentProtocol.sha256("original audio")
  private val part = CloudTextPart("a", sha, text, "en", "groq", "whisper")
  private val raw = TranscriptEntity("raw", "s", TranscriptKind.RAW, "groq", "whisper", "en", text, wordCount = 2, createdAt = 42).let {
    it.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(it, text, listOf(part))))
  }
  private val request = AlignmentRequest(source = AlignmentSource("raw", 42, AlignmentProtocol.sha256(text), listOf(AlignmentPart("a", sha))), partId = "a", text = text,
    textSha256 = AlignmentProtocol.sha256(text), language = "en", segments = listOf(AlignmentWindow(0, text.length, 0, 3000)))
  private val snapshot = AlignmentSnapshot("s", "n", "folder", "refined", "refined", AlignmentProtocol.sha256("cleaned selected text"), "en", "owner-account", "privacy-settings", "coarse-windows",
    listOf(AlignmentPlacement("a", sha, 0, 5000, "a.wav")), request)
  private val response = AlignmentResponse(1, request.source, "a", text, request.textSha256, "en", "utf16", "ms", sha, "fresh-job-42", "companion-instance", "partial",
    AlignmentProtocol.tokens(text).mapIndexed { i, span -> if (i == 0) AlignmentTiming(span.first, span.last + 1, 100, 700, "measured")
      else AlignmentTiming(span.first, span.last + 1, null, null, "missing", "unknown_character") })
  private fun apply(current: AlignmentSnapshot? = snapshot, source: TranscriptEntity = raw, account: String = snapshot.accountKey, cancelled: Boolean = false) =
    AlignmentApplication.plan(snapshot, current, source, response, account, cancelled, "fresh-job-42")

  @Test fun `apply changes only overlay never words word count parent or selected transcript`() {
    val result = apply()!!
    assertEquals(raw, result.copy(cloudSourceJson = raw.cloudSourceJson))
    val before = CloudSources.decode(raw.cloudSourceJson)!!; val after = CloudSources.decode(result.cloudSourceJson)!!
    assertEquals(before, after.copy(overlays = emptyList()))
    assertEquals(1, after.overlays.single().timings.count { it.status == "measured" })
    assertNull(after.overlays.single().timings.last().startMs)
    assertEquals(text, CloudSources.display(part, after.overlays.single()).joinToString("") { it.text })
    assertEquals("refined", snapshot.selectedTranscriptId)
  }
  @Test fun `reject text version selection refined text language hashes order placement and account changes`() {
    val p = snapshot.placement.single()
    val variants = listOf(snapshot.copy(activeTranscriptId = "new-raw"), snapshot.copy(selectedTranscriptId = "new-raw"), snapshot.copy(selectedTextSha256 = "edited-refinement"),
      snapshot.copy(rawLanguage = "it"), snapshot.copy(accountKey = "other-account"), snapshot.copy(settingsKey = "revoked-privacy"), snapshot.copy(segmentSha256 = "edited-coarse-text"),
      snapshot.copy(placement = listOf(p.copy(sha256 = AlignmentProtocol.sha256("other audio")))), snapshot.copy(placement = listOf(p.copy(position = 1))),
      snapshot.copy(placement = listOf(p.copy(durationMs = 6000))), snapshot.copy(noteId = "moved-note"), snapshot.copy(sessionId = "moved-session"),
      snapshot.copy(request = request.copy(source = request.source.copy(rawVersion = 43))), snapshot.copy(request = request.copy(source = request.source.copy(rawSha256 = sha))),
      snapshot.copy(request = request.copy(language = "it")), snapshot.copy(request = request.copy(source = request.source.copy(parts = request.source.parts + AlignmentPart("b", sha)))))
    variants.forEach { assertNull(apply(it)) }
    assertNull(apply(null)); assertNull(apply(account = "other-account")); assertNull(apply(cancelled = true))
  }
  @Test fun `reject raw mutated after snapshot and never migrate overlay to another raw`() {
    listOf(raw.copy(id = "another"), raw.copy(createdAt = 43), raw.copy(text = text.trim()), raw.copy(language = "it"), raw.copy(kind = TranscriptKind.REFINED), raw.copy(cloudSourceJson = null))
      .forEach { assertNull(apply(source = it)) }
  }
  @Test fun `partial missing evidence cannot enter legacy complete word timings`() {
    val result = apply()!!; val doc = CloudSources.decode(result.cloudSourceJson)!!
    val spans = CloudSources.display(doc.parts.single(), doc.overlays.single())
    assertEquals(1, spans.count { it.startMs != null })
    assertTrue(spans.filter { it.evidence == "missing" }.all { it.startMs == null && it.endMs == null })
    // No legacy word array is emitted: original coarse SegmentEntity rows stay a separate source.
    assertEquals(2, result.wordCount)
  }
  @Test fun `presentation preserves exact offsets and only highlights measured subset after placement`() {
    val result = apply()!!
    val preceding = AudioPartEntity("before", "s", 0, "before.wav", "before.wav", "audio/wav", 4, 9000, AlignmentProtocol.sha256("other"), 1)
    val source = preceding.copy(id = "a", position = 1, fileName = "a.wav", durationMs = 5000, sha256 = sha)
    val display = AlignmentPresentation.forRaw(result, listOf(preceding, source))!!
    assertEquals(text, display.text)
    assertEquals(1, display.measured.size)
    assertEquals(9100L, display.measured.single().startMs)
    assertTrue(display.spans.filter { it.evidence == "missing" }.all { it.startMs == null && it.endMs == null })
    assertEquals(text, display.spans.joinToString("") { display.text.substring(it.textStart, it.textEnd) })
    assertNull(AlignmentPresentation.forRaw(result, listOf(source.copy(sha256 = AlignmentProtocol.sha256("replacement")))))
  }
}
