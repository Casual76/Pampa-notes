package dev.pampa.pampanotes.core.share

import dev.pampa.pampanotes.core.testing.TinyHttpServer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Il client delle condivisioni contro un server finto: quello che manda, e a chi. */
class ShareApiTest {

  private lateinit var server: TinyHttpServer
  private val api = ShareApi(userAgent = "test")

  @Before
  fun start() {
    server = TinyHttpServer()
  }

  @After
  fun stop() {
    server.close()
  }

  private fun temp(size: Int): File {
    val file = File.createTempFile("pampa", ".m4a").apply { deleteOnExit() }
    val bytes = ByteArray(size)
    java.util.Random(42).nextBytes(bytes)
    file.writeBytes(bytes)
    return file
  }

  @Test
  fun `crea, elenca e revoca passano dal bearer e leggono il JSON`() = runBlocking {
    server.respond { request ->
      when {
        request.method == "POST" && request.path == "/v1/shares" ->
          TinyHttpServer.Response(200, """{"shareId":"s1","noteId":"n1","title":"Kant","url":"http://x/s/tok","createdAt":1,"opens":0,"audioBytes":0}""")
        request.method == "GET" && request.path == "/v1/shares" ->
          TinyHttpServer.Response(200, """{"shares":[{"shareId":"s1","noteId":"n1","title":"Kant","url":"http://x/s/tok","createdAt":1,"openedAt":5,"opens":2,"audioBytes":10}]}""")
        request.method == "DELETE" && request.path == "/v1/shares/s1" -> TinyHttpServer.Response(200, """{"revoked":true}""")
        else -> TinyHttpServer.Response(404, """{"error":"non trovato"}""")
      }
    }
    val created = api.create(server.url(""), "segreto", "n1", "Kant")
    assertEquals("s1", created.shareId)
    assertEquals("http://x/s/tok", created.url)
    assertEquals("""{"noteId":"n1","title":"Kant"}""", String(server.requests.last().body))
    assertEquals("Bearer segreto", server.requests.last().header("Authorization"))

    val list = api.list(server.url(""), "segreto")
    assertEquals(2, list.single().opens)
    assertEquals(5L, list.single().openedAt)

    api.revoke(server.url(""), "segreto", "s1")
    val missing = runCatching { api.revoke(server.url(""), "segreto", "s2") }.exceptionOrNull()
    assertTrue(missing is ShareException && missing.code == 404)
  }

  @Test
  fun `un file piccolo sale in una PUT sola, con il progresso che arriva in fondo`() = runBlocking {
    val file = temp(300_000)
    server.respond { request ->
      if (request.method == "PUT" && request.path == "/v1/shares/s1/audio/p1") TinyHttpServer.Response(200, """{"bytes":${request.body.size}}""")
      else TinyHttpServer.Response(404, """{"error":"no"}""")
    }
    val reports = mutableListOf<Pair<Long, Long>>()
    val bytes = api.uploadAudio(server.url(""), "t", "s1", "p1", file, "audio/mp4") { sent, total -> reports += sent to total }
    assertEquals(300_000L, bytes)
    assertEquals(1, server.requests.size)
    assertArrayEquals(file.readBytes(), server.requests.single().body)
    assertEquals("audio/mp4", server.requests.single().header("Content-Type"))
    assertEquals(300_000L to 300_000L, reports.last())
  }

  @Test
  fun `un file grande sale a blocchi, e il completamento porta gli etag nell'ordine`() = runBlocking {
    val size = (ShareApi.CHUNK + 1_048_576).toInt()
    val file = temp(size)
    server.respond { request ->
      when {
        request.method == "POST" && request.path.endsWith("/multipart") -> TinyHttpServer.Response(200, """{"uploadId":"up-1"}""")
        request.method == "PUT" && request.path.contains("/multipart/up-1/") -> {
          val n = request.path.substringAfterLast('/')
          TinyHttpServer.Response(200, """{"partNumber":$n,"etag":"etag-$n"}""")
        }
        request.method == "POST" && request.path.endsWith("/complete") -> TinyHttpServer.Response(200, """{"bytes":$size}""")
        else -> TinyHttpServer.Response(404, """{"error":"no"}""")
      }
    }
    val bytes = api.uploadAudio(server.url(""), "t", "s1", "p2", file, "audio/mp4")
    assertEquals(size.toLong(), bytes)

    val methods = server.requests.map { it.method + " " + it.path.substringAfter("/audio/p2") }
    assertEquals(listOf("POST /multipart", "PUT /multipart/up-1/1", "PUT /multipart/up-1/2", "POST /multipart/up-1/complete"), methods)
    val original = file.readBytes()
    assertArrayEquals(original.copyOfRange(0, ShareApi.CHUNK.toInt()), server.requests[1].body)
    assertArrayEquals(original.copyOfRange(ShareApi.CHUNK.toInt(), size), server.requests[2].body)
    assertEquals("""{"parts":[{"partNumber":1,"etag":"etag-1"},{"partNumber":2,"etag":"etag-2"}]}""", String(server.requests[3].body))
    assertEquals("""{"mime":"audio/mp4"}""", String(server.requests[0].body))
  }

  @Test
  fun `un blocco che fallisce fa buttare il caricamento e rilancia l'errore`() = runBlocking {
    val file = temp((ShareApi.CHUNK + 10).toInt())
    server.respond { request ->
      when {
        request.method == "POST" && request.path.endsWith("/multipart") -> TinyHttpServer.Response(200, """{"uploadId":"up-2"}""")
        request.method == "PUT" && request.path.endsWith("/up-2/1") -> TinyHttpServer.Response(200, """{"partNumber":1,"etag":"e1"}""")
        request.method == "PUT" && request.path.endsWith("/up-2/2") -> TinyHttpServer.Response(500, """{"error":"disco pieno"}""")
        request.method == "DELETE" && request.path.endsWith("/multipart/up-2") -> TinyHttpServer.Response(200, """{"aborted":true}""")
        else -> TinyHttpServer.Response(404, """{"error":"no"}""")
      }
    }
    val error = runCatching { api.uploadAudio(server.url(""), "t", "s1", "p3", file, "audio/mp4") }.exceptionOrNull()
    assertTrue(error is ShareException)
    assertEquals("disco pieno", error?.message)
    assertEquals("DELETE", server.requests.last().method)
    assertTrue(server.requests.last().path.endsWith("/multipart/up-2"))
  }
}
