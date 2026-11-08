package dev.pampa.pampanotes.work

import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.*
import dev.pampa.pampanotes.core.spending.CloudSpending
import dev.pampa.pampanotes.core.transcription.*
import io.mockk.*
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual worker pre-start body + repository/guard; no WorkManager or Android runtime. */
class PreStartQueueOwnershipRegressionTest {
  @get:Rule val temp = TemporaryFolder()

  private inner class Fixture {
    val jobs = mockk<JobDao>()
    val sessions = mockk<SessionDao>()
    val transcripts = mockk<TranscriptDao>()
    val parts = mockk<AudioPartDao>()
    val settings = mockk<PampaSettingsStore>()
    val keys = mockk<AiKeyStore>()
    val http = mockk<TranscriptionHttp>()
    val cooldowns = mockk<CloudCooldownStore>()
    val scheduler = mockk<WorkScheduler>(relaxed = true)
    val runner = mockk<TranscriptionRunner>(relaxed = true)
    val old = JobEntity("job", "session", JobType.TRANSCRIBE, "openrouter", model = "chosen/model",
      optionsJson = """{"cloudChunkMinutes":10,"groqMaxUploadMb":25}""", createdAt = 1, updatedAt = 1)
    var row = old
    var prefs = PampaSettings(syncAccount = "fixture-account")
    val entered = CompletableDeferred<Unit>()
    val resume = CompletableDeferred<Unit>()
    var failParts = false
    val repository: TranscriptionRepository
    val worker: TranscriptionQueueWorker
    init {
      coEvery { settings.current() } answers { prefs }
      coEvery { jobs.get("job") } answers { row }
      coEvery { sessions.get("session") } returns null
      coEvery { transcripts.bySession("session") } returns emptyList()
      coEvery { keys.key(ProviderId.OPENROUTER) } returns "synthetic-key"
      every { cooldowns.contexts } returns DurableCloudJobContexts(File(temp.newFolder(), "contexts.json"))
      val part = mockk<AudioPartEntity> { every { id } returns "part" }
      coEvery { parts.bySession("session") } coAnswers {
        entered.complete(Unit)
        resume.await() // Actual suspension after bind, before validatePinnedModel.
        if (failParts) throw IOException("synthetic pre-start DB failure")
        listOf(part)
      }
      repository = TranscriptionRepository(jobs, sessions, mockk(), parts, transcripts, mockk(), settings, keys, http,
        mockk(), mockk(), mockk(), mockk(), mockk(), mockk<AppFiles>(), mockk<CloudSpending>(), mockk(), cooldowns)
      // Bypass CoroutineWorker's Android constructor; invoke only the real suspend body under test.
      val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
      worker = (field.get(null) as sun.misc.Unsafe).allocateInstance(TranscriptionQueueWorker::class.java) as TranscriptionQueueWorker
      for ((name, value) in mapOf("repository" to repository, "settingsStore" to settings, "scheduler" to scheduler, "runner" to runner))
        worker.javaClass.getDeclaredField(name).apply { isAccessible = true; set(worker, value) }
    }
    suspend fun run() {
      val method = worker.javaClass.declaredMethods.single { it.name == "transcribe" }.apply { isAccessible = true }
      suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
        try { method.invoke(worker, old, continuation) }
        catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
      }
    }
    fun verifyNoDispatchOrFailure() {
      coVerify(exactly = 0) { jobs.startOwned(any(), any(), any(), any(), any(), any(), any(), any()) }
      coVerify(exactly = 0) { jobs.fail(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
      coVerify(exactly = 0) { jobs.requeueOwned(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
      coVerify(exactly = 0) { http.getJson(any(), any(), any()) }
      coVerify(exactly = 0) { http.postAudio(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
      coVerify(exactly = 0) { runner.cleanUp(any()) }
    }
  }

  @Test fun `privacy migration during parts suspension retains and wakes authoritative custom queue`() = runTest {
    val f = Fixture()
    val work = async { f.run() }
    f.entered.await()
    f.prefs = f.prefs.copy(customOnly = true)
    f.row = f.old.copy(provider = "custom", model = null)
    f.resume.complete(Unit)
    work.await() // CompanionOnly is recovered inside the worker, not escaped to generic fail.
    assertEquals("custom", f.row.provider); assertEquals(JobState.QUEUED, f.row.state); assertNull(f.row.model)
    assertEquals(f.old.optionsJson, f.row.optionsJson)
    verify(exactly = 1) { f.scheduler.kick("custom") }
    f.verifyNoDispatchOrFailure()
  }

  @Test fun `generic pre-start failure cannot fail migrated or edited or newly started dispatch`() = runTest {
    for (change in listOf("provider", "model", "options", "started")) {
      val f = Fixture().also { it.failParts = true }
      val work = async { f.run() }
      f.entered.await()
      f.row = when (change) {
        "provider" -> f.old.copy(provider = "custom", model = null)
        "model" -> f.old.copy(model = "corrected/model")
        "options" -> f.old.copy(optionsJson = """{"cloudChunkMinutes":5,"groqMaxUploadMb":25}""")
        else -> f.old.copy(state = JobState.PREPARING, attempts = 1)
      }
      val authoritative = f.row
      f.resume.complete(Unit); work.await()
      assertEquals(authoritative, f.row)
      if (change != "started") verify(exactly = 1) { f.scheduler.kick(authoritative.provider) }
      else verify(exactly = 0) { f.scheduler.kick(any()) }
      f.verifyNoDispatchOrFailure()
    }
  }

  @Test fun `migration after error ownership read is fenced by production failure caller arguments`() = runTest {
    val f = Fixture()
    coEvery { f.jobs.fail(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
      assertEquals(f.old.provider, arg<String>(4)); assertEquals(f.old.model, arg<String?>(5))
      assertEquals(f.old.optionsJson, arg<String?>(6)); assertEquals(f.old.attempts, arg<Int>(7)); assertTrue(arg<Boolean>(8))
      f.row = f.old.copy(provider = "custom", model = null)
      0 // Production SQL's zero-row CAS result; exact SQL is tested separately in SQLite.
    }
    assertEquals(TranscriptionRepository.FailOutcome.SUPERSEDED, f.repository.fail(f.old, TranscriptionError.Parse("fixture")))
    assertEquals(JobState.QUEUED, f.row.state)
    coVerify(exactly = 0) { f.jobs.markCancelled(any(), any()) }
  }
}
