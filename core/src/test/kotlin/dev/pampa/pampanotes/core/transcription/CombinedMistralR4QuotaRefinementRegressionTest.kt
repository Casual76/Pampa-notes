package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobType
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Prepared real HTTP/provider/runner/journal/ledger checks for R4-F2. No sockets or waits. */
class CombinedMistralR4QuotaRefinementRegressionTest {
  @get:Rule val temp = TemporaryFolder()

  @Test fun `readable short long date and zero headers replace this response fallback before queue retry`() = runBlocking {
    for (header in listOf("2", "60", "172800.125", "date", "0")) {
      val f = CombinedMistralR4ClosureFixture(temp.newFolder()).also { it.status = 429 }
      val date = (System.currentTimeMillis() + 30_000) / 1000 * 1000
      f.retryAfter = if (header == "date") DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(date).atZone(ZoneOffset.UTC)) else header
      f.atHeaders = {
        // The known refusal, conservative quota and exact zero charge are durable BEFORE IO.
        assertTrue(f.marker().readText().startsWith("pending:"))
        assertTrue(File(f.marker().path + ".refusal").readText().endsWith(":429"))
        assertTrue(f.deadline() >= f.now + CloudRetry.DEFAULT_WAIT_MS)
        assertEquals(false, f.timingKnown())
        assertEquals("0", f.charges().last().actualUsd)
        assertNotNull(f.charges().last().refusedAt)
      }
      val error = runCatching { f.run() }.exceptionOrNull() as TranscriptionError.RateLimited
      val at = f.deadline()
      assertEquals(at, error.retryAtMillis)
      assertEquals(true, f.timingKnown())
      when (header) {
        "date" -> assertEquals(date, at)
        "0" -> assertEquals(f.now + 1000, at)
        else -> {
          val delay = java.math.BigDecimal(header).multiply(java.math.BigDecimal(1000)).toLong()
          assertTrue("$header at=$at now=${f.now}", at >= f.now + delay)
          assertTrue("$header incorrectly retained fallback", at < f.now + delay + 5_000)
        }
      }
      val scope = CloudRetry.scope("mistral", f.fingerprint, f.account)
      assertEquals(scope, error.scopeFingerprint)
      assertEquals(CloudJobContext(f.account, scope), DurableCloudJobContexts(f.jobContexts).get("job"))
      assertTrue(f.marker().readText().startsWith("rejected:"))
      val a = f.result("a").readBytes()
      val charges = f.charges()
      assertEquals(2, charges.size); assertEquals("0.01", charges.first().actualUsd)
      assertEquals("0", charges.last().actualUsd)
      f.atHeaders = {}; f.status = 200
      f.now = at - 1
      assertTrue(runCatching { f.run() }.exceptionOrNull() is TranscriptionError.RateLimited)
      assertTrue(runCatching { f.run("other-job") }.exceptionOrNull() is TranscriptionError.RateLimited)
      assertEquals(2, f.posts)
      f.now = at + 1
      f.run()
      assertEquals(3, f.posts); assertArrayEquals(a, f.result("a").readBytes())
      assertEquals(charges.first(), f.charges().first())
      val completed = f.charges()
      f.run(); assertEquals(3, f.posts); assertEquals(completed, f.charges())
    }
  }

  @Test fun `independent longer waits before and during header access survive short response refinement`() = runBlocking {
    for (whenObserved in listOf("before-fallback", "during-header")) for (independentDelay in listOf(60_000L, 3_600_000L)) {
      val f = CombinedMistralR4ClosureFixture(temp.newFolder()).also { it.status = 429; it.retryAfter = "2" }
      val independent = f.now + independentDelay
      val earlier = {
        f.cooldowns().extend("mistral", f.fingerprint, f.account, independent, true)
        f.cooldowns().extend("groq", f.fingerprint, f.account, independent + 1, true)
        f.cooldowns().extend("mistral", "other-key", f.account, independent + 2, false)
        f.cooldowns().extend("mistral", f.fingerprint, "other-account", independent + 3, false)
        Unit
      }
      if (whenObserved == "before-fallback") f.atStatus = earlier else f.atHeaders = earlier
      val error = runCatching { f.run() }.exceptionOrNull() as TranscriptionError.RateLimited
      assertEquals(independent, error.retryAtMillis); assertEquals(independent, f.deadline())
      assertEquals(true, f.timingKnown())
      assertEquals(independent + 1, f.cooldowns().deadline("groq", f.fingerprint, f.account))
      assertEquals(independent + 2, f.cooldowns().deadline("mistral", "other-key", f.account))
      assertEquals(independent + 3, f.cooldowns().deadline("mistral", f.fingerprint, "other-account"))
      assertEquals(2, f.posts)
    }
  }

  @Test fun `missing invalid and nonfinite headers keep explicitly unknown fallback`() = runBlocking {
    for (header in listOf(null, "", "not-a-date", "-1", "NaN", "Infinity")) {
      val f = CombinedMistralR4ClosureFixture(temp.newFolder()).also { it.status = 429; it.retryAfter = header }
      val error = runCatching { f.run() }.exceptionOrNull() as TranscriptionError.RateLimited
      assertEquals(f.now + CloudRetry.DEFAULT_WAIT_MS, error.retryAtMillis)
      assertEquals(false, f.timingKnown())
      assertTrue(f.marker().readText().startsWith("rejected:"))
      assertEquals("0", f.charges().last().actualUsd)
      assertEquals(2, f.posts)
    }
  }

  @Test fun `concurrent equal fallback observation cannot be erased by this HTTP response`() = runBlocking {
    val f = CombinedMistralR4ClosureFixture(temp.newFolder()).also { it.status = 429; it.retryAfter = "2" }
    val fallback = f.now + CloudRetry.DEFAULT_WAIT_MS
    var other: DurableCloudCooldowns.Observation? = null
    f.atHeaders = {
      // Deterministic second in-flight response after admission, before A reads its headers.
      other = f.cooldowns().beginObservation("mistral", f.fingerprint, f.account, fallback)
    }
    // Uses the production repository guard too: its limited callback must re-read a matching
    // persisted scope instead of promoting another response's provisional wait via extend().
    val error = runCatching { f.run(repositoryGuard = true) }.exceptionOrNull() as TranscriptionError.RateLimited
    assertEquals(fallback, error.retryAtMillis); assertEquals(false, f.timingKnown())
    assertEquals(fallback, f.cooldowns().deadline("mistral", f.fingerprint, f.account))
    // Reopened storage can refine the OTHER response, retaining A's known short wait.
    val remaining = f.cooldowns().finishObservation(other!!, f.now + 60_000)
    assertEquals(f.now + 60_000, remaining); assertEquals(true, f.timingKnown())
    val queued = JobEntity("job", "session", JobType.TRANSCRIBE, "mistral", model = f.model,
      phase = JobPhase.ProviderCooldown.encode(), createdAt = 1, updatedAt = 1)
    // Reopened production queue scheduling resolves the saved scope against CURRENT disk state.
    // No Room runtime: the queue row is supplied, while contexts/cooldowns/ledger are real files.
    assertEquals(remaining, f.repository().waitUntil(queued))
    assertEquals("quota", queued.phase)
    assertEquals(2, f.posts)
  }

  @Test fun `crash before headers and interleaved observations preserve every unresolved fallback`() {
    val file = File(temp.root, "interleaved.json")
    val disk = DurableCloudCooldowns(file)
    val first = disk.beginObservation("mistral", "key", "account", 600_000)
    val second = DurableCloudCooldowns(file).beginObservation("mistral", "key", "account", 600_000)
    // No header processed: a new instance still sees the conservative crash-safe deadline.
    assertEquals(600_000L, DurableCloudCooldowns(file).deadline("mistral", "key", "account"))
    assertEquals(600_000L, disk.finishObservation(first, 2_000))
    assertEquals(false, disk.retryAfterKnown("mistral", "key", "account"))
    assertEquals(60_000L, DurableCloudCooldowns(file).finishObservation(second, 60_000))
    assertEquals(true, disk.retryAfterKnown("mistral", "key", "account"))
    val settled = file.readBytes()
    // Duplicate/stale callbacks cannot shorten a later independent observation.
    assertTrue(runCatching { disk.finishObservation(first, 1_000) }.isFailure)
    assertArrayEquals(settled, file.readBytes())
    disk.extend("mistral", "key", "account", 3_600_000, false)
    val third = disk.beginObservation("mistral", "key", "account", 4_200_000)
    assertEquals(3_600_000L, disk.finishObservation(third, 3_000))
    assertEquals(false, disk.retryAfterKnown("mistral", "key", "account"))
    // Both historical private formats are independent waits, never this response's fallback.
    val legacy = File(temp.root, "legacy.json")
    val scope = CloudRetry.scope("mistral", "key", "account")
    for (value in listOf("900000", """{"until":900000,"retryAfterKnown":false}""")) {
      legacy.writeText("""{"$scope":$value}""")
      val reopened = DurableCloudCooldowns(legacy)
      val observation = reopened.beginObservation("mistral", "key", "account", 600_000)
      assertEquals(900_000L, reopened.finishObservation(observation, 2_000))
      assertEquals(if (value == "900000") null else false, reopened.retryAfterKnown("mistral", "key", "account"))
    }
  }

  @Test fun `header IO and cancellation keep durable fallback exact refusal and completed A`() = runBlocking {
    for (failure in listOf("io", "cancel-header", "cancel-status-readable")) {
      val f = CombinedMistralR4ClosureFixture(temp.newFolder()).also { it.status = 429 }
      var observed: Throwable? = null
      val task = launch {
        val own = currentCoroutineContext()[Job]!!
        when (failure) {
          "io" -> f.headerFailure = IOException("synthetic disconnected headers")
          "cancel-header" -> f.atHeaders = { val stop = CancellationException("synthetic header stop"); own.cancel(stop); throw stop }
          else -> f.atStatus = { own.cancel(CancellationException("synthetic stop after known status")) }
        }
        observed = runCatching { f.run() }.exceptionOrNull()
      }
      task.join()
      if (failure == "io") assertTrue(observed is TranscriptionError.RateLimited)
      else { assertTrue(task.isCancelled); assertTrue(observed is CancellationException) }
      assertTrue(f.marker().readText().startsWith("rejected:"))
      assertEquals("0", f.charges().last().actualUsd); assertNotNull(f.charges().last().refusedAt)
      val at = f.deadline()
      if (failure == "cancel-status-readable") {
        assertEquals(true, f.timingKnown()); assertTrue(at < f.now + 10_000)
      } else {
        assertEquals(false, f.timingKnown()); assertEquals(f.now + CloudRetry.DEFAULT_WAIT_MS, at)
      }
      val a = f.result("a").readBytes()
      f.atHeaders = {}; f.atStatus = {}; f.headerFailure = null; f.status = 200
      assertTrue(runCatching { f.run() }.exceptionOrNull() is TranscriptionError.RateLimited)
      assertEquals(2, f.posts)
      f.now = at + 1
      f.run(); assertEquals(3, f.posts); assertArrayEquals(a, f.result("a").readBytes())
    }
  }

  @Test fun `initial or refinement durable write failure never clears uncertain upload barrier`() = runBlocking {
    for (boundary in listOf("initial", "refinement")) {
      val f = CombinedMistralR4ClosureFixture(temp.newFolder()).also { it.status = 429 }
      val blockWrite = { assertTrue(File(f.quota.path + ".tmp").mkdir()); Unit }
      if (boundary == "initial") f.atStatus = blockWrite else f.atHeaders = blockWrite
      val error = runCatching { f.run() }.exceptionOrNull()
      assertTrue(error is TranscriptionError.CloudControl)
      assertEquals("cloud_cost_storage", (error as TranscriptionError.CloudControl).code)
      assertEquals(if (boundary == "initial") 0 else 1, f.headerReads)
      val a = f.result("a").readBytes(); val marker = f.marker().readBytes(); val ledger = f.charges()
      assertTrue(String(marker).startsWith("pending:"))
      assertTrue(File(f.marker().path + ".refusal").readText().endsWith(":429"))
      if (boundary == "refinement") {
        assertEquals(f.now + CloudRetry.DEFAULT_WAIT_MS, f.deadline())
        assertEquals(false, f.timingKnown()); assertEquals("0", ledger.last().actualUsd)
      } else {
        assertNull(ledger.last().settledAt); assertNull(ledger.last().refusedAt)
      }
      assertTrue(File(f.quota.path + ".tmp").delete()) // Empty synthetic blocker only.
      f.atStatus = {}; f.atHeaders = {}; f.status = 200
      if (boundary == "refinement") f.now = f.deadline() + 1
      assertTrue(runCatching { f.run() }.exceptionOrNull() is IOException)
      assertEquals(2, f.posts); assertArrayEquals(a, f.result("a").readBytes())
      assertArrayEquals(marker, f.marker().readBytes()); assertEquals(ledger, f.charges())
    }
  }
}
