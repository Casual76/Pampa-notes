package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.testing.TinyHttpServer
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Il companion che lavora da se', visto dal provider: cosa si manda (impronta, archivio, tetto),
 * cosa si capisce della risposta, e i due rifiuti che non sono guasti ma indicazioni.
 */
class CompanionByRefTest {

  private lateinit var server: TinyHttpServer
  private lateinit var audio: File

  @Before
  fun start() {
    server = TinyHttpServer()
    audio = File.createTempFile("pampa", ".m4a").apply { writeBytes(ByteArray(2048)); deleteOnExit() }
  }

  @After
  fun stop() {
    server.close()
  }

  private fun provider(token: String? = "segreto") =
    OpenAiCompatProvider(TranscriptionHttp("test"), server.url(""), CompanionAuth.fixed(token), pollIntervalMs = 100)

  private val answer =
    """{"text":"ciao","language":"it","duration":1.0,"segments":[{"start":0.0,"end":1.0,"text":"ciao"}],"archived":true,"source":"archive","chunks":2}"""

  private fun TinyHttpServer.Request.text() = body.toString(Charsets.UTF_8)

  @Test
  fun `health legge le caratteristiche, e un server qualsiasi non ne ha`() = runBlocking {
    server.respond { request ->
      when (request.path) {
        "/health" -> TinyHttpServer.Response(200, """{"model":"large-v3","features":["by_ref","archive_upload","server_chunks",3]}""")
        "/v1/models" -> TinyHttpServer.Response(200, """{"data":[{"id":"large-v3"}]}""")
        else -> TinyHttpServer.Response(404, "{}")
      }
    }
    val health = provider().health()
    assertEquals(setOf("by_ref", "archive_upload", "server_chunks"), health.features)
    assertEquals(setOf("by_ref", "archive_upload", "server_chunks"), provider().features())

    assertTrue(OpenAiCompatProvider.parseFeatures(Json.parseToJsonElement("""{"model":"x"}""")).isEmpty())
    assertTrue(OpenAiCompatProvider.parseFeatures(null).isEmpty())
  }

  @Test
  fun `le caratteristiche si chiedono una volta per provider`() = runBlocking {
    server.respond { TinyHttpServer.Response(200, """{"features":["by_ref"]}""") }
    val provider = provider()
    provider.features()
    provider.features()
    assertEquals(1, server.requests.count { it.path == "/health" })
  }

  @Test
  fun `per impronta si manda un form senza file, e le domande sullo stato partono subito`() = runBlocking {
    server.respond { request ->
      when {
        request.method == "POST" -> {
          Thread.sleep(600)
          TinyHttpServer.Response(200, answer)
        }
        request.path.startsWith("/v1/jobs/") ->
          TinyHttpServer.Response(200, """{"state":"transcribing","fraction":0.4,"chunk":1,"chunks":2}""")
        else -> TinyHttpServer.Response(404, "{}")
      }
    }
    val seen = mutableListOf<RemoteProgress>()
    val result = provider().transcribeByRef("ABCDEF", TranscribeRequest("m", language = "it"), maxMinutes = 30) {
      synchronized(seen) { seen += it }
    }

    val post = server.requests.first { it.method == "POST" }
    assertEquals("/v1/audio/transcriptions", post.path)
    assertTrue(post.header("Content-Type")!!.startsWith("multipart/form-data"))
    val body = post.text()
    assertTrue(body.contains("name=\"source_sha256\"\r\n\r\nabcdef\r\n"))
    assertTrue(body.contains("name=\"max_minutes\"\r\n\r\n30\r\n"))
    assertTrue(body.contains("name=\"language\"\r\n\r\nit\r\n"))
    assertFalse("un riferimento non carica niente", body.contains("filename="))
    assertTrue(body.trimEnd().endsWith("--"))
    assertTrue(post.header(OpenAiCompatProvider.JOB_HEADER) != null)

    assertEquals("ciao", result.text)
    assertTrue(result.archived)
    assertEquals(2, result.serverChunks)
    val first = synchronized(seen) { seen.firstOrNull() }
    assertEquals(1, first?.chunk)
    assertEquals(2, first?.chunks)
  }

  @Test
  fun `il caricamento chiede al computer di tenere il file e di dividerlo`() = runBlocking {
    server.respond { request ->
      if (request.method == "POST") TinyHttpServer.Response(200, answer) else TinyHttpServer.Response(404, "{}")
    }
    provider().transcribeUpload(
      audio, "audio/mp4", TranscribeRequest("m"),
      CompanionUpload(sha256 = "ABC", name = "Voce 001.m4a", archive = true, maxMinutes = 60),
    )
    val body = server.requests.first { it.method == "POST" }.text()
    assertTrue(body.contains("name=\"source_sha256\"\r\n\r\nabc\r\n"))
    assertTrue(body.contains("name=\"archive\"\r\n\r\n1\r\n"))
    assertTrue(body.contains("name=\"name\"\r\n\r\nVoce 001.m4a\r\n"))
    assertTrue(body.contains("name=\"max_minutes\"\r\n\r\n60\r\n"))
    assertTrue(body.contains("name=\"file\"; filename="))
  }

  @Test
  fun `senza archivio ne' impronta il caricamento e' quello di sempre`() = runBlocking {
    server.respond { request ->
      if (request.method == "POST") TinyHttpServer.Response(200, answer) else TinyHttpServer.Response(404, "{}")
    }
    provider().transcribeUpload(audio, "audio/mp4", TranscribeRequest("m"), CompanionUpload(null, null, archive = true, maxMinutes = null))
    val body = server.requests.first { it.method == "POST" }.text()
    assertFalse(body.contains("source_sha256"))
    assertFalse(body.contains("name=\"archive\""))
    assertFalse(body.contains("max_minutes"))
  }

  @Test
  fun `un file che l'archivio non ha e' BlobMissing`() {
    server.respond { request ->
      if (request.method == "POST") TinyHttpServer.Response(404, """{"detail":"blob_missing"}""") else TinyHttpServer.Response(404, "{}")
    }
    val error = runCatching { runBlocking { provider().transcribeByRef("abc", TranscribeRequest("m"), null) } }.exceptionOrNull()
    assertTrue("era $error", error is TranscriptionError.BlobMissing)
    assertEquals(false, (error as TranscriptionError).retryable)
  }

  @Test
  fun `un ospite che chiede l'archivio e' OwnerOnly, e il suo codice non si rinnova`() {
    server.respond { request ->
      if (request.method == "POST") TinyHttpServer.Response(403, """{"detail":"owner_only"}""") else TinyHttpServer.Response(404, "{}")
    }
    val error = runCatching { runBlocking { provider("pg_ospite").transcribeByRef("abc", TranscribeRequest("m"), null) } }.exceptionOrNull()
    assertTrue("era $error", error is TranscriptionError.OwnerOnly)
    assertEquals(1, server.requests.count { it.method == "POST" })
  }

  @Test
  fun `un 404 qualsiasi resta quello che era`() {
    assertNull(OpenAiCompatProvider.refusal(TranscriptionError.Parse("Not Found")))
    assertNull(OpenAiCompatProvider.refusal(TranscriptionError.Unauthorized("token sbagliato")))
    assertTrue(OpenAiCompatProvider.refusal(TranscriptionError.Unauthorized("""{"detail":"owner_only"}""")) is TranscriptionError.OwnerOnly)
  }

  @Test
  fun `lo stato del computer porta il pezzo quando divide lui`() {
    val progress = RemoteJobPoller.parse(Json.parseToJsonElement("""{"state":"aligning","fraction":0.5,"chunk":2,"chunks":3}"""))!!
    assertEquals(2, progress.chunk)
    assertEquals(3, progress.chunks)
    val plain = RemoteJobPoller.parse(Json.parseToJsonElement("""{"state":"aligning","fraction":0.5}"""))!!
    assertNull(plain.chunk)
    assertNull(plain.chunks)
  }

  @Test
  fun `la barra del computer che divide da se' non torna indietro fra un pezzo e l'altro`() {
    val scale = ProgressScale(listOf(60_000L), ProgressScale.COMPUTER_UPLOAD_SHARE)
    val steps = listOf(
      RemoteProgress(RemoteStage.RECEIVED),
      RemoteProgress(RemoteStage.TRANSCRIBING, 0.5f, chunk = 1, chunks = 2),
      RemoteProgress(RemoteStage.ALIGNING, 1f, chunk = 1, chunks = 2),
      RemoteProgress(RemoteStage.TRANSCRIBING, 0f, chunk = 2, chunks = 2),
      RemoteProgress(RemoteStage.ALIGNING, 0.5f, chunk = 2, chunks = 2),
      RemoteProgress(RemoteStage.DONE, 1f, chunk = 2, chunks = 2),
    ).map { scale.onComputer(it, byRef = true) }
    assertEquals(steps, steps.sorted())
    assertEquals(0f, steps.first(), 0.001f)
    assertEquals(1f, steps.last(), 0.001f)
    // Caricando, il lavoro del computer parte dopo la quota del caricamento.
    assertEquals(ProgressScale.COMPUTER_UPLOAD_SHARE, scale.onComputer(RemoteProgress(RemoteStage.RECEIVED), byRef = false), 0.001f)
  }
}
