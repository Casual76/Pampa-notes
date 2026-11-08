package dev.pampa.pampanotes.core.alignment

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AlignmentQueueTest {
  private fun snapshot(): AlignmentSnapshot {
    val text = "  Cloud, exact!\n"; val sha = AlignmentProtocol.sha256("audio")
    val request = AlignmentRequest(source = AlignmentSource("raw", 2, AlignmentProtocol.sha256(text), listOf(AlignmentPart("a", sha))), partId = "a",
      text = text, textSha256 = AlignmentProtocol.sha256(text), language = "en", segments = emptyList())
    return AlignmentSnapshot("s", "n", "f", "refined", "refined", AlignmentProtocol.sha256("refined"), "en", "account-hash", "settings-hash", "segments-hash",
      listOf(AlignmentPlacement("a", sha, 0, 5000, "a.wav")), request)
  }
  @Test fun `restart retains exact snapshot deadline attempts and in-flight identity without credentials`() {
    val dir = Files.createTempDirectory("alignment-queue-test").toFile()
    try {
      val snap = snapshot(); val first = DurableAlignmentQueue(dir)
      assertTrue(first.enqueue(snap)); assertFalse(first.enqueue(snap))
      first.update(snap.key) { it.copy(remoteJobId = "fresh-job-id", remoteBase = "http://pc/v1", remoteInstance = "pc-1") }
      first.defer(snap.key, "offline", 1000)
      val after = DurableAlignmentQueue(dir)
      assertEquals(snap, after.entries().single().snapshot)
      assertEquals(61000L, after.nextAt()); assertNull(after.due(60999)); assertNotNull(after.due(61000))
      assertEquals("fresh-job-id", after.entries().single().remoteJobId)
      assertFalse(dir.resolve("queue-v1.json").readText().contains("Bearer"))
    } finally { dir.deleteRecursively() }
  }
  @Test fun `bounded retries park and endpoint wake revives while cancellation survives discovery`() {
    val dir = Files.createTempDirectory("alignment-retries-test").toFile()
    try {
      val q = DurableAlignmentQueue(dir); val s = snapshot(); q.enqueue(s)
      repeat(DurableAlignmentQueue.MAX_ATTEMPTS) { q.defer(s.key, "offline", 0) }
      assertNull(q.nextAt()); assertNull(q.due(Long.MAX_VALUE - 1))
      q.wakeEndpoint("new-pc"); assertNotNull(q.due(0))
      q.cancelSession("s"); q.wake(); assertNull(q.due(0)); assertFalse(q.enqueue(s))
      assertEquals("cancelled", DurableAlignmentQueue(dir).entries().single().state)
    } finally { dir.deleteRecursively() }
  }
  @Test fun `retry after never runs before saved deadline and unsupported capability stays parked`() {
    val dir = Files.createTempDirectory("alignment-deadline-test").toFile()
    try {
      val q = DurableAlignmentQueue(dir); val s = snapshot(); q.enqueue(s)
      q.defer(s.key, "server", 100, retryAfterMs = 8 * 60 * 60_000L)
      assertNull(q.due(8 * 60 * 60_000L)); assertEquals(8 * 60 * 60_000L + 100, q.nextAt())
      q.defer(s.key, "capability_absent", 100, retryable = false)
      assertNull(q.nextAt()); q.wakeEndpoint("new-capable-pc"); assertNotNull(q.due(0))
    } finally { dir.deleteRecursively() }
  }
  @Test fun `all stale dimensions produce distinct durable snapshot keys`() {
    val s = snapshot(); val r = s.request; val p = s.placement.single()
    val variants = listOf(s.copy(activeTranscriptId = "other"), s.copy(selectedTranscriptId = "other"), s.copy(selectedTextSha256 = "other"),
      s.copy(rawLanguage = "it"), s.copy(accountKey = "other"), s.copy(settingsKey = "privacy-changed"), s.copy(segmentSha256 = "changed-window"),
      s.copy(noteId = "moved"), s.copy(folderId = "moved"), s.copy(sessionId = "moved"), s.copy(placement = listOf(p.copy(durationMs = 6000))),
      s.copy(placement = listOf(p.copy(position = 1))), s.copy(placement = listOf(p.copy(sha256 = "changed-audio"))), s.copy(placement = listOf(p.copy(fileName = "moved.wav"))),
      s.copy(request = r.copy(source = r.source.copy(rawId = "new-raw"))), s.copy(request = r.copy(source = r.source.copy(rawVersion = 3))),
      s.copy(request = r.copy(source = r.source.copy(rawSha256 = "changed-text"))), s.copy(request = r.copy(language = "it")),
      s.copy(request = r.copy(text = "changed")), s.copy(request = r.copy(source = r.source.copy(parts = r.source.parts.reversed() + AlignmentPart("b", p.sha256)))))
    variants.forEach { assertNotEquals(s, it); assertNotEquals(s.key, it.key) }
  }
  @Test fun `reselected source can reenqueue stale snapshot but missing evidence waits for new instance`() {
    val dir = Files.createTempDirectory("alignment-stale-test").toFile()
    try {
      val q = DurableAlignmentQueue(dir); val s = snapshot(); q.enqueue(s)
      q.update(s.key) { it.copy(state = "stale", nextAt = Long.MAX_VALUE) }
      assertTrue(q.enqueue(s)); assertNotNull(q.due(0))
      q.update(s.key) { it.copy(state = "deferred", nextAt = Long.MAX_VALUE, reason = "missing_evidence", remoteInstance = "same-process") }
      q.wakeEndpoint("same-process"); assertNull(q.due(0))
      q.wakeEndpoint("new-process"); assertNotNull(q.due(0))
    } finally { dir.deleteRecursively() }
  }
}
