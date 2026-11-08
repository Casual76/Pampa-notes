package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.alignment.*
import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.repo.RawTimelinePlan
import dev.pampa.pampanotes.core.repo.RawTimelineSnapshot
import dev.pampa.pampanotes.core.relay.RelayKind
import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** PREPARED, not executed by the implementation author. Actual collector, plan and PN codec;
 * every attachment is synthetic bytes, not an audio/acoustic fixture. No imported user database. */
class R6PampaHistoryScopeRegressionTest {
  @get:Rule val temp = TemporaryFolder()
  private val options = ExportTarget.PAMPA.defaults()
  private val folder = FolderEntity("f", "Personal", createdAt = 1, updatedAt = 2, kind = FolderEntity.KIND_PERSONAL)
  private val note = NoteEntity("n", "f", "Legacy recording", "Associated material", createdAt = 1, updatedAt = 2)
  private fun session(id: String = "s", active: String? = "ref") = SessionEntity(id, "n", date = "2026-11-08",
    position = if (id == "s") 0 else 1, activeTranscriptId = active, createdAt = 1, updatedAt = 2)
  private fun part(id: String = "p", session: String = "s", position: Int = 0) = AudioPartEntity(id, session, position,
    "$id.wav", "$id.wav", "audio/wav", 0, 1000, AlignmentProtocol.sha256(""), 1)
  private fun legacy(sibling: Boolean = false, active: String = "ref"): PampaBundleNote {
    val raw = TranscriptEntity("raw", "s", TranscriptKind.RAW, "custom", "whisper", text = "Original raw text", wordCount = 3, createdAt = 1)
    val refined = raw.copy(id = "ref", kind = TranscriptKind.REFINED, parentId = raw.id,
      text = "SELECTED LEGACY REFINEMENT", createdAt = 2)
    return PampaBundleNote(note, sessions = listOf(session(active = active)) + if (sibling) listOf(session("private", null)) else emptyList(),
      parts = listOf(part()) + if (sibling) listOf(part("private-p", "private")) else emptyList(), transcripts = listOf(raw, refined))
  }

  private fun typed(sibling: Boolean = true): PampaBundleNote {
    val graph = legacy(sibling)
    val raw = graph.transcripts.first().let { row -> row.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(row,
      row.text, listOf(CloudTextPart("p", graph.parts.first().sha256, row.text, "en", row.provider, row.model))))) }
    return graph.copy(transcripts = listOf(raw, graph.transcripts.last(), raw.copy(id = "older-raw", cloudSourceJson = null, createdAt = 3),
      graph.transcripts.last().copy(id = "unselected-ref", createdAt = 4, text = "UNSELECTED SAFE REFINEMENT")))
  }

  private inner class Fixture(val graph: PampaBundleNote) {
    val notes = mockk<NoteDao>()
    val folders = mockk<FolderDao>()
    val tags = mockk<NoteTagDao>()
    val sessions = mockk<SessionDao>()
    val transcripts = mockk<TranscriptDao>()
    val segments = mockk<SegmentDao>()
    val sources = mockk<SourceDao>()
    val parts = mockk<AudioPartDao>()
    val fetcher = mockk<ArchiveFetcher>()
    val files = mockk<AppFiles>()
    val directory = temp.newFolder()
    val service: ExportService
    init {
      coEvery { notes.get("n") } returns graph.note
      coEvery { notes.getAll(any()) } returns listOf(graph.note)
      coEvery { folders.all() } returns listOf(folder)
      coEvery { tags.tags("n") } returns graph.tags
      coEvery { sessions.getAll(any()) } answers { graph.sessions.filter { it.id in firstArg<List<String>>() } }
      coEvery { sessions.byNote("n") } returns graph.sessions.map { s -> SessionWithParts(s, graph.parts.filter { it.sessionId == s.id }) }
      coEvery { transcripts.bySession(any()) } answers { graph.transcripts.filter { it.sessionId == firstArg<String>() } }
      coEvery { segments.byTranscript(any()) } answers { graph.segments.filter { it.transcriptId == firstArg<String>() } }
      coEvery { sources.byNote("n") } returns graph.sources
      coEvery { sources.get(any()) } answers { graph.sources.find { it.id == firstArg<String>() } }
      coEvery { parts.get(any()) } answers { graph.parts.find { it.id == firstArg<String>() } }
      every { files.audio } returns directory
      every { files.sources } returns directory
      every { files.audioFile(any()) } answers { File(directory, firstArg<String>()) }
      every { files.sourceFile(any()) } answers { File(directory, firstArg<String>()) }
      coEvery { fetcher.relayAvailable(any(), any()) } returns true
      coEvery { fetcher.fetchPart(any(), any()) } coAnswers {
        File(directory, firstArg<AudioPartEntity>().fileName).also { it.writeBytes(byteArrayOf()) }
      }
      coEvery { fetcher.fetchSource(any(), any()) } coAnswers {
        File(directory, firstArg<SourceEntity>().storedFileName!!).also { it.writeText("private-container") }
      }
      service = ExportService(mockk(), notes, folders, tags, sessions, transcripts, segments, sources, parts, files, fetcher, FilesInUse())
      File(directory, "p.wav").writeBytes(byteArrayOf())
    }
    suspend fun gather(scope: ExportScope, choice: ExportOptions = options) = service.gather(scope, choice, "R6 synthetic fixture", "All")
    fun writeRead(set: ExportSet): PampaBundleNote {
      val archive = File(directory, "result.pn")
      archive.outputStream().use { PampaNoteWriter(directory, directory).write(set, it) }
      return PampaBundleArchive.read(archive).data.notes.single()
    }
  }

  @Test fun `legacy one and two session PN stop for selected or unselected refinements without timeline edits`() = runTest {
    for (sibling in listOf(false, true)) for (active in listOf("raw", "ref")) {
      val graph = legacy(sibling, active)
      val fixture = Fixture(graph)
      try { fixture.gather(ExportScope.Sessions(listOf("s"), "One")); fail("PN silently reduced history") }
      catch (failure: PampaHistoryScopeException) { assertEquals(listOf("n"), failure.noteIds) }
      coVerify(exactly = 0) { fixture.segments.byTranscript(any()) }
      coVerify(exactly = 0) { fixture.sources.byNote(any()) }
      coVerify(exactly = 0) { fixture.transcripts.bySession("private") }
      coVerify(exactly = 0) { fixture.fetcher.relayAvailable(any(), any()) }
      assertEquals(active, graph.sessions.first().activeTranscriptId)
      assertEquals(listOf("raw", "ref"), graph.transcripts.map { it.id })
    }
  }

  @Test fun `ordinary AI legacy selected-history projection remains unchanged`() = runTest {
    val fixture = Fixture(legacy(true))
    val set = fixture.gather(ExportScope.Sessions(listOf("s"), "One"), ExportOptions())
    assertEquals("raw", set.notes.single().sessions.single().transcript!!.id)
    assertEquals(listOf("raw"), set.notes.single().native!!.transcripts.map { it.id })
    assertEquals(setOf("s"), set.notes.single().selectedSessionIds)
    coVerify(exactly = 0) { fixture.transcripts.bySession("private") }
  }

  @Test fun `safe typed PN gather plan write read keeps selected and unselected histories without private fetch`() = runTest {
    val opaque = SourceEntity("private-source", "n", SourceKind.SDOCX, "Private original", "application/octet-stream", 17,
      AlignmentProtocol.sha256("private-container"), "private.sdocx", importedAt = 1)
    val graph = typed().copy(sources = listOf(opaque))
    val fixture = Fixture(graph)
    val set = fixture.gather(ExportScope.Sessions(listOf("s"), "One"))
    assertEquals(setOf("s"), set.notes.single().selectedSessionIds)
    assertEquals(listOf("s"), set.notes.single().sessions.map { it.id })
    assertTrue(fixture.service.plan(set, options).isEmpty)
    coVerify(exactly = 0) { fixture.fetcher.relayAvailable(any(), any()) }
    coVerify(exactly = 0) { fixture.fetcher.fetchPart(any(), any()) }
    coVerify(exactly = 0) { fixture.fetcher.fetchSource(any(), any()) }
    val restored = fixture.writeRead(set)
    assertEquals(graph.transcripts.map { it.id }, restored.transcripts.map { it.id })
    assertEquals("ref", restored.sessions.single().activeTranscriptId)
    graph.transcripts.forEach { before -> assertEquals(before.text, restored.transcripts.single { it.id == before.id }.text) }
    assertEquals(listOf("s"), restored.sessions.map { it.id })
    assertEquals(listOf("p"), restored.parts.map { it.id })
    assertNull(restored.attachments.single { it.id == opaque.id }.path)
  }

  @Test fun `only explicit whole note scope permits private attachment fetch and preserves every legacy history`() = runTest {
    val source = SourceEntity("private-source", "n", SourceKind.SDOCX, "Private original", "application/octet-stream", 17,
      AlignmentProtocol.sha256("private-container"), "private.sdocx", importedAt = 1)
    val graph = legacy(true).copy(sources = listOf(source))
    val fixture = Fixture(graph)
    try { fixture.gather(ExportScope.Sessions(listOf("s"), "One")); fail("Expected scope stop") }
    catch (_: PampaHistoryScopeException) { }
    coVerify(exactly = 0) { fixture.fetcher.relayAvailable(any(), any()) }
    // This call represents the separately confirmed scope, not a fallback inside gather/plan.
    val whole = fixture.gather(ExportScope.Note("n"))
    assertNull(whole.notes.single().selectedSessionIds)
    val plan = fixture.service.plan(whole, options)
    assertEquals(setOf("private-p", source.id), plan.toFetch.map { it.id }.toSet())
    assertTrue(fixture.service.fetchMissing(plan).missing.isEmpty())
    coVerify(exactly = 1) { fixture.fetcher.fetchSource(match { it.id == source.id }, any()) }
    coVerify(exactly = 1) { fixture.fetcher.fetchPart(match { it.id == "private-p" }, any()) }
    val restored = fixture.writeRead(whole)
    assertEquals(graph.transcripts, restored.transcripts)
    assertEquals(graph.sessions.map { it.id to it.activeTranscriptId }, restored.sessions.map { it.id to it.activeTranscriptId })
    assertNotNull(restored.attachments.single { it.id == source.id }.path)
  }

  private fun edited(move: Boolean): PampaBundleNote {
    val a = part()
    val b = part("private-p", position = 1)
    val raw = legacy().transcripts.first().copy(text = "Allowed\n\nPRIVATE OLD AUDIO").let { row ->
      row.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(row, row.text, listOf(
        CloudTextPart(a.id, a.sha256, "Allowed", "en", "custom", "whisper"),
        CloudTextPart(b.id, b.sha256, "PRIVATE OLD AUDIO", "en", "custom", "whisper")))))
    }
    val ref = legacy().transcripts.last().copy(text = "PRIVATE OLD REFINEMENT")
    val before = RawTimelineSnapshot(listOf(session()), listOf(a, b), listOf(raw, ref), emptyList())
    val sessions = before.sessions + if (move) listOf(session("private", null)) else emptyList()
    val parts = listOf(a) + if (move) listOf(b.copy(sessionId = "private", position = 0)) else emptyList()
    val plan = RawTimelinePlan.build(before, sessions, parts, 100)
    return PampaBundleNote(note, sessions = sessions.map { it.copy(activeTranscriptId = if (it.id == "s") "ref" else plan.active[it.id]) },
      parts = parts, transcripts = plan.transcripts, segments = plan.segments)
  }

  @Test fun `actual timeline edit and move never make current single-session shape or parent ID sufficient`() = runTest {
    for (move in listOf(false, true)) for (active in listOf("raw", "ref")) {
      val input = edited(move)
      val graph = input.copy(sessions = input.sessions.map { if (it.id == "s") it.copy(activeTranscriptId = active) else it })
      val fixture = Fixture(graph)
      try { fixture.gather(ExportScope.Sessions(listOf("s"), "One")); fail("Old full refinement leaked or disappeared") }
      catch (_: PampaHistoryScopeException) { }
      coVerify(exactly = 0) { fixture.sources.byNote(any()) }
      coVerify(exactly = 0) { fixture.fetcher.relayAvailable(any(), any()) }
    }
  }

  @Test fun `direct writer and service plan cannot bypass complete history guard and opaque fixture cannot authorize restricted scope`() = runTest {
    for (graph in listOf(legacy(), edited(false), edited(true))) {
      val fixture = Fixture(graph)
      val full = fixture.gather(ExportScope.Note("n"))
      val supplied = full.copy(notes = full.notes.map { it.copy(selectedSessionIds = setOf("s")) })
      for (container in listOf(false, true)) {
        val output = ByteArrayOutputStream()
        try {
          val writer = PampaNoteWriter(fixture.directory, fixture.directory)
          if (container) writer.writeContainer(supplied, output) else writer.write(supplied, output)
          fail("Restricted direct writer bypass")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, output.size())
      }
      try { fixture.service.plan(supplied, options); fail("Plan must stop before attachment discovery") }
      catch (_: PampaHistoryScopeException) { }
      try { fixture.service.export(supplied, options, ExportDestination.Share); fail("Export must recheck direct callers") }
      catch (_: PampaHistoryScopeException) { }
      coVerify(exactly = 0) { fixture.fetcher.relayAvailable(any(), any()) }
    }
  }

  @Test fun `RAW-only historical originals cannot be silently reduced by restricted PN`() = runTest {
    val graph = edited(false).let { it.copy(transcripts = it.transcripts.filter { row -> row.kind == TranscriptKind.RAW },
      sessions = it.sessions.map { s -> s.copy(activeTranscriptId = "raw") }) }
    val fixture = Fixture(graph)
    try { fixture.gather(ExportScope.Sessions(listOf("s"), "One")); fail("Public source history was reduced") }
    catch (_: PampaHistoryScopeException) { }
    val restored = fixture.writeRead(fixture.gather(ExportScope.Note("n")))
    val original = CloudSources.decode(graph.transcripts.single().cloudSourceJson)!!.originals
    assertEquals(original, CloudSources.decode(restored.transcripts.single().cloudSourceJson)!!.originals)
  }

  @Test fun `explicitly selecting both moved sessions preserves proven full refinement and all public history`() = runTest {
    val graph = edited(true)
    val fixture = Fixture(graph)
    val set = fixture.gather(ExportScope.Sessions(listOf("s", "private"), "Both selected"))
    assertEquals(setOf("s", "private"), set.notes.single().selectedSessionIds)
    val plan = fixture.service.plan(set, options)
    assertTrue(fixture.service.fetchMissing(plan).missing.isEmpty())
    val restored = fixture.writeRead(set)
    assertEquals(graph.transcripts.map { it.id }.toSet(), restored.transcripts.map { it.id }.toSet())
    assertEquals("ref", restored.sessions.single { it.id == "s" }.activeTranscriptId)
    assertEquals("PRIVATE OLD REFINEMENT", restored.transcripts.single { it.id == "ref" }.text)
    assertEquals(setOf("s", "private"), restored.sessions.map { it.id }.toSet())
    graph.transcripts.filter { it.kind == TranscriptKind.RAW }.forEach { raw ->
      assertEquals(CloudSources.decode(raw.cloudSourceJson)!!.originals,
        CloudSources.decode(restored.transcripts.single { it.id == raw.id }.cloudSourceJson)!!.originals)
    }
  }

  @Test fun `plan refuses timing omissions before any source reference discovery and offers all backing notes`() = runTest {
    val graph = typed().let { source -> source.copy(segments = listOf(SegmentEntity(transcriptId = "raw", partId = "private-p",
      indexInPart = 0, partStartMs = 0, partEndMs = 500, sessionStartMs = 0, sessionEndMs = 500, text = "Historical moved timing"))) }
    val fixture = Fixture(graph)
    val gathered = fixture.gather(ExportScope.Sessions(listOf("s"), "One"))
    val other = gathered.notes.single().let { it.copy(note = it.note.copy(id = "other-backing-note")) }
    val request = gathered.copy(notes = gathered.notes + other)
    try { fixture.service.plan(request, options); fail("Historical timings were silently omitted") }
    catch (failure: PampaHistoryScopeException) { assertEquals(listOf("n", "other-backing-note"), failure.noteIds) }
    coVerify(exactly = 0) { fixture.sources.get(any()) }
    coVerify(exactly = 0) { fixture.fetcher.relayAvailable(any(), any()) }
    val output = ByteArrayOutputStream()
    assertThrows(PampaHistoryScopeException::class.java) { PampaNoteWriter(fixture.directory, fixture.directory).write(gathered, output) }
    assertEquals(0, output.size())
  }
}
