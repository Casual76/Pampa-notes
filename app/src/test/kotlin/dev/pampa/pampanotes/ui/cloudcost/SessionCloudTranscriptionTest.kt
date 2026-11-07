package dev.pampa.pampanotes.ui.cloudcost

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.*
import dev.pampa.pampanotes.core.spending.*
import dev.pampa.pampanotes.core.transcription.TranscribeOverrides
import dev.pampa.pampanotes.work.WorkScheduler
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SessionCloudTranscriptionTest {
  private val settings = mockk<PampaSettingsStore>()
  private val spending = mockk<CloudSpending>()
  private val repository = mockk<TranscriptionRepository>()
  private val scheduler = mockk<WorkScheduler>(relaxed = true)
  private val action = SessionCloudTranscription(settings, spending, repository, scheduler)
  private fun preflight(reason: String? = null) = CloudPreflight("openrouter", "openai/whisper-1", 60_000,
    EffectiveCloudPolicy(true), CostEstimate(), SpendingTotals("0", "0", 0, "1"), reason)
  private fun job(provider: String = "openrouter", state: JobState = JobState.QUEUED, code: String? = null) =
    JobEntity("job", "s", JobType.TRANSCRIBE, provider, state = state, errorCode = code, createdAt = 1, updatedAt = 1)
  private fun cloud() { coEvery { settings.current() } returns PampaSettings(preferredProvider = TranscriptionProviderId.OPENROUTER) }

  @Test fun `primary cloud action checks preflight before enqueue and schedules only the returned queue`() = runTest {
    cloud()
    coEvery { spending.preflight("s", "openrouter") } returns preflight()
    coEvery { repository.enqueue("s", TranscriptionProviderId.OPENROUTER, null) } returns job()
    assertNull(action.enqueue("s", null))
    coVerifyOrder { spending.preflight("s", "openrouter"); repository.enqueue("s", TranscriptionProviderId.OPENROUTER, null); scheduler.kick("openrouter") }
  }
  @Test fun `options action preserves job overrides and denied budget never enqueues`() = runTest {
    cloud()
    coEvery { spending.preflight("s", "openrouter") } returns preflight("cloud_budget_exceeded")
    assertEquals("cloud_budget_exceeded", action.enqueue("s", TranscribeOverrides(language = "en")))
    coVerify(exactly = 0) { repository.enqueue(any(), any(), any()) }
    coVerify(exactly = 0) { scheduler.kick(any()) }
    val options = TranscribeOverrides(language = "en", vocabulary = "Names")
    coEvery { spending.preflight("s", "openrouter") } returns preflight()
    coEvery { repository.enqueue("s", TranscriptionProviderId.OPENROUTER, options) } returns job()
    assertNull(action.enqueue("s", options))
    coVerify { repository.enqueue("s", TranscriptionProviderId.OPENROUTER, options) }
  }
  @Test fun `stale capped preflight refreshes metadata and rechecks before enqueue`() = runTest {
    cloud()
    coEvery { spending.preflight("s", "openrouter") } returnsMany listOf(preflight("cloud_price_unknown"), preflight())
    coEvery { spending.refresh("openai/whisper-1") } just Runs
    coEvery { repository.enqueue("s", TranscriptionProviderId.OPENROUTER, null) } returns job()
    assertNull(action.enqueue("s", null))
    coVerifyOrder { spending.preflight("s", "openrouter"); spending.refresh("openai/whisper-1"); spending.preflight("s", "openrouter"); repository.enqueue("s", TranscriptionProviderId.OPENROUTER, null) }
  }
  @Test fun `a policy race at central enqueue remains visible and does not wake a paid queue`() = runTest {
    cloud()
    coEvery { spending.preflight("s", "openrouter") } returns preflight()
    coEvery { repository.enqueue("s", TranscriptionProviderId.OPENROUTER, null) } returns job(state = JobState.FAILED, code = "cloud_folder_blocked")
    assertEquals("cloud_folder_blocked", action.enqueue("s", null))
    coVerify(exactly = 0) { scheduler.kick(any()) }
  }
  @Test fun `companion action remains available without cloud pricing checks`() = runTest {
    coEvery { settings.current() } returns PampaSettings(customOnly = true)
    coEvery { repository.enqueue("s", TranscriptionProviderId.CUSTOM, null) } returns job("custom")
    assertNull(action.enqueue("s", null))
    coVerify(exactly = 0) { spending.preflight(any(), any()) }
    coVerify { scheduler.kick("custom") }
  }
}
