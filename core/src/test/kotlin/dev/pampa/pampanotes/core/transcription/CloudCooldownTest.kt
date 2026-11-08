package dev.pampa.pampanotes.core.transcription

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudCooldownTest {
  @get:Rule val temp = TemporaryFolder()
  @Test fun `seconds HTTP date and long quotas round up without a daily cap`() {
    val now = 784111777000L // Sun, 06 Nov 1994 08:49:37 GMT
    assertEquals(now + 30L * 86_400_000, CloudRetry.parseRetryAfter("2592000", now))
    assertEquals(now + 1, CloudRetry.parseRetryAfter("0.0001", now))
    assertEquals(now + 86_400_000, CloudRetry.parseRetryAfter("Mon, 07 Nov 1994 08:49:37 GMT", now))
    assertNull(CloudRetry.parseRetryAfter("-1", now))
    assertNull(CloudRetry.parseRetryAfter("NaN", now))
    assertEquals(Long.MAX_VALUE, CloudRetry.parseRetryAfter("99999999999999999999999", now))
  }
  @Test fun `shared deadline survives restart and isolates provider account and key`() {
    val file = File(temp.root, "cooldowns.json")
    val key = CloudRetry.fingerprint("fixture-secret")
    val account = CloudRetry.fingerprint("fixture-account")
    val first = DurableCloudCooldowns(file)
    first.extend("mistral", key, account, 9999999999999)
    assertEquals(9999999999999L, first.extend("mistral", key, account, 10))
    val restarted = DurableCloudCooldowns(file)
    assertEquals(9999999999999L, restarted.deadline("mistral", key, account))
    assertNull(restarted.deadline("groq", key, account))
    assertNull(restarted.deadline("mistral", CloudRetry.fingerprint("another-key"), account))
    assertNull(restarted.deadline("mistral", key, CloudRetry.fingerprint("another-account")))
    assertFalse(file.readText().contains("fixture-secret"))
    assertFalse(file.readText().contains("fixture-account"))
  }
  @Test fun `installation job context survives restart without placing account or quota data in Room options`() {
    val file = File(temp.root, "private-job-contexts.json")
    val account = CloudRetry.fingerprint("fixture-account")
    val scope = CloudRetry.scope("mistral", CloudRetry.fingerprint("fixture-key"), account)
    val contexts = DurableCloudJobContexts(file)
    contexts.bind("job", account)
    contexts.setCooldown("job", scope, account)
    val restarted = DurableCloudJobContexts(file)
    assertEquals(CloudJobContext(account, scope), restarted.get("job"))
    assertEquals(account, restarted.bind("job", "replacement-account-hash").accountFingerprint)
    assertFalse(file.readText().contains("fixture-account"))
    assertFalse(file.readText().contains("fixture-key"))
    assertEquals(JobPhase.ProviderCooldown, JobPhase.parse("quota"))
  }

  @Test fun `two jobs share a quota without any second provider probe or sleep`() = runBlocking {
    var calls = 0
    val file = File(temp.root, "cooldown.json")
    val provider = object : TranscriptionProvider {
      override val id = "mistral"
      override val capabilities = TranscriptionCapabilities(null, false, false)
      override suspend fun listModels(): List<String> { calls++; throw TranscriptionError.RateLimited(2592000.0, "fixture") }
      override suspend fun health() = EndpointHealth(false, 0)
      override suspend fun transcribe(file: File, mime: String, request: TranscribeRequest, onProgress: (UploadProgress) -> Unit, onRemote: (RemoteProgress) -> Unit): TranscriptResult {
        calls++; throw TranscriptionError.RateLimited(2592000.0, "fixture")
      }
    }
    fun job(): CloudTranscriptionGuard {
      val disk = DurableCloudCooldowns(file)
      return CloudTranscriptionGuard(provider, beforeRequest = {
        val until = disk.deadline("mistral", "key-hash", "account-hash")
        if (until != null && until > System.currentTimeMillis()) throw TranscriptionError.RateLimited(null, "cooldown", until)
      }, limited = { error ->
        val at = disk.extend("mistral", "key-hash", "account-hash", CloudRetry.deadline(error, System.currentTimeMillis()))
        TranscriptionError.RateLimited(null, "cooldown", at)
      }) { false }
    }
    val first = runCatching { job().transcribe(File("unused"), "audio/wav", TranscribeRequest("m")) }.exceptionOrNull() as TranscriptionError.RateLimited
    val second = runCatching { job().listModels() }.exceptionOrNull() as TranscriptionError.RateLimited
    assertEquals(first.retryAtMillis, second.retryAtMillis)
    assertEquals(1, calls)
  }

  @Test fun `cancelled coroutine cannot upload or reserve`() = runBlocking {
    var reserved = false
    val provider = object : TranscriptionProvider {
      override val id = "mistral"
      override val capabilities = TranscriptionCapabilities(null, false, false)
      override suspend fun listModels() = emptyList<String>()
      override suspend fun health() = EndpointHealth(false, 0)
      override suspend fun transcribe(file: File, mime: String, request: TranscribeRequest, onProgress: (UploadProgress) -> Unit, onRemote: (RemoteProgress) -> Unit): TranscriptResult = error("Must not upload")
    }
    val guard = CloudTranscriptionGuard(provider, beforeUpload = { reserved = true; it }) { false }
    launch {
      currentCoroutineContext().cancel()
      try { guard.transcribe(File("unused"), "audio/wav", TranscribeRequest("m")); fail() }
      catch (_: CancellationException) { }
    }.join()
    assertFalse(reserved)
  }
}
