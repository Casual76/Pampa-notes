package dev.pampa.pampanotes.core.importing

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.export.*
import dev.pampa.pampanotes.core.files.AppFiles
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Compile-only on the resource-constrained workflow; no Android execution claimed. */
@RunWith(AndroidJUnit4::class)
class PampaBundleUpdatePreservationRoomTest {
  private lateinit var db: PampaDatabase
  private lateinit var files: AppFiles
  private lateinit var directory: File
  private lateinit var importer: PampaBundleImporter

  @Before fun setup() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    directory = File(context.cacheDir, "preservation-${UUID.randomUUID()}").also { it.mkdirs() }
    val isolated = object : ContextWrapper(context) {
      override fun getFilesDir() = File(directory, "files").also { it.mkdirs() }
      override fun getCacheDir() = File(directory, "cache").also { it.mkdirs() }
    }
    db = PampaDatabase.inMemory(context)
    files = AppFiles(isolated)
    importer = PampaBundleImporter(db, files)
  }

  @After fun cleanup() { db.close(); directory.deleteRecursively() }

  private fun archive(native: Boolean = true, known: Boolean = true): File {
    val bytes = byteArrayOf(1, 2, 3)
    val audio = File(directory, "input").also { it.mkdirs() }
    File(audio, "audio.bin").writeBytes(bytes)
    val folder = FolderEntity("folder", "Sender folder", createdAt = 1, updatedAt = 2)
    val note = NoteEntity("note", folder.id, "Sender title", "Base", createdAt = 1, updatedAt = 2)
    val raw = TranscriptEntity("raw", "session", TranscriptKind.RAW, "cloud", "model", text = "Exact cloud text",
      wordCount = 3, createdAt = 1, cloudSourceJson = if (known) "{\"text\":\"  Exact cloud text  \"}" else null)
    val part = AudioPartEntity("part", "session", 0, "audio.bin", "audio.m4a", "audio/mp4", bytes.size.toLong(), 1000,
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, 1,
      recordedAtEpochMs = if (known) 1794178800123 else null, recordedAtLocal = if (known) "2026-11-09T00:00:00.123" else null,
      recordedAtZoneId = if (known) "Europe/Rome" else null, recordedAtSource = if (known) "media_metadata" else null,
      recordedAtPrecision = if (known) "millisecond" else null)
    val session = SessionEntity("session", note.id, date = "2026-11-09", position = 0, activeTranscriptId = raw.id, createdAt = 1, updatedAt = 2)
    val graph = PampaBundleNote(note, sessions = listOf(session), parts = listOf(part), transcripts = listOf(raw))
    val exported = ExportNote(note, listOf(folder.name), emptyList(), emptyList(), listOf(ExportSession(session.id, 1, "", session.date,
      listOf(ExportPart(part.id, part.originalName, part.fileName, part.durationMs, 0, part.sizeBytes)), raw, raw, emptyList())), native = graph)
    val set = ExportSet("Shared", "shared", listOf(exported), "Pampa Notes test", 1, folders = listOf(folder))
    return File(directory, "${UUID.randomUUID()}.${if (native) "pn" else "zip"}").also { output -> output.outputStream().use {
      if (native) PampaNoteWriter(audio, audio).write(set, it)
      else BundleWriter(audio, audio).write(set, ExportTarget.AGENT.defaults(), it)
    } }
  }

  @Test fun oldNativeAndSchemaThreePreserveKnownProvenanceAndLocalAudio() = runTest {
    importer.import(archive(), updateExisting = true)
    val knownPart = db.audioParts().get("part")!!
    val knownSource = db.transcripts().get("raw")!!.cloudSourceJson
    for (native in listOf(true, false)) {
      importer.import(archive(native = native, known = false), updateExisting = true)
      assertEquals(knownPart, db.audioParts().get("part"))
      assertEquals(knownSource, db.transcripts().get("raw")!!.cloudSourceJson)
      assertArrayEquals(byteArrayOf(1, 2, 3), files.audioFile(knownPart.fileName).readBytes())
    }
  }

  @Test fun repeatedSnapshotPreservesLocalFolderAndNoteOrganization() = runTest {
    val snapshot = archive()
    importer.import(snapshot, updateExisting = true)
    val parent = FolderEntity("personal-parent", "Personal", createdAt = 1, updatedAt = 1)
    val destination = FolderEntity("personal-destination", "Elsewhere", createdAt = 1, updatedAt = 1)
    db.folders().upsert(parent)
    db.folders().upsert(destination)
    val movedFolder = db.folders().get("folder")!!.copy(name = "My folder", parentId = parent.id, tone = "Blue", updatedAt = 20)
    db.folders().upsert(movedFolder)
    val movedNote = db.notes().get("note")!!.copy(folderId = destination.id, title = "My title", language = "it",
      pinned = true, body = "Base\nPersonal", updatedAt = 20)
    db.notes().upsert(movedNote)
    importer.import(snapshot, updateExisting = true)
    assertEquals(movedFolder, db.folders().get("folder"))
    assertEquals(movedNote, db.notes().get("note"))
  }
}
