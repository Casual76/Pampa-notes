package dev.pampa.pampanotes.core.files

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CanonicalOrphanRetentionTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun `orphan cleanup preserves equivalent audio and source references and removes real orphans`() {
    val context = mockk<Context>()
    every { context.filesDir } returns temporary.newFolder("files")
    every { context.cacheDir } returns temporary.newFolder("cache")
    val files = AppFiles(context)
    val audio = files.audioFile("shared.wav").apply { writeText("selected sibling audio"); setLastModified(1) }
    val source = files.sourceFile("shared.pdf").apply { writeText("legacy source"); setLastModified(1) }
    val orphan = files.audioFile("orphan.wav").apply { writeText("orphan"); setLastModified(1) }
    val removed = files.sweepOrphans(setOf("./shared.wav"), setOf("./shared.pdf"), emptySet(), now = 2 * AppFiles.ORPHAN_MIN_AGE_MS)
    assertEquals(1, removed)
    assertTrue(audio.isFile)
    assertTrue(source.isFile)
    assertFalse(orphan.exists())
  }
}
