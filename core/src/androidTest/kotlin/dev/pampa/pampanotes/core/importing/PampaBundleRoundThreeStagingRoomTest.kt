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
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Room/real-import regression SOURCES ONLY. No device, Gradle or compiler was run for this round. */
@RunWith(AndroidJUnit4::class)
class PampaBundleRoundThreeStagingRoomTest {
  private lateinit var db: PampaDatabase
  private lateinit var files: AppFiles
  private lateinit var directory: File
  private lateinit var importer: PampaBundleImporter
  private val audioBytes = byteArrayOf(1, 2, 3)
  private val sourceBytes = byteArrayOf(4, 5, 6)
  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  @Before fun setup() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    directory = File(context.cacheDir, "pn-round-three-staging-${UUID.randomUUID()}").also { it.mkdirs() }
    val isolated = object : ContextWrapper(context) {
      override fun getFilesDir() = File(directory, "files").also { it.mkdirs() }
      override fun getCacheDir() = File(directory, "cache").also { it.mkdirs() }
    }
    db = PampaDatabase.inMemory(context)
    files = AppFiles(isolated)
    importer = PampaBundleImporter(db, files)
  }

  @After fun cleanup() { db.close(); directory.deleteRecursively() }

  private fun bundle(): File {
    val folder = FolderEntity("folder", "Subject", createdAt = 1, updatedAt = 1)
    val note = NoteEntity("note", folder.id, "Note", "Body", createdAt = 1, updatedAt = 1)
    val session = SessionEntity("session", note.id, "Session", "2026-11-08", 0, createdAt = 1, updatedAt = 1)
    val part = AudioPartEntity("part", session.id, 0, "audio.bin", "Audio.m4a", "audio/mp4", 3, 1000, sha(audioBytes), 1)
    val source = SourceEntity("source", note.id, SourceKind.TEXT, "Original.txt", "text/plain", 3, sha(sourceBytes),
      "source.bin", importedAt = 1)
    val native = PampaBundleNote(note, sessions = listOf(session), parts = listOf(part), sources = listOf(source))
    val input = File(directory, "input-${UUID.randomUUID()}").also { it.mkdirs() }
    File(input, part.fileName).writeBytes(audioBytes)
    File(input, source.storedFileName!!).writeBytes(sourceBytes)
    val export = ExportNote(note, listOf(folder.name), emptyList(), emptyList(), emptyList(), native = native)
    return File(directory, "${UUID.randomUUID()}.pn").also { target -> target.outputStream().use {
      PampaNoteWriter(input, input).write(ExportSet("Subject", "subject", listOf(export), "Pampa Notes test", 1,
        folders = listOf(folder)), it)
    } }
  }

  private fun allStaged() = files.audio.listFiles().orEmpty().toList() + files.sources.listFiles().orEmpty()
  private fun oldNow(): Long = allStaged().maxOf { it.lastModified() } + 4 * AppFiles.ORPHAN_MIN_AGE_MS

  @Test fun twoBlobsSurviveSweepWhileStagingBeforeCommitAndWithADbSnapshotTakenBeforeTheCommit() = runTest {
    val token = files.beginOrphanSweep()
    try {
      // The production hook acquires the token BEFORE all these DB reads.
      val staleAudio = db.audioParts().all().mapTo(HashSet()) { it.fileName }
      val staleSources = db.sources().all().mapNotNullTo(HashSet()) { it.storedFileName }
      var duringCopy = false
      var beforeCommit = false
      importer.import(bundle(), onProgress = { progress, event ->
        if (event.phase == PampaBundlePhase.STAGING_ATTACHMENTS && allStaged().size == 1 && allStaged().single().length() > 0) {
          duringCopy = true
          assertEquals(0, files.sweepOrphans(staleAudio, staleSources, emptySet(), snapshot = token, now = oldNow()))
        }
        if (event.phase == PampaBundlePhase.IMPORTING_NOTES && progress < 1f) {
          beforeCommit = true
          assertEquals(2, allStaged().size)
          assertEquals(0, files.sweepOrphans(staleAudio, staleSources, emptySet(), snapshot = token, now = oldNow()))
        }
      })
      assertTrue(duringCopy)
      assertTrue(beforeCommit)
      // import returned: its write owner is gone, but this sweep's DB rows are still stale.
      assertEquals(0, files.sweepOrphans(staleAudio, staleSources, emptySet(), snapshot = token, now = oldNow()))
      assertArrayEquals(audioBytes, files.audioFile(db.audioParts().get("part")!!.fileName).readBytes())
      assertArrayEquals(sourceBytes, files.sourceFile(db.sources().get("source")!!.storedFileName!!).readBytes())
    } finally { token.close() }
    val now = oldNow()
    db.notes().delete("note")
    files.beginOrphanSweep().use { fresh ->
      assertEquals(2, files.sweepOrphans(db.audioParts().all().mapTo(HashSet()) { it.fileName },
        db.sources().all().mapNotNullTo(HashSet()) { it.storedFileName }, emptySet(), snapshot = fresh, now = now))
    }
  }

  @Test fun missingOrTamperedIncludedNewFileBeforePersistenceCannotCommitAnEntity() = runTest {
    for (missing in listOf(true, false)) {
      var injected = false
      try {
        importer.import(bundle(), onProgress = { progress, event ->
          if (!injected && event.phase == PampaBundlePhase.STAGING_ATTACHMENTS && progress >= 0.7f) {
            injected = true
            val target = allStaged().first()
            if (missing) assertTrue(target.delete()) else target.writeBytes(byteArrayOf(9, 9, 9))
          }
        })
        fail("Expected included-file integrity rejection")
      } catch (_: IllegalArgumentException) { }
      assertTrue(injected)
      assertEquals(0, db.notes().count())
      assertTrue(db.audioParts().all().isEmpty())
      assertTrue(db.sources().all().isEmpty())
      assertTrue(allStaged().isEmpty())
    }
  }

  @Test fun cancellationAndPersistenceObserverFailureRollbackNewBytesAndRows() = runTest {
    for (cancel in listOf(true, false)) {
      var injected = false
      try {
        importer.import(bundle(), onProgress = { progress, event ->
          if (event.phase == PampaBundlePhase.IMPORTING_NOTES && progress < 1f) {
            injected = true
            assertEquals(0, files.sweepOrphans(emptySet(), emptySet(), emptySet(), oldNow()))
            if (cancel) throw CancellationException("cancel in transaction") else error("observer in transaction")
          }
        })
        fail("Expected transaction failure")
      } catch (_: CancellationException) { assertTrue(cancel) }
      catch (_: IllegalStateException) { assertFalse(cancel) }
      assertTrue(injected)
      assertEquals(0, db.notes().count())
      assertTrue(allStaged().isEmpty())
    }
  }

  @Test fun cancellingTheActualImportJobCleansAndReleasesInNonCancellableFinally() = runTest {
    var injected = false
    lateinit var job: Job
    job = launch(start = CoroutineStart.LAZY) {
      importer.import(bundle(), onProgress = { progress, event ->
        if (!injected && event.phase == PampaBundlePhase.STAGING_ATTACHMENTS && progress > 0.1f) {
          injected = true
          job.cancel()
        }
      })
    }
    job.start()
    job.join()
    assertTrue(injected)
    assertTrue(job.isCancelled)
    assertEquals(0, db.notes().count())
    assertTrue(allStaged().isEmpty())
  }

  @Test fun postCommitObserverFailureRetainsReferencedBytesButReleasesTheWriteOwner() = runTest {
    var injected = false
    try {
      importer.import(bundle(), onProgress = { progress, event ->
        if (event.phase == PampaBundlePhase.IMPORTING_NOTES && progress == 1f) {
          injected = true
          error("observer after commit")
        }
      })
      fail("Expected post-commit observer failure")
    } catch (_: IllegalStateException) { }
    assertTrue(injected)
    assertEquals(1, db.notes().count())
    val now = oldNow()
    files.beginOrphanSweep().use { fresh ->
      assertEquals(0, files.sweepOrphans(db.audioParts().all().mapTo(HashSet()) { it.fileName },
        db.sources().all().mapNotNullTo(HashSet()) { it.storedFileName }, emptySet(), snapshot = fresh, now = now))
    }
    assertArrayEquals(audioBytes, files.audioFile(db.audioParts().get("part")!!.fileName).readBytes())
    assertArrayEquals(sourceBytes, files.sourceFile(db.sources().get("source")!!.storedFileName!!).readBytes())
    db.notes().delete("note")
    assertEquals(2, files.sweepOrphans(emptySet(), emptySet(), emptySet(), now))
  }
}
