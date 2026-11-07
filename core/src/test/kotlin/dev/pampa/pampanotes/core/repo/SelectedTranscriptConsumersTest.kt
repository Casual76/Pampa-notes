package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SelectedTranscriptConsumersTest {
  private val sessions = mockk<SessionDao>()
  private val transcripts = mockk<TranscriptDao>()
  private val segments = mockk<SegmentDao>()
  private val parts = mockk<AudioPartDao>()
  private val author = TranscriptEntity("author", "s", TranscriptKind.RAW, "custom", "model", text = "author words", wordCount = 2, createdAt = 100)
  private val local = author.copy(id = "local", text = "local words", createdAt = 200)
  private val refined = author.copy(id = "refined", kind = TranscriptKind.REFINED, parentId = author.id, createdAt = 300)
  private val session = SessionEntity("s", "n", date = "2026-11-05", position = 0, activeTranscriptId = refined.id, createdAt = 1, updatedAt = 1)
  private val a = AudioPartEntity("A", "s", 0, "a.wav", "a.wav", "audio/wav", 1, 3000, "a", 1)
  private val b = a.copy(id = "B", position = 1)
  private val timed = SegmentEntity(id = 1, transcriptId = author.id, partId = a.id, indexInPart = 0,
    partStartMs = 250, partEndMs = 950, sessionStartMs = 250, sessionEndMs = 950, text = author.text)

  private fun seed() {
    coEvery { sessions.get("s") } returns session
    coEvery { transcripts.bySession("s") } returns listOf(author, local, refined)
  }

  private fun transcription() = TranscriptionRepository(mockk(), sessions, mockk(), parts, transcripts, segments,
    mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk())

  @Test fun `search seeks inside the selected version's RAW parent`() = runTest {
    seed()
    coEvery { segments.byTranscriptWithoutWords(author.id) } returns listOf(timed)
    coEvery { segments.wordsOf(timed.id) } returns null
    val search = SearchRepository(mockk(), mockk(), mockk(), sessions, transcripts, segments)
    assertEquals(250L, search.momentOf("s", "author")?.timeMs)
    coVerify(exactly = 0) { segments.byTranscriptWithoutWords(local.id) }
  }

  @Test fun `refinement and worker RAW input follow the selected parent instead of newest history`() = runTest {
    seed()
    assertEquals(author, transcription().rawFor("s"))
    coEvery { sessions.get("s") } returns session.copy(activeTranscriptId = local.id)
    assertEquals(local, transcription().rawFor("s"))
  }

  @Test fun `remote completion requires one selected RAW covering all parts rather than a union of histories`() = runTest {
    seed()
    coEvery { parts.bySession("s") } returns listOf(a, b)
    coEvery { segments.byTranscript(author.id) } returns listOf(timed)
    val timedB = timed.copy(id = 2, partId = b.id, sessionStartMs = 3250, sessionEndMs = 3950)
    coEvery { segments.byParts(any()) } returns listOf(timed, timedB.copy(transcriptId = local.id))
    val job = JobEntity("job", "s", JobType.TRANSCRIBE, "custom", createdAt = 50, updatedAt = 50)
    val repository = transcription()
    assertFalse(repository.arrivedFromElsewhere(job))
    coEvery { segments.byTranscript(author.id) } returns listOf(timed, timedB)
    assertTrue(repository.arrivedFromElsewhere(job))
    assertFalse(repository.arrivedFromElsewhere(job.copy(createdAt = 150)))
    coVerify(exactly = 0) { segments.byParts(any()) }
  }
}
