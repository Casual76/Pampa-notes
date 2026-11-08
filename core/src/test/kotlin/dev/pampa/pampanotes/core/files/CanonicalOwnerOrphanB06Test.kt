package dev.pampa.pampanotes.core.files

import android.content.Context
import dev.pampa.pampanotes.core.sync.SyncLocalFiles
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Combines the two owned AppFiles merge branches: physical aliases AND PN owner generations. */
class CanonicalOwnerOrphanB06Test {
  @get:Rule val temp = TemporaryFolder()
  private fun files(): AppFiles {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder()
    every { context.cacheDir } returns temp.newFolder()
    return AppFiles(context)
  }

  @Test fun aliasesAndLiveAndReleasedOwnersAllSurviveTheSameStaleSweep() {
    val files = files()
    val now = 2 * AppFiles.ORPHAN_MIN_AGE_MS
    val audio = files.audioFile("shared.wav").apply { writeText("shared audio"); setLastModified(1) }
    val source = files.sourceFile("shared.pdf").apply { writeText("shared original"); setLastModified(1) }
    val active = files.audioFile("adopted.wav").apply { writeText("live adopted bytes"); setLastModified(1) }
    val released = files.sourceFile("committed.pdf").apply { writeText("committed bytes"); setLastModified(1) }
    val orphan = files.audioFile("orphan.wav").apply { writeText("abandoned bytes"); setLastModified(1) }
    val page = SyncLocalFiles.beginWrites()
    try {
      files.beginOrphanSweep().use { stale ->
        // Token precedes references, adoption and commit/release, as in StorageRepository.
        val audioRefs = setOf("./shared.wav")
        val sourceRefs = setOf("./shared.pdf")
        page.hold(active)
        SyncLocalFiles.beginWrites().use { committed -> committed.hold(released) }
        assertEquals(1, files.sweepOrphans(audioRefs, sourceRefs, emptySet(), stale, now))
        assertTrue(audio.exists()); assertTrue(source.exists())
        assertTrue(active.exists()); assertTrue(released.exists()); assertFalse(orphan.exists())
      }
    } finally { page.close() }
    // Both closed owners become ordinary orphans; canonical retained refs remain safe.
    assertEquals(2, files.sweepOrphans(setOf("./shared.wav"), setOf("./shared.pdf"), emptySet(), now))
    assertTrue(audio.exists()); assertTrue(source.exists())
    assertFalse(active.exists()); assertFalse(released.exists())
  }

  @Test fun directSyncAdoptionRegistersBeforeHashingAndRollbackDoesNotOwnTheDonor() {
    val files = files()
    val donor = files.audioFile("donor.wav").apply { writeText("synthetic donor"); setLastModified(1) }
    val hash = donor.inputStream().use { Hashing.sha256(it) }
    val created = mutableListOf<File>()
    files.beginOrphanSweep().use { stale ->
      SyncLocalFiles.beginWrites().use { page ->
        var checkpoints = 0
        val name = SyncLocalFiles.selectName(files.audio, donor.name, donor.length(), hash, donor.name,
          emptyList(), emptySequence(), created, page, checkpoint = {
            checkpoints++
            assertEquals(0, files.sweepOrphans(emptySet(), emptySet(), emptySet(), stale,
              now = 2 * AppFiles.ORPHAN_MIN_AGE_MS))
            assertTrue(donor.exists())
          })
        assertEquals(donor.name, name)
        assertTrue(checkpoints > 0)
        assertTrue(created.isEmpty())
        SyncLocalFiles.cleanupCreated(created, emptySet()) // rollback only owns newly created files
        assertTrue(donor.exists())
      }
      assertEquals(0, files.sweepOrphans(emptySet(), emptySet(), emptySet(), stale,
        now = 2 * AppFiles.ORPHAN_MIN_AGE_MS))
    }
    assertEquals(1, files.sweepOrphans(emptySet(), emptySet(), emptySet(), now = 2 * AppFiles.ORPHAN_MIN_AGE_MS))
    assertFalse(donor.exists())
  }
}
