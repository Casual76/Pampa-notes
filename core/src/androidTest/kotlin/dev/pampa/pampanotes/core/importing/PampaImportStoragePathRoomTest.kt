package dev.pampa.pampanotes.core.importing

import android.content.ContextWrapper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.export.*
import dev.pampa.pampanotes.core.files.AppFiles
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Prepared Android regressions; runtime is separate from host path-alias checks. */
@RunWith(AndroidJUnit4::class)
class PampaImportStoragePathRoomTest {
  private val content = byteArrayOf(1, 2, 3)
  private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

  @Test fun importAndReimportThroughRealAndroidParentSymlinkPreserveHistoryAndBytes() = runTest {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "pn-parent-alias-${UUID.randomUUID()}").also { it.mkdirs() }
    val physical = File(root, "real").also { it.mkdirs() }
    val alias = File(root, "user0")
    Os.symlink(physical.absolutePath, alias.absolutePath)
    val db = PampaDatabase.inMemory(context)
    try {
      val isolated = object : ContextWrapper(context) {
        override fun getFilesDir() = File(alias, "files").also { it.mkdirs() }
        override fun getCacheDir() = File(root, "cache").also { it.mkdirs() }
      }
      val files = AppFiles(isolated)
      assertNotEquals(files.audio.absoluteFile, files.audio.canonicalFile)
      val input = File(root, "input").also { it.mkdir() }
      File(input, "audio.bin").writeBytes(content)
      File(input, "source.bin").writeBytes(content)
      val folder = FolderEntity("folder", "Subject", createdAt = 1, updatedAt = 1)
      val note = NoteEntity("note", folder.id, "Note", "Body", createdAt = 1, updatedAt = 1)
      val raw = TranscriptEntity("raw", "session", TranscriptKind.RAW, "custom", "WhisperX", text = "First.\n\nSecond!\n", wordCount = 2, createdAt = 1)
      val session = SessionEntity("session", note.id, "Session", "2026-11-09", 0, activeTranscriptId = raw.id, createdAt = 1, updatedAt = 1)
      val part = AudioPartEntity("part", session.id, 0, "audio.bin", "Audio.bin", "audio/mp4", 3, 1000, sha, 1)
      val source = SourceEntity("source", note.id, SourceKind.TEXT, "Original.txt", "text/plain", 3, sha, "source.bin", importedAt = 1)
      val segment = SegmentEntity(transcriptId = raw.id, partId = part.id, indexInPart = 0,
        partStartMs = 100, partEndMs = 800, sessionStartMs = 100, sessionEndMs = 800,
        text = "First. Second!", wordsJson = "-50,300,First.\n300,750,Second!", wordsEstimated = false)
      val native = PampaBundleNote(note, sessions = listOf(session), parts = listOf(part), transcripts = listOf(raw),
        segments = listOf(segment), sources = listOf(source))
      val export = ExportNote(note, listOf(folder.name), emptyList(), emptyList(), emptyList(), native = native)
      val archive = File(root, "input.pn").also { target -> target.outputStream().use { out ->
        PampaNoteWriter(input, input).write(ExportSet("Subject", "subject", listOf(export), "Pampa Notes test", 1, folders = listOf(folder)), out)
      } }
      val importer = PampaBundleImporter(db, files)
      assertEquals(1, importer.inspect(archive).noteCount)
      importer.import(archive)
      importer.import(archive)
      assertEquals(raw.text, db.transcripts().get(raw.id)!!.text)
      assertEquals(raw.id, db.sessions().get(session.id)!!.activeTranscriptId)
      assertEquals(segment.wordsJson, db.segments().byTranscript(raw.id).single().wordsJson)
      assertArrayEquals(content, files.audioFile(db.audioParts().get(part.id)!!.fileName).readBytes())
      assertArrayEquals(content, files.sourceFile(db.sources().get(source.id)!!.storedFileName!!).readBytes())
    } finally {
      db.close()
      java.nio.file.Files.delete(alias.toPath()) // Never traverse a directory symlink during cleanup.
      root.deleteRecursively()
    }
  }

  @Test fun storageDirectoryAndAttachmentSymlinksStillCannotRedirectVerification() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "pn-leaf-link-${UUID.randomUUID()}").also { it.mkdirs() }
    val outside = File(root, "outside").also { it.mkdir() }
    val audio = File(root, "audio")
    val source = File(outside, "original.bin").also { it.writeBytes(content) }
    try {
      Os.symlink(outside.absolutePath, audio.absolutePath)
      assertFalse(PampaBundleFileIntegrity.verified(audio, source.name, 3, sha, true))
      java.nio.file.Files.delete(audio.toPath())
      assertTrue(audio.mkdir())
      val leaf = File(audio, "redirect.bin")
      Os.symlink(source.absolutePath, leaf.absolutePath)
      assertFalse(PampaBundleFileIntegrity.verified(audio, leaf.name, 3, sha, true))
      assertArrayEquals(content, source.readBytes())
      java.nio.file.Files.delete(leaf.toPath())
    } finally {
      if (java.nio.file.Files.isSymbolicLink(audio.toPath())) java.nio.file.Files.delete(audio.toPath())
      root.deleteRecursively()
    }
  }
}
