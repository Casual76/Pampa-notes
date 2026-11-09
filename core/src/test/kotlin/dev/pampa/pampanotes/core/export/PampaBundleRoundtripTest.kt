package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.transcription.VoiceNames
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.*

class PampaBundleRoundtripTest {
  @get:Rule val temp = TemporaryFolder()
  private val folder = FolderEntity("f", "School", createdAt = 1, updatedAt = 2)
  private val note = NoteEntity("n", "f", "Title", "## Original\nBody", createdAt = 1, updatedAt = 2)
  private val raw = TranscriptEntity("raw", "s", TranscriptKind.RAW, "custom", "whisperx", text = "Hello world", wordCount = 2, createdAt = 1)
  private val refined = raw.copy(id = "refined", kind = TranscriptKind.REFINED, text = "Hello, world!", parentId = "raw")
  private fun native() = PampaBundleNote(note, listOf("tag"),
    listOf(SessionEntity("s", "n", date = "2026-11-05", position = 0, activeTranscriptId = "refined", createdAt = 1, updatedAt = 2, voiceNames = VoiceNames.encode(mapOf("p2|SPEAKER_00" to "Teacher")))),
    listOf("p1", "p2").mapIndexed { i, id -> AudioPartEntity(id, "s", i, "$id.m4a", "$id.m4a", "audio/mp4", 3, 1000, "", 1) },
    listOf(raw, refined),
    listOf("p1", "p2").mapIndexed { i, p -> SegmentEntity(transcriptId = "raw", partId = p, indexInPart = 0, partStartMs = 123, partEndMs = 789, sessionStartMs = i * 1000L + 123, sessionEndMs = i * 1000L + 789, text = "Hello", wordsJson = "0,321,Hello", speaker = "SPEAKER_00") })

  private fun write(include: Boolean = true, value: PampaBundleNote = native(), choice: TranscriptChoice = TranscriptChoice.BEST): File {
    val audio = temp.newFolder(); val sources = temp.newFolder()
    value.parts.forEach { File(audio, it.fileName).writeBytes(byteArrayOf(1, 2, 3)) }
    value.sources.forEach { row -> row.storedFileName?.let { File(sources, it).writeBytes(byteArrayOf(4, 5, 6)) } }
    val originals = value.sources.filter { it.derivedFromId == null }.map { ExportSource(it.originalName, it.kind, it.sha256, it.sizeBytes, it.storedFileName, it.status, it.extractedChars, id = it.id) }
    val pages = value.sources.filter { it.derivedFromId != null }.mapIndexed { index, row -> ExportImage(row.storedFileName!!, index + 1, row.sizeBytes, id = row.id) }
    val export = ExportNote(value.note, listOf("School"), value.tags, originals,
      listOf(ExportSession("s", 1, "", "2026-11-05", value.parts.map { ExportPart(it.id, it.originalName, it.fileName, it.durationMs, it.position * 1000L, it.sizeBytes) }, if (choice == TranscriptChoice.RAW) raw else refined, raw, value.segments)), handwriting = pages, native = value)
    val set = ExportSet("School", "school", listOf(export), "Pampa Notes test", 1000, folders = listOf(folder))
    return temp.newFile().also { file -> file.outputStream().use { BundleWriter(audio, sources).write(set, ExportOptions(includeAudio = include, includeSources = include, transcript = choice), it) } }
  }

  private fun writeCoverage(values: List<PampaBundleNote>): File {
    val exports = values.map { value ->
      val sessions = value.sessions.mapIndexed { index, session ->
        val selected = value.transcripts.firstOrNull { it.id == session.activeTranscriptId }
        val selectedRaw = dev.pampa.pampanotes.core.repo.RawTranscripts.forSelection(value.transcripts.filter { it.sessionId == session.id }, selected?.id)
        ExportSession(session.id, index + 1, session.title, session.date,
          value.parts.filter { it.sessionId == session.id }.map { ExportPart(it.id, it.originalName, it.fileName, it.durationMs, it.position * 1000L, it.sizeBytes) },
          selected, selectedRaw, value.segments.filter { it.transcriptId == selectedRaw?.id })
      }
      ExportNote(value.note, listOf("School"), emptyList(), emptyList(), sessions, native = value)
    }
    val folders = values.map { FolderEntity(it.note.folderId, "School", createdAt = 1, updatedAt = 2) }.distinctBy { it.id }
    return temp.newFile().also { file -> file.outputStream().use {
      BundleWriter(temp.newFolder(), temp.newFolder()).write(ExportSet("School", "school", exports, "Pampa Notes test", 1000, folders = folders),
        ExportOptions(includeAudio = false), it)
    } }
  }

  @Test fun `precision requires actual nonestimated word timings for every recording`() {
    val complete = native()
    val incomplete = listOf(
      complete.copy(segments = complete.segments.map { it.copy(wordsJson = null) }),
      complete.copy(segments = complete.segments.map { it.copy(wordsEstimated = true) }),
      complete.copy(segments = complete.segments.map { it.copy(wordsJson = "") }),
      complete.copy(segments = complete.segments.take(1)),
      complete.copy(segments = complete.segments.mapIndexed { index, segment -> if (index == 0) segment else segment.copy(wordsJson = null) }),
      complete.copy(segments = complete.segments.map { it.copy(text = "Hello world") }),
      complete.copy(segments = complete.segments.map { it.copy(wordsJson = "0,321,Unrelated") }),
    )
    incomplete.forEach { value -> assertFalse(PampaBundleArchive.read(write(value = value)).precise) }
    assertTrue(PampaBundleArchive.read(write(value = complete)).precise)
  }

  @Test fun `one precise note cannot mask another untimed recording while text only notes need no timing`() {
    val complete = native()
    val second = PampaBundleImporter.remap(PampaBundleData(folders = listOf(folder), notes = listOf(complete)), "second").notes.single()
    assertTrue(PampaBundleArchive.read(writeCoverage(listOf(complete, second))).precise)
    assertFalse(PampaBundleArchive.read(writeCoverage(listOf(complete, second.copy(segments = emptyList())))).precise)
    assertFalse(PampaBundleArchive.read(writeCoverage(listOf(complete, second.copy(segments = second.segments.map { it.copy(wordsEstimated = true) })))).precise)
    val textOnly = second.copy(sessions = emptyList(), parts = emptyList(), transcripts = emptyList(), segments = emptyList())
    assertTrue(PampaBundleArchive.read(writeCoverage(listOf(complete, textOnly))).precise)
    val onlyText = PampaBundleArchive.read(writeCoverage(listOf(textOnly)))
    assertFalse(onlyText.precise)
    assertTrue(onlyText.warnings.isEmpty())
  }

  @Test fun `writer reader keeps both texts and exact multipart word and speaker timing`() {
    val file = write(); assertTrue(PampaBundleArchive.isBundle(file))
    val read = PampaBundleArchive.read(file)
    assertTrue(read.precise)
    val actual = read.data.notes.single()
    assertEquals(native().segments, actual.segments)
    assertEquals(listOf(raw, refined), actual.transcripts)
    assertEquals(note, actual.note)
    assertEquals(2, actual.attachments.count { it.path != null })
    assertEquals(0L, actual.parts.first().archivedAt)
  }


  @Test fun `handwriting is content while originals respect attachment choice`() {
    val original = SourceEntity("src", "n", SourceKind.SDOCX, "Original.sdocx", "application/zip", 3, "", "original.sdocx", importedAt = 1)
    val page = original.copy(id = "page", kind = SourceKind.IMAGE, originalName = "Page 1", storedFileName = "page.png", mime = "image/png", derivedFromId = "src")
    val read = PampaBundleArchive.read(write(false, native().copy(sources = listOf(original, page)))).data.notes.single()
    assertNull(read.attachments.first { it.id == "src" }.path)
    assertNotNull(read.attachments.first { it.id == "page" }.path)
    assertEquals("src", read.sources.first { it.id == "page" }.derivedFromId)
  }

  @Test fun `attachment options honestly declare omitted files`() {
    val read = PampaBundleArchive.read(write(false))
    assertTrue(read.data.notes.single().attachments.all { it.path == null })
    assertTrue(read.warnings.isNotEmpty())
  }

  @Test fun `stable identity survives rename while copies remap every reference including voices`() {
    val read = PampaBundleArchive.read(write()).data
    val original = PampaBundleImporter.remap(read, "origin").notes.single()
    val renamed = PampaBundleImporter.remap(read.copy(notes = read.notes.map { it.copy(note = it.note.copy(title = "Renamed")) }), "origin").notes.single()
    assertEquals(original.note.id, renamed.note.id)
    val copy = PampaBundleImporter.remap(read, "copy").notes.single()
    assertNotEquals(original.note.id, copy.note.id)
    assertEquals(copy.parts[1].id + "|SPEAKER_00", VoiceNames.decode(copy.sessions.single().voiceNames).keys.single())
    assertEquals(copy.transcripts.first().id, copy.transcripts.last().parentId)
    assertEquals(copy.parts[1].id, copy.segments[1].partId)
  }

  @Test fun `persistence updates without deleting local children and retains omitted local files`() = runTest {
    val db = mockk<PampaDatabase>()
    val folders = mockk<FolderDao>(relaxed = true); val notes = mockk<NoteDao>(relaxed = true)
    val tags = mockk<NoteTagDao>(relaxed = true); val sessions = mockk<SessionDao>(relaxed = true)
    val parts = mockk<AudioPartDao>(relaxed = true); val sources = mockk<SourceDao>(relaxed = true)
    val transcripts = mockk<TranscriptDao>(relaxed = true); val segments = mockk<SegmentDao>(relaxed = true)
    every { db.folders() } returns folders; every { db.notes() } returns notes; every { db.tags() } returns tags
    every { db.sessions() } returns sessions; every { db.audioParts() } returns parts; every { db.sources() } returns sources
    every { db.transcripts() } returns transcripts; every { db.segments() } returns segments
    val data = PampaBundleArchive.read(write(false)).data
    coEvery { folders.get(any()) } returns null; coEvery { notes.get(any()) } returns null
    coEvery { sessions.get(any()) } returns null; coEvery { transcripts.get(any()) } returns null
    coEvery { parts.get(any()) } returns null
    val audioDirectory = temp.newFolder()
    val sourceDirectory = temp.newFolder()
    val files = mockk<AppFiles>()
    every { files.audio } returns audioDirectory
    every { files.sources } returns sourceDirectory
    every { files.audioFile(any()) } answers { File(audioDirectory, firstArg<String>()) }
    every { files.sourceFile(any()) } answers { File(sourceDirectory, firstArg<String>()) }
    val importer = PampaBundleImporter(db, files)
    val phases = mutableListOf<PampaBundleProgress>()
    assertEquals(1, importer.persist(data, null, emptyMap(), onProgress = { _, progress -> phases += progress }).importedNotes)
    assertEquals(listOf(PampaBundleProgress(PampaBundlePhase.IMPORTING_NOTES, note.title)), phases)
    coEvery { notes.get("n") } returns note
    val localBytes = byteArrayOf(1, 2, 3)
    val localHash = java.security.MessageDigest.getInstance("SHA-256").digest(localBytes).joinToString("") { "%02x".format(it) }
    val local = data.notes.single().parts.first().copy(fileName = "local.m4a", sha256 = localHash)
    val localOnly = local.copy(id = "local-only-part", fileName = "local-only.m4a", position = 2)
    File(audioDirectory, local.fileName).writeBytes(localBytes)
    File(audioDirectory, localOnly.fileName).writeBytes(localBytes)
    coEvery { parts.get("p1") } returns local
    val localSegment = data.notes.single().segments.first().copy(partId = "local-only-part", text = "Local speech", sessionStartMs = 2000, sessionEndMs = 2500)
    coEvery { sessions.get("s") } returns data.notes.single().sessions.single()
    coEvery { sessions.plainByNote("n") } returns data.notes.single().sessions
    val localParts = listOf(local) + data.notes.single().parts.drop(1) + localOnly
    coEvery { parts.bySession("s") } returns localParts
    coEvery { parts.all() } returns localParts
    coEvery { transcripts.bySession("s") } returns data.notes.single().transcripts
    coEvery { segments.byTranscript("raw") } returns listOf(localSegment)
    assertEquals(1, importer.persist(data, null, emptyMap()).updatedNotes)
    assertArrayEquals(localBytes, File(audioDirectory, local.fileName).readBytes())
    assertArrayEquals(localBytes, File(audioDirectory, localOnly.fileName).readBytes())
    coVerify { parts.upsert(local) }
    coVerify { segments.insertAll(match { rows -> rows.any { it.partId == "local-only-part" && it.text == "Local speech" } }) }
    coVerify(exactly = 0) { notes.delete(any()) }
    coVerify(exactly = 0) { sessions.delete(any()) }
    coVerify(exactly = 0) { transcripts.delete(any()) }
  }

  private fun rewrite(file: File, change: (String, ByteArray) -> Pair<String, ByteArray>?): File = temp.newFile().also { target ->
    ZipFile(file).use { input -> ZipOutputStream(target.outputStream()).use { out ->
      input.entries().asSequence().filterNot { it.isDirectory }.forEach { e ->
        change(e.name, input.getInputStream(e).use { it.readBytes() })?.let { (name, bytes) -> out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
      }
    } }
  }

  private fun rejected(file: File) { try { PampaBundleArchive.read(file); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }

  @Test fun `word timings preserve metadata boundaries and observations beyond them but reject negative absolute time`() {
    val value = native()
    val parts = value.parts.associateBy { it.id }
    val exact = value.copy(segments = value.segments.map { segment ->
      val end = parts.getValue(segment.partId).durationMs - segment.partStartMs
      segment.copy(wordsJson = "${-segment.partStartMs},$end,Hello")
    })
    assertEquals(exact.segments, PampaBundleArchive.read(write(value = exact)).data.notes.single().segments)
    for (case in 0..2) {
      val observation = value.copy(segments = value.segments.map { segment ->
        val timings = when (case) {
          0 -> "900000,900001,Hello"
          1 -> "0,${parts.getValue(segment.partId).durationMs - segment.partStartMs + 1},Hello"
          else -> "${-segment.partStartMs - 1},0,Hello"
        }
        segment.copy(wordsJson = timings, wordsEstimated = false)
      })
      if (case < 2) {
        assertEquals(observation.segments, PampaBundleArchive.read(write(value = observation)).data.notes.single().segments)
      } else rejected(write(value = observation))
    }
  }

  @Test fun `reject traversal missing reference incompatible schema and corrupt attachment before writes`() {
    val file = write()
    rejected(rewrite(file) { n, b -> (if (n.endsWith("native.json")) "../native.json" else n) to b })
    rejected(rewrite(file) { n, b -> if (n.contains("/audio/")) null else n to b })
    rejected(rewrite(file) { n, b -> n to if (n.endsWith("manifest.json")) String(b).replace("\"schema\": 3", "\"schema\": 99").toByteArray() else b })
    rejected(rewrite(file) { n, b -> n to if (n.contains("/audio/")) byteArrayOf(9, 9, 9) else b })
  }

  @Test fun `flat root accepted and arbitrary zip not classified`() {
    val file = rewrite(write()) { n, b -> n.substringAfter('/') to b }
    assertTrue(PampaBundleArchive.isBundle(file)); assertEquals(1, PampaBundleArchive.read(file).data.notes.size)
    val other = temp.newFile(); ZipOutputStream(other.outputStream()).use { it.putNextEntry(ZipEntry("manifest.json")); it.write("{}".toByteArray()); it.closeEntry() }
    assertFalse(PampaBundleArchive.isBundle(other))
    val text = temp.newFile().also { it.writeText("ordinary Markdown, not a ZIP") }
    assertFalse(PampaBundleArchive.isBundle(text))
    assertFalse(PampaBundleArchive.isBundle(File(temp.root, "does-not-exist")))
  }

  @Test fun `legacy schemas preserve Markdown without claiming precise timings`() {
    listOf(1, 2).forEach { schema ->
      val legacy = rewrite(write()) { name, bytes ->
        if (name.endsWith("native.json")) null else name to if (name.endsWith("manifest.json"))
          String(bytes).replace("\"schema\": 3", "\"schema\": $schema").toByteArray() else bytes
      }
      assertTrue(PampaBundleArchive.isBundle(legacy))
      val read = PampaBundleArchive.read(legacy)
      assertFalse(read.precise)
      assertTrue(PampaBundleWarning.LEGACY_FORMAT in read.warnings)
      assertTrue(read.data.notes.single().note.body.contains("Original"))
      assertTrue(read.data.notes.single().segments.isEmpty())
    }
  }

  @Test fun `validation responds to cancellation before persistence`() {
    val file = write()
    try {
      PampaBundleArchive.read(file) { throw java.util.concurrent.CancellationException("cancel") }
      fail("Expected cancellation")
    } catch (_: java.util.concurrent.CancellationException) { }
  }

  @Test fun `native selection excludes unrelated histories and private body baseline`() {
    val historicalRaw = raw.copy(id = "history-raw", text = "PRIVATE_OLD_RAW")
    val historicalRefined = refined.copy(id = "history-refined", parentId = historicalRaw.id, text = "PRIVATE_OLD_REFINED")
    val input = native().copy(note = note.copy(bundleBodyBase = "PRIVATE_BASELINE", bundleNativeAdopted = true),
      transcripts = listOf(raw, refined, historicalRaw, historicalRefined))
    val best = write(value = input)
    val bestNote = PampaBundleArchive.read(best).data.notes.single()
    assertEquals(setOf(raw.id, refined.id), bestNote.transcripts.map { it.id }.toSet())
    assertNull(bestNote.note.bundleBodyBase)
    assertNull(bestNote.note.bundleNativeAdopted)
    val rawOnly = write(value = input, choice = TranscriptChoice.RAW)
    assertEquals(listOf(raw), PampaBundleArchive.read(rawOnly).data.notes.single().transcripts)
    ZipFile(rawOnly).use { zip ->
      val text = zip.entries().asSequence().filter { !it.isDirectory && !it.name.contains("/audio/") }.joinToString("\n") { entry -> zip.getInputStream(entry).use { String(it.readBytes()) } }
      assertFalse(text.contains(refined.text))
      assertFalse(text.contains("PRIVATE_OLD_"))
      assertFalse(text.contains("PRIVATE_BASELINE"))
    }
  }

  @Test fun `legacy handwriting remains native content through default reexport`() {
    val original = SourceEntity("src", "n", SourceKind.SDOCX, "Original.sdocx", "application/zip", 3, "", "original.sdocx", importedAt = 1)
    val page = original.copy(id = "page", kind = SourceKind.IMAGE, storedFileName = "page.png", derivedFromId = "src")
    val legacy = rewrite(write(false, native().copy(sources = listOf(original, page)))) { name, bytes ->
      if (name.endsWith("native.json")) null else name to if (name.endsWith("manifest.json")) String(bytes).replace("\"schema\": 3", "\"schema\": 2").toByteArray() else bytes
    }
    val decoded = PampaBundleArchive.read(legacy).data.notes.single()
    val handwriting = decoded.sources.single { it.kind == SourceKind.IMAGE }
    assertNotNull(handwriting.derivedFromId)
    val usable = decoded.copy(parts = decoded.parts.mapIndexed { index, p -> p.copy(fileName = "recovered-$index.m4a") }, sources = decoded.sources.map { row -> row.copy(storedFileName = if (row.id == handwriting.id) "recovered.png" else null) })
    // The helper mirrors ExportService's derivedFromId page classification; no originals requested.
    val next = PampaBundleArchive.read(write(false, usable)).data.notes.single()
    assertNotNull(next.attachments.single { it.id == handwriting.id }.path)
  }
}
