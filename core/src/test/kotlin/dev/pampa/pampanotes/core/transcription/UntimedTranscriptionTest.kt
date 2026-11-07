package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.audio.ChunkSpec
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.playback.SilenceSkipper
import org.junit.Assert.*
import org.junit.Test

class UntimedTranscriptionTest {
  @Test fun `text chunks survive resume stitch assembly and reorder without invented word timing`() {
    val spec = ChunkSpec(0, 0, 60_000)
    val chunk = ChunkTranscript(spec, emptyList(), "First chunk.")
    val restored = StoredChunks.decode(StoredChunks.encode(chunk, "openrouter|m"), spec, "openrouter|m")!!
    val stitched = TranscriptStitcher.stitch(listOf(restored, ChunkTranscript(
      ChunkSpec(1, 55_000, 120_000), emptyList(), "Second chunk.",
    )))
    assertEquals("First chunk.\n\nSecond chunk.", stitched.text)
    assertTrue(stitched.segments.isEmpty())
    val part = AudioPartEntity("a", "s", 0, "a.m4a", "a.m4a", "audio/mp4", 3, 120_000, "sha", 1)
    val result = SessionAssembler.assemble(listOf(PartTranscript(part, stitched.text, emptyList(), "en")), "openrouter", "m")
    val row = result.segments.single()
    assertEquals(0L, row.partStartMs)
    assertEquals(0L, row.partEndMs)
    assertNull(row.wordsEncoded)
    assertFalse(row.wordsEstimated)
    val rebuilt = SessionAssembler.reassemble(listOf(SessionAssembler.Part("b", 1000), SessionAssembler.Part("a", 120_000)), result.segments)
    assertEquals(stitched.text, rebuilt.text)
    assertEquals(1000L, rebuilt.segments.single().sessionStartMs)
  }

  @Test fun `mixed chunk response never loses text when only some chunks have timestamps`() {
    val result = TranscriptStitcher.stitch(listOf(
      ChunkTranscript(ChunkSpec(0, 0, 60_000), listOf(RawSegment(0, 3000, "First sentence."))),
      ChunkTranscript(ChunkSpec(1, 55_000, 120_000), emptyList(), "Untimed sentence."),
    ))
    assertTrue(result.text.contains("First sentence."))
    assertTrue(result.text.contains("Untimed sentence."))
    assertTrue(result.segments.isEmpty())
  }

  @Test fun `untimed text never implies that the whole recording was silent`() {
    val row = SegmentEntity(transcriptId = "t", partId = "p", indexInPart = 0,
      partStartMs = 0, partEndMs = 0, sessionStartMs = 0, sessionEndMs = 0, text = "Speech without timestamps")
    assertTrue(SilenceSkipper.gapsOf(listOf(row), listOf(SessionAssembler.Part("p", 120_000))).isEmpty())
    assertTrue(TranscriptParagraphs.split(listOf(row)).isEmpty())
    assertTrue(Chapters.index(listOf(row)).isEmpty())
  }
}
