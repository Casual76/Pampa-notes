package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.repo.FailedJobs
import dev.pampa.pampanotes.core.repo.TranscribeOptions
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.*
import dev.pampa.pampanotes.core.spending.CloudSpending
import dev.pampa.pampanotes.core.spending.EffectiveCloudPolicy
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CloudJobPinningTest {
  @get:Rule val temp = TemporaryFolder()
  private val jobs = mockk<JobDao>()
  private val sessions = mockk<SessionDao>()
  private val settings = mockk<PampaSettingsStore>()
  private val spending = mockk<CloudSpending>()
  private val credentials = mockk<CloudCredentials>()
  private val cooldowns = mockk<CloudCooldownStore>()
  private val saved = mutableMapOf<String, JobEntity>()
  private var prefs = PampaSettings(preferredProvider = TranscriptionProviderId.MISTRAL, syncAccount = "fixture-account")
  private fun repository(): TranscriptionRepository {
    every { cooldowns.contexts } returns DurableCloudJobContexts(java.io.File(temp.root, "job-contexts.json"))
    coEvery { settings.current() } answers { prefs }
    coEvery { jobs.activeForSession(any()) } returns null
    coEvery { sessions.get(any()) } returns null
    coEvery { spending.policyForSession(any(), any(), any()) } returns EffectiveCloudPolicy(true)
    coEvery { jobs.upsert(any()) } answers { val job = firstArg<JobEntity>(); saved[job.id] = job }
    coEvery { jobs.get(any()) } answers { saved[firstArg()] }
    coEvery { jobs.retry(any(), any(), any(), any()) } answers {
      val old = saved[firstArg<String>()]!!
      saved[old.id] = old.copy(provider = secondArg(), optionsJson = thirdArg(), state = JobState.QUEUED)
      1
    }
    return TranscriptionRepository(jobs, sessions, mockk(), mockk(), mockk(), mockk(), settings, mockk(),
      mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), spending, credentials, cooldowns)
  }

  @Test fun `first automatic job pins provider model and plan despite changed defaults then retry`() = runTest {
    val repo = repository()
    val job = repo.enqueue("s", prefs.transcriptionProvider)!!
    assertEquals("mistral", job.provider)
    assertEquals("voxtral-mini-transcribe-3", job.model)
    prefs = prefs.copy(preferredProvider = TranscriptionProviderId.OPENROUTER, mistralTranscriptionModel = "voxtral-small-transcribe-3", chunkMinutes = 3)
    assertEquals(job, repo.pinForRun(job))
    saved[job.id] = job.copy(state = JobState.FAILED, errorCode = "rate_limited")
    val retry = repo.retry(job.id)!!
    assertEquals("mistral", retry.provider)
    assertEquals(job.model, retry.model)
    assertEquals(10, TranscribeOptions.decode(retry.optionsJson).cloudChunkMinutes)
    assertFalse(FailedJobs.discardsWorkOnRetry(retry.copy(errorCode = "rate_limited")))
  }

  @Test fun `manual provider and model are authoritative and survive retry`() = runTest {
    val repo = repository()
    val overrides = TranscribeOverrides(provider = "mistral", model = "voxtral-small-transcribe-3", language = "en")
    val job = repo.enqueue("s", TranscriptionProviderId.MISTRAL, overrides)!!
    prefs = prefs.copy(customOnly = true, mistralTranscriptionModel = "voxtral-mini-transcribe-3")
    saved[job.id] = job.copy(state = JobState.FAILED, errorCode = "credit_required")
    val retry = repo.retry(job.id)!!
    assertEquals("mistral", retry.provider)
    assertEquals("voxtral-small-transcribe-3", retry.model)
    assertEquals(overrides, repo.overridesOf(retry))
    assertFalse(FailedJobs.discardsWorkOnRetry(retry))
  }

  @Test fun `account change blocks resumed cloud work and privacy cannot reroute to paid provider`() = runTest {
    val repo = repository()
    val job = repo.enqueue("s", TranscriptionProviderId.MISTRAL)!!
    prefs = prefs.copy(syncAccount = "different-account")
    assertEquals("cloud_account_changed", (runCatching { repo.pinForRun(job) }.exceptionOrNull() as TranscriptionError).code)
    prefs = prefs.copy(customOnly = true)
    assertEquals("custom", repo.effectiveProvider(job))
    for (provider in TranscriptionProviderId.entries) {
      assertEquals("custom", prefs.providerForJob(provider.id, transcription = true))
    }
  }

  @Test fun `startup and retry wakes honor shared quota but replacement key and account do not inherit it`() = runTest {
    val repo = repository()
    val job = repo.enqueue("s", TranscriptionProviderId.MISTRAL)!!
    val disk = DurableCloudCooldowns(java.io.File(temp.root, "cooldowns.json"))
    every { cooldowns.durable } returns disk
    coEvery { jobs.queued("mistral") } answers { listOf(saved[job.id]!!) }
    coEvery { credentials.key("mistral") } returns "first-key"
    val account = CloudRetry.fingerprint(prefs.syncAccount)
    val at = System.currentTimeMillis() + 30L * 86_400_000
    disk.extend("mistral", CloudRetry.fingerprint("first-key"), account, at)
    assertEquals(at, repo.waitUntil(job))
    assertFalse(repo.readyToWake("mistral"))
    cooldowns.contexts.setCooldown(job.id, CloudRetry.scope("mistral", CloudRetry.fingerprint("first-key"), account), account)
    saved[job.id] = job.copy(phase = JobPhase.ProviderCooldown.encode())
    assertFalse(saved[job.id]!!.optionsJson.orEmpty().contains("Fingerprint"))
    assertFalse(saved[job.id]!!.optionsJson.orEmpty().contains("cooldownScope"))
    assertFalse(saved[job.id]!!.phase.orEmpty().contains(at.toString()))
    coEvery { credentials.key("mistral") } returns "replacement-key"
    assertTrue(repo.readyToWake("mistral"))
    prefs = prefs.copy(syncAccount = "replacement-account")
    assertNull(repo.waitUntil(saved[job.id]!!))
    assertEquals("cloud_account_changed", (runCatching { repo.pinForRun(saved[job.id]!!) }.exceptionOrNull() as TranscriptionError).code)
  }

  @Test fun `automatic first transcription pins every cloud service model without catalogue or inference probes`() = runTest {
    prefs = prefs.copy(groqTranscriptionModel = "whisper-large-v3", openRouterTranscriptionModel = "fixture/transcribe", mistralTranscriptionModel = "voxtral-small-transcribe-3")
    val repo = repository()
    val choices = mapOf(TranscriptionProviderId.GROQ to "whisper-large-v3", TranscriptionProviderId.OPENROUTER to "fixture/transcribe", TranscriptionProviderId.MISTRAL to "voxtral-small-transcribe-3")
    for ((provider, model) in choices) {
      val job = repo.enqueue("s", provider)!!
      assertEquals(provider.id, job.provider)
      assertEquals(model, job.model)
    }
    coVerify(exactly = 0) { credentials.key(any()) }
  }
}
