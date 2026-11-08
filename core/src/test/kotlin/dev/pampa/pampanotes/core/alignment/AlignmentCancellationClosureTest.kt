package dev.pampa.pampanotes.core.alignment

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlignmentCancellationClosureTest {
  @get:Rule val temp = TemporaryFolder()

  private fun snapshot(): AlignmentSnapshot {
    val text = "Exact source"
    val sha = AlignmentProtocol.sha256("synthetic audio")
    val request = AlignmentRequest(source = AlignmentSource("raw", 1, AlignmentProtocol.sha256(text), listOf(AlignmentPart("p", sha))),
      partId = "p", text = text, textSha256 = AlignmentProtocol.sha256(text), language = "en", segments = emptyList())
    return AlignmentSnapshot("s", "n", "f", "raw", "raw", AlignmentProtocol.sha256(text), "en", "account", "settings", "segments",
      listOf(AlignmentPlacement("p", sha, 0, 1000, "p.wav")), request)
  }

  private fun assertCancelled(directory: File, key: String) {
    val reopened = DurableAlignmentQueue(directory)
    assertEquals("cancelled", reopened.get(key)!!.state)
    assertEquals(Long.MAX_VALUE, reopened.get(key)!!.nextAt)
    reopened.wake()
    reopened.wakeEndpoint("new-instance")
    assertNull(reopened.nextAt())
    assertNull(reopened.due(Long.MAX_VALUE - 1))
  }

  @Test fun completionArrivingAfterUserCancellationCannotReplaceTheTombstone() {
    for (completion in listOf("done", "deferred")) {
      val directory = temp.newFolder()
      val queue = DurableAlignmentQueue(directory)
      val source = snapshot()
      assertTrue(queue.enqueue(source))
      DurableAlignmentQueue(directory).cancelSession(source.sessionId)
      queue.update(source.key) { it.copy(state = completion, nextAt = 0, reason = "missing_evidence") }
      assertCancelled(directory, source.key)
      assertFalse(queue.enqueue(source))
    }
  }

  @Test fun errorHandlerWithAnEarlierNoncancelledReadCannotRequeueCancelledWork() {
    val directory = temp.newFolder()
    val queue = DurableAlignmentQueue(directory)
    val source = snapshot()
    queue.enqueue(source)
    val mayDefer = queue.get(source.key)!!.state != "cancelled"
    DurableAlignmentQueue(directory).cancelAll()
    if (mayDefer) queue.defer(source.key, "timeout", 100)
    assertCancelled(directory, source.key)
    assertEquals(0, queue.get(source.key)!!.attempts)
  }

  @Test fun retryableUncancelledWorkStillPersistsItsDeadlineAndCanBeWoken() {
    val directory = temp.newFolder()
    val queue = DurableAlignmentQueue(directory)
    val source = snapshot()
    queue.enqueue(source)
    queue.defer(source.key, "offline", 100)
    assertEquals(60100L, DurableAlignmentQueue(directory).nextAt())
    queue.wake()
    assertEquals(source, queue.due(0)!!.snapshot)
  }
}
