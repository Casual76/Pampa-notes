package dev.pampa.pampanotes.core.importing

import android.content.ContextWrapper
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.export.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.transcription.VoiceNames
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Regression sources only until the coordinator's resource lease permits compilation. */
@RunWith(AndroidJUnit4::class)
class PampaBundleRoundTwoIntegrityRoomTest {
  private lateinit var db: PampaDatabase
  private lateinit var files: AppFiles
  private lateinit var directory: File
  private lateinit var importer: PampaBundleImporter
  private val audioBytes = byteArrayOf(1, 2, 3)
  private val sourceBytes = byteArrayOf(4, 5, 6)
  private val folder = FolderEntity("folder", "Subject", createdAt = 1, updatedAt = 1)
  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  @Before fun setup() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    directory = File(context.cacheDir, "pn-round-two-${UUID.randomUUID()}").also { it.mkdirs() }
    val isolated = object : ContextWrapper(context) {
      override fun getFilesDir() = File(directory, "files").also { it.mkdirs() }
      override fun getCacheDir() = File(directory, "cache").also { it.mkdirs() }
    }
    db = PampaDatabase.inMemory(context)
    files = AppFiles(isolated)
    importer = PampaBundleImporter(db, files)
  }
  @After fun cleanup() { db.close(); directory.deleteRecursively() }

  private fun graph(marked: Boolean = false): PampaBundleNote {
    fun id(kind: String, value: String) = if (marked) PampaBundleLegacyReconcile.id(kind, "round-two-$value") else value
    val session = SessionEntity(id("session", "session"), "note", "Sender", "2026-11-01", 0, createdAt = 1, updatedAt = 10,
      voiceNames = VoiceNames.encode(mapOf("${id("part", "part")}|SPEAKER_00" to "Sender name")))
    val part = AudioPartEntity(id("part", "part"), session.id, 0, "input-audio.bin", "Audio.m4a", "audio/mp4", 3, 1000, sha(audioBytes), 1,
      recordedAtLocal = "2026-11-01", recordedAtSource = "filename", recordedAtPrecision = "day")
    val source = SourceEntity(id("source", "source"), "note", SourceKind.TEXT, "Original.txt", "text/plain", 3, sha(sourceBytes),
      "input-source.bin", importedAt = 1)
    return PampaBundleNote(NoteEntity("note", if (marked) id("folder", "folder") else folder.id, "Note", "Body", createdAt = 1, updatedAt = 10,
      bundleNativeAdopted = !marked), sessions = listOf(session), parts = listOf(part), sources = listOf(source))
  }

  private fun portable(graph: PampaBundleNote): File {
    val input = File(directory, "input-${UUID.randomUUID()}").also { it.mkdir() }
    graph.parts.forEach { File(input, it.fileName).writeBytes(audioBytes) }
    graph.sources.forEach { File(input, it.storedFileName!!).writeBytes(sourceBytes) }
    val export = ExportNote(graph.note, listOf(folder.name), graph.tags, emptyList(), emptyList(), native = graph)
    return File(directory, "${UUID.randomUUID()}.pn").also { target -> target.outputStream().use {
      PampaNoteWriter(input, input).write(ExportSet("Subject", "subject", listOf(export), "Pampa Notes test", 1,
        folders = listOf(folder.copy(id = graph.note.folderId))), it)
    } }
  }

  /** Seed installed rows without changing the native/legacy provenance under test. */
  private suspend fun seed(graph: PampaBundleNote) {
    db.folders().upsert(folder.copy(id = graph.note.folderId))
    db.notes().upsert(graph.note)
    graph.sessions.forEach { db.sessions().upsert(it) }
    graph.parts.forEach { db.audioParts().upsert(it); files.audioFile(it.fileName).writeBytes(audioBytes) }
    graph.sources.forEach { db.sources().upsert(it); files.sourceFile(it.storedFileName!!).writeBytes(sourceBytes) }
  }

  /** Exercise actual bounded staging and persist, with the measured graph supplied to persist. */
  private fun stage(graph: PampaBundleNote): Pair<PampaBundleStaging, Map<Pair<Boolean, String>, String>> {
    val attachments = graph.parts.map { PampaBundleAttachment(it.id, true, "audio.bin", 3, sha(audioBytes)) } +
      graph.sources.map { PampaBundleAttachment(it.id, false, "source.bin", 3, sha(sourceBytes)) }
    val zip = File(directory, "stage-${UUID.randomUUID()}.zip")
    ZipOutputStream(zip.outputStream()).use { out ->
      for ((name, bytes) in listOf("audio.bin" to audioBytes, "source.bin" to sourceBytes)) {
        out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry()
      }
    }
    val staging = PampaBundleStaging(files.audio, files.sources)
    return staging to staging.stage(zip, "", attachments, {}, {})
  }

  @Test fun realPnImportRepairsSameLengthAndTruncatedAudioAndSourceWithoutDeletingOldBytesOrPrivateState() = runTest {
    val snapshot = portable(graph())
    importer.import(snapshot)
    for (corrupt in listOf(byteArrayOf(9, 9, 9), byteArrayOf(1, 2))) {
      val oldAudio = db.audioParts().get("part")!!.copy(archivedAt = 71, originalName = "My recording", createdAt = 7)
      val oldSource = db.sources().get("source")!!.copy(archivedAt = 81, detail = "My original", importedAt = 8)
      db.audioParts().upsert(oldAudio); db.sources().upsert(oldSource)
      files.audioFile(oldAudio.fileName).writeBytes(corrupt)
      files.sourceFile(oldSource.storedFileName!!).writeBytes(corrupt)
      importer.import(snapshot)
      val repairedAudio = db.audioParts().get("part")!!
      val repairedSource = db.sources().get("source")!!
      assertNotEquals(oldAudio.fileName, repairedAudio.fileName)
      assertNotEquals(oldSource.storedFileName, repairedSource.storedFileName)
      assertEquals(oldAudio.copy(fileName = repairedAudio.fileName), repairedAudio)
      assertEquals(oldSource.copy(storedFileName = repairedSource.storedFileName), repairedSource)
      assertArrayEquals(audioBytes, files.audioFile(repairedAudio.fileName).readBytes())
      assertArrayEquals(sourceBytes, files.sourceFile(repairedSource.storedFileName!!).readBytes())
      assertArrayEquals(corrupt, files.audioFile(oldAudio.fileName).readBytes())
      assertArrayEquals(corrupt, files.sourceFile(oldSource.storedFileName!!).readBytes())
      importer.import(snapshot)
      assertEquals(repairedAudio, db.audioParts().get("part"))
      assertEquals(repairedSource, db.sources().get("source"))
    }
  }

  @Test fun actualImporterKeepsNewerSessionEditsWithAndWithoutSelectionAndAppliesNewerSenderRemoval() = runTest {
    val initial = graph().let { g -> g.copy(sessions = g.sessions + g.sessions.single().copy(id = "empty", position = 1, voiceNames = null)) }
    val snapshot = portable(initial)
    importer.import(snapshot)
    val history = TranscriptEntity("local-history", "session", TranscriptKind.RAW, "local", "test", text = "Personal", wordCount = 1, createdAt = 15)
    db.transcripts().upsert(history)
    val localSessions = db.sessions().plainByNote("note").map { it.copy(title = "Local ${it.id}", date = "2024-01-02", updatedAt = 20,
      voiceNames = null, activeTranscriptId = history.id.takeIf { _ -> it.id == "session" }) }
    localSessions.forEach { db.sessions().upsert(it) }
    repeat(2) { importer.import(snapshot); assertEquals(localSessions.toSet(), db.sessions().plainByNote("note").toSet()) }
    val newer = initial.copy(sessions = initial.sessions.map { it.copy(title = "Fresh sender", date = "2026-11-08", updatedAt = 30, voiceNames = null) })
    importer.import(portable(newer))
    assertTrue(db.sessions().plainByNote("note").all { it.title == "Fresh sender" && it.date == "2026-11-08" && it.updatedAt == 30L && it.voiceNames == null })
    assertEquals(history, db.transcripts().get(history.id))
  }

  private suspend fun repairLegacy(mode: String) {
    val local = graph(marked = mode != "reverse").let { value -> value.copy(
      parts = value.parts.map { it.copy(archivedAt = 71, createdAt = 7) }, sources = value.sources.map { it.copy(archivedAt = 81, importedAt = 8) }) }
    seed(local)
    val incoming = graph(marked = mode != "forward")
    val oldAudio = local.parts.single()
    val oldSource = local.sources.single()
    files.audioFile(oldAudio.fileName).writeBytes(byteArrayOf(9, 9, 9))
    files.sourceFile(oldSource.storedFileName!!).writeBytes(byteArrayOf(1, 2))
    val (staging, stored) = stage(incoming)
    db.withTransaction { importer.persist(PampaBundleData(folders = listOf(folder.copy(id = incoming.note.folderId)), notes = listOf(incoming)),
      null, stored, publicLegacy = mode != "forward") }
    val audio = db.audioParts().all().single()
    val source = db.sources().byNote("note").single()
    assertTrue(audio.fileName in staging.files.map { it.name })
    assertTrue(source.storedFileName in staging.files.map { it.name })
    assertEquals(71L, audio.archivedAt); assertEquals(7L, audio.createdAt)
    assertEquals(81L, source.archivedAt); assertEquals(8L, source.importedAt)
    assertEquals(oldAudio.recordedAtLocal, audio.recordedAtLocal)
    assertArrayEquals(audioBytes, files.audioFile(audio.fileName).readBytes())
    assertArrayEquals(sourceBytes, files.sourceFile(source.storedFileName!!).readBytes())
    assertArrayEquals(byteArrayOf(9, 9, 9), files.audioFile(oldAudio.fileName).readBytes())
    assertArrayEquals(byteArrayOf(1, 2), files.sourceFile(oldSource.storedFileName!!).readBytes())
    if (mode == "reverse") assertEquals(local.note, db.notes().get("note"))
    db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
  }

  @Test fun legacyUpdateUsesVerifiedStageWhenBothLocalFilesAreInvalid() = runTest { repairLegacy("legacy") }
  @Test fun forwardNativeAdoptionRepairsBothFilesAfterRekeying() = runTest { repairLegacy("forward") }
  @Test fun reverseLegacyImportRepairsBothFilesEvenWhenPreservingNativeNote() = runTest { repairLegacy("reverse") }

  @Test fun byteIdenticalFilesOwnedByOtherEntitiesAreReplacedWithIndependentStaging() = runTest {
    val incoming = graph()
    seed(incoming)
    val sharedAudio = incoming.parts.single().copy(id = "other-recording", position = 1)
    val sharedSource = incoming.sources.single().copy(id = "other-original")
    db.audioParts().upsert(sharedAudio); db.sources().upsert(sharedSource)
    importer.import(portable(incoming))
    assertNotEquals(sharedAudio.fileName, db.audioParts().get("part")!!.fileName)
    assertNotEquals(sharedSource.storedFileName, db.sources().get("source")!!.storedFileName)
    assertEquals(sharedAudio, db.audioParts().get(sharedAudio.id))
    assertEquals(sharedSource, db.sources().get(sharedSource.id))
    assertArrayEquals(audioBytes, files.audioFile(sharedAudio.fileName).readBytes())
  }

  @Test fun absentStageRejectsCorruptionAndCancellationDuringHashingRollsBackWithoutChangingLocalFiles() = runTest {
    val incoming = graph()
    seed(incoming)
    val old = db.audioParts().get("part")!!
    files.audioFile(old.fileName).writeBytes(byteArrayOf(9, 9, 9))
    val data = PampaBundleData(folders = listOf(folder), notes = listOf(incoming))
    try { db.withTransaction { importer.persist(data, null, emptyMap(), publicLegacy = false) }; fail("Expected integrity rejection") }
    catch (_: IllegalArgumentException) { }
    assertEquals(old, db.audioParts().get(old.id))
    files.audioFile(old.fileName).writeBytes(audioBytes)
    val (staging, stored) = stage(incoming)
    var checks = 0
    try {
      db.withTransaction { importer.persist(data, null, stored, checkpoint = { if (++checks == 5) throw CancellationException("hash cancelled") }, publicLegacy = false) }
      fail("Expected hash cancellation")
    } catch (_: CancellationException) { } finally { staging.cleanup() }
    assertEquals(5, checks)
    assertEquals(old, db.audioParts().get(old.id))
    assertArrayEquals(audioBytes, files.audioFile(old.fileName).readBytes())
    assertArrayEquals(sourceBytes, files.sourceFile(incoming.sources.single().storedFileName!!).readBytes())
    assertTrue(staging.files.none { it.exists() })
  }
}
