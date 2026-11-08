package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.relay.RelayKind
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Parent runs these with the real codec integrated; no replacement codec is supplied here. */
class PampaExportServiceTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun gatherKeepsEveryNativeVersionSelectionTimingAndUnavailableSource() = runBlocking {
    val folder = FolderEntity("folder", "History", createdAt = 11, updatedAt = 12)
    val note = NoteEntity("note", folder.id, "Title", "My notes", createdAt = 13, updatedAt = 14)
    val session = SessionEntity("session", note.id, date = "2026-11-08", position = 0, activeTranscriptId = "refined",
      createdAt = 15, updatedAt = 16, voiceNames = "{\"part:speaker\":\"Ada\"}")
    val part = AudioPartEntity("part", session.id, 0, "part.m4a", "Audio", "audio/mp4", 64, 1000, "a".repeat(64), 17)
    val raw = TranscriptEntity("raw", session.id, TranscriptKind.RAW, "custom", "whisper", text = "Timed speech", wordCount = 2, createdAt = 18)
    val refined = raw.copy(id = "refined", kind = TranscriptKind.REFINED, parentId = raw.id, text = "Refined speech", createdAt = 19)
    val untimed = raw.copy(id = "untimed", provider = "openrouter", text = "No timing", createdAt = 20)
    val versions = listOf(raw, refined, untimed)
    val segment = SegmentEntity(transcriptId = raw.id, partId = part.id, indexInPart = 0,
      partStartMs = 100, partEndMs = 800, sessionStartMs = 100, sessionEndMs = 800, text = raw.text,
      wordsJson = "100,300,Timed\n300,800,speech", wordsEstimated = true, speaker = "speaker")
    val source = SourceEntity("source", note.id, SourceKind.PDF, "Original", "application/pdf", 128,
      "b".repeat(64), storedFileName = "original.pdf", importedAt = 21)
    val page = source.copy(id = "page", originalName = "Derived page", storedFileName = "page.png", derivedFromId = source.id)
    val allSources = listOf(source, page, source.copy(id = "no-file", storedFileName = null))
    val notes = mockk<NoteDao> { coEvery { get(note.id) } returns note }
    val folders = mockk<FolderDao> { coEvery { all() } returns listOf(folder) }
    val tags = mockk<NoteTagDao> { coEvery { tags(note.id) } returns listOf("tag") }
    val sessions = mockk<SessionDao> { coEvery { byNote(note.id) } returns listOf(SessionWithParts(session, listOf(part))) }
    val transcripts = mockk<TranscriptDao> { coEvery { bySession(session.id) } returns versions }
    val segments = mockk<SegmentDao> {
      coEvery { byTranscript(raw.id) } returns listOf(segment)
      coEvery { byTranscript(refined.id) } returns emptyList()
      coEvery { byTranscript(untimed.id) } returns emptyList()
    }
    val sources = mockk<SourceDao> { coEvery { byNote(note.id) } returns allSources }
    val files = mockk<AppFiles> { every { sourceFile(any()) } answers { File(temporary.root, firstArg<String>()) } }
    val fetcher = mockk<ArchiveFetcher> { coEvery { relayAvailable(RelayKind.SOURCE, page.id) } returns false }
    val service = ExportService(mockk(), notes, folders, tags, sessions, transcripts, segments, sources, mockk(), files, fetcher, FilesInUse())

    for (choice in TranscriptChoice.entries) {
      val options = ExportTarget.PAMPA.defaults().copy(transcript = choice, timestamps = false, includeAudio = false, includeSources = false)
      val set = service.gather(ExportScope.Note(note.id), options, "test", "Everything")
      val native = set.notes.single().native!!
      assertEquals(versions, native.transcripts)
      assertEquals(listOf(segment), native.segments)
      assertEquals(session, native.sessions.single())
      assertEquals(allSources, native.sources)
      assertEquals(listOf("source", "page", "no-file"), set.notes.single().sourceFiles.map { it.id })
      assertTrue(set.notes.single().handwriting.isEmpty())
      assertEquals(listOf("part", "source", "page", "no-file"), ExportFiles.wanted(set, options).map { it.id })
      assertTrue(ExportService.fileName(set, options.format).endsWith(".pn"))
      assertTrue(native.segments.none { it.transcriptId == untimed.id })
    }
  }
}
