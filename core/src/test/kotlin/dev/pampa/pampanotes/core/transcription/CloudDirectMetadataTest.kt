package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.testing.CloudHttpFixture
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudDirectMetadataTest {
  @get:Rule val temp = TemporaryFolder()

  @Test fun `direct setup catalogues share durable job quota across a reopened HTTP client`() = runBlocking {
    for ((provider, url) in listOf("openrouter" to OpenRouterTranscriptionProvider.BASE_URL,
      "groq" to GroqWhisperProvider.BASE_URL, "mistral" to "https://api.mistral.ai/v1")) {
      val f = CloudHttpFixture(File(temp.root, "$provider.json"))
      f.code = 429
      f.retryAfter = "172800.125"
      suspend fun setup() = when (provider) {
        "openrouter" -> CloudSetupMetadata(f.http()).openRouter(f.key)
        "groq" -> CloudSetupMetadata(f.http()).groq(f.key)
        else -> CloudSetupMetadata(f.http()).mistral(f.key).models
      }
      assertTrue(runCatching { setup() }.exceptionOrNull() is TranscriptionError.RateLimited)
      val at = f.deadline(provider)!!
      assertTrue(at > System.currentTimeMillis() + 172_799_000)
      assertTrue(runCatching { setup() }.exceptionOrNull() is TranscriptionError.RateLimited)
      assertEquals(1, f.requests.size)
      // Health, /key and /models use the identical scope, even when called directly.
      assertTrue(runCatching { f.http().getJson("$url/key", f.headers()) }.exceptionOrNull() is TranscriptionError.RateLimited)
      assertEquals(1, f.requests.size)
      assertEquals(at, f.deadline(provider))
    }
  }

  @Test fun `HTTP date quotas persist and new key account scopes remain independent`() = runBlocking {
    val f = CloudHttpFixture(File(temp.root, "scopes.json"))
    val at = System.currentTimeMillis() + 7 * 86_400_000L
    f.retryAfter = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC))
    f.code = 429
    val url = "${OpenRouterTranscriptionProvider.BASE_URL}/key"
    assertTrue(runCatching { f.http().getJson(url, f.headers()) }.exceptionOrNull() is TranscriptionError.RateLimited)
    val saved = f.deadline("openrouter")!!
    assertEquals(at / 1000 * 1000, saved)
    f.code = 200
    f.key = "key-B"
    f.http().getJson(url, f.headers())
    f.key = "fixture-key"
    f.account = "account-B"
    f.http().getJson(url, f.headers())
    f.account = "account-A"
    assertTrue(runCatching { f.http().getJson(url, f.headers()) }.exceptionOrNull() is TranscriptionError.RateLimited)
    assertEquals(3, f.requests.size)
    assertEquals(saved, f.deadline("openrouter"))
  }

  @Test fun `cancel after observing 429 preserves deadline before returning cancellation`() = runBlocking {
    val f = CloudHttpFixture(File(temp.root, "cancel.json"))
    f.code = 429
    f.retryAfter = "3600"
    val work = launch {
      val own = currentCoroutineContext()[Job]!!
      f.beforeCode = { own.cancel() }
      f.http().getJson("${OpenRouterTranscriptionProvider.BASE_URL}/key", f.headers())
    }
    work.join()
    assertTrue(work.isCancelled)
    assertTrue(f.deadline("openrouter")!! > System.currentTimeMillis() + 3_599_000)
    f.beforeCode = {}
    assertTrue(runCatching { f.http().getJson("${OpenRouterTranscriptionProvider.BASE_URL}/models", f.headers()) }.exceptionOrNull() is TranscriptionError.RateLimited)
    assertEquals(1, f.requests.size)
  }

  @Test fun `account key switch or cancellation between metadata requests sends no next request`() = runBlocking {
    for (change in listOf("account", "key", "cancel")) {
      val f = CloudHttpFixture(File(temp.root, "$change.json"))
      val http = f.http()
      val url = OpenRouterTranscriptionProvider.BASE_URL
      val work = async {
        runCatching {
          http.cloudSession(url, f.headers()) {
            http.getJson("$url/key", f.headers())
            when (change) {
              "account" -> f.account = "account-B"
              "key" -> f.key = "key-B"
              else -> currentCoroutineContext()[Job]!!.cancel()
            }
            http.getJson("$url/models", f.headers())
          }
        }.exceptionOrNull()
      }
      val error = try { work.await() } catch (e: CancellationException) { e }
      if (change == "cancel") assertTrue(error is CancellationException)
      else assertEquals("cloud_${change}_changed", (error as TranscriptionError).code)
      assertEquals(1, f.requests.size)
    }
  }

  @Test fun `corrupt quota storage fails closed before even constructing a connection`() = runBlocking {
    val file = File(temp.root, "corrupt.json").apply { writeText("broken") }
    val f = CloudHttpFixture(file)
    assertNotNull(runCatching { f.http().getJson("${OpenRouterTranscriptionProvider.BASE_URL}/key", f.headers()) }.exceptionOrNull())
    assertTrue(f.requests.isEmpty())
  }

  @Test fun `setup catalogue 429 after successful key check blocks every HTTP method before connection`() = runBlocking {
    val f = CloudHttpFixture(File(temp.root, "catalogue.json"))
    f.onRequest = { url -> f.code = if (url.path.endsWith("/models")) 429 else 200 }
    f.retryAfter = "3600"
    val url = OpenRouterTranscriptionProvider.BASE_URL
    assertTrue(runCatching { CloudSetupMetadata(f.http()).openRouter(f.key) }.exceptionOrNull() is TranscriptionError.RateLimited)
    assertEquals(2, f.requests.size)
    val http = f.http()
    val calls: List<suspend () -> Any?> = listOf(
      { http.getJson("$url/key", f.headers()) },
      { http.postJson("$url/audio/transcriptions", f.headers(), "{}") },
      { http.postForm("$url/audio/transcriptions", f.headers(), emptyMap(), 1000) },
      { http.delete("$url/request", f.headers(), 1000) },
      { http.postAudio("$url/audio/transcriptions", f.headers(), emptyMap(), File(temp.root, "never-read.bin"), "application/octet-stream", readTimeoutMillis = 1000) },
    )
    for (call in calls) assertTrue(runCatching { call() }.exceptionOrNull() is TranscriptionError.RateLimited)
    assertEquals(2, f.requests.size)
  }
}
