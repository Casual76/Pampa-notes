package dev.pampa.pampanotes.ui.session

import dev.pampa.pampanotes.core.db.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionTranscriptSelectionTest {
  private fun raw(id: String, at: Long) = TranscriptEntity(id, "s", TranscriptKind.RAW, "custom", "whisper", text = id, wordCount = 1, createdAt = at)
  private fun session(active: String?) = SessionEntity("s", "n", "", "2026-11-05", 0, activeTranscriptId = active, createdAt = 1, updatedAt = 1)
  private fun segment(id: String) = SegmentEntity(transcriptId = id, partId = "p", indexInPart = 0, partStartMs = 0, partEndMs = 100, sessionStartMs = 0, sessionEndMs = 100, text = id)

  @Test fun `active-only changes resubscribe timings and cancel previous RAW observer`() = runTest {
    val author = raw("author", 100)
    val newer = raw("local", 200)
    val sessions = MutableStateFlow<SessionEntity?>(session("author"))
    val transcripts = MutableStateFlow(listOf(author, newer))
    val streams = mapOf("author" to MutableStateFlow(listOf(segment("author"))), "local" to MutableStateFlow(listOf(segment("local"))))
    val seen = mutableListOf<SessionTranscriptSelection>()
    val job = launch { sessionTranscriptSelection(sessions, transcripts) { streams.getValue(it) }.collect { seen += it } }
    runCurrent()
    assertEquals("author", seen.last().active?.id)
    assertEquals("author", seen.last().raw?.id)
    assertEquals(listOf("author"), seen.last().segments.map { it.transcriptId })
    sessions.value = session("local")
    runCurrent()
    assertEquals("local", seen.last().active?.id)
    assertEquals(listOf("local"), seen.last().segments.map { it.transcriptId })
    val count = seen.size
    streams.getValue("author").value = listOf(segment("author").copy(text = "late old data"))
    runCurrent()
    assertEquals(count, seen.size)
    assertTrue(seen.all { snapshot -> snapshot.segments.all { it.transcriptId == snapshot.raw?.id } })
    job.cancel()
  }

  @Test fun `refinement follows its own parent and reacts when parent arrives or session changes`() = runTest {
    val author = raw("author", 100)
    val refined = raw("refined", 300).copy(kind = TranscriptKind.REFINED, parentId = author.id)
    val sessions = MutableStateFlow<SessionEntity?>(session(refined.id))
    val transcripts = MutableStateFlow(listOf(raw("local", 200), refined))
    val seen = mutableListOf<SessionTranscriptSelection>()
    val job = launch { sessionTranscriptSelection(sessions, transcripts) { MutableStateFlow(listOf(segment(it))) }.collect { seen += it } }
    runCurrent()
    assertEquals(refined.id, seen.last().active?.id)
    assertNull(seen.last().raw)
    assertTrue(seen.last().segments.isEmpty())
    transcripts.value = transcripts.value + author
    runCurrent()
    assertEquals(author.id, seen.last().raw?.id)
    assertEquals(listOf(author.id), seen.last().segments.map { it.transcriptId })
    sessions.value = session(refined.id).copy(id = "another-session")
    runCurrent()
    assertTrue(seen.last().transcripts.isEmpty())
    assertNull(seen.last().active)
    assertTrue(seen.last().segments.isEmpty())
    job.cancel()
  }
}
