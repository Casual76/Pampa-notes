package dev.pampa.pampanotes.core.importing

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.alignment.*
import dev.pampa.pampanotes.core.export.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.transcription.VoiceNames
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Prepared sources only: neither compiled nor run on a device in correction round 3. */
@RunWith(AndroidJUnit4::class)
class PampaBundleLegacyGrowthRoundThreeRoomTest {
  private lateinit var db: PampaDatabase
  private lateinit var files: AppFiles
  private lateinit var directory: File
  private lateinit var importer: PampaBundleImporter
  private val sourceBytes = "Identical public source".toByteArray(Charsets.UTF_8)
  private val pageBytes = byteArrayOf(7, 8, 9)
  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  @Before fun setup() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    directory = File(context.cacheDir, "pn-growth-round3-${UUID.randomUUID()}").also { it.mkdirs() }
    val isolated = object : ContextWrapper(context) {
      override fun getFilesDir() = File(directory, "files").also { it.mkdirs() }
      override fun getCacheDir() = File(directory, "cache").also { it.mkdirs() }
    }
    db = PampaDatabase.inMemory(context)
    files = AppFiles(isolated)
    importer = PampaBundleImporter(db, files)
  }

  @After fun cleanup() { db.close(); directory.deleteRecursively() }

  private fun wav(): ByteArray {
    val dataBytes = 16000 // One second of 8 kHz mono PCM16; both names contain exactly these bytes.
    return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVEfmt ".toByteArray()); putInt(16)
      putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
      put("data".toByteArray()); putInt(dataBytes)
    }.array()
  }

  /** Actual schema 1/2 ZIPs: no native payload and no mock of the importer's persistence path. */
  private fun archive(noteId: String, schema: Int, order: List<String>, audio: Boolean = true, sources: Boolean = true): File {
    val entries = linkedMapOf("note.md" to "Body".toByteArray())
    val parts = if (audio) order.map { name -> "audio/$name.wav".also { entries[it] = wav() } } else emptyList()
    val sessions = if (audio) listOf(ManifestSession("2026-11-01", "Lecture", parts.size, 0, audio = parts)) else emptyList()
    val originals = if (sources) order.map { name ->
      val path = "sources/$name.txt"
      entries[path] = sourceBytes
      ManifestSource("$name.txt", "TEXT", sha(sourceBytes), sourceBytes.size.toLong(), path)
    } else emptyList()
    val note = ManifestNote(noteId, "Note", "note.md", "Subject", "Subject", emptyList(), "2026-11-01", "2026-11-01",
      sessions = sessions, sources = originals)
    val manifest = ExportManifest(schema, "Pampa Notes round 3", "2026-11-01T00:00:00Z", "Subject", ExportOptions(),
      ExportStats(1, 1, sessions.size, parts.size, 0, 0), listOf(note))
    entries["manifest.json"] = PampaBundleArchive.json.encodeToString(manifest).toByteArray()
    return File(directory, "${UUID.randomUUID()}.zip").also { target ->
      ZipOutputStream(target.outputStream()).use { zip -> entries.forEach { (path, bytes) ->
        zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
      } }
    }
  }

  private data class History(val part: AudioPartEntity, val source: SourceEntity, val page: SourceEntity,
    val raw: TranscriptEntity, val refined: TranscriptEntity, val segment: SegmentEntity, val job: JobEntity, val run: TranscriptionRunEntity)

  private suspend fun personalize(noteId: String, name: String): History {
    val session = db.sessions().plainByNote(noteId).single()
    val part = db.audioParts().bySession(session.id).single { it.originalName == "$name.wav" }.copy(
      originalName = "My $name.wav", createdAt = 7, archivedAt = 71, recordedAtLocal = "2026-11-01",
      recordedAtSource = "filename", recordedAtPrecision = "day")
    val source = db.sources().byNote(noteId).single { it.originalName == "$name.txt" }.copy(
      originalName = "My $name.txt", importedAt = 8, archivedAt = 81, detail = "Personal $name")
    val page = SourceEntity("$noteId-personal-page-$name", noteId, SourceKind.IMAGE, "Personal $name.png", "image/png",
      pageBytes.size.toLong(), sha(pageBytes), "$noteId-personal-page-$name.png", importedAt = 9, derivedFromId = source.id)
    val rawRow = TranscriptEntity("$noteId-personal-raw-$name", session.id, TranscriptKind.RAW, "custom", "test",
      text = "WordsFrom$name", wordCount = 1, createdAt = 50)
    val doc = CloudSources.create(rawRow, rawRow.text, listOf(CloudTextPart(part.id, part.sha256, rawRow.text, null, rawRow.provider, rawRow.model)))
    val raw = rawRow.copy(cloudSourceJson = CloudSources.encode(doc.copy(alignmentBinding = "portable_unbound")))
    val refined = raw.copy(id = "$noteId-personal-refined-$name", kind = TranscriptKind.REFINED, parentId = raw.id,
      text = "Personal refinement $name", wordCount = 3, cloudSourceJson = null)
    val offset = db.audioParts().bySession(session.id).filter { it.position < part.position }.sumOf { it.durationMs }
    val segment = SegmentEntity(transcriptId = raw.id, partId = part.id, indexInPart = 0, partStartMs = 100, partEndMs = 800,
      sessionStartMs = offset + 100, sessionEndMs = offset + 800, text = raw.text, wordsJson = "0,700,WordsFrom$name",
      wordsEstimated = true, speaker = "SPEAKER_00")
    val job = JobEntity("$noteId-job-$name", session.id, JobType.TRANSCRIBE, "custom", state = JobState.DONE,
      createdAt = 10, updatedAt = 20, finishedAt = 20)
    val run = TranscriptionRunEntity("$noteId-run-$name", job.id, session.id, noteId, "custom", "test",
      audioMs = 1000, wallMs = 10, words = 1, segments = 1, finishedAt = 20, deviceName = "Personal device")
    db.audioParts().upsert(part)
    db.sources().upsert(source)
    db.sources().upsert(page)
    files.sourceFile(page.storedFileName!!).writeBytes(pageBytes)
    db.transcripts().upsert(raw)
    db.transcripts().upsert(refined)
    db.segments().insertAll(listOf(segment))
    db.sessions().upsert(session.copy(title = "My lecture", date = "2026-10-31", updatedAt = 100,
      activeTranscriptId = session.activeTranscriptId ?: refined.id,
      voiceNames = VoiceNames.encode(VoiceNames.decode(session.voiceNames) + ("${part.id}|SPEAKER_00" to "Person $name"))))
    db.jobs().upsert(job)
    db.stats().insert(run)
    return History(part, source, page, raw, refined, segment, job, run)
  }

  private suspend fun assertHistory(history: History) {
    val actualPart = db.audioParts().get(history.part.id)!!
    assertEquals(history.part.copy(position = actualPart.position), actualPart)
    assertEquals(history.source, db.sources().get(history.source.id))
    assertEquals(history.page, db.sources().get(history.page.id))
    assertEquals(history.raw, db.transcripts().get(history.raw.id))
    assertEquals(history.refined, db.transcripts().get(history.refined.id))
    val segment = db.segments().byTranscript(history.raw.id).single()
    assertEquals(history.segment.copy(id = 0, sessionStartMs = 0, sessionEndMs = 0), segment.copy(id = 0, sessionStartMs = 0, sessionEndMs = 0))
    val offset = db.audioParts().bySession(actualPart.sessionId).filter { it.position < actualPart.position }.sumOf { it.durationMs }
    assertEquals(offset + segment.partStartMs, segment.sessionStartMs)
    assertEquals(offset + segment.partEndMs, segment.sessionEndMs)
    assertEquals(actualPart.sessionId, db.transcripts().get(segment.transcriptId)!!.sessionId)
    val session = db.sessions().get(actualPart.sessionId)!!
    val name = history.raw.id.substringAfterLast('-')
    assertEquals("Person $name", VoiceNames.decode(session.voiceNames)["${actualPart.id}|SPEAKER_00"])
    assertEquals("My lecture", session.title)
    assertEquals("2026-10-31", session.date)
    assertEquals(history.job, db.jobs().get(history.job.id))
    assertEquals(history.run, db.stats().get(history.run.id))
    assertArrayEquals(wav(), files.audioFile(actualPart.fileName).readBytes())
    assertArrayEquals(sourceBytes, files.sourceFile(history.source.storedFileName!!).readBytes())
    assertArrayEquals(pageBytes, files.sourceFile(history.page.storedFileName!!).readBytes())
  }

  private suspend fun rows(): List<Any> = listOf(db.folders().all().sortedBy { it.id }, db.notes().all().sortedBy { it.id },
    db.tags().all().sortedWith(compareBy({ it.noteId }, { it.tag })), db.sessions().all().sortedBy { it.id },
    db.audioParts().all().sortedBy { it.id }, db.sources().all().sortedBy { it.id }, db.transcripts().all().sortedBy { it.id },
    db.segments().all().sortedBy { it.id }, db.jobs().all().sortedBy { it.id }, db.stats().observeRuns().first().sortedBy { it.id }, db.sync().outbox())

  private fun installedFiles(): Map<String, String> = listOf("audio" to files.audio, "sources" to files.sources).flatMap { (kind, dir) ->
    dir.listFiles().orEmpty().map { "$kind/${it.name}" to sha(it.readBytes()) }
  }.toMap()

  @Test fun actualLegacyZipGrowthPreservesBothNamedHistoriesAndStableIdsAcrossBothOrdersAndSchemas() = runTest {
    for (schema in 1..2) for (order in listOf(listOf("A", "B"), listOf("B", "A"))) {
      val noteId = "growth-$schema-${order.joinToString("")}"
      importer.import(archive(noteId, schema, listOf("A")))
      val a = personalize(noteId, "A")
      val update = archive(noteId, schema, order)
      importer.import(update)
      assertHistory(a)
      val b = personalize(noteId, "B")
      assertNotEquals(a.part.id, b.part.id)
      assertNotEquals(a.source.id, b.source.id)
      assertNotEquals(a.part.fileName, b.part.fileName)
      assertNotEquals(a.source.storedFileName, b.source.storedFileName)
      val audioIds = setOf(a.part.id, b.part.id)
      val sourceIds = setOf(a.source.id, b.source.id, a.page.id, b.page.id)
      val installed = installedFiles()
      for (names in listOf(order, order, order.reversed(), order)) {
        importer.import(archive(noteId, schema, names))
        val session = db.sessions().plainByNote(noteId).single()
        assertEquals(audioIds, db.audioParts().bySession(session.id).map { it.id }.toSet())
        assertEquals(names.map { if (it == "A") a.part.id else b.part.id }, db.audioParts().bySession(session.id).map { it.id })
        assertEquals(sourceIds, db.sources().byNote(noteId).map { it.id }.toSet())
        assertEquals(a.refined.id, session.activeTranscriptId)
        assertHistory(a)
        assertHistory(b)
        assertEquals(installed, installedFiles())
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
      }
    }
  }

  @Test fun actualSourceOnlyZipGrowthAlsoKeepsIndependentIdentitiesInBothOrders() = runTest {
    for (order in listOf(listOf("A", "B"), listOf("B", "A"))) {
      val noteId = "source-growth-${order.joinToString("")}"
      importer.import(archive(noteId, 2, listOf("A"), audio = false))
      val a = db.sources().byNote(noteId).single().copy(detail = "Personal A", archivedAt = 91)
      db.sources().upsert(a)
      val update = archive(noteId, 2, order, audio = false)
      repeat(3) { importer.import(update) }
      val sources = db.sources().byNote(noteId)
      assertEquals(2, sources.size)
      assertEquals(a, sources.single { it.id == a.id })
      assertEquals(setOf("A.txt", "B.txt"), sources.map { it.originalName }.toSet())
      assertEquals(2, sources.map { it.storedFileName }.distinct().size)
    }
  }

  @Test fun ambiguousActualZipRollsBackEveryRowOutboxEntryAndStagedFile() = runTest {
    for (audio in listOf(false, true)) for (manyLocal in listOf(false, true)) {
      val noteId = "ambiguous-$audio-$manyLocal"
      importer.import(archive(noteId, 2, if (manyLocal) listOf("A", "B") else listOf("A"), audio = audio, sources = !audio))
      val update = archive(noteId, 2, if (manyLocal) listOf("Unknown") else listOf("X", "Y"), audio = audio, sources = !audio)
      val beforeRows = rows()
      val beforeFiles = installedFiles()
      try { importer.import(update); fail("Expected global identity ambiguity rejection") }
      catch (_: IllegalArgumentException) { }
      assertEquals(beforeRows, rows())
      assertEquals(beforeFiles, installedFiles())
    }
  }
}
