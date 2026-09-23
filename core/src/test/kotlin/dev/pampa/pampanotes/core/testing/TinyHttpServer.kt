package dev.pampa.pampanotes.core.testing

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Un server HTTP/1.1 minimo su un socket vero, per provare i client senza un server vero.
 *
 * Non `com.sun.net.httpserver`: il classpath dei test Android non lo vede. Bastano poche righe:
 * legge la riga di richiesta, le intestazioni e il corpo (se c'e' `Content-Length`), chiama il
 * gestore, risponde, chiude. Le richieste ricevute restano in [requests], nell'ordine.
 */
class TinyHttpServer {
  class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
  }

  class Response(
    val code: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
  ) {
    constructor(code: Int, json: String, headers: Map<String, String> = emptyMap()) : this(code, json.toByteArray(Charsets.UTF_8), headers = headers)
  }

  private val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
  val port: Int get() = socket.localPort
  val requests: MutableList<Request> = java.util.Collections.synchronizedList(mutableListOf())

  @Volatile
  private var handler: (Request) -> Response = { Response(404) }

  init {
    thread(isDaemon = true, name = "tiny-http") {
      while (!socket.isClosed) {
        val client = runCatching { socket.accept() }.getOrNull() ?: break
        thread(isDaemon = true) { serve(client) }
      }
    }
  }

  fun respond(handler: (Request) -> Response) {
    this.handler = handler
  }

  fun url(path: String): String = "http://127.0.0.1:$port$path"

  private fun serve(client: Socket) = client.use { c ->
    val input = BufferedInputStream(c.getInputStream())
    val requestLine = readLine(input) ?: return@use
    val headers = mutableMapOf<String, String>()
    while (true) {
      val line = readLine(input) ?: break
      if (line.isEmpty()) break
      val colon = line.indexOf(':')
      if (colon > 0) headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
    }
    val length = headers.entries.firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }?.value?.toLongOrNull() ?: 0L
    val body = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var remaining = length
    while (remaining > 0) {
      val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
      if (read < 0) break
      body.write(buffer, 0, read)
      remaining -= read
    }
    val parts = requestLine.split(' ')
    val request = Request(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, headers, body.toByteArray())
    requests += request
    val response = handler(request)
    val reason = when (response.code) { 200 -> "OK"; 404 -> "Not Found"; 500 -> "Internal Server Error"; else -> "Status" }
    val extra = response.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
    val out = c.getOutputStream()
    out.write("HTTP/1.1 ${response.code} $reason\r\nContent-Type: ${response.contentType}\r\nContent-Length: ${response.body.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
    out.write(response.body)
    out.flush()
  }

  private fun readLine(input: BufferedInputStream): String? {
    val line = ByteArrayOutputStream()
    while (true) {
      val b = input.read()
      if (b < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
      if (b == '\n'.code) break
      if (b != '\r'.code) line.write(b)
    }
    return line.toString("ISO-8859-1")
  }

  fun close() = socket.close()
}
