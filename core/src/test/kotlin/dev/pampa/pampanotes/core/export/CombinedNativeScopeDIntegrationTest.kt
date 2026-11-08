package dev.pampa.pampanotes.core.export

import android.net.Uri
import dev.pampa.pampanotes.core.alignment.*
import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.relay.RelayKind
import dev.pampa.pampanotes.core.sync.SyncCloudSourceWire
import dev.pampa.pampanotes.core.transcription.CloudResponseText
import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Prepared integration regressions. Synthetic bytes/DAO doubles; no runtime or acoustic claim. */
class CombinedNativeScopeDIntegrationTest {
  @get:Rule val temp = TemporaryFolder()
  private val folder = FolderEntity("f", "Recordings", createdAt = 1, updatedAt = 2, kind = FolderEntity.KIND_PERSONAL)
  private val note = NoteEntity("n", "f", "Interview", "Associated body\n  unchanged", language = "it", createdAt = 3, updatedAt = 4)
  private val a = part("a", "s")
  private val b = part("b", "sibling")
  private val raw = raw("raw", a, "  Prima parola\n  ", 10)
  private val newer = raw("newer", a, "Alternative public history", 20)
  private val refined = raw.copy(id = "refined", kind = TranscriptKind.REFINED, text = "Refined chosen parent", parentId = raw.id, createdAt = 11, cloudSourceJson = null)
  private val otherRefined = refined.copy(id = "other-refined", parentId = newer.id, text = "Alternative refinement", createdAt = 21)
  private val sibling = raw("sibling-raw", b, "PRIVATE_SIBLING_WORDS", 30)
  private fun bytes(id: String) = "synthetic-audio-$id".toByteArray()
  private fun part(id: String, session: String) = AudioPartEntity(id, session, 0, "$id.wav", "$id.wav", "audio/wav",
    bytes(id).size.toLong(), 1000, AlignmentProtocol.sha256(String(bytes(id))), 1,
    recordedAtLocal = "2026-11-08T14:32", recordedAtSource = "filename", recordedAtPrecision = "minute")
  private fun raw(id: String, p: AudioPartEntity, text: String, version: Long): TranscriptEntity {
    val row = TranscriptEntity(id, p.sessionId, TranscriptKind.RAW, "openrouter", "synthetic-model", "Italian", text, wordCount = 3, createdAt = version)
    val response = listOf(CloudResponseText(0, 0, 1000, text, null, p.sha256, row.provider, row.model),
      CloudResponseText(1, 0, 1000, null, "", p.sha256, row.provider, row.model))
    val doc = CloudSources.create(row, text, listOf(CloudTextPart(p.id, p.sha256, text, row.language, row.provider, row.model, response)))
    val timings = if (text.length > AlignmentProtocol.MAX_TEXT) emptyList() else AlignmentProtocol.tokens(text).mapIndexed { index, span -> AlignmentTiming(span.first, span.last + 1,
      if (index == 0) 10 else null, if (index == 0) 100 else null, if (index == 0) "measured" else "missing") }
    val portable = doc.copy(
      originals = doc.originals.map { it.copy(reportedText = "  Original runner report\n") },
      overlays = if (text.length <= AlignmentProtocol.MAX_TEXT) listOf(CloudOverlay(p.id, AlignmentProtocol.sha256(text), "it", timings)) else emptyList())
    return row.copy(cloudSourceJson = CloudSources.encode(portable))
  }
  private fun session(id: String, selected: String, position: Int) = SessionEntity(id, note.id, "Day $position", "2026-11-08", position,
    selected, createdAt = 1, updatedAt = 2)

  @Before fun uri() { mockkStatic(Uri::class); every { Uri.fromFile(any()) } returns mockk() }
  @After fun resetUri() { unmockkStatic(Uri::class) }

  private inner class Fixture(val versions: List<TranscriptEntity> = listOf(raw, refined, newer, otherRefined, sibling)) {
    val audio = temp.newFolder()
    val originals = temp.newFolder()
    val exports = temp.newFolder()
    val transcriptDao = mockk<TranscriptDao>()
    val segmentDao = mockk<SegmentDao>()
    val fetcher = mockk<ArchiveFetcher>()
    val rows = linkedMapOf<String, SourceEntity>()
    val parts = listOf(a, b)
    val sessions = listOf(session("s", refined.id, 0), session("sibling", sibling.id, 1))
    val sourceDao = mockk<SourceDao>()
    val service: ExportService
    init {
      parts.forEach { File(audio, it.fileName).writeBytes(bytes(it.id)) }
      for (kind in listOf(SourceKind.DOCX, SourceKind.SDOCX, SourceKind.OTHER, SourceKind.PDF)) {
        val id = kind.name
        val content = "original-$id-PRIVATE_EMBEDDED_BYTES".toByteArray()
        File(originals, "$id.bin").writeBytes(content)
        rows[id] = SourceEntity(id, note.id, kind, "$id.bin", "application/octet-stream", content.size.toLong(),
          AlignmentProtocol.sha256(String(content)), "$id.bin", importedAt = 5)
      }
      for (p in parts) {
        File(originals, "source-${p.id}.wav").writeBytes(bytes(p.id))
        rows["source-${p.id}"] = SourceEntity("source-${p.id}", note.id, SourceKind.AUDIO, "${p.id}.wav", "audio/wav",
          p.sizeBytes, p.sha256, "source-${p.id}.wav", importedAt = 5)
      }
      rows["clipboard"] = SourceEntity("clipboard", note.id, SourceKind.CLIPBOARD, "Text", "text/plain", 0, "", importedAt = 5)
      rows["missing-pdf"] = rows.getValue("PDF").copy(id = "missing-pdf", storedFileName = null)
      rows["page"] = rows.getValue("PDF").copy(id = "page", kind = SourceKind.IMAGE, derivedFromId = "SDOCX")
      val notes = mockk<NoteDao>()
      coEvery { notes.get(note.id) } returns note
      coEvery { notes.getAll(listOf(note.id)) } returns listOf(note)
      val folders = mockk<FolderDao> { coEvery { all() } returns listOf(folder) }
      val tags = mockk<NoteTagDao> { coEvery { tags(note.id) } returns listOf("tag") }
      val sessionDao = mockk<SessionDao> {
        coEvery { byNote(note.id) } returns sessions.map { s -> SessionWithParts(s, parts.filter { it.sessionId == s.id }) }
        coEvery { getAll(listOf("s")) } returns listOf(sessions.first())
      }
      coEvery { transcriptDao.bySession(any()) } answers { versions.filter { it.sessionId == firstArg<String>() } }
      coEvery { segmentDao.byTranscript(any()) } answers {
        val t = versions.single { it.id == firstArg<String>() }
        if (t.kind != TranscriptKind.RAW) emptyList() else listOf(SegmentEntity(transcriptId = t.id,
          partId = parts.single { it.sessionId == t.sessionId }.id, indexInPart = 0, partStartMs = 10, partEndMs = 100,
          sessionStartMs = 10, sessionEndMs = 100, text = "Coarse segment", wordsJson = "0,90,Coarse", speaker = "SPEAKER_00"))
      }
      coEvery { sourceDao.byNote(note.id) } answers { rows.values.toList() }
      coEvery { sourceDao.get(any()) } answers { rows[firstArg<String>()] }
      coEvery { fetcher.relayAvailable(any(), any()) } returns false
      val files = mockk<AppFiles> {
        every { audio } returns this@Fixture.audio
        every { sources } returns originals
        every { exports } returns this@Fixture.exports
        every { audioFile(any()) } answers { File(this@Fixture.audio, firstArg<String>()) }
        every { sourceFile(any()) } answers { File(originals, firstArg<String>()) }
      }
      service = ExportService(mockk(), notes, folders, tags, sessionDao, transcriptDao, segmentDao, sourceDao, mockk(), files, fetcher, FilesInUse())
    }
    suspend fun gather(scope: ExportScope, options: ExportOptions) = service.gather(scope, options, "Pampa Notes test", "All")
  }

  @Test fun `production PN keeps all histories while AI keeps the chosen refinement and actual RAW parent`() = runBlocking {
    val f = Fixture()
    for (choice in TranscriptChoice.entries) {
      val options = ExportTarget.PAMPA.defaults().copy(transcript = choice)
      val set = f.gather(ExportScope.Note(note.id), options)
      assertEquals(5, set.notes.single().native!!.transcripts.size)
      assertEquals(f.rows.keys, set.notes.single().sourceFiles.map { it.id }.toSet())
      assertEquals(listOf("missing-pdf"), f.service.plan(set, options).unobtainable.map { it.file.id })
      val result = f.service.export(set, options, ExportDestination.Share)
      val data = PampaBundleArchive.read(result.file!!).data
      val n = data.notes.single()
      assertEquals(f.versions.map { it.id }.toSet(), n.transcripts.map { it.id }.toSet())
      assertEquals(refined.id, n.sessions.first().activeTranscriptId)
      assertEquals(note.body, n.note.body)
      for (before in f.versions.filter { it.kind == TranscriptKind.RAW }) {
        val after = n.transcripts.single { it.id == before.id }
        assertEquals(before.text, after.text)
        assertEquals(CloudSources.decode(before.cloudSourceJson)!!.originals, CloudSources.decode(after.cloudSourceJson)!!.originals)
        assertTrue(CloudSources.validatePortable(after, n.parts.filter { it.sessionId == after.sessionId }))
        assertNull(CloudSources.bindSource(CloudSources.decode(after.cloudSourceJson)!!, n.parts))
      }
      val copied = PampaBundleImporter.remap(data, "copy-$choice").notes.single()
      assertTrue(copied.transcripts.none { row -> f.versions.any { it.id == row.id } })
      assertTrue(copied.transcripts.filter { it.kind == TranscriptKind.RAW }.all { CloudSources.validatePortable(it, copied.parts.filter { p -> p.sessionId == it.sessionId }) })
    }
    val ai = ExportOptions(includeAudio = false, includeSources = false)
    val set = f.gather(ExportScope.Note(note.id), ai)
    val zip = temp.newFile()
    zip.outputStream().use { BundleWriter(f.audio, f.originals).write(set, ai, it) }
    val chosen = PampaBundleArchive.read(zip).data.notes.single().transcripts.map { it.id }.toSet()
    assertEquals(setOf(raw.id, refined.id, sibling.id), chosen)
    assertEquals(raw.id, set.notes.single().sessions.first().raw!!.id)
  }

  @Test fun `exact production sessions exclude sibling histories audio and opaque originals through plan and PN bytes`() = runBlocking {
    val f = Fixture()
    val options = ExportTarget.PAMPA.defaults()
    val set = f.gather(ExportScope.Sessions(listOf("s"), "Selected"), options)
    coVerify(exactly = 0) { f.transcriptDao.bySession("sibling") }
    coVerify(exactly = 0) { f.segmentDao.byTranscript(sibling.id) }
    val wanted = ExportFiles.wanted(set, options)
    assertEquals(setOf("a", "PDF", "missing-pdf", "page", "source-a"), wanted.map { it.id }.toSet())
    assertEquals(2 * a.sizeBytes + 3 * f.rows.getValue("PDF").sizeBytes, ExportEstimator.estimate(set, options).let { it.audioBytes + it.sourceBytes + f.rows.getValue("page").sizeBytes })
    f.service.plan(set, options)
    for (id in listOf("DOCX", "SDOCX", "OTHER", "source-b", "clipboard")) {
      coVerify(exactly = 0) { f.fetcher.relayAvailable(RelayKind.SOURCE, id) }
    }
    val read = PampaBundleArchive.read(f.service.export(set, options, ExportDestination.Share).file!!).data.notes.single()
    assertEquals(listOf("s"), read.sessions.map { it.id })
    assertEquals(listOf("a"), read.parts.map { it.id })
    assertEquals(setOf(raw.id, refined.id, newer.id, otherRefined.id), read.transcripts.map { it.id }.toSet())
    for (id in listOf("DOCX", "SDOCX", "OTHER", "source-b")) assertNull(read.attachments.single { it.id == id }.path)
    assertTrue(read.attachments.single { it.id == "clipboard" }.fileless)
    assertEquals(f.rows.keys, read.sources.map { it.id }.toSet())
    assertEquals(note.body, read.note.body)
    assertEquals(listOf("tag"), read.tags)
  }

  @Test fun `unsafe refinement and multipart originals fall back before both visible and native collections`() = runBlocking {
    val doc = CloudSources.decode(raw.cloudSourceJson)!!
    val text = raw.text + "\n\n" + sibling.text
    val shared = raw.copy(cloudSourceJson = CloudSources.encode(doc.copy(originals = listOf(doc.originals.single().copy(
      text = text, textSha256 = AlignmentProtocol.sha256(text), parts = doc.parts + CloudSources.decode(sibling.cloudSourceJson)!!.parts,
      reportedText = "PRIVATE_SIBLING_REPORT")))))
    val f = Fixture(listOf(shared, refined.copy(text = "PRIVATE_SIBLING_REFINEMENT"), newer, otherRefined, sibling))
    val set = f.gather(ExportScope.Sessions(listOf("s"), "Selected"), ExportTarget.PAMPA.defaults())
    assertEquals(raw.id, set.notes.single().sessions.single().transcript!!.id)
    assertEquals(raw.id, set.notes.single().native!!.sessions.single().activeTranscriptId)
    val file = f.service.export(set, ExportTarget.PAMPA.defaults(), ExportDestination.Share).file!!
    val transferred = PampaBundleArchive.read(file).data.notes.single().transcripts.single { it.id == raw.id }
    assertEquals(raw.text, transferred.text)
    assertEquals("portable_projection", CloudSources.decode(transferred.cloudSourceJson)!!.originals.single().contentScope)
    ZipFile(file).use { zip -> zip.entries().asSequence().filter { it.name.startsWith("records/") || it.name.startsWith("blobs/") }.forEach { entry ->
      assertFalse(zip.getInputStream(entry).use { String(it.readBytes()) }.contains("PRIVATE_SIBLING_"))
    } }
  }

  @Test fun `expanded wire and null empty large response source states survive PN while unknown mutations fail safely`() {
    val p = a.copy(fileName = "")
    val large = raw("large", p, "Caffè,  exact response!\n".repeat(16_000), 45)
    assertTrue(large.cloudSourceJson!!.toByteArray().size > 1024 * 1024)
    val payload = JsonObject(mapOf("text" to JsonPrimitive(large.text), "cloudSourceJson" to JsonPrimitive(large.cloudSourceJson)))
    val wire = SyncCloudSourceWire.toWire(payload).jsonObject.getValue("cloudSourceJson").jsonPrimitive.content
    assertTrue(wire.startsWith(SyncCloudSourceWire.PREFIX))
    val versions = listOf(large.copy(cloudSourceJson = wire), raw.copy(id = "null", cloudSourceJson = null), raw.copy(id = "empty", cloudSourceJson = ""))
    val graph = PampaBundleNote(note, sessions = listOf(session("s", large.id, 0)), parts = listOf(p), transcripts = versions)
    fun set(n: PampaBundleNote) = ExportSet("x", "x", listOf(ExportNote(note, listOf(folder.name), emptyList(), emptyList(), emptyList(), native = n)), "test", 1, folders = listOf(folder))
    val file = temp.newFile()
    file.outputStream().use { PampaNoteWriter(temp.newFolder(), temp.newFolder()).write(set(graph), it) }
    val read = PampaBundleArchive.read(file).data.notes.single()
    val actual = read.transcripts.single { it.id == large.id }
    assertEquals(large.text, actual.text)
    assertEquals(CloudSources.decode(large.cloudSourceJson)!!.originals, CloudSources.decode(actual.cloudSourceJson)!!.originals)
    assertFalse(actual.cloudSourceJson!!.startsWith(SyncCloudSourceWire.PREFIX))
    assertNull(read.transcripts.single { it.id == "null" }.cloudSourceJson)
    assertEquals("", read.transcripts.single { it.id == "empty" }.cloudSourceJson)
    ZipFile(file).use { zip -> assertTrue(zip.entries().asSequence().any { it.name.startsWith("blobs/") && it.size > 1024 * 1024 }) }
    val opaque = graph.copy(transcripts = listOf(large.copy(cloudSourceJson = "{\"future\":true}")))
    val out = ByteArrayOutputStream()
    assertThrows(IllegalArgumentException::class.java) { PampaNoteWriter(temp.newFolder(), temp.newFolder()).write(set(opaque), out) }
    assertEquals(0, out.size())
    val error = assertThrows(IllegalArgumentException::class.java) { PampaBundleImporter.remap(PampaBundleData(folders = listOf(folder), notes = listOf(opaque)), "copy") }
    assertEquals(ImportFailure.CORRUPT, ImportFailure.of(error))
    assertEquals("{\"future\":true}", opaque.transcripts.single().cloudSourceJson)
    assertEquals(large.text, opaque.transcripts.single().text)
  }

  @Test fun `same ID incoming keeps independent local RAW refinement and segments across repeated return trips`() {
    val base = PampaBundleNote(note.copy(updatedAt = 100, title = "Local newer title"),
      sessions = listOf(session("s", refined.id, 0).copy(updatedAt = 100)), parts = listOf(a),
      transcripts = listOf(raw, refined), segments = listOf(SegmentEntity(transcriptId = raw.id, partId = a.id,
        indexInPart = 0, partStartMs = 10, partEndMs = 100, sessionStartMs = 10, sessionEndMs = 100, text = "Coarse local before-image")))
    val replacement = raw(raw.id, a, "Incoming exact RAW", 99)
    val received = base.copy(note = note, sessions = listOf(session("s", refined.id, 0)),
      transcripts = listOf(replacement, refined.copy(text = "Incoming refinement", createdAt = 101)), segments = emptyList())
    val incoming = PampaBundleCloudSources.remap(received, received)
    val merged = PampaBundleCloudSources.merge(base, incoming, false)
    assertEquals("Local newer title", merged.note.title)
    assertEquals(4, merged.transcripts.size)
    val retainedRaw = merged.transcripts.single { it.kind == TranscriptKind.RAW && it.id != raw.id }
    val retainedRefined = merged.transcripts.single { it.kind == TranscriptKind.REFINED && it.id != refined.id }
    assertEquals(raw.text, retainedRaw.text)
    assertEquals(refined.text, retainedRefined.text)
    assertEquals(retainedRaw.id, retainedRefined.parentId)
    assertEquals(retainedRefined.id, merged.sessions.single().activeTranscriptId)
    assertEquals(retainedRaw.id, merged.segments.single().transcriptId)
    assertEquals(CloudSources.decode(raw.cloudSourceJson)!!.originals, CloudSources.decode(retainedRaw.cloudSourceJson)!!.originals)
    assertEquals(CloudSources.decode(raw.cloudSourceJson)!!.overlays, CloudSources.decode(retainedRaw.cloudSourceJson)!!.overlays)
    assertTrue(CloudSources.validatePortable(retainedRaw, merged.parts))
    val again = PampaBundleCloudSources.merge(merged, incoming, false)
    assertEquals(merged.transcripts.toSet(), again.transcripts.toSet())
    assertEquals(merged.segments, again.segments)
    val destination = base.copy(parts = listOf(a.copy(id = "alias")),
      transcripts = listOf(raw.copy(id = "new-raw", createdAt = 500), refined.copy(parentId = "new-raw")),
      segments = base.segments.map { it.copy(transcriptId = "new-raw", partId = "alias") })
    val moved = PampaBundleCloudSources.remap(base, destination, mapOf(a.id to "alias"), mapOf(raw.id to "new-raw"))
    val movedRaw = moved.transcripts.single { it.kind == TranscriptKind.RAW }
    assertEquals(500L, CloudSources.decode(movedRaw.cloudSourceJson)!!.rawVersion)
    assertEquals(raw.createdAt, CloudSources.decode(movedRaw.cloudSourceJson)!!.originals.single().rawVersion)
    assertEquals("alias", CloudSources.decode(movedRaw.cloudSourceJson)!!.overlays.single().partId)
    assertTrue(CloudSources.validatePortable(movedRaw, moved.parts))
  }
}
