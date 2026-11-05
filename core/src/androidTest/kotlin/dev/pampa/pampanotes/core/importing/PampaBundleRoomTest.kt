package dev.pampa.pampanotes.core.importing

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.export.*
import dev.pampa.pampanotes.core.files.AppFiles
import java.io.File
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import dev.pampa.pampanotes.core.transcription.SessionAssembler
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real Room/FK/transaction coverage; runs only on an Android target. */
@RunWith(AndroidJUnit4::class)
class PampaBundleRoomTest {
  private lateinit var db: PampaDatabase
  private lateinit var directory: File
  private lateinit var files: AppFiles
  private lateinit var importer: PampaBundleImporter

  @Before fun setup() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    directory = File(context.cacheDir, "bundle-test-${UUID.randomUUID()}").also { it.mkdirs() }
    val isolated = object : ContextWrapper(context) {
      override fun getFilesDir() = File(directory, "files").also { it.mkdirs() }
      override fun getCacheDir() = File(directory, "cache").also { it.mkdirs() }
    }
    files = AppFiles(isolated)
    db = PampaDatabase.inMemory(context)
    importer = PampaBundleImporter(db, files)
  }

  @After fun cleanup() { db.close(); directory.deleteRecursively() }

  private fun bundle(audio: Boolean, title: String = "Original"): File {
    val folder = FolderEntity("folder", "Subject", createdAt = 1, updatedAt = 2)
    val note = NoteEntity("note", "folder", title, "User Markdown", createdAt = 1, updatedAt = 2)
    val part = AudioPartEntity("part", "session", 0, "input.m4a", "recording.m4a", "audio/mp4", 3, 1000, "", 1)
    val session = SessionEntity("session", "note", date = "2026-11-05", position = 0, createdAt = 1, updatedAt = 2)
    val native = PampaBundleNote(note, sessions = listOf(session), parts = listOf(part))
    val audioDir = File(directory, "input").also { it.mkdirs() }
    File(audioDir, "input.m4a").writeBytes(byteArrayOf(1, 2, 3))
    val export = ExportNote(note, listOf(folder.name), emptyList(), emptyList(), listOf(ExportSession(session.id, 1, "", session.date,
      listOf(ExportPart(part.id, part.originalName, part.fileName, part.durationMs, 0, 3)), null, null, emptyList())), native = native)
    return File(directory, "${UUID.randomUUID()}.zip").also { zip -> zip.outputStream().use {
      BundleWriter(audioDir, directory).write(ExportSet("Subject", "subject", listOf(export), "Pampa Notes test", 1000, folders = listOf(folder)), ExportOptions(includeAudio = audio), it)
    } }
  }

  @Test fun real_import_update_copy_and_missing_attachment_retention() = runTest {
    val first = importer.import(bundle(true))
    val id = first.noteIds.single()
    val session = db.sessions().plainByNote(id).single()
    val originalPart = db.audioParts().bySession(session.id).single()
    val local = TranscriptEntity("local-only", session.id, TranscriptKind.RAW, "custom", "test", text = "Local work", wordCount = 2, createdAt = 3)
    db.transcripts().upsert(local)
    assertEquals(1, importer.inspect(bundle(false, "Renamed")).updateCount)
    val update = importer.import(bundle(false, "Renamed"))
    assertEquals(listOf(id), update.noteIds)
    assertEquals(1, update.updatedNotes)
    assertEquals("Renamed", db.notes().get(id)?.title)
    assertNotNull(db.transcripts().get(local.id))
    assertEquals(originalPart.fileName, db.audioParts().get(originalPart.id)?.fileName)
    assertTrue(files.audioFile(originalPart.fileName).isFile)
    val copy = importer.import(bundle(true), updateExisting = false)
    assertNotEquals(id, copy.noteIds.single())
    assertEquals(2, db.notes().count())
  }

  @Test fun failure_inside_transaction_rolls_back_database_and_new_files() = runTest {
    importer.import(bundle(true))
    val oldFiles = files.audio.listFiles()!!.map { it.name }.toSet()
    val before = db.notes().all().single()
    try {
      importer.import(bundle(true, "Should rollback"), onProgress = { progress, _ -> if (progress >= 1f) error("Injected failure") })
      fail("Expected failure")
    } catch (_: IllegalStateException) { }
    assertEquals(before, db.notes().all().single())
    assertEquals(oldFiles, files.audio.listFiles()!!.map { it.name }.toSet())
  }

  private fun graph(vararg groups: Pair<String, List<String>>): PampaBundleNote {
    val sessions = groups.mapIndexed { index, (id, _) -> SessionEntity(id, "note", date = "2026-11-05", position = index, activeTranscriptId = "raw-$id", createdAt = 1, updatedAt = 2) }
    val parts = groups.flatMap { (session, ids) -> ids.mapIndexed { index, id -> AudioPartEntity(id, session, index, "$id.m4a", "$id.m4a", "audio/mp4", 3, 1000, "", 1) } }
    val segments = parts.map { p -> SegmentEntity(transcriptId = "raw-${p.sessionId}", partId = p.id, indexInPart = 0, partStartMs = 100, partEndMs = 800,
      sessionStartMs = p.position * 1000L + 100, sessionEndMs = p.position * 1000L + 800, text = p.id, wordsJson = "0,700,${p.id}", speaker = "SPEAKER_00") }
    val transcripts = sessions.map { s -> TranscriptEntity("raw-${s.id}", s.id, TranscriptKind.RAW, "custom", "test", text = parts.filter { it.sessionId == s.id }.joinToString(" ") { it.id }, wordCount = 1, createdAt = 1) }
    return PampaBundleNote(NoteEntity("note", "folder", "Title", "Base\nMiddle\nEnd", createdAt = 1, updatedAt = 2), sessions = sessions, parts = parts, transcripts = transcripts, segments = segments)
  }

  private fun archive(graph: PampaBundleNote, includeAudio: Boolean = true, content: (AudioPartEntity) -> ByteArray = { byteArrayOf(1, 2, 3) }): File {
    val input = File(directory, "incoming").also { it.mkdirs() }
    graph.parts.forEach { File(input, it.fileName).writeBytes(content(it)) }
    val sessions = graph.sessions.mapIndexed { index, s ->
      val chosen = graph.transcripts.firstOrNull { it.id == s.activeTranscriptId }
      val raw = if (chosen?.kind == TranscriptKind.RAW) chosen else graph.transcripts.firstOrNull { it.id == chosen?.parentId }
      var offset = 0L
      ExportSession(s.id, index + 1, s.title, s.date, graph.parts.filter { it.sessionId == s.id }.sortedBy { it.position }.map { p ->
        ExportPart(p.id, p.originalName, p.fileName, p.durationMs, offset, p.sizeBytes).also { offset += p.durationMs }
      }, chosen, raw, graph.segments.filter { it.transcriptId == raw?.id })
    }
    val export = ExportNote(graph.note, listOf("Subject"), graph.tags, emptyList(), sessions, native = graph)
    val folder = FolderEntity("folder", "Subject", createdAt = 1, updatedAt = 2)
    return File(directory, "${UUID.randomUUID()}.zip").also { zip -> zip.outputStream().use {
      BundleWriter(input, directory).write(ExportSet("Subject", "subject", listOf(export), "Pampa Notes test", 1000, folders = listOf(folder)), ExportOptions(includeAudio = includeAudio), it)
    } }
  }

  private suspend fun storedGraph(): PampaBundleNote {
    val sessions = db.sessions().plainByNote("note")
    val transcripts = sessions.flatMap { db.transcripts().bySession(it.id) }
    return PampaBundleNote(db.notes().get("note")!!, sessions = sessions, parts = sessions.flatMap { db.audioParts().bySession(it.id) }, transcripts = transcripts,
      segments = transcripts.flatMap { db.segments().byTranscript(it.id) })
  }

  @Test fun competing_append_split_merge_and_reexport_preserve_local_audio() = runTest {
    val phases = mutableSetOf<PampaBundlePhase>()
    importer.import(archive(graph("s" to listOf("A"))), onProgress = { _, progress -> phases += progress.phase })
    assertEquals(PampaBundlePhase.entries.toSet(), phases)
    val local = graph("s" to listOf("A", "L"))
    val localPart = local.parts.single { it.id == "L" }
    files.audioFile(localPart.fileName).writeBytes(byteArrayOf(7, 8, 9))
    db.audioParts().upsert(localPart)
    db.segments().insertAll(local.segments.filter { it.partId == "L" })
    val other = graph("local" to listOf("Z"))
    db.sessions().upsert(other.sessions.single())
    db.audioParts().upsert(other.parts.single())
    db.transcripts().upsert(other.transcripts.single())
    db.segments().insertAll(other.segments)

    importer.import(archive(graph("s" to listOf("A", "B"), "new" to listOf("N"))))
    assertEquals(listOf("A", "B", "L"), db.audioParts().bySession("s").map { it.id })
    assertEquals(listOf(0, 1, 2), db.audioParts().bySession("s").map { it.position })
    assertEquals(2100L, db.segments().byTranscript("raw-s").single { it.partId == "L" }.sessionStartMs)
    assertEquals(localPart.fileName, db.audioParts().get("L")!!.fileName)
    PampaBundleArchive.read(archive(storedGraph(), false))

    importer.import(archive(graph("s" to listOf("A"), "s2" to listOf("B"), "new" to listOf("N"))))
    assertEquals("s2", db.audioParts().get("B")!!.sessionId)
    assertEquals(listOf("A", "L"), db.audioParts().bySession("s").map { it.id })
    PampaBundleArchive.read(archive(storedGraph(), false))
    importer.import(archive(graph("s" to listOf("A", "B"), "new" to listOf("N"))))
    assertEquals("s", db.audioParts().get("B")!!.sessionId)
    assertEquals(listOf("A", "B", "L"), db.audioParts().bySession("s").map { it.id })
    val stored = storedGraph()
    assertTrue(stored.transcripts.any { it.id == "raw-local" })
    assertTrue(stored.segments.all { seg -> stored.parts.single { it.id == seg.partId }.sessionId == stored.transcripts.single { it.id == seg.transcriptId }.sessionId })
    PampaBundleArchive.read(archive(stored, false))
  }

  @Test fun original_same_id_is_updated_and_body_baseline_survives_repeat_import() = runTest {
    val initial = graph("s" to listOf("A"))
    db.folders().upsert(FolderEntity("folder", "Subject", createdAt = 1, updatedAt = 2))
    db.notes().upsert(initial.note)
    assertEquals(1, importer.inspect(archive(initial)).updateCount)
    val first = importer.import(archive(initial))
    assertEquals(listOf("note"), first.noteIds)
    assertEquals(1, db.notes().count())
    db.notes().updateBody("note", initial.note.body + "\nPDF appendix", 10)
    val incoming = initial.copy(note = initial.note.copy(body = "Base corrected\nMiddle\nEnd"))
    importer.import(archive(incoming))
    val merged = db.notes().get("note")!!
    assertEquals("Base corrected\nMiddle\nEnd\nPDF appendix", merged.body)
    assertEquals(incoming.note.body, merged.bundleBodyBase)
    importer.import(archive(incoming))
    assertEquals(merged, db.notes().get("note"))
    assertNull(PampaBundleArchive.read(archive(storedGraph(), false)).data.notes.single().note.bundleBodyBase)
  }

  @Test fun audio_identity_cannot_hijack_another_note_and_staging_is_rolled_back() = runTest {
    val initial = graph("s" to listOf("A"))
    importer.import(archive(initial))
    db.notes().upsert(initial.note.copy(id = "other"))
    val foreign = graph("foreign" to listOf("B"))
    db.sessions().upsert(foreign.sessions.single().copy(noteId = "other"))
    db.audioParts().upsert(foreign.parts.single())
    val before = files.audio.listFiles()!!.map { it.name }.toSet()
    try {
      importer.import(archive(graph("s" to listOf("A", "B"))))
      fail("Expected cross-note identity conflict")
    } catch (_: IllegalArgumentException) { }
    assertEquals("foreign", db.audioParts().get("B")!!.sessionId)
    assertEquals(listOf("A"), db.audioParts().bySession("s").map { it.id })
    assertEquals(before, files.audio.listFiles()!!.map { it.name }.toSet())
  }

  private fun wav(milliseconds: Int): ByteArray {
    val dataBytes = milliseconds * 16 // 8kHz mono PCM16, small test fixtures only.
    return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVEfmt ".toByteArray()); putInt(16)
      putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
      put("data".toByteArray()); putInt(dataBytes)
    }.array()
  }

  private fun unknownDurationGraph(): PampaBundleNote = graph("s" to listOf("A", "B")).let { n ->
    n.copy(parts = n.parts.map { it.copy(fileName = "${it.id}.wav", originalName = "${it.id}.wav", durationMs = 0) },
      transcripts = emptyList(), segments = emptyList(), sessions = n.sessions.map { it.copy(activeTranscriptId = null) })
  }

  private fun legacy(file: File, schema: Int): File = File(directory, "legacy-$schema-${UUID.randomUUID()}.zip").also { target ->
    ZipFile(file).use { source -> ZipOutputStream(target.outputStream()).use { output ->
      source.entries().asSequence().filterNot { it.isDirectory || it.name.endsWith("/native.json") }.forEach { entry ->
        output.putNextEntry(ZipEntry(entry.name))
        if (entry.name.endsWith("/manifest.json")) {
          val manifest = source.getInputStream(entry).use { Json.decodeFromString(ExportManifest.serializer(), it.readBytes().toString(Charsets.UTF_8)) }
          output.write(Json.encodeToString(manifest.copy(schema = schema, nativeFile = null)).toByteArray())
        } else source.getInputStream(entry).use { it.copyTo(output) }
        output.closeEntry()
      }
    } }
  }

  @Test fun legacy_included_wavs_have_real_player_duration_offsets_and_native_reexport_metadata() = runTest {
    for (schema in listOf(1, 2)) {
      val source = archive(unknownDurationGraph(), content = { wav(if (it.id == "A") 1000 else 2000) })
      val input = legacy(source, schema)
      assertTrue(PampaBundleArchive.read(input).data.notes.single().parts.all { it.durationMs == 0L })
      importer.import(input)
      val session = db.sessions().plainByNote("note").single()
      val parts = db.audioParts().bySession(session.id)
      assertEquals(listOf(1000L, 2000L), parts.map { it.durationMs })
      assertEquals(listOf(0, 1), parts.map { it.position })
      assertTrue(parts.all { it.mime.startsWith("audio/") && files.audioFile(it.fileName).isFile })
      val timeline = parts.map { SessionAssembler.Part(it.id, it.durationMs) }
      assertEquals(3000L, SessionAssembler.totalDurationMs(timeline))
      assertEquals(1000L, SessionAssembler.offsets(timeline).getValue(parts[1].id))
      assertEquals(SessionAssembler.Position(1, parts[1].id, 250), SessionAssembler.locate(timeline, 1250))
      val reexport = PampaBundleArchive.read(archive(storedGraph(), includeAudio = false)).data.notes.single()
      assertEquals(listOf(1000L, 2000L), reexport.parts.map { it.durationMs })
      assertTrue(reexport.attachments.all { it.path == null })
    }
  }

  @Test fun native_included_unknown_duration_is_probed_but_omitted_files_stay_missing() = runTest {
    val input = unknownDurationGraph()
    importer.import(archive(input, content = { wav(if (it.id == "A") 1000 else 2000) }))
    assertEquals(listOf(1000L, 2000L), db.audioParts().bySession("s").map { it.durationMs })
    val copy = importer.import(archive(input, includeAudio = false), updateExisting = false)
    val copiedSession = db.sessions().plainByNote(copy.noteIds.single()).single()
    val missing = db.audioParts().bySession(copiedSession.id)
    assertEquals(2, missing.size)
    assertTrue(missing.all { it.durationMs == 0L && it.archivedAt == 0L && !files.audioFile(it.fileName).exists() })
  }

  @Test fun unknown_duration_probe_failure_rolls_back_staged_files_before_database_writes() = runTest {
    val input = unknownDurationGraph()
    val before = files.audio.listFiles()!!.map { it.name }.toSet()
    try {
      importer.import(archive(input)) // Invalid media bytes with unknown duration.
      fail("Expected probe failure")
    } catch (_: IllegalArgumentException) { }
    assertEquals(0, db.notes().count())
    assertEquals(before, files.audio.listFiles()!!.map { it.name }.toSet())
  }
}
