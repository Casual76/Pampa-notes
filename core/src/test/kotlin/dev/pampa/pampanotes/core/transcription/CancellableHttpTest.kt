package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.archive.ArchiveHttp
import dev.pampa.pampanotes.core.testing.TinyHttpServer
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Un server che non risponde mai — il computer di casa che macina, o che si e' spento a meta' —
 * e una richiesta annullata: deve tornare subito, non quando scade il timeout di lettura.
 */
class CancellableHttpTest {

  private lateinit var server: TinyHttpServer

  @Before
  fun start() {
    server = TinyHttpServer()
    server.respond {
      Thread.sleep(60_000)
      TinyHttpServer.Response(200, "{}")
    }
  }

  @After
  fun stop() {
    server.close()
  }

  private fun cancelledQuickly(call: suspend () -> Unit) = runBlocking {
    val started = System.currentTimeMillis()
    val pending = async { call() }
    delay(300)
    pending.cancel()
    val error = runCatching { withTimeout(5_000) { pending.await() } }.exceptionOrNull()
    val elapsed = System.currentTimeMillis() - started
    assertTrue("era $error", error is CancellationException)
    assertTrue("ci ha messo $elapsed ms", elapsed < 5_000)
  }

  @Test
  fun `annullare una richiesta al companion stacca la connessione`() = cancelledQuickly {
    TranscriptionHttp("test").getJson(server.url("/v1/models"), emptyMap(), readTimeoutMillis = 120_000)
  }

  @Test
  fun `annullare un upload in attesa della risposta stacca la connessione`() = cancelledQuickly {
    val audio = File.createTempFile("pampa", ".m4a").apply { writeBytes(ByteArray(1024)); deleteOnExit() }
    TranscriptionHttp("test").postAudio(
      url = server.url("/v1/audio/transcriptions"),
      headers = emptyMap(),
      fields = mapOf("model" to "m"),
      file = audio,
      fileMime = "audio/mp4",
      readTimeoutMillis = 120_000,
    )
  }

  @Test
  fun `annullare un prelievo dall'archivio stacca la connessione`() = cancelledQuickly {
    val target = File.createTempFile("pampa", ".part").apply { deleteOnExit() }
    ArchiveHttp("test").download(server.url("/v1/files/abc"), null, target)
  }
}
