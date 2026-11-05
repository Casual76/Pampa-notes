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
}
