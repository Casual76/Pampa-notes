package dev.pampa.pampanotes.core.archive

import dev.pampa.pampanotes.core.files.Hashing
import dev.pampa.pampanotes.core.testing.TinyHttpServer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Il prelievo dal computer di casa, contro un server HTTP minimo su un socket vero. */
class ArchiveHttpTest {

  private lateinit var server: TinyHttpServer
  private val http = ArchiveHttp(userAgent = "test")

  @Before
  fun start() {
    server = TinyHttpServer()
  }

  @After
  fun stop() {
    server.close()
  }

  @Test
  fun `scarica tutto, torna l'impronta di quello che ha scritto, e il progresso arriva in fondo`() = runBlocking {
    val body = ByteArray(300_000) { (it % 251).toByte() }
    server.respond { request -> if (request.path == "/v1/files/abc") TinyHttpServer.Response(200, body, "audio/mp4") else TinyHttpServer.Response(404) }
    val target = File.createTempFile("pampa", ".m4a").apply { deleteOnExit() }
    val reports = mutableListOf<Pair<Long, Long>>()

    val sha = http.download(server.url("/v1/files/abc"), "segreto", target) { received, total -> reports += received to total }

    assertEquals(Hashing.sha256(body.inputStream()), sha)
    assertArrayEquals(body, target.readBytes())
    assertEquals(300_000L to 300_000L, reports.last())
    assertEquals("Bearer segreto", server.requests.last().header("Authorization"))
  }

  @Test
  fun `un 404 e' un'eccezione con il codice, e il file di destinazione resta vuoto`() = runBlocking {
    server.respond { TinyHttpServer.Response(404) }
    val target = File.createTempFile("pampa", ".m4a").apply { deleteOnExit() }

    val error = runCatching { http.download(server.url("/v1/files/nope"), null, target) }.exceptionOrNull()

    assertTrue(error is ArchiveException)
    assertEquals(404, (error as ArchiveException).code)
    assertEquals(0L, target.length())
  }

  @Test
  fun `senza token non manda un bearer vuoto`() = runBlocking {
    server.respond { TinyHttpServer.Response(200, byteArrayOf(1, 2, 3), "application/octet-stream") }
    val target = File.createTempFile("pampa", ".bin").apply { deleteOnExit() }

    http.download(server.url("/v1/files/open"), "", target)

    assertFalse(server.requests.last().headers.keys.any { it.equals("Authorization", ignoreCase = true) })
    assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
  }
}
