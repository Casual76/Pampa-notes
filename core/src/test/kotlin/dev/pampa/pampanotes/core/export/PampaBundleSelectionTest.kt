package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.importing.PampaBundleArchive
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class PampaBundleSelectionTest {
  @get:Rule val temp = TemporaryFolder()
  private val note = NoteEntity("n", "f", "Title", "Body", createdAt = 1, updatedAt = 2)
  private val folder = FolderEntity("f", "Subject", createdAt = 1, updatedAt = 2)
  private val part = AudioPartEntity("p", "s", 0, "p.wav", "p.wav", "audio/wav", 0, 1000, "", 1)
  private val selected = TranscriptEntity("R", "s", TranscriptKind.RAW, "custom", "test", text = "SELECTED_R_TEXT", wordCount = 1, createdAt = 100)
  private val local = selected.copy(id = "L", text = "UNRELATED_L_TEXT", createdAt = 200)
  private val refined = selected.copy(id = "F", kind = TranscriptKind.REFINED, parentId = "R", text = "REFINED_R_TEXT", createdAt = 300)
  private fun segment(id: String, start: Long, text: String) = SegmentEntity(transcriptId = id, partId = "p", indexInPart = 0,
    partStartMs = start, partEndMs = start + 100, sessionStartMs = start, sessionEndMs = start + 100, text = text, wordsJson = "0,100,$text")
  private val selectedTiming = segment("R", 111, "R_WORD")
  private val localTiming = segment("L", 666, "UNRELATED_L_WORD")

  private fun service(activeId: String, versions: List<TranscriptEntity>): ExportService {
    val notes = mockk<NoteDao>(); val folders = mockk<FolderDao>(); val tags = mockk<NoteTagDao>()
    val sessions = mockk<SessionDao>(); val transcripts = mockk<TranscriptDao>(); val segments = mockk<SegmentDao>(); val sources = mockk<SourceDao>()
    coEvery { notes.get("n") } returns note
    coEvery { folders.all() } returns listOf(folder)
    coEvery { tags.tags("n") } returns emptyList()
    coEvery { sessions.byNote("n") } returns listOf(SessionWithParts(SessionEntity("s", "n", date = "2026-11-05", position = 0, activeTranscriptId = activeId, createdAt = 1, updatedAt = 2), listOf(part)))
    coEvery { transcripts.bySession("s") } returns versions
    coEvery { segments.byTranscript("R") } returns listOf(selectedTiming)
    coEvery { segments.byTranscript("L") } returns listOf(localTiming)
    coEvery { segments.byTranscript("F") } returns emptyList()
    coEvery { sources.byNote("n") } returns emptyList()
    return ExportService(mockk(), notes, folders, tags, sessions, transcripts, segments, sources, mockk(), mockk<AppFiles>(), mockk(), mockk())
  }

  @Test fun `gather export follows active R and refined parent R despite newer local L history`() = runTest {
    for (active in listOf("R", "F")) for (choice in TranscriptChoice.entries) {
      val options = ExportOptions(transcript = choice)
      val set = service(active, listOf(local, selected, refined)).gather(ExportScope.Note("n"), options, "Pampa Notes test", "Everything")
      val session = set.notes.single().sessions.single()
      val chosen = if (choice == TranscriptChoice.RAW || active == "R") selected else refined
      assertEquals(chosen, session.transcript)
      assertEquals(selected, session.raw)
      assertEquals(listOf(selectedTiming), session.segments)
      val archive = temp.newFile()
      archive.outputStream().use { BundleWriter(temp.root, temp.root).write(set, options, it) }
      val restored = PampaBundleArchive.read(archive).data.notes.single()
      assertEquals(chosen.id, restored.sessions.single().activeTranscriptId)
      assertEquals(setOf("R", chosen.id), restored.transcripts.map { it.id }.toSet())
      assertEquals(listOf(selectedTiming), restored.segments)
      ZipFile(archive).use { zip ->
        val text = zip.entries().asSequence().filterNot { it.isDirectory }.joinToString("\n") { zip.getInputStream(it).use { stream -> String(stream.readBytes(), Charsets.UTF_8) } }
        assertFalse(text.contains(local.text))
        assertFalse(text.contains(localTiming.text))
        if (choice == TranscriptChoice.RAW) assertFalse(text.contains(refined.text))
      }
    }
  }

  @Test fun `RAW with explicitly missing refinement parent never falls back to unrelated L`() = runTest {
    val options = ExportOptions(transcript = TranscriptChoice.RAW)
    val set = service("F", listOf(local, refined.copy(parentId = "missing"))).gather(ExportScope.Note("n"), options, "Pampa Notes test", "Everything")
    val session = set.notes.single().sessions.single()
    assertNull(session.raw)
    assertNull(session.transcript)
    assertTrue(session.segments.isEmpty())
    val archive = temp.newFile()
    archive.outputStream().use { BundleWriter(temp.root, temp.root).write(set, options, it) }
    assertTrue(PampaBundleArchive.read(archive).data.notes.single().transcripts.isEmpty())
  }

  @Test fun `BEST with broken required parent fails before writing a malformed native archive`() = runTest {
    val options = ExportOptions()
    val set = service("F", listOf(local, refined.copy(parentId = "missing"))).gather(ExportScope.Note("n"), options, "Pampa Notes test", "Everything")
    assertEquals("F", set.notes.single().sessions.single().transcript?.id)
    assertNull(set.notes.single().sessions.single().raw)
    val output = java.io.ByteArrayOutputStream()
    try {
      BundleWriter(temp.root, temp.root).write(set, options, output)
      fail("Expected missing required RAW parent rejection")
    } catch (_: IllegalArgumentException) { }
    assertEquals(0, output.size())
  }
}
