package dev.pampa.pampanotes.core.transcription

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.keys.SecretCipher
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampanotes.core.backup.BackupSettings
import dev.pampa.pampanotes.core.backup.providerId
import dev.pampa.pampanotes.core.backup.toBackup
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobType
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import io.mockk.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OpenRouterWiringTest {
  private val jobs = mockk<JobDao>()
  private val settings = mockk<PampaSettingsStore>()
  private val keys = mockk<AiKeyStore>()
  private fun repository() = TranscriptionRepository(jobs, mockk(), mockk(), mockk(), mockk(), mockk(),
    settings, keys, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk())

  @Test fun `provider model choice persists and backup remains compatible with old Groq settings`() = runTest {
    val data = object : DataStore<Preferences> {
      override val data = MutableStateFlow(emptyPreferences())
      override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
    }
    val cipher = object : SecretCipher {
      override fun encrypt(plain: String) = "sealed:$plain"
      override fun decrypt(blob: String) = blob.removePrefix("sealed:")
    }
    val store = PampaSettingsStore(data, cipher, cipher)
    store.setPreferredProvider(TranscriptionProviderId.OPENROUTER)
    store.setOpenRouterTranscriptionModel("google/gemini-3.5-transcribe")
    store.setGroqMaxUploadMb(100)
    store.setCustomOnly(true)
    val reopened = PampaSettingsStore(data, cipher, cipher).current()
    assertEquals(TranscriptionProviderId.OPENROUTER, reopened.preferredProvider)
    assertEquals(TranscriptionProviderId.CUSTOM, reopened.transcriptionProvider)
    assertEquals("google/gemini-3.5-transcribe", reopened.openRouterTranscriptionModel)
    assertEquals(100, reopened.groqMaxUploadMb)
    val backup = reopened.toBackup()
    assertEquals(TranscriptionProviderId.OPENROUTER, backup.providerId())
    assertEquals(reopened.openRouterTranscriptionModel, Json.decodeFromString<BackupSettings>(Json.encodeToString(backup)).openRouterTranscriptionModel)
    val old = Json.decodeFromString<BackupSettings>("""{"preferredProvider":"groq","endpointModel":"local"}""")
    assertEquals("", old.openRouterTranscriptionModel)
    assertEquals(TranscriptionProviderId.GROQ, old.providerId())
    assertEquals("local", old.endpointModel)
  }

  @Test fun `custom only routes all cloud transcription jobs but preserves refinement jobs`() = runTest {
    val prefs = PampaSettings(customOnly = true, preferredProvider = TranscriptionProviderId.OPENROUTER)
    coEvery { settings.current() } returns prefs
    val repo = repository()
    for (provider in TranscriptionProviderId.entries) {
      val job = JobEntity(id = "job", sessionId = "s", type = JobType.TRANSCRIBE,
        provider = provider.id, createdAt = 1, updatedAt = 1)
      assertEquals("custom", repo.effectiveProvider(job))
      assertEquals(provider.id, repo.effectiveProvider(job.copy(type = JobType.REFINE)))
    }
    assertEquals(setOf("groq", "openrouter", "mistral", "custom"), TranscriptionProviderId.entries.map { it.id }.toSet())
  }

  @Test fun `privacy migration preserves explicit cloud choices and moves ordinary jobs`() = runTest {
    val explicit = JobEntity(id = "explicit", sessionId = "s", type = JobType.TRANSCRIBE, provider = "groq", optionsJson = """{"overrides":{"provider":"groq"}}""", createdAt = 1, updatedAt = 1)
    val ordinary = explicit.copy(id = "ordinary", optionsJson = null)
    coEvery { jobs.movableTranscriptions("groq") } returns listOf(explicit, ordinary)
    coEvery { jobs.movableTranscriptions("openrouter") } returns emptyList()
    coEvery { jobs.movableTranscriptions("mistral") } returns emptyList()
    coEvery { jobs.moveTranscription("ordinary", "groq", "custom", null, any()) } returns 1
    coEvery { settings.current() } returns PampaSettings(customOnly = true)
    val repo = repository()
    assertEquals("groq", repo.effectiveProvider(explicit))
    assertEquals("custom", repo.effectiveProvider(ordinary))
    assertEquals(1, repo.moveCloudTranscriptionsToComputer())
    coVerify(exactly = 0) { jobs.moveTranscription("explicit", any(), any(), any(), any()) }
  }

  @Test fun `OpenRouter binding uses the shared secure key and no Groq key`() = runTest {
    coEvery { settings.current() } returns PampaSettings()
    coEvery { keys.key(ProviderId.OPENROUTER) } returns null
    assertNull(repository().bind("openrouter"))
    coEvery { keys.key(ProviderId.OPENROUTER) } returns "test-secret"
    assertEquals("openrouter", repository().bind("openrouter")?.provider?.id)
    coVerify(exactly = 0) { keys.key(ProviderId.GROQ) }
  }

  @Test fun `saved transcription model is used and a saved chat model is rejected before inference`() = runTest {
    val provider = mockk<TranscriptionProvider>()
    every { provider.id } returns "openrouter"
    coEvery { provider.listModels() } returns listOf("google/gemini-3.5-transcribe", "openai/whisper-1")
    val repo = repository()
    assertEquals("openai/whisper-1", repo.resolveModel(provider, PampaSettings(openRouterTranscriptionModel = "openai/whisper-1")))
    val error = runCatching { repo.resolveModel(provider, PampaSettings(openRouterTranscriptionModel = "some/chat")) }.exceptionOrNull()
    assertTrue(error is TranscriptionError.UnknownModel)
    coVerify(exactly = 0) { provider.transcribe(any(), any(), any(), any(), any()) }
  }

  @Test fun `turning on privacy prevents the next cloud chunk or retry from uploading`() = runTest {
    val provider = mockk<TranscriptionProvider>()
    every { provider.id } returns "openrouter"
    var customOnly = false
    coEvery { provider.transcribe(any(), any(), any(), any(), any()) } returns TranscriptResult("words", emptyList(), null, null)
    val guarded = CloudTranscriptionGuard(provider) { customOnly }
    guarded.transcribe(File("unused"), "audio/mp4", TranscribeRequest("m"))
    customOnly = true
    assertTrue(runCatching { guarded.transcribe(File("unused"), "audio/mp4", TranscribeRequest("m")) }.exceptionOrNull() is TranscriptionError.CompanionOnly)
    coVerify(exactly = 1) { provider.transcribe(any(), any(), any(), any(), any()) }
  }
}
