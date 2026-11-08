package dev.pampa.pampanotes.core.sync

import android.content.Context
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.Hashing
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real copies and AppFiles sweeps, with simulated age and no sleeps, threads or Android runtime. */
class PampaSyncWriteOwnershipRoundFourTest {
  @get:Rule val temp = TemporaryFolder()
  private val bytes = ByteArray(256 * 1024) { (it * 19).toByte() }
  private val sha = Hashing.sha256(bytes.inputStream())
  private val future get() = System.currentTimeMillis() + 60 * 60_000L

  private fun files(): AppFiles {
    val directory = temp.newFolder()
    val cache = temp.newFolder()
    return AppFiles(mockk<Context> {
      every { filesDir } returns directory
      every { cacheDir } returns cache
    })
  }

  private fun copy(root: File, donor: File, created: MutableList<File>, owner: SyncLocalFiles.WriteOwner,
    checkpoint: () -> Unit = {}): File = File(root, SyncLocalFiles.selectName(root, donor.name, bytes.size.toLong(), sha,
      null, setOf(donor.name), sequenceOf(donor.name), created, owner, checkpoint = checkpoint)!!)

  @Test fun stalePrecreationReferencesCannotDeleteAudioOrSourcesAfterCommitAndRelease() {
    for (audio in listOf(true, false)) {
      val files = files()
      val directory = if (audio) files.audio else files.sources
      val donor = File(directory, "donor.bin").also { it.writeBytes(bytes) }
      val created = mutableListOf<File>()
      val owner = SyncLocalFiles.beginWrites()
      // This token and reference read precede target creation and the simulated page commit.
      files.beginOrphanSweep().use { stale ->
        val audioRefs = if (audio) setOf(donor.name) else emptySet()
        val sourceRefs = if (audio) emptySet() else setOf(donor.name)
        try {
          val target = copy(directory, donor, created, owner)
          assertNotEquals(donor, target)
          assertEquals(0, files.sweepOrphans(audioRefs, sourceRefs, emptySet(), stale, future))
          // A successful commit, including cancellation delivered just after it, is determined
          // by current references rather than by whether the caller received a result.
          SyncLocalFiles.cleanupCreated(created, setOf(target.canonicalFile))
          owner.close()
          assertEquals(0, files.sweepOrphans(audioRefs, sourceRefs, emptySet(), stale, future))
          files.beginOrphanSweep().use { current ->
            assertEquals(0, files.sweepOrphans(if (audio) audioRefs + target.name else audioRefs,
              if (audio) sourceRefs else sourceRefs + target.name, emptySet(), current, future))
          }
          assertArrayEquals(bytes, target.readBytes())
          // Ordinary deletion of the donor must leave the borrowed copy intact.
          assertTrue(donor.delete())
          assertArrayEquals(bytes, target.readBytes())
        } finally { owner.close() }
      }
      // No live owner or stale release record may pin an actually abandoned copy forever.
      assertEquals(1, files.sweepOrphans(emptySet(), emptySet(), emptySet(), future))
      assertFalse(created.single().exists())
    }
  }

  @Test fun activePartialCopySurvivesSweepsWithNewSnapshotsAndAnHourOfSimulatedAge() {
    val files = files()
    val donor = files.audioFile("donor.bin").also { it.writeBytes(bytes) }
    val created = mutableListOf<File>()
    var partialSweeps = 0
    SyncLocalFiles.beginWrites().use { owner ->
      val target = copy(files.audio, donor, created, owner) {
        val partial = created.singleOrNull()
        if (partial != null && partial.length() in 1 until bytes.size.toLong()) {
          files.beginOrphanSweep().use { duringCopy ->
            assertEquals(0, files.sweepOrphans(setOf(donor.name), emptySet(), emptySet(), duringCopy, future))
          }
          partialSweeps++
        }
      }
      assertTrue("Must sweep while bytes are only partially copied", partialSweeps > 0)
      assertArrayEquals(bytes, target.readBytes())
      SyncLocalFiles.cleanupCreated(created, setOf(target.canonicalFile))
    }
    assertEquals(1, files.sweepOrphans(setOf(donor.name), emptySet(), emptySet(), future))
    assertArrayEquals(bytes, donor.readBytes())
  }

  @Test fun rollbackAndCancellationCleanPartialCopiesAndAlwaysReleaseTheirOwner() {
    for (cancel in listOf(false, true)) {
      val files = files()
      val donor = files.sourceFile("donor.bin").also { it.writeBytes(bytes) }
      val created = mutableListOf<File>()
      val owner = SyncLocalFiles.beginWrites()
      try {
        if (cancel) {
          assertThrows(CancellationException::class.java) {
            copy(files.sources, donor, created, owner) {
              if (created.singleOrNull()?.length()?.let { it > 0 } == true) throw CancellationException("mid-copy")
            }
          }
          assertTrue(created.single().length() < bytes.size)
        } else copy(files.sources, donor, created, owner)
      } finally {
        try { SyncLocalFiles.cleanupCreated(created, emptySet()) } finally { owner.close() }
      }
      val abandoned = created.single()
      assertFalse(abandoned.exists())
      // Reuse the exact path: deleting the partial file alone would hide an unreleased owner.
      abandoned.writeBytes(bytes)
      assertEquals(1, files.sweepOrphans(emptySet(), setOf(donor.name), emptySet(), future))
      assertFalse(abandoned.exists())
      assertArrayEquals(bytes, donor.readBytes())
    }
  }

  @Test fun failedCommittedReferenceQueryStillReleasesForALaterFreshSweep() {
    val files = files()
    val donor = files.audioFile("donor.bin").also { it.writeBytes(bytes) }
    val created = mutableListOf<File>()
    files.beginOrphanSweep().use { stale ->
      val owner = SyncLocalFiles.beginWrites()
      try { copy(files.audio, donor, created, owner) } finally {
        try {
          runCatching { error("reference query failed before cleanup") }
        } finally { owner.close() }
      }
      assertEquals(0, files.sweepOrphans(setOf(donor.name), emptySet(), emptySet(), stale, future))
    }
    assertEquals(1, files.sweepOrphans(setOf(donor.name), emptySet(), emptySet(), future))
  }

  @Test fun compatibilityHelperReleasesOnSuccessAndCancellationWithoutPermanentHolds() {
    for (cancel in listOf(false, true)) {
      val files = files()
      val donor = files.audioFile("donor.bin").also { it.writeBytes(bytes) }
      val created = mutableListOf<File>()
      val select = {
        SyncLocalFiles.selectName(files.audio, donor.name, bytes.size.toLong(), sha, null, setOf(donor.name),
          sequenceOf(donor.name), created) { if (cancel && created.isNotEmpty()) throw CancellationException("cancel") }
      }
      if (cancel) assertThrows(CancellationException::class.java) { select() } else select()
      assertEquals(1, files.sweepOrphans(setOf(donor.name), emptySet(), emptySet(), future))
      assertFalse(created.single().exists())
      assertArrayEquals(bytes, donor.readBytes())
    }
  }

  @Test fun closedPageOwnerCannotAcquireAnotherCopyAndCloseIsIdempotent() {
    val files = files()
    val donor = files.audioFile("donor.bin").also { it.writeBytes(bytes) }
    val created = mutableListOf<File>()
    val owner = SyncLocalFiles.beginWrites()
    owner.close()
    owner.close()
    assertThrows(IllegalStateException::class.java) { copy(files.audio, donor, created, owner) }
    assertTrue(created.isEmpty())
    assertEquals(listOf(donor), files.audio.listFiles()!!.toList())
  }
}
