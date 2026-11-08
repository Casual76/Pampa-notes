package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.*
import org.junit.Assert.*
import org.junit.Test

class AlignmentLanguageApplicationRegressionTest {
  private val text = "  Exact,\tcloud!\r\n"
  private val hash = AlignmentProtocol.sha256("original audio")
  private val audio = AudioPartEntity("a", "s", 0, "a.wav", "a.wav", "audio/wav", 1, 3000, hash, 1)

  private fun source(label: String?): TranscriptEntity {
    val raw = TranscriptEntity("r", "s", TranscriptKind.RAW, "groq", "whisper", label, text, wordCount = 2, createdAt = 42)
    return raw.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(raw, text,
      listOf(CloudTextPart("a", hash, text, label, raw.provider, raw.model)), " Exact runner report. ")))
  }
  private fun snapshot(raw: TranscriptEntity, code: String): AlignmentSnapshot {
    val request = AlignmentRequest(source = AlignmentSource(raw.id, 42, AlignmentProtocol.sha256(text), listOf(AlignmentPart("a", hash))),
      partId = "a", text = text, textSha256 = AlignmentProtocol.sha256(text), language = code, segments = emptyList())
    return AlignmentSnapshot("s", "n", "f", "r", "r", AlignmentProtocol.sha256(text), raw.language, "owner", "privacy", "coarse",
      listOf(AlignmentPlacement("a", hash, 0, 3000, "a.wav")), request)
  }
  private fun response(snapshot: AlignmentSnapshot, code: String = snapshot.request.language) = AlignmentResponse(
    1, snapshot.request.source, "a", text, AlignmentProtocol.sha256(text), code, "utf16", "ms", hash, "fresh-job-42", "instance", "partial",
    AlignmentProtocol.tokens(text).mapIndexed { i, span -> AlignmentTiming(span.first, span.last + 1,
      if (i == 0) 100 else null, if (i == 0) 500 else null, if (i == 0) "measured" else "missing") })

  @Test fun `named uppercase and ISO labels apply encode display and transfer without changing reported language`() {
    for ((label, code) in listOf("English" to "en", "Italian" to "it", "IT" to "it", "EN" to "en", "en" to "en", "eng" to "en", "ita" to "it")) {
      val raw = source(label)
      val expected = snapshot(raw, AlignmentLanguage.code(label)!!)
      assertEquals(code, expected.request.language)
      val applied = AlignmentApplication.plan(expected, expected, raw, response(expected), "owner", false, "fresh-job-42")!!
      assertEquals(raw, applied.copy(cloudSourceJson = raw.cloudSourceJson))
      val before = CloudSources.decode(raw.cloudSourceJson)!!
      val doc = CloudSources.decode(applied.cloudSourceJson)!!
      assertEquals(before, doc.copy(overlays = emptyList()))
      assertEquals(label, doc.parts.single().language)
      assertEquals(label, doc.originals.single().language)
      assertEquals(code, doc.overlays.single().language)
      val display = AlignmentPresentation.forRaw(applied, listOf(audio))!!
      assertEquals(text, display.text)
      assertEquals(100L, display.measured.single().startMs)
      assertTrue(display.spans.filter { it.evidence == "missing" }.all { it.startMs == null && it.endMs == null })
      val projection = CloudSources.portableProjection(applied, listOf(audio), "copy-r", mapOf("a" to "copy-a"), 99)
      val imported = applied.copy(id = "copy-r", createdAt = 99, text = projection.text, cloudSourceJson = projection.cloudSourceJson)
      val copiedParts = listOf(audio.copy(id = "copy-a"))
      assertTrue(CloudSources.validatePortable(imported, copiedParts))
      assertNull(CloudSources.bindSource(CloudSources.decode(imported.cloudSourceJson)!!, copiedParts))
      assertEquals(text, AlignmentPresentation.forRaw(imported, copiedParts)!!.text)
      assertEquals(100L, AlignmentPresentation.forRaw(imported, copiedParts)!!.measured.single().startMs)
    }
  }

  @Test fun `wrong wire language including equivalent noncanonical spelling is rejected`() {
    val raw = source("English")
    val expected = snapshot(raw, "en")
    for (wrong in listOf("it", "eng", "English", "EN", "auto")) {
      assertThrows(IllegalArgumentException::class.java) {
        AlignmentApplication.plan(expected, expected, raw, response(expected, wrong), "owner", false, "fresh-job-42")
      }
    }
    assertTrue(CloudSources.decode(raw.cloudSourceJson)!!.overlays.isEmpty())
  }

  @Test fun `unknown and auto language never acquire an overlay or measured display`() {
    for (label in listOf(null, "", "auto", "unsupported", "zz")) {
      assertNull(AlignmentLanguage.code(label))
      val raw = source(label)
      val expected = snapshot(raw, "en")
      assertNull(AlignmentApplication.plan(expected, expected, raw, response(expected), "owner", false, "fresh-job-42"))
      val doc = CloudSources.decode(raw.cloudSourceJson)!!
      val overlay = CloudOverlay("a", AlignmentProtocol.sha256(text), "en", response(expected).timings)
      assertThrows(IllegalArgumentException::class.java) { CloudSources.encode(doc.copy(overlays = listOf(overlay))) }
      assertTrue(CloudSources.display(doc.parts.single(), overlay).all { it.startMs == null && it.endMs == null })
    }
  }

  @Test fun `existing lowercase ISO3 overlays keep their language identity and measured display`() {
    val raw = source("eng")
    val expected = snapshot(raw, "en")
    val doc = CloudSources.decode(raw.cloudSourceJson)!!
    val overlay = CloudOverlay("a", AlignmentProtocol.sha256(text), "eng", response(expected).timings)
    val stored = raw.copy(cloudSourceJson = CloudSources.encode(doc.copy(overlays = listOf(overlay))))
    assertEquals(100L, AlignmentPresentation.forRaw(stored, listOf(audio))!!.measured.single().startMs)
  }
}
