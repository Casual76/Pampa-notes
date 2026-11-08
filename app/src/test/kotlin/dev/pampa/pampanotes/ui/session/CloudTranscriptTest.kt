package dev.pampa.pampanotes.ui.session

import dev.pampa.pampanotes.core.alignment.*
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.transcription.TranscriptSearch
import org.junit.Assert.*
import org.junit.Test

class CloudTranscriptTest {
  private val hash = AlignmentProtocol.sha256("fixture audio identity only")
  private fun audio(id: String = "p", position: Int = 0, duration: Long = 5_000) =
    AudioPartEntity(id, "s", position, "$id.wav", "$id.wav", "audio/wav", 1, duration, hash, 1)
  private fun raw(text: String) = TranscriptEntity("raw", "s", TranscriptKind.RAW, "mistral", "fixture", "en", text, wordCount = 2, createdAt = 42)
  private fun document(texts: List<String>, measured: Set<Pair<Int, Int>> = setOf(0 to 0)): Pair<TranscriptEntity, List<AudioPartEntity>> {
    val parts = texts.mapIndexed { index, _ -> audio("p$index", index) }
    val text = texts.joinToString("\n\n")
    val raw = raw(text)
    val sourceParts = texts.mapIndexed { index, body -> CloudTextPart(parts[index].id, hash, body, "en", "mistral", "fixture") }
    val overlays = sourceParts.mapIndexed { part, source ->
      val sourceText = requireNotNull(source.text)
      CloudOverlay(source.id, AlignmentProtocol.sha256(sourceText), "en", AlignmentProtocol.tokens(sourceText).mapIndexed { word, range ->
        if (part to word in measured) AlignmentTiming(range.first, range.last + 1, 100 + word * 100L, 180 + word * 100L, "measured")
        else AlignmentTiming(range.first, range.last + 1, null, null, "missing", "no_evidence")
      })
    }
    return raw.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(raw, text, sourceParts).copy(overlays = overlays))) to parts
  }
  private fun find(display: CloudTranscript, query: String) = display.locate(TranscriptSearch.find(listOf(display.text), query))

  @Test fun `partial evidence displays exact punctuation whitespace and nullable unknown words`() {
    val (raw, parts) = document(listOf("  Hello,\tmissing! 😀\n "))
    val display = CloudTranscript.prepare(raw, parts)
    assertEquals(raw.text, display.blocks.joinToString("") { it.text })
    assertEquals(1, display.blocks.flatMap { it.measured }.size)
    assertEquals(100L, display.timeAt(0, 2))
    listOf(-1, 0, 1, 8, 9, 15, raw.text.length).forEach { assertNull(display.timeAt(0, it)) }
    assertNull(display.timeAt(1, 0))
  }

  @Test fun `unavailable source metadata always gives exact plain RAW without fabricated times`() {
    val text = "\t  # **literal**\n\n  原文 😀  "
    listOf(null, "", "{}", "{invalid", "{\"version\":999}").forEach { metadata ->
      val display = CloudTranscript.prepare(raw(text).copy(cloudSourceJson = metadata), listOf(audio()))
      assertEquals(text, display.blocks.joinToString("") { it.text })
      assertFalse(display.hasMeasured)
      assertEquals(-1, display.activeBlock(100))
      assertTrue(display.blocks.all { it.measured.isEmpty() })
    }
  }

  @Test fun `all missing evidence remains readable searchable and untimed`() {
    val (raw, parts) = document(listOf("  unknown\twords\n"), emptySet())
    val display = CloudTranscript.prepare(raw, parts)
    assertEquals(raw.text, display.blocks.single().text)
    assertFalse(display.hasMeasured)
    val match = find(display, "unknown words").single()
    assertEquals(2, match.start)
    assertNull(display.timeAt(match.block, match.start))
    assertEquals(-1, display.activeBlock(0))
  }

  @Test fun `complete measured evidence uses actual intervals without filling whitespace`() {
    val (raw, parts) = document(listOf("  first,\tsecond!  "), setOf(0 to 0, 0 to 1))
    val display = CloudTranscript.prepare(raw, parts)
    assertEquals(2, display.blocks.single().measured.size)
    assertEquals(listOf(100L, 200L), display.blocks.single().measured.map { it.startMs })
    assertEquals(100L, display.timeAt(0, 2))
    assertEquals(200L, display.timeAt(0, 9))
    assertNull(display.timeAt(0, 8))
    assertNull(display.timeAt(0, 16))
    assertEquals(raw.text, display.blocks.single().text)
  }

  @Test fun `current audio hash or raw identity changes suppress every timing without hiding text`() {
    val (raw, parts) = document(listOf("known missing"))
    val cases = listOf(raw to parts.map { it.copy(sha256 = AlignmentProtocol.sha256("replacement")) },
      raw.copy(text = " edited! ") to parts, raw.copy(createdAt = 43) to parts, raw.copy(id = "replacement") to parts)
    cases.forEach { (current, audio) ->
      val display = CloudTranscript.prepare(current, audio)
      assertFalse(display.hasMeasured)
      assertEquals(current.text, display.blocks.joinToString("") { it.text })
    }
  }

  @Test fun `unknown multipart text boundaries cannot be guessed from the aggregate RAW`() {
    val raw = raw("  exact\twhole\ntext!  ")
    val parts = listOf(audio("a", 0), audio("b", 1))
    val source = CloudSources.create(raw, raw.text, parts.map { CloudTextPart(it.id, hash, null, "en", "mistral", "fixture") })
    val display = CloudTranscript.prepare(raw.copy(cloudSourceJson = CloudSources.encode(source)), parts)
    assertEquals(raw.text, display.blocks.joinToString("") { it.text })
    assertFalse(display.hasMeasured)
  }

  @Test fun `invalid duration session placement duplicate positions and overflow suppress timings`() {
    val (raw, parts) = document(listOf("first words", "second words"), setOf(0 to 0, 1 to 0))
    val invalid = listOf(parts.map { it.copy(durationMs = -1) }, parts.map { it.copy(durationMs = 50) },
      parts.map { it.copy(sessionId = "other") }, parts.map { it.copy(position = 0) },
      parts.map { it.copy(durationMs = Long.MAX_VALUE) })
    invalid.forEach { assertFalse(CloudTranscript.prepare(raw, it).hasMeasured) }
  }

  @Test fun `multiple source parts and preceding audio gaps retain correct session times and local text offsets`() {
    val (raw, parts) = document(listOf(" \tfirst missing\n", " second missing  "), setOf(0 to 0, 1 to 0))
    val preceding = audio("before", 0, 9_000)
    val display = CloudTranscript.prepare(raw, listOf(preceding) + parts.map { it.copy(position = it.position + 1) })
    assertEquals(raw.text, display.blocks.joinToString("") { it.text })
    val first = find(display, "first").single(); val second = find(display, "second").single()
    assertEquals(9_100L, display.timeAt(first.block, first.start))
    assertEquals(14_100L, display.timeAt(second.block, second.start))
    assertEquals(first.block, display.activeBlock(9_100))
    assertEquals(second.block, display.activeBlock(14_100))
    assertEquals(-1, display.activeBlock(10_000))
    assertEquals(-1, display.activeBlock(14_180))
    assertNull(display.timeAt(second.block, second.start - 1))
  }

  @Test fun `portable unbound verified public overlay can display but cannot attest new alignment`() {
    val (raw, parts) = document(listOf("known missing"))
    val doc = CloudSources.decode(raw.cloudSourceJson)!!
    val portable = doc.copy(alignmentBinding = "portable_unbound", originals = doc.originals.map { it.copy(contentScope = "portable_projection") })
    val imported = raw.copy(cloudSourceJson = CloudSources.encode(portable))
    assertTrue(CloudSources.validatePortable(imported, parts))
    assertNull(CloudSources.bindSource(portable, parts))
    assertTrue(CloudTranscript.prepare(imported, parts).hasMeasured)
    assertFalse(CloudTranscript.prepare(imported, parts.map { it.copy(sha256 = AlignmentProtocol.sha256("replacement")) }).hasMeasured)
  }

  @Test fun `portable projection retains existing measured overlay without fabricating missing words`() {
    val (raw, parts) = document(listOf("known missing", "second unknown"), setOf(0 to 0, 1 to 0))
    val projection = CloudSources.portableProjection(raw, parts.takeLast(1), "copy", mapOf(parts.last().id to "copy-part"), 99)
    val copied = raw.copy(id = "copy", createdAt = 99, text = projection.text, cloudSourceJson = projection.cloudSourceJson)
    val copiedParts = listOf(parts.last().copy(id = "copy-part", position = 0))
    val display = CloudTranscript.prepare(copied, copiedParts)
    assertEquals("second unknown", display.text)
    assertEquals(1, display.blocks.flatMap { it.measured }.size)
    assertNull(display.timeAt(0, 7))
  }

  @Test fun `search spans reading boundaries exactly and never invents a seek`() {
    val text = "a".repeat(1_593) + "BEFORE\tAFTER  😀\n" + " tail".repeat(50)
    val display = CloudTranscript.prepare(raw(text), emptyList())
    val boundary = display.blocks.first().textEnd
    val query = text.substring(boundary - 7, boundary + 5)
    val match = find(display, query).single()
    assertEquals(boundary - 7, match.start + display.blocks[match.block].textStart)
    val painted = display.blocks.indices.flatMap { index ->
      display.highlights(index, listOf(match)).map { range -> display.blocks[index].text.substring(range) }
    }.joinToString("")
    assertEquals(query, painted)
    assertTrue(match.end > display.blocks[match.block].text.length)
    val index = display.highlightIndex(listOf(match))
    assertEquals(2, index.size)
    display.blocks.indices.forEach { block -> assertEquals(display.highlights(block, listOf(match)), index[block].orEmpty()) }
    assertNull(display.timeAt(match.block, match.start))
  }

  @Test fun `search finds an unbroken token across hard split and paints all fragments`() {
    val text = "a".repeat(1_599) + "XYZ😀boundary" + "z".repeat(1_700)
    val display = CloudTranscript.prepare(raw(text), emptyList())
    val matches = find(display, "XYZ😀boundary")
    assertEquals(1, matches.size)
    val paint = display.blocks.indices.flatMap { i -> display.highlights(i, matches).map { display.blocks[i].text.substring(it) } }.joinToString("")
    assertEquals("XYZ😀boundary", paint)
  }

  @Test fun `search on missing words or whitespace has no nearest measured fallback`() {
    val (raw, parts) = document(listOf("known missing known"), setOf(0 to 0, 0 to 2))
    val display = CloudTranscript.prepare(raw, parts)
    val missing = find(display, "missing").single()
    assertNull(display.timeAt(missing.block, missing.start))
    assertNull(display.timeAt(0, 5))
    assertEquals(300L, display.timeAt(0, 14))
    val known = find(display, "known")
    assertEquals(1, display.matchAt(known, 351))
    assertEquals(-1, display.activeBlock(180))
    assertEquals(-1, display.activeBlock(299))
  }

  @Test fun `legacy computer and refined paths keep their existing Paragraph selection`() {
    assertFalse(CloudTranscript.appliesTo(raw("computer").copy(provider = "custom")))
    assertFalse(CloudTranscript.appliesTo(raw("school legacy").copy(provider = "local")))
    assertFalse(CloudTranscript.appliesTo(raw("refined").copy(kind = TranscriptKind.REFINED, cloudSourceJson = "{}")))
    assertFalse(CloudTranscript.appliesTo(null))
    assertTrue(CloudTranscript.appliesTo(raw("unknown source").copy(provider = "mixed", cloudSourceJson = "{}")))
  }
}
