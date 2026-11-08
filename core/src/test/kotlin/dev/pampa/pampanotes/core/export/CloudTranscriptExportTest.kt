package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.alignment.*
import dev.pampa.pampanotes.core.db.*
import org.junit.Assert.*
import org.junit.Test

class CloudTranscriptExportTest {
  private val hash = AlignmentProtocol.sha256("synthetic audio identity")
  private val text = "  Cloud,\ttext!\n  "
  private val part = ExportPart("p", "original.wav", "p.wav", 5000, 0, 1, sha256 = hash)
  private val base = TranscriptEntity("r", "s", TranscriptKind.RAW, "mistral", "fixture", "en", text, wordCount = 2, createdAt = 1)

  private fun session(segmentText: String, measured: Boolean = false): ExportSession {
    val source = CloudTextPart(part.id, hash, text, "en", "mistral", "fixture")
    var doc = CloudSources.create(base, text, listOf(source))
    if (measured) {
      val timings = AlignmentProtocol.tokens(text).mapIndexed { index, span ->
        AlignmentTiming(span.first, span.last + 1, if (index == 0) 250 else null,
          if (index == 0) 800 else null, if (index == 0) "measured" else "missing")
      }
      doc = doc.copy(overlays = listOf(CloudOverlay(part.id, AlignmentProtocol.sha256(text), "en", timings)))
    }
    val raw = base.copy(cloudSourceJson = CloudSources.encode(doc))
    val segment = SegmentEntity(transcriptId = raw.id, partId = part.id, indexInPart = 0,
      partStartMs = 1000, partEndMs = 2000, sessionStartMs = 1000, sessionEndMs = 2000, text = segmentText)
    return ExportSession("s", 1, "Interview", "2026-11-08", listOf(part), raw, raw, listOf(segment))
  }

  @Test fun `timed human export retains cloud words instead of different coarse segment text`() {
    val session = session("DIFFERENT SEGMENT WORDS")
    val blocks = TranscriptPieces.blocks(session, ExportOptions(timestamps = true))
    assertEquals(text, blocks.joinToString("") { it.text })
    assertTrue(blocks.all { it.startMs == null })
    val note = ExportNote(NoteEntity("n", "f", "Recording", "LEGACY MATERIAL", createdAt = 1, updatedAt = 1),
      emptyList(), emptyList(), emptyList(), listOf(session))
    val markdown = MarkdownWriter().singleNote(note, ExportOptions(timestamps = true))
    assertTrue(markdown.contains(text))
    assertFalse(markdown.contains("DIFFERENT SEGMENT WORDS"))
    assertTrue(markdown.contains("LEGACY MATERIAL"))
  }

  @Test fun `legacy cloud RAW with null or empty provenance exports exact text without segment timing`() {
    for (provider in listOf("groq", "openrouter", "mistral")) for (metadata in listOf(null, "")) {
      val source = session("DIFFERENT SEGMENT WORDS")
      val legacy = source.transcript!!.copy(provider = provider, cloudSourceJson = metadata)
      val old = source.copy(transcript = legacy, raw = legacy)
      for (options in listOf(ExportOptions(), ExportOptions(timestamps = true), ExportOptions(speechClock = true))) {
        val blocks = TranscriptPieces.blocks(old, options)
        assertEquals(text, blocks.joinToString("") { it.text })
        assertTrue(blocks.all { it.startMs == null && it.partOffsetMs == null })
      }
      val note = ExportNote(NoteEntity("n", "f", "Recording", createdAt = 1, updatedAt = 1),
        emptyList(), emptyList(), emptyList(), listOf(old))
      val markdown = MarkdownWriter().singleNote(note, ExportOptions(timestamps = true))
      assertTrue(markdown.contains(text))
      assertFalse(markdown.contains("DIFFERENT SEGMENT WORDS"))
    }
  }

  @Test fun `matching coarse cloud segment has an explicitly estimated clock anchor`() {
    val block = TranscriptPieces.blocks(session(text.trim()), ExportOptions(speechClock = true)).single()
    assertEquals(text, block.text)
    assertEquals(1000L, block.partOffsetMs)
    assertTrue(block.clockEstimated)
  }

  @Test fun `measured first word wins over coarse segment without inventing missing word evidence`() {
    val block = TranscriptPieces.blocks(session("DIFFERENT", measured = true), ExportOptions(timestamps = true)).single()
    assertEquals(text, block.text)
    assertEquals(250L, block.startMs)
    assertFalse(block.clockEstimated)
  }

  @Test fun `changed audio identity retains exact text but cannot reuse timing`() {
    val original = session("DIFFERENT", measured = true)
    val changed = original.copy(parts = listOf(part.copy(sha256 = AlignmentProtocol.sha256("replacement"))))
    val block = TranscriptPieces.blocks(changed, ExportOptions()).single()
    assertEquals(text, block.text)
    assertNull(block.startMs)
    assertNull(block.partOffsetMs)
  }

  @Test fun `typed cloud speech clock retains hour gap independently of relative timestamps`() {
    val first = part.copy(id = "first", durationMs = 1000,
      origin = dev.pampa.pampanotes.core.importing.RecordingOrigin.fromMetadata("2025-09-22T10:00:00Z"))
    val second = part.copy(id = "second", durationMs = 1000, startMs = 1000,
      origin = dev.pampa.pampanotes.core.importing.RecordingOrigin.fromMetadata("2025-09-22T11:00:00Z"))
    val texts = listOf("First.", "Second.")
    val before = base.copy(text = texts.joinToString("\n\n"))
    val source = listOf(first, second).mapIndexed { index, p -> CloudTextPart(p.id, hash, texts[index], "en", "mistral", "fixture") }
    val raw = before.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(before, before.text, source)))
    val segments = listOf(first, second).mapIndexed { index, p -> SegmentEntity(transcriptId = raw.id, partId = p.id,
      indexInPart = 0, partStartMs = 100, partEndMs = 500, sessionStartMs = p.startMs + 100, sessionEndMs = p.startMs + 500, text = texts[index]) }
    val session = ExportSession("s", 1, "Interview", "2025-09-22", listOf(first, second), raw, raw, segments)
    val blocks = TranscriptPieces.blocks(session, ExportOptions(timestamps = false, speechClock = true))
    assertEquals(raw.text, blocks.joinToString("") { it.text })
    assertEquals(listOf("first", "second"), blocks.map { it.partId })
    assertEquals(listOf(100L, 100L), blocks.map { it.partOffsetMs })
    assertTrue(blocks.all { it.startMs == null && it.showClock && it.clockEstimated })
    assertTrue(SpeechClock.label(first.origin, blocks[0].partOffsetMs).contains("10:00:00"))
    assertTrue(SpeechClock.label(second.origin, blocks[1].partOffsetMs).contains("11:00:00"))
  }
}
