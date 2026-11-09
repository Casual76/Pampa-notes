package dev.pampa.pampanotes.core.importing

import android.content.Context
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real filesystem aliases in a trusted parent, independent of OS-specific symlink privileges. */
class PampaImportStoragePathTest {
  @get:Rule val temp = TemporaryFolder()
  private val content = byteArrayOf(1, 2, 3)
  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun aliasedFiles(): AppFiles {
    val root = temp.newFolder()
    val intermediate = File(root, "parent-alias").also { assertTrue(it.mkdir()) }
    val alias = File(intermediate, "..")
    assertEquals(root.canonicalFile, alias.canonicalFile)
    assertNotEquals(alias.absoluteFile, alias.canonicalFile)
    val cache = temp.newFolder()
    val context = mockk<Context>()
    every { context.filesDir } returns alias
    every { context.cacheDir } returns cache
    return AppFiles(context)
  }

  @Test fun healthyAudioAndSourceFilesRemainVerifiedThroughParentAliases() {
    val files = aliasedFiles()
    for (directory in listOf(files.audio, files.sources)) {
      val original = File(directory, "original.bin").also { it.writeBytes(content) }
      assertTrue(PampaBundleFileIntegrity.verified(directory, original.name, 3, sha(content), true))
      assertTrue(PampaBundleFileIntegrity.verified(directory.canonicalFile, original.name, 3, sha(content), true))
      assertArrayEquals(content, original.readBytes())
      assertFalse(PampaBundleFileIntegrity.verified(directory, original.name, 3, sha(content), false))
    }
  }

  @Test fun freshlyStagedOriginalsPassTheRealPrecommitGateThroughParentAliases() {
    val files = aliasedFiles()
    val db = mockk<PampaDatabase>()
    val folder = FolderEntity("folder", "Subject", createdAt = 1, updatedAt = 1)
    val note = NoteEntity("note", folder.id, "Note", createdAt = 1, updatedAt = 1)
    val session = SessionEntity("session", note.id, "Session", "2026-11-09", 0, createdAt = 1, updatedAt = 1)
    val part = AudioPartEntity("part", session.id, 0, "", "Audio.bin", "audio/mp4", 3, 1000, sha(content), 1)
    val source = SourceEntity("source", note.id, SourceKind.TEXT, "Original.txt", "text/plain", 3, sha(content), importedAt = 1)
    val attachments = listOf(PampaBundleAttachment(part.id, true, "audio.bin", 3, sha(content)),
      PampaBundleAttachment(source.id, false, "source.bin", 3, sha(content)))
    val data = PampaBundleData(folders = listOf(folder), notes = listOf(PampaBundleNote(note,
      sessions = listOf(session), parts = listOf(part), sources = listOf(source), attachments = attachments)))
    PampaBundleArchive.validate(data)
    val archive = temp.newFile().also { target ->
      ZipOutputStream(target.outputStream()).use { zip ->
        for (path in listOf("audio.bin", "source.bin")) {
          zip.putNextEntry(ZipEntry(path)); zip.write(content); zip.closeEntry()
        }
      }
    }
    val staging = PampaBundleStaging(files.audio, files.sources)
    try {
      val stored = staging.stage(archive, "", attachments, {}, {})
      staging.requireProtected()
      PampaBundleImporter(db, files).verifyIncludedFiles(data, stored)
      assertEquals(2, staging.files.size)
      staging.files.forEach { assertArrayEquals(content, it.readBytes()) }
      confirmVerified(db)
    } finally { staging.cleanup() }
    assertTrue(staging.files.none { it.exists() })
  }

  @Test fun omittedSafeReferenceRemainsRecoverableButCorruptionStillRejects() {
    val files = aliasedFiles()
    for (directory in listOf(files.audio, files.sources)) {
      assertEquals("missing.bin", PampaBundleFileIntegrity.select(directory, "missing.bin", 3, sha(content), true, null))
      val corrupt = File(directory, "corrupt.bin").also { it.writeBytes(byteArrayOf(9, 9, 9)) }
      assertThrows(IllegalArgumentException::class.java) {
        PampaBundleFileIntegrity.select(directory, corrupt.name, 3, sha(content), true, null)
      }
      assertArrayEquals(byteArrayOf(9, 9, 9), corrupt.readBytes())
    }
  }

  @Test fun verifiedStagingRepairsReferencesWithoutMutatingTheOldFile() {
    val files = aliasedFiles()
    for (directory in listOf(files.audio, files.sources)) {
      val old = File(directory, "old.bin").also { it.writeBytes(byteArrayOf(9, 9, 9)) }
      val staged = File(directory, "staged.bin").also { it.writeBytes(content) }
      assertEquals(staged.name, PampaBundleFileIntegrity.select(directory, old.name, 3, sha(content), true, staged.name))
      assertArrayEquals(byteArrayOf(9, 9, 9), old.readBytes())
      assertArrayEquals(content, staged.readBytes())
    }
  }

  @Test fun parentAliasDoesNotAuthorizeTraversalUnknownHashesOrWrongSizes() {
    val files = aliasedFiles()
    val valid = File(files.audio, "valid.bin").also { it.writeBytes(content) }
    val outside = File(files.root, "outside.bin").also { it.writeBytes(content) }
    for (name in listOf("../outside.bin", "..\\outside.bin", outside.absolutePath, "nested/../valid.bin", ".", "..", "bad:name")) {
      assertFalse(PampaBundleFileIntegrity.verified(files.audio, name, 3, sha(content), true))
    }
    assertFalse(PampaBundleFileIntegrity.verified(files.audio, valid.name, 3, "", true))
    assertFalse(PampaBundleFileIntegrity.verified(files.audio, valid.name, 2, sha(content), true))
    assertFalse(PampaBundleFileIntegrity.verified(files.audio, valid.name, 3, sha(byteArrayOf(4, 5, 6)), true))
    assertArrayEquals(content, outside.readBytes())
  }

  @Test fun cancellationDuringHashingAnAliasedPathPropagatesWithoutDeletingBytes() {
    val files = aliasedFiles()
    val large = ByteArray(4 * 65536) { (it % 251).toByte() }
    val original = files.audioFile("original.bin").also { it.writeBytes(large) }
    var checkpoints = 0
    assertThrows(CancellationException::class.java) {
      PampaBundleFileIntegrity.verified(files.audio, original.name, large.size.toLong(), sha(large), true) {
        if (++checkpoints == 3) throw CancellationException("cancel verification")
      }
    }
    assertEquals(3, checkpoints)
    assertArrayEquals(large, original.readBytes())
  }
}
