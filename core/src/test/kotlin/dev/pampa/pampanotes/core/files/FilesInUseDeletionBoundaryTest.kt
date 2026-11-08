package dev.pampa.pampanotes.core.files

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesInUseDeletionBoundaryTest {
  @Test fun `final deletion check shares the lock with new holders`() {
    val inUse = FilesInUse()
    val started = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val executor = Executors.newSingleThreadExecutor()
    try {
      inUse.withCurrent { held ->
        assertTrue(held.isEmpty())
        executor.submit {
          started.countDown()
          inUse.hold(listOf(FilesInUse.audio("shared.wav")), 60_000)
          finished.countDown()
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertFalse("hold must wait for the check/delete boundary", finished.await(100, TimeUnit.MILLISECONDS))
      }
      assertTrue(finished.await(5, TimeUnit.SECONDS))
      inUse.withCurrent { assertTrue(FilesInUse.audio("shared.wav") in it) }
    } finally { executor.shutdownNow() }
  }
}
