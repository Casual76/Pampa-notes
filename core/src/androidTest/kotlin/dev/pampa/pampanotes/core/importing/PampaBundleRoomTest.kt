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

  private fun archive(graph: PampaBundleNote, includeAudio: Boolean = true, includeSources: Boolean = false, content: (AudioPartEntity) -> ByteArray = { byteArrayOf(1, 2, 3) }): File {
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
    val originals = graph.sources.filter { it.derivedFromId == null }.map { ExportSource(it.originalName, it.kind, it.sha256, it.sizeBytes, it.storedFileName, it.status, it.extractedChars, id = it.id) }
    val pages = graph.sources.filter { it.derivedFromId != null }.mapIndexed { index, source -> ExportImage(source.storedFileName!!, index + 1, source.sizeBytes, id = source.id) }
    val export = ExportNote(graph.note, listOf("Subject"), graph.tags, originals, sessions, handwriting = pages, native = graph)
    val folder = FolderEntity("folder", "Subject", createdAt = 1, updatedAt = 2)
    return File(directory, "${UUID.randomUUID()}.zip").also { zip -> zip.outputStream().use {
      BundleWriter(input, directory).write(ExportSet("Subject", "subject", listOf(export), "Pampa Notes test", 1000, folders = listOf(folder)), ExportOptions(includeAudio = includeAudio, includeSources = includeSources), it)
    } }
  }

  @Test fun omitted_original_update_learns_creation_without_clobbering_retained_file_or_archive_state() = runTest {
    val originalFile = File(directory, "samsung.sdocx").apply { writeBytes(byteArrayOf(4, 5, 6)) }
    val source = SourceEntity("samsung", "note", SourceKind.SDOCX, "Alpha.sdocx", "application/sdoc", 3, "", originalFile.name, importedAt = 1)
    val initial = graph().copy(sources = listOf(source))
    importer.import(archive(initial, includeSources = true))
    val old = db.sources().get(source.id)!!.copy(archivedAt = 100)
    db.sources().upsert(old)
    val omittedUpdate = initial.copy(sources = listOf(old.copy(storedFileName = null, archivedAt = 0, sdocxCreatedAtMillis = 1_700_000_000_000L)))
    importer.import(archive(omittedUpdate, includeSources = false))
    val learned = db.sources().get(source.id)!!
    assertEquals(old.copy(sdocxCreatedAtMillis = 1_700_000_000_000L), learned)
    assertTrue(files.sourceFile(learned.storedFileName!!).readBytes().contentEquals(originalFile.readBytes()))
    val beforeFiles = files.sources.listFiles()!!.map { it.name }.toSet()
    val beforeNote = db.notes().get("note")
    val contradictions = listOf(
      learned.copy(sdocxCreatedAtMillis = 1_700_000_001_000L),
      learned.copy(sha256 = "a".repeat(64)),
      learned.copy(kind = SourceKind.PDF),
    )
    for (contradiction in contradictions) {
      val incoming = omittedUpdate.copy(note = initial.note.copy(title = "Must rollback"), sources = listOf(contradiction))
      try { importer.import(archive(incoming, includeSources = false)); fail("Expected conflicting identity to be rejected") }
      catch (_: IllegalArgumentException) { }
      assertEquals(learned, db.sources().get(source.id))
      assertEquals(beforeNote, db.notes().get("note"))
      assertEquals(beforeFiles, files.sources.listFiles()!!.map { it.name }.toSet())
    }
  }

  private suspend fun storedGraph(): PampaBundleNote {
    val sessions = db.sessions().plainByNote("note")
    val transcripts = sessions.flatMap { db.transcripts().bySession(it.id) }
    return PampaBundleNote(db.notes().get("note")!!, sessions = sessions, parts = sessions.flatMap { db.audioParts().bySession(it.id) }, transcripts = transcripts,
      segments = transcripts.flatMap { db.segments().byTranscript(it.id) })
  }

  @Test fun legacy_to_native_upgrade_adopts_children_without_losing_local_history_or_reimporting_duplicates() = runTest {
    var canonical = graph("canonical" to listOf("A", "B"))
    canonical = canonical.copy(parts = canonical.parts.map { it.copy(fileName = "${it.id}.wav", originalName = "${it.id}.wav", durationMs = 2000) },
      segments = canonical.segments.map { segment -> val position = canonical.parts.single { it.id == segment.partId }.position
        segment.copy(sessionStartMs = position * 2000L + segment.partStartMs, sessionEndMs = position * 2000L + segment.partEndMs) })
    val authorRaw = canonical.transcripts.single()
    val authorRefined = authorRaw.copy(id = "canonical-refined", kind = TranscriptKind.REFINED, parentId = authorRaw.id, text = "Author cleaned text")
    File(directory, "original.txt").writeText("Original source")
    File(directory, "page.png").writeBytes(byteArrayOf(1, 2, 3))
    canonical = canonical.copy(transcripts = canonical.transcripts + authorRefined,
      sessions = canonical.sessions.map { it.copy(activeTranscriptId = authorRefined.id) },
      sources = listOf(SourceEntity("canonical-source", "note", SourceKind.TEXT, "original.txt", "text/plain", 15, "", "original.txt", importedAt = 1),
        SourceEntity("canonical-page", "note", SourceKind.IMAGE, "page.png", "image/png", 3, "", "page.png", importedAt = 1, derivedFromId = "canonical-source")))
    val native = archive(canonical, includeSources = true, content = { part -> wav(2000).also { it[it.lastIndex] = if (part.id == "A") 1 else 2 } })
    val older = legacy(native, 2)
    importer.import(older)
    val oldSession = db.sessions().plainByNote("note").single()
    val oldParts = db.audioParts().bySession(oldSession.id)
    assertTrue(PampaBundleLegacyReconcile.isSynthetic(oldSession.id))
    assertEquals(listOf(2000L, 2000L), oldParts.map { it.durationMs })
    val history = TranscriptEntity("personal-raw", oldSession.id, TranscriptKind.RAW, "custom", "local", text = "My own words", wordCount = 3, createdAt = 10)
    val personalRefined = history.copy(id = "personal-refined", kind = TranscriptKind.REFINED, parentId = history.id, text = "My local refinement")
    db.transcripts().upsert(history); db.transcripts().upsert(personalRefined)
    val personalSegments = oldParts.mapIndexed { index, part -> SegmentEntity(transcriptId = history.id, partId = part.id, indexInPart = 0,
      partStartMs = 0, partEndMs = 500, sessionStartMs = index * 2000L, sessionEndMs = index * 2000L + 500,
      text = "Personal", wordsJson = "0,500,Personal", speaker = "LOCAL") }
    db.segments().insertAll(personalSegments)
    db.notes().updateBody("note", db.notes().get("note")!!.body + "\nMy personal appendix", 100)
    // Native identity and timing must also reconcile when the new package omits the actual audio.
    val known = PampaBundleArchive.read(native).data.notes.single()
    val update = archive(known, includeAudio = false, includeSources = false)
    repeat(2) {
      val result = importer.import(update)
      assertEquals(0, result.importedNotes); assertEquals(1, result.updatedNotes)
      val sessions = db.sessions().plainByNote("note")
      assertEquals(listOf("canonical"), sessions.map { it.id })
      assertEquals(listOf("A", "B"), db.audioParts().bySession("canonical").map { it.id })
      assertEquals(listOf(2000L, 2000L), db.audioParts().bySession("canonical").map { it.durationMs })
      assertEquals(authorRefined.id, sessions.single().activeTranscriptId)
      assertEquals(authorRaw.id, dev.pampa.pampanotes.core.repo.RawTranscripts.forSelection(db.transcripts().bySession("canonical"), sessions.single().activeTranscriptId)?.id)
      assertEquals("canonical", db.transcripts().get(history.id)!!.sessionId)
      assertEquals(history.text, db.transcripts().get(history.id)!!.text)
      assertEquals(personalRefined.text, db.transcripts().get(personalRefined.id)!!.text)
      assertEquals(setOf("A", "B"), db.segments().byTranscript(history.id).map { it.partId }.toSet())
      assertTrue(db.notes().get("note")!!.body.contains("My personal appendix"))
      assertEquals(setOf("canonical-source", "canonical-page"), db.sources().byNote("note").map { it.id }.toSet())
      assertTrue(db.folders().all().none { PampaBundleLegacyReconcile.isFolder(it.id) })
    }
    val before = db.notes().get("note")!!
    val beforeParts = db.audioParts().bySession("canonical")
    val fileNames = files.audio.listFiles()!!.map { it.name }.toSet()
    importer.import(older)
    assertEquals(before, db.notes().get("note"))
    assertEquals(beforeParts, db.audioParts().bySession("canonical"))
    assertEquals(authorRefined.id, db.sessions().get("canonical")!!.activeTranscriptId)
    assertEquals(fileNames, files.audio.listFiles()!!.map { it.name }.toSet())
    assertEquals(1, db.sessions().plainByNote("note").size)
    assertTrue(db.folders().all().none { PampaBundleLegacyReconcile.isFolder(it.id) })
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
  private fun publicLegacy(vararg groups: Pair<String, List<String>>, sourceNames: List<String> = emptyList(),
    includeAudio: Boolean = true, repeatedAudio: Boolean = false): File {
    var value = graph(*groups)
    value = value.copy(sessions = value.sessions.map { it.copy(title = it.id) },
      parts = value.parts.map { it.copy(fileName = "${it.id}.wav", originalName = "${it.id}.wav") },
      sources = sourceNames.map { name ->
        val input = File(directory, "$name.txt").apply { writeText("source-$name") }
        SourceEntity("native-source-$name", "note", SourceKind.TEXT, input.name, "text/plain", input.length(), "", input.name, importedAt = 1)
      })
    return legacy(archive(value, includeAudio = includeAudio, includeSources = true, content = { part ->
      wav(1000).also { if (!repeatedAudio) it[it.lastIndex] = part.id.first().code.toByte() }
    }), 2)
  }

  /** Simulate already-installed marked ordinal rows rather than relying on the new reader IDs. */
  private suspend fun restoreOldOrdinalIdentities() {
    db.sessions().plainByNote("note").forEach { session ->
      db.audioParts().bySession(session.id).forEachIndexed { index, part ->
        val ordinal = PampaBundleLegacyReconcile.id("part", "${session.id}:part:$index")
        if (ordinal != part.id) { db.audioParts().upsert(part.copy(id = ordinal)); db.audioParts().delete(part.id) }
      }
    }
    db.sources().byNote("note").sortedBy { it.originalName }.forEachIndexed { index, source ->
      val ordinal = PampaBundleLegacyReconcile.id("source", "note:source:$index")
      if (ordinal != source.id) { db.sources().upsert(source.copy(id = ordinal)); db.sources().delete(source.id) }
    }
  }

  private suspend fun personalLegacyHistory(): Pair<String, String> {
    val part = db.audioParts().all().single { files.audioFile(it.fileName).readBytes().last() == 'A'.code.toByte() }
    val raw = TranscriptEntity("personal-legacy-raw", part.sessionId, TranscriptKind.RAW, "custom", "local", text = "WordsFromA", wordCount = 1, createdAt = 100)
    val refined = raw.copy(id = "personal-legacy-refined", kind = TranscriptKind.REFINED, parentId = raw.id, text = "Personal refined words")
    db.transcripts().upsert(raw); db.transcripts().upsert(refined)
    db.segments().insertAll(listOf(SegmentEntity(transcriptId = raw.id, partId = part.id, indexInPart = 0,
      partStartMs = 100, partEndMs = 800, sessionStartMs = part.position * 1000L + 100, sessionEndMs = part.position * 1000L + 800,
      text = raw.text, wordsJson = "0,700,WordsFromA", speaker = "PERSONAL")))
    db.sessions().setActiveTranscript(part.sessionId, refined.id, 100)
    return part.id to part.sessionId
  }

  @Test fun legacy_reorder_and_removal_keep_local_words_files_metadata_sources_and_selection() = runTest {
    val first = publicLegacy("Lecture" to listOf("A", "B"), sourceNames = listOf("A", "B"))
    importer.import(first)
    restoreOldOrdinalIdentities()
    val (aId, sessionId) = personalLegacyHistory()
    val a = db.audioParts().get(aId)!!.copy(originalName = "Personal audio name", archivedAt = 101)
    db.audioParts().upsert(a)
    val sourceA = db.sources().byNote("note").single { it.originalName == "A.txt" }.copy(detail = "Personal source metadata", archivedAt = 201)
    db.sources().upsert(sourceA)
    val page = SourceEntity("local-page", "note", SourceKind.IMAGE, "Personal page", "image/png", 3, "local", "local.png", importedAt = 10, derivedFromId = sourceA.id)
    files.sourceFile(page.storedFileName!!).writeBytes(byteArrayOf(1, 2, 3)); db.sources().upsert(page)
    db.notes().updateBody("note", db.notes().get("note")!!.body + "\nPersonal appendix", 100)
    val beforeAudioFiles = files.audio.listFiles()!!.map { it.name }.toSet()
    val beforeSourceFiles = files.sources.listFiles()!!.map { it.name }.toSet()
    val reordered = publicLegacy("Lecture" to listOf("B", "A"), sourceNames = listOf("B", "A"))
    val removedFirst = publicLegacy("Lecture" to listOf("B"), sourceNames = listOf("B"))
    for (update in listOf(reordered, removedFirst, removedFirst)) {
      importer.import(update)
      val parts = db.audioParts().bySession(sessionId)
      assertEquals(2, parts.size)
      assertEquals('B'.code.toByte(), files.audioFile(parts[0].fileName).readBytes().last())
      assertEquals(aId, parts[1].id)
      assertEquals(a.copy(position = 1), db.audioParts().get(aId))
      val segment = db.segments().byTranscript("personal-legacy-raw").single()
      assertEquals(aId, segment.partId); assertEquals(1100L, segment.sessionStartMs)
      assertEquals("WordsFromA", segment.text); assertEquals("0,700,WordsFromA", segment.wordsJson); assertEquals("PERSONAL", segment.speaker)
      assertEquals("personal-legacy-refined", db.sessions().get(sessionId)!!.activeTranscriptId)
      assertEquals("personal-legacy-raw", db.transcripts().get("personal-legacy-refined")!!.parentId)
      assertEquals(sourceA, db.sources().get(sourceA.id))
      assertEquals(page, db.sources().get(page.id))
      assertTrue(db.notes().get("note")!!.body.contains("Personal appendix"))
      assertEquals(beforeAudioFiles, files.audio.listFiles()!!.map { it.name }.toSet())
      assertEquals(beforeSourceFiles, files.sources.listFiles()!!.map { it.name }.toSet())
    }
    val count = db.transcripts().count()
    importer.import(removedFirst)
    assertEquals(count, db.transcripts().count())
    // An unchanged older public bundle may restore its ordering, never its positional identities.
    importer.import(first)
    assertEquals(aId, db.audioParts().bySession(sessionId).first().id)
    assertEquals(100L, db.segments().byTranscript("personal-legacy-raw").single().sessionStartMs)
    assertEquals("personal-legacy-refined", db.sessions().get(sessionId)!!.activeTranscriptId)
    assertEquals(beforeAudioFiles, files.audio.listFiles()!!.map { it.name }.toSet())
    assertEquals(beforeSourceFiles, files.sources.listFiles()!!.map { it.name }.toSet())
    val exportGraph = storedGraph().let { it.copy(note = it.note.copy(folderId = "folder")) }
    val reexport = PampaBundleArchive.read(archive(exportGraph, includeAudio = false)).data.notes.single()
    assertEquals(aId, reexport.segments.single().partId)
    assertEquals(100L, reexport.segments.single().sessionStartMs)
    assertEquals("0,700,WordsFromA", reexport.segments.single().wordsJson)
    assertEquals("personal-legacy-refined", reexport.sessions.single().activeTranscriptId)
  }

  @Test fun legacy_new_audio_and_sources_use_their_original_staging_keys_after_reconciliation() = runTest {
    importer.import(publicLegacy("Lecture" to listOf("A", "B"), sourceNames = listOf("A", "B")))
    restoreOldOrdinalIdentities()
    val (aId, sessionId) = personalLegacyHistory()
    val oldSourceIds = db.sources().byNote("note").map { it.id }.toSet()
    val update = publicLegacy("Lecture" to listOf("C", "B"), sourceNames = listOf("B", "C"))
    importer.import(update)
    val parts = db.audioParts().bySession(sessionId)
    assertEquals(listOf('C', 'B', 'A'), parts.map { files.audioFile(it.fileName).readBytes().last().toInt().toChar() })
    assertEquals(aId, parts.last().id)
    assertEquals(aId, db.segments().byTranscript("personal-legacy-raw").single().partId)
    assertEquals(2100L, db.segments().byTranscript("personal-legacy-raw").single().sessionStartMs)
    val sourceC = db.sources().byNote("note").single { it.originalName == "C.txt" }
    assertTrue(sourceC.id !in oldSourceIds)
    assertEquals("source-C", files.sourceFile(sourceC.storedFileName!!).readText())
    val beforeAudioFiles = files.audio.listFiles()!!.map { it.name }.toSet()
    val beforeSourceFiles = files.sources.listFiles()!!.map { it.name }.toSet()
    importer.import(update)
    assertEquals(3, db.audioParts().bySession(sessionId).size)
    assertEquals(beforeAudioFiles, files.audio.listFiles()!!.map { it.name }.toSet())
    assertEquals(beforeSourceFiles, files.sources.listFiles()!!.map { it.name }.toSet())
    assertEquals("personal-legacy-refined", db.sessions().get(sessionId)!!.activeTranscriptId)
  }

  @Test fun legacy_session_reorder_does_not_transfer_Alpha_transcription_to_Beta() = runTest {
    importer.import(publicLegacy("Alpha" to listOf("A"), "Beta" to listOf("B")))
    restoreOldOrdinalIdentities()
    val (aId, alphaId) = personalLegacyHistory()
    val betaId = db.sessions().plainByNote("note").single { it.id != alphaId }.id
    val filesBefore = files.audio.listFiles()!!.map { it.name }.toSet()
    val update = publicLegacy("Beta" to listOf("B"), "Alpha" to listOf("A"))
    repeat(2) { importer.import(update) }
    assertEquals(listOf(betaId, alphaId), db.sessions().plainByNote("note").map { it.id })
    assertEquals("Alpha", db.sessions().get(alphaId)!!.title)
    assertEquals("Beta", db.sessions().get(betaId)!!.title)
    assertEquals("personal-legacy-refined", db.sessions().get(alphaId)!!.activeTranscriptId)
    assertNotEquals("personal-legacy-refined", db.sessions().get(betaId)!!.activeTranscriptId)
    assertEquals(alphaId, db.transcripts().get("personal-legacy-raw")!!.sessionId)
    assertEquals(aId, db.segments().byTranscript("personal-legacy-raw").single().partId)
    assertEquals(alphaId, db.audioParts().get(aId)!!.sessionId)
    assertEquals(filesBefore, files.audio.listFiles()!!.map { it.name }.toSet())
  }

  @Test fun ambiguous_or_unavailable_legacy_identity_rolls_back_rows_and_all_new_staging() = runTest {
    importer.import(publicLegacy("Lecture" to listOf("A", "B"), repeatedAudio = true))
    restoreOldOrdinalIdentities()
    val before = storedGraph()
    val beforeFiles = files.audio.listFiles()!!.map { it.name }.toSet()
    for (update in listOf(publicLegacy("Lecture" to listOf("C"), repeatedAudio = true),
      publicLegacy("Lecture" to listOf("C"), includeAudio = false))) {
      try { importer.import(update); fail("Expected ambiguous/unavailable legacy identity rejection") }
      catch (_: IllegalArgumentException) { }
      assertEquals(before, storedGraph())
      assertEquals(beforeFiles, files.audio.listFiles()!!.map { it.name }.toSet())
    }
  }

}
