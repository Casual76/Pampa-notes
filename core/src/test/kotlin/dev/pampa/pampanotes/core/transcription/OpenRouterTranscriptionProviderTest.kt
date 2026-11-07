package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.testing.TinyHttpServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenRouterTranscriptionProviderTest {
  @get:Rule val temp = TemporaryFolder()
  private lateinit var server: TinyHttpServer
  private lateinit var provider: OpenRouterTranscriptionProvider
  private val json = Json

  @Before fun start() {
    server = TinyHttpServer()
    provider = OpenRouterTranscriptionProvider(TranscriptionHttp("test"), "test-key", server.url("/api/v1"))
  }
  @After fun stop() = server.close()
  private fun body(value: String) = json.parseToJsonElement(value)
  private fun audio() = temp.newFile("audio.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }

  @Test fun `catalogue requests transcription and rejects audio chat models and missing architecture`() = runBlocking {
    server.respond { TinyHttpServer.Response(200, """{"data":[
      {"id":"google/gemini-3.5-transcribe","architecture":{"output_modalities":["transcription"]}},
      {"id":"openai/whisper-1","architecture":{"output_modalities":["transcription"]}},
      {"id":"chat-with-audio","architecture":{"input_modalities":["audio"],"output_modalities":["text"]}},
      {"id":"fake-whisper-name"},
      {"id":"openai/whisper-1","architecture":{"output_modalities":["transcription"]}}
    ]}""") }
    assertEquals(listOf("google/gemini-3.5-transcribe", "openai/whisper-1"), provider.listModels())
    val request = server.requests.single()
    assertEquals("GET", request.method)
    assertEquals("/api/v1/models?output_modalities=transcription", request.path)
    assertEquals("Bearer test-key", request.header("Authorization"))
  }

  @Test fun `text models use json and omit ignored hints and companion options`() = runBlocking {
    server.respond { TinyHttpServer.Response(200, """{"text":"Il testo completo."}""") }
    val result = provider.transcribe(audio(), "audio/mp4", TranscribeRequest(
      "openai/gpt-4o-transcribe", language = "it", prompt = "A vocabulary hint", diarize = true,
    ))
    assertEquals("Il testo completo.", result.text)
    assertTrue(result.segments.isEmpty())
    val request = server.requests.single()
    assertEquals("POST", request.method)
    assertEquals("/api/v1/audio/transcriptions", request.path)
    assertEquals("Bearer test-key", request.header("Authorization"))
    val multipart = request.body.toString(Charsets.UTF_8)
    assertTrue(multipart.contains("name=\"response_format\"\r\n\r\njson"))
    assertTrue(multipart.contains("name=\"language\"\r\n\r\nit"))
    assertTrue(multipart.contains("name=\"file\"; filename=\"audio.m4a\""))
    assertFalse(multipart.contains("name=\"prompt\""))
    assertFalse(multipart.contains("timestamp_granularities"))
    assertFalse(multipart.contains("diarize"))
  }

  @Test fun `documented structured models request words and accept top level word times`() = runBlocking {
    server.respond { TinyHttpServer.Response(200, """{
      "text":"Ciao mondo.", "duration":2.0,
      "segments":[{"start":0,"end":2,"text":"Ciao mondo."}],
      "words":[{"start":0.2,"end":0.7,"word":"Ciao"},{"start":1,"end":1.8,"word":"mondo."}]
    }""") }
    val result = provider.transcribe(audio(), "audio/mp4", TranscribeRequest("microsoft/mai-transcribe-2"))
    assertEquals(2, result.segments.single().words.size)
    assertEquals(200L, result.segments.single().words.first().startMs)
    val request = server.requests.single().body.toString(Charsets.UTF_8)
    assertTrue(request.contains("name=\"response_format\"\r\n\r\nverbose_json"))
    assertTrue(request.contains("name=\"timestamp_granularities[]\"\r\n\r\nword"))
  }

  @Test fun `unsupported verbose is a terminal failure and never causes a second upload`() = runBlocking {
    server.respond { TinyHttpServer.Response(400, """{"error":{"message":"verbose_json unsupported"}}""") }
    val error = runCatching { provider.transcribe(audio(), "audio/mp4", TranscribeRequest("openai/whisper-1")) }.exceptionOrNull()
    assertTrue(error is TranscriptionError.Parse)
    assertFalse((error as TranscriptionError).retryable)
    assertEquals(1, server.requests.size)
  }

  @Test fun `text only and new models choose json without probing inference`() {
    for (model in listOf("openai/gpt-4o-transcribe", "microsoft/mai-transcribe-1.5", "google/gemini-3.5-transcribe", "future/model")) {
      assertEquals("json", OpenRouterTranscriptionProvider.responseFormat(model))
    }
    assertEquals("verbose_json", OpenRouterTranscriptionProvider.responseFormat("openai/whisper-large-v3"))
  }

  @Test fun `partially timed responses retain the full transcript without invented word times`() {
    val result = OpenRouterTranscriptionProvider.parseResponse(body("""{
      "text":"First sentence. Untimed ending.",
      "segments":[{"start":0,"end":2,"text":"First sentence."}]
    }"""))
    assertEquals("First sentence. Untimed ending.", result.text)
    assertTrue(result.segments.isEmpty())
  }

  @Test fun `words without segments keep real boundaries and malformed words remain untimed`() {
    val result = OpenRouterTranscriptionProvider.parseResponse(body("""{
      "text":"Ciao.","words":[{"word":"Ciao.","start":0.5,"end":1.2}]
    }"""))
    assertEquals(500L, result.segments.single().startMs)
    assertEquals(1200L, result.segments.single().words.single().endMs)
    val malformed = OpenRouterTranscriptionProvider.parseResponse(body("""{
      "text":"Still here.","words":[{"word":"Still","start":4,"end":1}]
    }"""))
    assertEquals("Still here.", malformed.text)
    assertTrue(malformed.segments.isEmpty())
  }

  @Test fun `partial word lists do not claim precise alignment for the whole segment`() {
    val result = OpenRouterTranscriptionProvider.parseResponse(body("""{
      "text":"Ciao mondo.",
      "segments":[{"text":"Ciao mondo.","start":0,"end":2}],
      "words":[{"word":"Ciao","start":0,"end":1}]
    }"""))
    val segment = result.segments.single()
    assertEquals(2000L, segment.endMs)
    assertTrue(segment.words.isEmpty())
    assertTrue(WordTimings.fill(segment.words, segment.text, segment.startMs, segment.endMs).estimated)
  }

  @Test fun `rate limit preserves retry after and auth is not retryable`() = runBlocking {
    server.respond { TinyHttpServer.Response(429, """{"error":{"message":"limited"}}""", mapOf("Retry-After" to "12")) }
    val error = runCatching { provider.transcribe(audio(), "audio/mp4", TranscribeRequest("m")) }.exceptionOrNull()
    assertEquals(12.0, (error as TranscriptionError.RateLimited).retryAfterSec)
    server.respond { TinyHttpServer.Response(401, """{"error":{"message":"Invalid API key"}}""") }
    val denied = runCatching { provider.listModels() }.exceptionOrNull() as TranscriptionError
    assertTrue(denied is TranscriptionError.Unauthorized)
    assertFalse(denied.retryable)
  }

  @Test fun `file cap is checked before network`() = runBlocking {
    val file = audio()
    java.io.RandomAccessFile(file, "rw").use { it.setLength(OpenRouterTranscriptionProvider.MAX_UPLOAD_BYTES + 1) }
    val error = runCatching { provider.transcribe(file, "audio/mp4", TranscribeRequest("m")) }.exceptionOrNull()
    assertTrue(error is TranscriptionError.FileTooLarge)
    assertTrue(server.requests.isEmpty())
  }

  @Test fun `insufficient credit is actionable and never retried automatically`() = runBlocking {
    server.respond { TinyHttpServer.Response(402, """{"error":{"message":"Insufficient credits"}}""") }
    val error = runCatching { provider.transcribe(audio(), "audio/mp4", TranscribeRequest("m")) }.exceptionOrNull()
    assertTrue(error is TranscriptionError.CreditRequired)
    assertFalse((error as TranscriptionError).retryable)
    assertEquals(1, server.requests.size)
  }

  @Test fun `cancellation interrupts waiting for OpenRouter response`() = runBlocking {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    server.respond {
      entered.countDown()
      release.await(5, TimeUnit.SECONDS)
      TinyHttpServer.Response(200, """{"text":"done"}""")
    }
    val pending = async { provider.transcribe(audio(), "audio/mp4", TranscribeRequest("model")) }
    try {
      withTimeout(3000) { while (entered.count > 0) delay(10) }
      pending.cancel()
      assertTrue(runCatching { withTimeout(2000) { pending.await() } }.exceptionOrNull() is CancellationException)
    } finally { release.countDown() }
  }
}
