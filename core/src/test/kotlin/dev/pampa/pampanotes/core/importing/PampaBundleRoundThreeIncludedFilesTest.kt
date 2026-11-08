package dev.pampa.pampanotes.core.importing

import android.content.Context
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.PampaDatabase
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

/** Verifies new-entity attachments before any DAO write, without depending on retained-file merge. */
class PampaBundleRoundThreeIncludedFilesTest {
  @get:Rule val temp = TemporaryFolder()
  private val bytes = byteArrayOf(1, 2, 3)
  private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  private val included = listOf(PampaBundleAttachment("audio", true, "audio.bin", 3, sha),
    PampaBundleAttachment("source", false, "source.bin", 3, sha))
  private fun data(attachments: List<PampaBundleAttachment> = included) = PampaBundleData(folders = emptyList(), notes = listOf(
    PampaBundleNote(NoteEntity("new-note", "folder", "New note", "Body", createdAt = 1, updatedAt = 1), attachments = attachments)))

  private fun appFiles(): AppFiles {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder()
    every { context.cacheDir } returns temp.newFolder()
    return AppFiles(context)
  }
  private fun archive(): File = temp.newFile().also { file ->
    ZipOutputStream(file.outputStream()).use { zip ->
      for (path in listOf("audio.bin", "source.bin")) {
        zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
      }
    }
  }

  @Test fun missingAndSameLengthTamperedNewEntityFilesAreRejectedBeforeDatabaseAccess() {
    for (audio in listOf(true, false)) for (missing in listOf(true, false)) {
      val files = appFiles()
      val db = mockk<PampaDatabase>()
      val importer = PampaBundleImporter(db, files)
      val staging = PampaBundleStaging(files.audio, files.sources)
      try {
        val stored = staging.stage(archive(), "", included, {}, {})
        staging.requireProtected()
        importer.verifyIncludedFiles(data(), stored)
        val key = audio to if (audio) "audio" else "source"
        val target = File(if (audio) files.audio else files.sources, stored.getValue(key))
        if (missing) assertTrue(target.delete()) else target.writeBytes(byteArrayOf(9, 9, 9))
        try { importer.verifyIncludedFiles(data(), stored); fail("Expected included-file rejection") }
        catch (_: IllegalArgumentException) { }
        confirmVerified(db)
      } finally { staging.cleanup() }
    }
  }

  @Test fun includedDeclarationCannotBecomeAnOmissionButOmittedDeclarationNeedsNoLocalBytes() {
    val files = appFiles()
    val db = mockk<PampaDatabase>()
    val importer = PampaBundleImporter(db, files)
    try { importer.verifyIncludedFiles(data(), emptyMap()); fail("Expected missing staging rejection") }
    catch (_: IllegalArgumentException) { }
    importer.verifyIncludedFiles(data(included.map { it.copy(path = null) }), emptyMap())
    confirmVerified(db)
  }

  @Test fun cancellationDuringPrePersistenceVerificationKeepsProtectionUntilRollbackCleanup() {
    val files = appFiles()
    val staging = PampaBundleStaging(files.audio, files.sources)
    val importer = PampaBundleImporter(mockk(), files)
    try {
      val stored = staging.stage(archive(), "", included, {}, {})
      var checks = 0
      try {
        importer.verifyIncludedFiles(data(), stored) { if (++checks == 3) throw CancellationException("verify cancelled") }
        fail("Expected verification cancellation")
      } catch (_: CancellationException) { }
      assertEquals(3, checks)
      staging.requireProtected()
      val now = staging.files.maxOf { it.lastModified() } + 4 * AppFiles.ORPHAN_MIN_AGE_MS
      assertEquals(0, files.sweepOrphans(emptySet(), emptySet(), emptySet(), now))
    } finally { staging.cleanup() }
    assertTrue(staging.files.none { it.exists() })
  }
}
