package dev.pampa.pampanotes.core.spending

import android.content.Context
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.settings.*
import dev.pampa.pampanotes.core.testing.CloudHttpFixture
import dev.pampa.pampanotes.core.transcription.*
import io.mockk.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudDirectRefreshTest {
  @get:Rule val temp = TemporaryFolder()
  private val model = "openai/whisper-1"
  private val prefs = PampaSettings(preferredProvider = TranscriptionProviderId.MISTRAL,
    openRouterTranscriptionModel = model, mistralTranscriptionModel = "voxtral-small-transcribe-3")

  private fun spending(f: CloudHttpFixture): CloudSpending {
    val context = mockk<Context> { every { noBackupFilesDir } returns temp.root }
    val settings = mockk<PampaSettingsStore> { coEvery { current() } returns prefs }
    val keys = mockk<AiKeyStore> { coEvery { key(ProviderId.OPENROUTER) } answers { f.key } }
    val credentials = mockk<CloudCredentials> { coEvery { key("openrouter") } answers { f.key } }
    val db = mockk<PampaDatabase>()
    val folders = mockk<FolderDao> { coEvery { all() } returns listOf(FolderEntity("root", "Root", createdAt = 1, updatedAt = 1)) }
    val parts = mockk<AudioPartDao>()
    coEvery { parts.bySession(any()) } returns emptyList()
    coEvery { parts.get(any()) } returns null
    val sessions = mockk<SessionDao>()
    coEvery { sessions.get(any()) } returns SessionEntity("session", "note", date = "2026-11-08", position = 0, createdAt = 1, updatedAt = 1)
    val notes = mockk<NoteDao>()
    coEvery { notes.get(any()) } returns NoteEntity("note", "root", "Note", createdAt = 1, updatedAt = 1)
    every { db.folders() } returns folders
    every { db.audioParts() } returns parts
    every { db.sessions() } returns sessions
    every { db.notes() } returns notes
    return CloudSpending(CloudCostStore(context), db, settings, keys, credentials, f.http())
  }

  private fun respond(f: CloudHttpFixture, limited: String? = null) {
    f.onRequest = { url ->
      f.code = if (url.path.endsWith(limited ?: "never")) 429 else 200
      f.retryAfter = "7200"
      f.body = when {
        url.path.endsWith("/key") -> """{"data":{"limit":10,"usage":0}}"""
        url.path.endsWith("/endpoints") -> """{"data":{"endpoints":[{"pricing":{"prompt":"0.001","completion":"0"}}]}}"""
        else -> """{"data":[{"id":"$model","architecture":{"output_modalities":["transcription"]},"pricing":{"unit":"per_second"}}]}"""
      }
    }
  }

  @Test fun `costs refresh always queries configured OpenRouter model with OR key while Mistral is selected`() = runBlocking {
    val f = CloudHttpFixture(File(temp.root, "routing.json"))
    respond(f)
    val spending = spending(f)
    spending.refresh()
    assertEquals(listOf("/api/v1/key", "/api/v1/models", "/api/v1/models/$model/endpoints"), f.requests.map { it.path })
    assertTrue(f.requests.all { it.host == "openrouter.ai" })
    assertEquals(model, spending.data().prices.keys.single())
    assertFalse(spending.data().prices.containsKey(prefs.mistralTranscriptionModel))
    assertEquals(OpenRouterCostMetadata.fingerprint(f.key), spending.currentQuota()!!.fingerprint)
  }

  @Test fun `direct refresh quotas stop every later refresh and preflight upload preparation after reopen`() = runBlocking {
    for (path in listOf("/key", "/models", "/endpoints")) {
      val f = CloudHttpFixture(File(temp.root, "${path.drop(1)}.json"))
      respond(f, path)
      val spending = spending(f)
      spending.configure(CloudCostSettings(unknownAction = UnknownCostAction.ALLOW))
      assertTrue(runCatching { spending.refresh() }.exceptionOrNull() is TranscriptionError.RateLimited)
      val count = f.requests.size
      assertTrue(f.deadline("openrouter")!! > System.currentTimeMillis() + 7_199_000)
      val reopened = spending(f)
      assertTrue(runCatching { reopened.refresh(model) }.exceptionOrNull() is TranscriptionError.RateLimited)
      // The session sheet/preflight path is permitted to show unknown costs. Upload preparation
      // must still rethrow quota even with the user's unknown-cost ALLOW preference.
      assertNull(reopened.preflight("session", "openrouter").estimate.maxUsd)
      val request = TranscribeRequest(model, cloudSessionId = "session", cloudDurationMs = 60_000)
      assertTrue(runCatching { reopened.beforeUpload("openrouter", CloudRetry.fingerprint(f.key), request) }.exceptionOrNull() is TranscriptionError.RateLimited)
      assertEquals(count, f.requests.size)
      assertTrue(reopened.data().charges.isEmpty())
    }
  }

  @Test fun `known existing job quota prevents direct refresh and setup from constructing any connection`() = runBlocking {
    val file = File(temp.root, "existing.json")
    val f = CloudHttpFixture(file)
    DurableCloudCooldowns(file).extend("openrouter", CloudRetry.fingerprint(f.key), CloudRetry.fingerprint(f.account), System.currentTimeMillis() + 3600000)
    assertTrue(runCatching { spending(f).refresh() }.exceptionOrNull() is TranscriptionError.RateLimited)
    assertTrue(runCatching { CloudSetupMetadata(f.http()).openRouter(f.key) }.exceptionOrNull() is TranscriptionError.RateLimited)
    assertTrue(f.requests.isEmpty())
  }

  @Test fun `quota write failure cannot become unknown price permission for a paid upload`() = runBlocking {
    val file = File(temp.root, "write-failure.json")
    val f = CloudHttpFixture(file)
    respond(f, "/key")
    f.beforeCode = { File(temp.root, "${file.name}.tmp").mkdirs() }
    val spending = spending(f)
    spending.configure(CloudCostSettings(unknownAction = UnknownCostAction.ALLOW))
    val error = runCatching {
      spending.beforeUpload("openrouter", CloudRetry.fingerprint(f.key), TranscribeRequest(model, cloudSessionId = "session"))
    }.exceptionOrNull() as TranscriptionError
    assertEquals("cloud_cost_storage", error.code)
    assertEquals(1, f.requests.size)
    assertTrue(spending.data().charges.isEmpty())
  }

  @Test fun `genuine metadata unavailability retains explicit unknown cost allow policy`() = runBlocking {
    val f = CloudHttpFixture(File(temp.root, "unavailable.json"))
    f.code = 503
    val spending = spending(f)
    spending.configure(CloudCostSettings(unknownAction = UnknownCostAction.ALLOW))
    val request = spending.beforeUpload("openrouter", CloudRetry.fingerprint(f.key), TranscribeRequest(model, cloudSessionId = "session", cloudDurationMs = 60_000))
    assertEquals(model, request.model)
    assertNull(spending.data().charges.single().reservedUsd)
    assertNull(spending.data().charges.single().actualUsd)
    assertNull(f.deadline("openrouter"))
    assertTrue(f.requests.all { it.host == "openrouter.ai" })
    request.cloudAttemptFinished?.invoke()
    Unit
  }
}
