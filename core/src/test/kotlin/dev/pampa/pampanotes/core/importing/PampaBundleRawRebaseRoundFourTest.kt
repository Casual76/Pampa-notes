package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import org.junit.Assert.*
import org.junit.Test

class PampaBundleRawRebaseRoundFourTest {
  private val raw = TranscriptEntity("raw", "s", TranscriptKind.RAW, "custom", "test",
    text = "First.\n\nSecond!\n", wordCount = 2, createdAt = 1,
    cloudSourceJson = "{\"text\":\" First.\\n\\nSecond!\\n \"}")
  private val alternative = raw.copy(id = "alternative", text = "Other  words\n\nexact!\n", cloudSourceJson = "")
  private val refined = raw.copy(id = "refined", kind = TranscriptKind.REFINED, parentId = raw.id,
    text = "Refined\n\nexact\n", cloudSourceJson = null)

  private fun part(id: String, position: Int, duration: Long = 1000) =
    AudioPartEntity(id, "s", position, "$id.m4a", "$id.m4a", "audio/mp4", 0, duration, "", 1)

  private fun segment(transcript: String, part: String, index: Int, start: Long, text: String) =
    SegmentEntity(id = (if (transcript == raw.id) 90L else 190L) - index, transcriptId = transcript, partId = part, indexInPart = index,
      partStartMs = 0, partEndMs = 100, sessionStartMs = start, sessionEndMs = start + 100,
      text = text, wordsJson = "0,100,$text", speaker = "SPEAKER_00")

  private fun graph(twoRecordings: Boolean = false): PampaBundleNote {
    val parts = if (twoRecordings) listOf(part("A", 0), part("B", 1)) else listOf(part("A", 0))
    val secondPart = if (twoRecordings) "B" else "A"
    val secondStart = if (twoRecordings) 1000L else 0L
    return PampaBundleNote(
      NoteEntity("n", "f", "Note", createdAt = 1, updatedAt = 1),
      sessions = listOf(SessionEntity("s", "n", date = "2026-11-08", position = 0,
        activeTranscriptId = refined.id, createdAt = 1, updatedAt = 1)),
      parts = parts, transcripts = listOf(raw, alternative, refined),
      segments = listOf(segment(raw.id, "A", 0, 0, "First."), segment(raw.id, secondPart, 1, secondStart, "Second!"),
        segment(alternative.id, "A", 0, 0, "Other words"), segment(alternative.id, secondPart, 1, secondStart, "exact!")),
    )
  }

  private fun layoutOnly(original: PampaBundleNote, parts: List<AudioPartEntity>) = original.copy(
    sessions = original.sessions.map { it.copy(activeTranscriptId = null) }, parts = parts,
    transcripts = emptyList(), segments = emptyList(),
  )

  @Test fun precedingUntranscribedAudioRebasesBothHistoriesWithoutRewritingExactRaw() {
    val original = graph()
    val incoming = layoutOnly(original, listOf(part("L", 0), part("A", 1)))

    val merged = PampaBundleMerge.merge(original, incoming)

    assertEquals(listOf("L", "A"), merged.parts.map { it.id })
    assertEquals(setOf(1000L), merged.segments.map { it.sessionStartMs }.toSet())
    assertEquals(setOf(1100L), merged.segments.map { it.sessionEndMs }.toSet())
    assertEquals(original.transcripts, merged.transcripts)
    assertEquals(refined.id, merged.sessions.single().activeTranscriptId)
    assertEquals(merged, PampaBundleMerge.merge(merged, incoming))
  }

  @Test fun insertingUntranscribedAudioBetweenRecordingsKeepsEachExactHistory() {
    val original = graph(twoRecordings = true)
    val merged = PampaBundleMerge.merge(original,
      layoutOnly(original, listOf(part("A", 0), part("L", 1), part("B", 2))))

    assertEquals(setOf(0L), merged.segments.filter { it.partId == "A" }.map { it.sessionStartMs }.toSet())
    assertEquals(setOf(2000L), merged.segments.filter { it.partId == "B" }.map { it.sessionStartMs }.toSet())
    assertEquals(original.transcripts, merged.transcripts)
  }

  @Test fun incomingRawAndLocalOnlyAlternativeRemainSeparateWhenLocalTimelineRebases() {
    val original = graph()
    val incoming = original.copy(transcripts = listOf(raw, refined), segments = original.segments.filter { it.transcriptId == raw.id })
    // Incoming A moves ahead of local-only L. The local-only alternative shifts back to zero.
    val local = original.copy(parts = listOf(part("L", 0), part("A", 1)),
      segments = original.segments.map { it.copy(sessionStartMs = 1000, sessionEndMs = 1100) })
    val merged = PampaBundleMerge.merge(local, incoming)

    assertEquals(original.transcripts, merged.transcripts)
    assertEquals(setOf(0L), merged.segments.map { it.sessionStartMs }.toSet())
    assertEquals(listOf("A", "L"), merged.parts.map { it.id })
  }

  @Test fun canonicalEqualTimeOrderAndDatabaseIdsDoNotRewriteRaw() {
    // A zero-duration recording and the start of B legitimately share session time zero.
    val original = graph(twoRecordings = true).let { graph ->
      graph.copy(parts = listOf(part("A", 0, duration = 0), part("B", 1)),
        segments = graph.segments.reversed().mapIndexed { index, s ->
          s.copy(id = 500L + index, partEndMs = if (s.partId == "A") 0 else 100,
            sessionStartMs = 0, sessionEndMs = if (s.partId == "A") 0 else 100, wordsJson = null)
        })
    }
    val incoming = original.copy(segments = original.segments.reversed().mapIndexed { index, s -> s.copy(id = 900L + index) })

    assertEquals(original.transcripts, PampaBundleMerge.merge(null, incoming).transcripts)
    assertEquals(original.transcripts, PampaBundleMerge.merge(original, incoming).transcripts)
  }

  @Test fun genuineRecordingReorderRebuildsEachRawAndKeepsCloudSourceAndRefinement() {
    val original = graph(twoRecordings = true)
    val merged = PampaBundleMerge.merge(original, layoutOnly(original, listOf(part("B", 0), part("A", 1))))

    assertEquals("Second! First.", merged.transcripts.single { it.id == raw.id }.text)
    assertEquals("exact! Other words", merged.transcripts.single { it.id == alternative.id }.text)
    assertEquals(raw.cloudSourceJson, merged.transcripts.single { it.id == raw.id }.cloudSourceJson)
    assertEquals("", merged.transcripts.single { it.id == alternative.id }.cloudSourceJson)
    assertEquals(refined, merged.transcripts.single { it.id == refined.id })
    assertEquals(listOf("B", "A"), merged.segments.filter { it.transcriptId == raw.id }.map { it.partId })
  }

  @Test fun replacingOnlyOneRecordingRebuildsAffectedRawWithoutCombiningHistories() {
    val original = graph(twoRecordings = true)
    // A partial incoming RAW covers B; retained A makes its actual sequence different.
    val incoming = original.copy(parts = listOf(part("B", 0)), transcripts = listOf(raw.copy(text = "Replacement\n", wordCount = 1)),
      segments = listOf(segment(raw.id, "B", 0, 0, "Replacement")))
    val merged = PampaBundleMerge.merge(original, incoming)

    assertEquals("Replacement First.", merged.transcripts.single { it.id == raw.id }.text)
    assertEquals(2, merged.transcripts.single { it.id == raw.id }.wordCount)
    assertEquals("exact! Other words", merged.transcripts.single { it.id == alternative.id }.text)
    assertEquals(raw.cloudSourceJson, merged.transcripts.single { it.id == raw.id }.cloudSourceJson)
    assertEquals(refined, merged.transcripts.single { it.id == refined.id })
  }
}
