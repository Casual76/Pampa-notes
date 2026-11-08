package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.testing.TinyHttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MistralTranscriptionProviderTest {
  @get:Rule val temp = TemporaryFolder()
  @Test fun `direct multipart requests documented segment field and preserves complete source`() = runBlocking {
    val server = TinyHttpServer()
    try {
      server.respond { TinyHttpServer.Response(200, """{"model":"voxtral-mini-transcribe-3","text":"  Ciao, mondo!\nCome va?  ","language":"it","segments":[{"text":" Ciao, mondo!","start":0.1,"end":1.2,"words":[{"word":"Ciao","start":0.1,"end":0.2}]}]}""") }
      val audio = temp.newFile("fixture.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
      val provider = MistralTranscriptionProvider(TranscriptionHttp("fixture"), "fake-key", server.url("/v1"))
      val result = provider.transcribe(audio, "audio/wav", TranscribeRequest(MistralTranscriptionProvider.DEFAULT_MODEL))
      assertEquals("  Ciao, mondo!\nCome va?  ", result.text)
      assertEquals("it", result.language)
      assertEquals(100L, result.segments.single().startMs)
      assertTrue(result.segments.single().words.isEmpty())
      val request = server.requests.single()
      assertEquals("POST", request.method)
      assertEquals("/v1/audio/transcriptions", request.path)
      assertEquals("Bearer fake-key", request.header("Authorization"))
      val fields = request.body.toString(Charsets.UTF_8)
      assertTrue(fields.contains("name=\"timestamp_granularities\"\r\n\r\nsegment"))
      assertTrue(fields.contains("name=\"return_language\"\r\n\r\ntrue"))
      assertFalse(fields.contains("timestamp_granularities[]"))
      assertFalse(fields.contains("\r\n\r\nword"))
      assertFalse(fields.contains("response_format"))
    } finally { server.close() }
  }

  @Test fun `explicit language and second documented model never use word timings`() {
    val fields = MistralTranscriptionProvider.fields(TranscribeRequest("voxtral-small-transcribe-3", language = "en", prompt = "Pampa"))
    assertEquals("en", fields["language"])
    assertEquals("Pampa", fields["context_bias"])
    assertFalse(fields.containsKey("return_language"))
    assertEquals("segment", fields["timestamp_granularities"])
  }

  @Test fun `setup verification only reads models and never transcribes`() = runBlocking {
    val server = TinyHttpServer()
    try {
      server.respond { TinyHttpServer.Response(200, """{"data":[{"id":"voxtral-mini-transcribe-3"},{"id":"chat"}]}""") }
      val health = MistralTranscriptionProvider(TranscriptionHttp("fixture"), "fake", server.url("/v1")).health()
      assertEquals(listOf("voxtral-mini-transcribe-3"), health.models)
      assertEquals("GET", server.requests.single().method)
      assertEquals("/v1/models", server.requests.single().path)
    } finally { server.close() }
  }

  @Test fun `auth credit rate and unavailable responses never upload to another endpoint`() = runBlocking {
    for ((status, type) in listOf(401 to TranscriptionError.Unauthorized::class.java, 402 to TranscriptionError.CreditRequired::class.java,
      429 to TranscriptionError.RateLimited::class.java, 503 to TranscriptionError.Server::class.java)) {
      val server = TinyHttpServer()
      try {
        server.respond { TinyHttpServer.Response(status, """{"message":"fixture failure"}""", mapOf("Retry-After" to "2592000")) }
        val provider = MistralTranscriptionProvider(TranscriptionHttp("fixture"), "fake", server.url("/v1"))
        val audio = temp.newFile().apply { writeText("fixture") }
        val failure = runCatching { provider.transcribe(audio, "audio/wav", TranscribeRequest(MistralTranscriptionProvider.DEFAULT_MODEL)) }.exceptionOrNull()
        assertEquals(type, TranscriptionError.from(failure!!).javaClass)
        assertEquals(1, server.requests.size)
        if (status == 429) assertTrue((failure as TranscriptionError.RateLimited).retryAtMillis!! > System.currentTimeMillis() + 29L * 86_400_000)
      } finally { server.close() }
    }
  }

  @Test fun `HTTP date Retry-After remains an absolute long quota deadline`() = runBlocking {
    val server = TinyHttpServer()
    try {
      val until = java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 30L * 86_400_000).atZone(java.time.ZoneOffset.UTC)
      val header = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(until)
      server.respond { TinyHttpServer.Response(429, "{}", mapOf("Retry-After" to header)) }
      val provider = MistralTranscriptionProvider(TranscriptionHttp("fixture"), "fake", server.url("/v1"))
      val audio = temp.newFile().apply { writeText("fixture") }
      val failure = runCatching { provider.transcribe(audio, "audio/wav", TranscribeRequest(MistralTranscriptionProvider.DEFAULT_MODEL)) }.exceptionOrNull() as TranscriptionError.RateLimited
      assertEquals(until.toInstant().toEpochMilli() / 1000 * 1000, failure.retryAtMillis)
      assertEquals(1, server.requests.size)
    } finally { server.close() }
  }
}
