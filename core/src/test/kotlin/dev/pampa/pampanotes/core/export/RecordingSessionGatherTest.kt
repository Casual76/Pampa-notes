package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.alignment.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.importing.*
import io.mockk.*
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingSessionGatherTest {
  @get:Rule val temp = TemporaryFolder()
  private val note = NoteEntity("n", "f", "Interview", "NONEMPTY LEGACY BODY", language = "it", createdAt = 1, updatedAt = 2)
  private val folder = FolderEntity("f", "Personal", createdAt = 1, updatedAt = 2, kind = FolderEntity.KIND_PERSONAL)
  private fun cloudRaw(id: String, session: String, text: String, createdAt: Long, partId: String): TranscriptEntity {
    val row = TranscriptEntity(id, session, TranscriptKind.RAW, "mistral", "fixture", language = "it", text = text, wordCount = 3, createdAt = createdAt)
    val p = part(partId, session)
    val source = CloudTextPart(p.id, p.sha256, text, "it", row.provider, row.model)
    return row.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(row, text, listOf(source))))
  }
  private val raw = cloudRaw("raw-old", "chosen", "OLD SELECTED RAW", 1, "a")
  private val fresh = cloudRaw("raw-new", "chosen", "NEWER UNSELECTED HISTORY", 3, "a")
  private val refined = raw.copy(id = "refined", kind = TranscriptKind.REFINED, parentId = raw.id, text = "SELECTED REFINEMENT", createdAt = 2, cloudSourceJson = null)
  private val siblingRaw = cloudRaw("sibling-raw", "sibling", "PRIVATE SIBLING", 1, "b")
  private fun part(id: String, session: String) = AudioPartEntity(id, session, 0, "$id.wav", "$id.wav", "audio/wav", 7, 1000, AlignmentProtocol.sha256("audio-$id"), 9,
    recordedAtEpochMs = 1000, recordedAtLocal = "1970-01-01T00:00:01", recordedAtZoneId = "Z", recordedAtSource = "media_metadata", recordedAtPrecision = "second")
  private fun segment(transcript: String, part: String) = SegmentEntity(transcriptId = transcript, partId = part, indexInPart = 0,
    partStartMs = 10, partEndMs = 50, sessionStartMs = 10, sessionEndMs = 50, text = "text-$transcript")

  private fun service(): Pair<ExportService, TranscriptDao> {
    val notes = mockk<NoteDao>(); val folders = mockk<FolderDao>(); val tags = mockk<NoteTagDao>()
    val sessions = mockk<SessionDao>(); val transcripts = mockk<TranscriptDao>(); val segments = mockk<SegmentDao>(); val sources = mockk<SourceDao>()
    coEvery { folders.all() } returns listOf(folder)
    coEvery { notes.getAll(listOf("n")) } returns listOf(note)
    coEvery { tags.tags("n") } returns listOf("legacy-tag")
    val chosen = SessionEntity("chosen", "n", date = "2025-09-22", position = 0, activeTranscriptId = refined.id, createdAt = 1, updatedAt = 2)
    val sibling = chosen.copy(id = "sibling", position = 1, activeTranscriptId = siblingRaw.id)
    coEvery { sessions.getAll(listOf("chosen")) } returns listOf(chosen)
    coEvery { sessions.byNote("n") } returns listOf(SessionWithParts(chosen, listOf(part("a", "chosen"))), SessionWithParts(sibling, listOf(part("b", "sibling"))))
    coEvery { transcripts.bySession("chosen") } returns listOf(raw, refined, fresh)
    coEvery { transcripts.bySession("sibling") } returns listOf(siblingRaw)
    coEvery { segments.byTranscript(any()) } answers { listOf(segment(firstArg(), "a")) }
    File(temp.root, "private.sdocx").writeText("PRIVATE SIBLING AUDIO FIXTURE")
    coEvery { sources.byNote("n") } returns listOf(
      SourceEntity("source", "n", SourceKind.DOCX, "history.docx", "application/zip", 4, "sourcehash", null, 14, SourceStatus.OK, importedAt = 2),
      SourceEntity("container", "n", SourceKind.SDOCX, "private.sdocx", "application/zip", 29, "fixture", "private.sdocx", importedAt = 2))
    val files = mockk<AppFiles>()
    every { files.sourceFile(any()) } answers { File(temp.root, firstArg<String>()) }
    return ExportService(mockk(), notes, folders, tags, sessions, transcripts, segments, sources, mockk(), files, mockk(), mockk()) to transcripts
  }

  @Test fun `exact session gather never reads sibling histories or plans sibling audio`() = runTest {
    val (service, transcripts) = service()
    val options = ExportOptions(includeAudio = true, includeSources = true)
    val set = service.gather(ExportScope.Sessions(listOf("chosen", "chosen"), "Selected"), options, "fixture", "All")
    val gathered = set.notes.single()
    assertTrue(set.personal)
    assertEquals(setOf("chosen"), gathered.selectedSessionIds)
    assertEquals(listOf("chosen"), gathered.sessions.map { it.id })
    assertEquals("raw-old", gathered.sessions.single().raw!!.id)
    assertEquals("refined", gathered.sessions.single().transcript!!.id)
    assertEquals(listOf("a"), gathered.native!!.parts.map { it.id })
    assertEquals(note, gathered.note)
    assertEquals(listOf("legacy-tag"), gathered.tags)
    assertEquals("history.docx", gathered.sources.first().originalName)
    assertFalse(gathered.sources.last().attachmentAllowed)
    coVerify(exactly = 0) { transcripts.bySession("sibling") }
    assertEquals(listOf("a"), ExportFiles.wanted(set, options).filter { it.kind == ExportFileKind.AUDIO }.map { it.id })
    assertTrue(ExportFiles.wanted(set, options).none { it.id == "container" })
    val missing = ExportFiles.plan(set, options) { false }
    assertEquals(setOf("a"), (missing.toFetch + missing.unobtainable.map { it.file }).filter { it.kind == ExportFileKind.AUDIO }.map { it.id }.toSet())
    val native = PampaBundleExport.build(set, BundleLayout(set, options), options, temp.root, temp.root) {}.notes.single()
    assertEquals(listOf("chosen"), native.sessions.map { it.id })
    assertEquals(setOf("raw-old", "refined"), native.transcripts.map { it.id }.toSet())
    val transferredRaw = native.transcripts.first { it.id == raw.id }
    assertEquals(raw.text, transferredRaw.text)
    assertTrue(CloudSources.validatePortable(transferredRaw, native.parts))
    assertEquals("portable_unbound", CloudSources.decode(transferredRaw.cloudSourceJson)!!.alignmentBinding)
    assertEquals(listOf("a"), native.parts.map { it.id })
    assertEquals(listOf("source", "container"), native.sources.map { it.id })
    assertNull(native.attachments.first { it.id == "container" }.path)
    assertEquals("NONEMPTY LEGACY BODY", native.note.body)
    assertTrue(native.segments.all { it.transcriptId in setOf(raw.id, refined.id) && it.partId == "a" })
    assertFalse(BundleWriter(temp.root, temp.root).single(set, options).contains("PRIVATE SIBLING"))
  }

  @Test fun `RAW choice follows selected refinement parent not newest history`() = runTest {
    val options = ExportOptions(transcript = TranscriptChoice.RAW)
    val set = service().first.gather(ExportScope.Sessions(listOf("chosen"), "Selected"), options, "fixture", "All")
    assertEquals(raw.id, set.notes.single().sessions.single().transcript!!.id)
    assertFalse(BundleWriter(temp.root, temp.root).single(set, options).contains(fresh.text))
  }

  @Test fun `native fence excludes accidental sibling graph while keeping associated sources`() {
    val graph = PampaBundleNote(note, listOf("legacy-tag"),
      sessions = listOf(SessionEntity("chosen", "n", date = "2025-09-22", position = 0, createdAt = 1, updatedAt = 2),
        SessionEntity("sibling", "n", date = "2025-09-22", position = 1, createdAt = 1, updatedAt = 2)),
      parts = listOf(part("a", "chosen"), part("b", "sibling")), transcripts = listOf(raw, refined, siblingRaw),
      segments = listOf(segment(raw.id, "a"), segment(siblingRaw.id, "b")),
      attachments = listOf(PampaBundleAttachment("a", true), PampaBundleAttachment("b", true)))
    val fenced = ExportSessionSelection.native(graph, setOf("chosen"))
    assertEquals(listOf("a"), fenced.parts.map { it.id })
    assertEquals(setOf(raw.id, refined.id), fenced.transcripts.map { it.id }.toSet())
    assertEquals(listOf("a"), fenced.attachments.map { it.id })
    assertEquals(note, fenced.note)
    assertEquals(listOf("legacy-tag"), fenced.tags)
  }
}
