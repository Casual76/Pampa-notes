package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.alignment.*
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.repo.RawTimelinePlan
import dev.pampa.pampanotes.core.repo.RawTimelineSnapshot
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

class SelectedRefinementExportRegressionTest {
  @get:Rule val temp = TemporaryFolder()
  private val text = "  Selected,\twords!\r\n"
  private val secret = "EXCLUDED_SIBLING_SECRET"
  private val refinedSecret = "EXCLUDED_REFINEMENT_SECRET"
  private val reportSecret = "EXCLUDED_RUNNER_REPORT"
  private val segmentReads = mutableListOf<String>()
  private val note = NoteEntity("n", "f", "Interview", "Associated body", createdAt = 1, updatedAt = 2)
  private val folder = FolderEntity("f", "Recordings", createdAt = 1, updatedAt = 2, kind = FolderEntity.KIND_PERSONAL)
  private fun part(id: String, position: Int, session: String = "s") = AudioPartEntity(id, session, position,
    "$id.wav", "$id.wav", "audio/wav", 1, 3000, AlignmentProtocol.sha256(id), 1)
  private fun session(id: String, active: String?) = SessionEntity(id, "n", date = "2026-11-08", position = if (id == "s") 0 else 1,
    activeTranscriptId = active, createdAt = 1, updatedAt = 2)

  /** The real split planner keeps full REFINED history and rewrites the existing RAW parent. */
  private fun split(): PampaBundleNote {
    val parts = listOf(part("a", 0), part("private-b", 1))
    val raw = TranscriptEntity("r", "s", TranscriptKind.RAW, "groq", "whisper", "en", text + "\n\n" + secret,
      wordCount = 3, createdAt = 42).let { raw -> raw.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(raw, raw.text,
        parts.map { p -> CloudTextPart(p.id, p.sha256, if (p.id == "a") text else secret, "en", "groq", "whisper") }, reportSecret))) }
    val refined = raw.copy(id = "ref", kind = TranscriptKind.REFINED, text = "Selected rewritten words. $refinedSecret",
      parentId = raw.id, createdAt = 43, cloudSourceJson = null)
    val before = RawTimelineSnapshot(listOf(session("s", "ref")), parts, listOf(raw, refined), emptyList())
    val sessions = before.sessions + session("t", null)
    val moved = listOf(parts[0], parts[1].copy(sessionId = "t", position = 0))
    val result = RawTimelinePlan.build(before, sessions, moved, 100)
    assertEquals("r", result.active["s"])
    // Explicit historical reselection is the trigger missed by the original tests.
    return PampaBundleNote(note, tags = listOf("Associated tag"), sessions = sessions.map { s ->
      s.copy(activeTranscriptId = if (s.id == "s") "ref" else result.active[s.id])
    }, parts = moved, transcripts = result.transcripts, segments = result.segments)
  }

  private fun service(graph: PampaBundleNote): ExportService {
    val notes = mockk<NoteDao>(); val folders = mockk<FolderDao>(); val tags = mockk<NoteTagDao>()
    val sessions = mockk<SessionDao>(); val transcripts = mockk<TranscriptDao>(); val segments = mockk<SegmentDao>(); val sources = mockk<SourceDao>()
    coEvery { notes.get("n") } returns graph.note
    coEvery { notes.getAll(any()) } answers { listOf(graph.note) }
    coEvery { notes.all() } returns listOf(graph.note)
    coEvery { folders.all() } returns listOf(folder)
    coEvery { tags.tags("n") } returns graph.tags
    coEvery { sessions.getAll(any()) } answers { val ids = firstArg<List<String>>(); graph.sessions.filter { it.id in ids } }
    coEvery { sessions.byNote("n") } returns graph.sessions.map { s -> SessionWithParts(s, graph.parts.filter { it.sessionId == s.id }) }
    coEvery { transcripts.bySession(any()) } answers { val id = firstArg<String>(); graph.transcripts.filter { it.sessionId == id } }
    coEvery { segments.byTranscript(any()) } answers { val id = firstArg<String>(); segmentReads += id; graph.segments.filter { it.transcriptId == id } }
    coEvery { sources.byNote("n") } returns emptyList()
    return ExportService(mockk(), notes, folders, tags, sessions, transcripts, segments, sources, mockk(), mockk(), mockk(), mockk())
  }
  private fun zip(set: ExportSet, name: String): java.io.File = temp.newFile(name).also { file ->
    file.outputStream().use { BundleWriter(temp.root, temp.root).write(set, ExportOptions(), it) }
    // Optional evidence copies remain outside the repository and contain synthetic text only.
    System.getProperty("pampa.regression.evidence")?.let { java.io.File(it, name).writeBytes(file.readBytes()) }
  }
  private fun contents(file: java.io.File): String = ZipFile(file).use { zip ->
    zip.entries().asSequence().filterNot { it.isDirectory }.joinToString("\n") { zip.getInputStream(it).use { stream -> stream.readBytes().toString(Charsets.UTF_8) } }
  }

  @Test fun `BEST exact single session consumes scoped RAW before Markdown single and native ZIP writers`() = runTest {
    val graph = split()
    val set = service(graph).gather(ExportScope.Sessions(listOf("s"), "One recording"), ExportOptions(), "Pampa Notes test", "All")
    val item = set.notes.single()
    val chosen = item.sessions.single()
    assertEquals("r", chosen.transcript!!.id)
    assertEquals(text, chosen.transcript.text)
    assertEquals(chosen.raw, chosen.transcript)
    assertEquals(listOf("r"), item.native!!.transcripts.map { it.id })
    assertEquals("r", item.native.sessions.single().activeTranscriptId)
    assertEquals(note.body, item.note.body)
    assertEquals(graph.tags, item.tags)
    assertEquals(listOf("r"), segmentReads)
    val file = zip(set, "restricted-best.zip")
    val restored = PampaBundleArchive.read(file).data.notes.single()
    assertEquals("r", restored.sessions.single().activeTranscriptId)
    assertEquals(listOf("r"), restored.transcripts.map { it.id })
    val body = contents(file) + BundleWriter(temp.root, temp.root).single(set, ExportOptions(format = ExportFormat.SINGLE))
    for (excluded in listOf(secret, refinedSecret, reportSecret, "private-b", AlignmentProtocol.sha256("private-b"))) assertFalse(excluded, body.contains(excluded))
    assertTrue(CloudSources.validatePortable(restored.transcripts.single(), restored.parts))
    assertEquals(graph.transcripts.single { it.id == "ref" }.text, "Selected rewritten words. $refinedSecret")
  }

  @Test fun `direct unsafe native and ZIP inputs refuse before writing any bytes`() {
    val graph = split()
    assertThrows(IllegalArgumentException::class.java) { ExportSessionSelection.native(graph, setOf("s")) }
    val raw = graph.transcripts.single { it.id == "r" }
    val projected = CloudSources.portableProjection(raw, graph.parts.filter { it.sessionId == "s" })
    val scoped = raw.copy(text = projected.text, cloudSourceJson = projected.cloudSourceJson)
    val p = graph.parts.first()
    val session = ExportSession("s", 1, "", "2026-11-08", listOf(ExportPart(p.id, p.originalName, p.fileName, p.durationMs, 0, p.sizeBytes)),
      graph.transcripts.single { it.id == "ref" }, scoped, emptyList())
    for (active in listOf("ref", "r")) {
      val native = graph.copy(sessions = graph.sessions.map { if (it.id == "s") it.copy(activeTranscriptId = active) else it })
      val item = ExportNote(note, listOf("Recordings"), emptyList(), emptyList(), listOf(session), native = native, selectedSessionIds = setOf("s"))
      val out = ByteArrayOutputStream()
      assertThrows(IllegalArgumentException::class.java) { BundleWriter(temp.root, temp.root).write(ExportSet("x", "x", listOf(item), "test", 1), ExportOptions(), out) }
      assertEquals(0, out.size())
    }
  }

  @Test fun `combined selected sessions retain refinement only when its entire source is authorized`() = runTest {
    val graph = split()
    val set = service(graph).gather(ExportScope.Sessions(listOf("s", "t"), "Both recordings"), ExportOptions(), "Pampa Notes test", "All")
    assertEquals("ref", set.notes.single().sessions.first().transcript!!.id)
    val restored = PampaBundleArchive.read(zip(set, "combined-best.zip")).data.notes.single()
    assertEquals("ref", restored.sessions.first().activeTranscriptId)
    assertTrue(restored.transcripts.any { it.text.contains(refinedSecret) })
    assertTrue(restored.transcripts.any { it.cloudSourceJson?.contains(reportSecret) == true })
    val onlyOther = service(graph).gather(ExportScope.Sessions(listOf("t"), "Other recording"), ExportOptions(), "Pampa Notes test", "All")
    assertFalse(contents(zip(onlyOther, "other-best.zip")).contains(refinedSecret))
  }

  @Test fun `genuine scoped refinement retains BEST and native selection`() = runTest {
    val graph = split()
    val raw = graph.transcripts.single { it.id == "r" }
    val p = graph.parts.first()
    val clean = raw.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(raw, raw.text,
      listOf(CloudTextPart(p.id, p.sha256, raw.text, raw.language, raw.provider, raw.model)))))
    val refined = graph.transcripts.single { it.id == "ref" }.copy(text = "Genuine selected refinement.")
    val scoped = graph.copy(transcripts = graph.transcripts.filterNot { it.id in setOf("r", "ref") } + listOf(clean, refined))
    val set = service(scoped).gather(ExportScope.Sessions(listOf("s"), "One recording"), ExportOptions(), "Pampa Notes test", "All")
    assertEquals(refined, set.notes.single().sessions.single().transcript)
    val restored = PampaBundleArchive.read(zip(set, "scoped-refinement.zip")).data.notes.single()
    assertEquals("ref", restored.sessions.single().activeTranscriptId)
    assertTrue(restored.transcripts.contains(refined))
  }

  @Test fun `whole note and folder keep selection and helper retains all public version histories`() = runTest {
    val source = split()
    val old = source.transcripts.first { it.kind == TranscriptKind.RAW }.copy(id = "older-public-history", cloudSourceJson = null, createdAt = 20)
    val graph = source.copy(transcripts = source.transcripts + old)
    val native = ExportSessionSelection.native(graph, graph.sessions.mapTo(HashSet()) { it.id }, retainOriginals = true)
    assertEquals(graph.transcripts.map { it.id }.toSet(), native.transcripts.map { it.id }.toSet())
    assertEquals(graph.transcripts.single { it.id == "ref" }, native.transcripts.single { it.id == "ref" })
    for (scope in listOf(ExportScope.Note("n"), ExportScope.Folder("f"))) {
      val set = service(graph).gather(scope, ExportOptions(), "Pampa Notes test", "All")
      assertNull(set.notes.single().selectedSessionIds)
      assertEquals("ref", set.notes.single().sessions.first().transcript!!.id)
      assertEquals(graph.transcripts.map { it.id }.toSet(), set.notes.single().native!!.transcripts.map { it.id }.toSet())
      val exported = contents(zip(set, "whole-${scope.javaClass.simpleName}.zip"))
      assertTrue(exported.contains(refinedSecret))
      assertTrue(exported.contains(reportSecret))
    }
  }

  @Test fun `unattested and incomplete lineage use actual parent without guessing refined word boundaries`() {
    val graph = split()
    val raw = graph.transcripts.single { it.id == "r" }
    val refined = graph.transcripts.single { it.id == "ref" }
    val newer = raw.copy(id = "newer", text = "Unrelated newer RAW", cloudSourceJson = null, createdAt = 900)
    for (metadata in listOf(null, "", raw.cloudSourceJson)) {
      val selection = ExportSessionSelection.transcripts(listOf(newer, raw.copy(cloudSourceJson = metadata), refined), "ref", graph.parts.take(1))
      assertEquals("r", selection.activeId)
      assertFalse(selection.versions.any { it.id == "ref" })
    }
    assertThrows(IllegalArgumentException::class.java) {
      ExportSessionSelection.transcripts(listOf(newer, refined.copy(parentId = "missing")), "ref", graph.parts.take(1))
    }
  }
}
