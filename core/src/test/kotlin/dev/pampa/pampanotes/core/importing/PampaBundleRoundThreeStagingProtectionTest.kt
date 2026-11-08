package dev.pampa.pampanotes.core.importing

import android.content.Context
import dev.pampa.pampanotes.core.files.AppFiles
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

/** New source regressions, prepared without executing a compiler or cached classes. */
class PampaBundleRoundThreeStagingProtectionTest {
  @get:Rule val temp = TemporaryFolder()
  private val first = byteArrayOf(1, 2, 3)
  private val second = byteArrayOf(4, 5, 6)
  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  private fun appFiles(): AppFiles {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder()
    every { context.cacheDir } returns temp.newFolder()
    return AppFiles(context)
  }
  private fun archive(): File = temp.newFile().also { file ->
    ZipOutputStream(file.outputStream()).use { zip ->
      for ((path, bytes) in listOf("first.bin" to first, "second.bin" to second)) {
        zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
      }
    }
  }
  private fun attachments(secondAudio: Boolean = true) = listOf(
    PampaBundleAttachment("first", true, "first.bin", first.size.toLong(), sha(first)),
    PampaBundleAttachment("second", secondAudio, "second.bin", second.size.toLong(), sha(second)),
  )
  private fun oldNow(files: AppFiles): Long =
    (files.audio.listFiles().orEmpty().toList() + files.sources.listFiles().orEmpty()).maxOf { it.lastModified() } +
      4 * AppFiles.ORPHAN_MIN_AGE_MS

  @Test fun twoDistinctBlobsSurviveSweepDuringCopyAndBeforeCommitInBothDirectories() {
    for (secondAudio in listOf(true, false)) {
      val files = appFiles()
      // The old constructor must automatically use the actual AppFiles sweep registry.
      val staging = PampaBundleStaging(files.audio, files.sources)
      var sweptDuringFirstBlob = false
      try {
        val stored = staging.stage(archive(), "", attachments(secondAudio), {}, {
          if (!sweptDuringFirstBlob && staging.files.size == 1 && staging.files.single().length() == first.size.toLong()) {
            sweptDuringFirstBlob = true
            assertEquals(0, files.sweepOrphans(emptySet(), emptySet(), emptySet(), oldNow(files)))
          }
        })
        assertTrue(sweptDuringFirstBlob)
        assertEquals(2, stored.values.toSet().size)
        staging.requireProtected()
        assertEquals(0, files.sweepOrphans(emptySet(), emptySet(), emptySet(), oldNow(files)))
        assertArrayEquals(first, files.audioFile(stored.getValue(true to "first")).readBytes())
        val other = File(if (secondAudio) files.audio else files.sources, stored.getValue(secondAudio to "second"))
        assertArrayEquals(second, other.readBytes())
      } finally { staging.cleanup() }
      assertTrue(files.audio.listFiles()!!.isEmpty())
      assertTrue(files.sources.listFiles()!!.isEmpty())
    }
  }

  @Test fun snapshotsBeforeCreationAndBeforeCommitSurviveReleaseAndCloseWithoutLeakingOrphans() {
    val files = appFiles()
    val beforeCreation = files.beginOrphanSweep()
    val staging = PampaBundleStaging(files.audio, files.sources)
    try {
      val stored = staging.stage(archive(), "", attachments(false), {}, {})
      val now = oldNow(files)
      val beforeCommit = files.beginOrphanSweep()
      try {
        // Simulates commit/release after both sweep callers already read empty DB references.
        staging.release()
        assertEquals(0, files.sweepOrphans(emptySet(), emptySet(), emptySet(), snapshot = beforeCreation, now = now))
        beforeCreation.close()
        assertEquals(0, files.sweepOrphans(emptySet(), emptySet(), emptySet(), snapshot = beforeCommit, now = now))
        assertArrayEquals(first, files.audioFile(stored.getValue(true to "first")).readBytes())
        assertArrayEquals(second, files.sourceFile(stored.getValue(false to "second")).readBytes())
      } finally { beforeCommit.close() }
      // A fresh snapshot sees committed references and retains their bytes.
      files.beginOrphanSweep().use { fresh ->
        assertEquals(0, files.sweepOrphans(setOf(stored.getValue(true to "first")), setOf(stored.getValue(false to "second")),
          emptySet(), snapshot = fresh, now = now))
      }
      // After deletion/abandonment there is no owner, old snapshot or permanent release pin.
      assertEquals(2, files.sweepOrphans(emptySet(), emptySet(), emptySet(), now))
    } finally { beforeCreation.close(); staging.cleanup() }
  }

  @Test fun overlappingImportsHaveIndependentNamesAndReleasingOneDoesNotExposeTheOther() {
    val files = appFiles()
    val a = PampaBundleStaging(files.audio, files.sources)
    val b = PampaBundleStaging(files.audio, files.sources)
    try {
      val firstNames = a.stage(archive(), "", attachments(), {}, {}).values.toSet()
      val secondNames = b.stage(archive(), "", attachments(), {}, {}).values.toSet()
      assertTrue(firstNames.intersect(secondNames).isEmpty())
      a.release()
      a.release()
      assertEquals(2, files.sweepOrphans(emptySet(), emptySet(), emptySet(), oldNow(files)))
      assertTrue(firstNames.all { !files.audioFile(it).exists() })
      assertTrue(secondNames.all { files.audioFile(it).isFile })
      b.requireProtected()
      b.release()
      assertEquals(2, files.sweepOrphans(emptySet(), emptySet(), emptySet(), oldNow(files)))
    } finally { a.cleanup(); b.cleanup() }
  }

  @Test fun cancelledAndThrowingObserversCleanBytesAndReleaseWithoutCallerCleanup() {
    for (cancel in listOf(true, false)) {
      val files = appFiles()
      val retained = files.audioFile("retained.bin").also { it.writeBytes(first) }
      val staging = PampaBundleStaging(files.audio, files.sources)
      var injected = false
      try {
        staging.stage(archive(), "", attachments(false), {}, { progress ->
          if (progress > 0f) {
            injected = true
            if (cancel) throw CancellationException("cancel copy") else error("observer failed")
          }
        })
        fail("Expected copy interruption")
      } catch (_: CancellationException) {
        assertTrue(cancel)
      } catch (_: IllegalStateException) {
        assertFalse(cancel)
      }
      assertTrue(injected)
      assertTrue(staging.files.none { it.exists() })
      assertArrayEquals(first, retained.readBytes())
      // A failed owner cannot keep even a same-path orphan pinned after its cleanup.
      staging.files.forEach { it.writeBytes(second) }
      assertEquals(staging.files.size, files.sweepOrphans(setOf(retained.name), emptySet(), emptySet(), oldNow(files)))
    }
  }

  @Test fun failedReferenceQueryClosesSnapshotAndLeavesAbandonedFilesSweepable() {
    val files = appFiles()
    val staging = PampaBundleStaging(files.audio, files.sources)
    try {
      staging.stage(archive(), "", attachments(), {}, {})
      try {
        files.beginOrphanSweep().use {
          staging.release()
          error("reference query failed")
        }
      } catch (_: IllegalStateException) { }
      assertEquals(2, files.sweepOrphans(emptySet(), emptySet(), emptySet(), oldNow(files)))
    } finally { staging.cleanup() }
  }
}
