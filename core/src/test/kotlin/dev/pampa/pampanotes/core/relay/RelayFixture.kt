package dev.pampa.pampanotes.core.relay

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class TestRelayClock : RelayClock {
  var elapsed = 10_000L
  var wall = 1_794_000_000_000L
  override fun elapsedMillis(): Long = elapsed
  override fun wallMillis(): Long = wall
  fun advance(ms: Long) { elapsed += ms; wall += ms }
}

internal class TestRelayAccounts(val session: RelaySession) : RelayAccounts {
  @Volatile var current = true
  override suspend fun snapshot(): RelaySession {
    if (!current) throw RelayFailure(RelayState.ACCOUNT_CHANGED, "account_changed")
    return session
  }
  override suspend fun isCurrent(session: RelaySession): Boolean = current && session === this.session
}

internal class TestRelayFiles(private val folder: File) : RelayLocalFiles {
  val rows = mutableListOf<RelayLocalFile>()
  var beforePlace: (() -> Unit)? = null
  var placements = 0
  override suspend fun list(noteId: String?): List<RelayLocalFile> = rows.filter { noteId == null || it.noteId == noteId }
  override suspend fun get(kind: RelayKind, entityId: String): RelayLocalFile? =
    rows.firstOrNull { it.kind == kind && it.entityId == entityId }
  override fun path(file: RelayLocalFile): File = File(folder, file.fileName)
  override suspend fun placeIfCurrent(expected: RelayLocalFile, place: suspend () -> Unit): Boolean {
    beforePlace?.invoke()
    if (get(expected.kind, expected.entityId) != expected) return false
    place()
    placements++
    return true
  }

  fun row(bytes: ByteArray, id: String = "row-a", kind: RelayKind = RelayKind.SOURCE, present: Boolean = true): RelayLocalFile =
    RelayLocalFile(kind, id, "note-a", "$id.bin", "Original.bin", "application/octet-stream",
      relayHash(bytes), bytes.size.toLong(), "revision-a").also {
      rows += it
      if (present) path(it).writeBytes(bytes)
    }
}

/** Actual loopback HTTP, including authenticated headers and fixed-size/chunk integrity checks. */
internal class RelayFixture(private val root: File) : AutoCloseable {
  val clock = TestRelayClock()
  private val executor = Executors.newCachedThreadPool { Thread(it, "relay-test-http").apply { isDaemon = true } }
  private val server = HttpServer(InetSocketAddress("127.0.0.1", 0)).apply {
    executor = this@RelayFixture.executor
  }
  val accounts = TestRelayAccounts(RelaySession("http://127.0.0.1:" + server.address.port,
    "owner-a", "secret-never-persist-this", "device-a", "Phone"))
  val local = TestRelayFiles(File(root, "files").also { it.mkdirs() })
  val journal = RelayJournal(File(root, "journal"))
  val beginBodies = mutableListOf<String>()
  val puts = mutableListOf<Int>()
  val gets = mutableListOf<Int>()
  val chunks = mutableMapOf<Int, ByteArray>()
  var transfer: RelayTransfer? = null
  var lostBeginOnce = false
  var staleBegin = false
  var failPutOnce: Int? = null
  var failGetOnce: Int? = null
  var corruptGet: Int? = null
  var wrongLengthHeader = false
  var expiredStatus = false
  var missingStatus = false
  var quotaBegin = false
  var failCommitOnce = false
  var invalidAck = false
  var afterPut: (() -> Unit)? = null
  var afterGet: (() -> Unit)? = null
  var beginWasDurable = false
  val authFailures = AtomicInteger()
  val failures = mutableListOf<Throwable>()

  init {
    server.createContext("/") { exchange ->
      try {
        dispatch(exchange)
      } catch (error: Throwable) {
        synchronized(failures) { failures += error }
        runCatching { error(exchange, 500, "fixture_failure") }
      } finally { exchange.close() }
    }
    server.start()
  }

  fun repository() = RelayRepository(accounts, local, RelayHttp(), journal, clock)

  fun ready(bytes: ByteArray, row: RelayLocalFile, remoteName: String = row.originalName) {
    chunks.clear()
    var offset = 0
    while (offset < bytes.size) {
      val end = minOf(bytes.size, offset + RelayLimits.CHUNK_BYTES)
      chunks[chunks.size] = bytes.copyOfRange(offset, end)
      offset = end
    }
    transfer = RelayTransfer(UUID.randomUUID().toString(), UUID.randomUUID().toString(), row.kind.wire,
      row.entityId, row.sha256, row.bytes, row.mime, remoteName, "other-device", "Tablet", "ready",
      clock.wall, clock.wall + RelayLimits.TTL_MS, clock.wall, RelayLimits.CHUNK_BYTES,
      chunks.size, manifest())
  }

  private fun manifest() = chunks.entries.sortedBy { it.key }.map { RelayChunk(it.key, it.value.size, relayHash(it.value)) }
  private fun current() = transfer!!.copy(serverTime = clock.wall, chunks = manifest())

  private fun dispatch(e: HttpExchange) {
    if (e.requestHeaders.getFirst("Authorization") != "Bearer " + accounts.session.token) {
      authFailures.incrementAndGet()
      error(e, 401, "unauthorized"); return
    }
    val path = e.requestURI.path
    if (path == "/v1/relay" && e.requestMethod == "GET") {
      val items = transfer?.takeIf { it.expiresAt > clock.wall }?.let { listOf(current().copy(chunks = emptyList())) }.orEmpty()
      json(e, RelayLimits.json.encodeToString(RelayDiscovery.serializer(), RelayDiscovery(clock.wall, items)))
      return
    }
    if (path == "/v1/relay" && e.requestMethod == "POST") {
      val raw = e.requestBody.readBytes().decodeToString()
      beginBodies += raw
      beginWasDurable = journal.all().any { it.beginJson == raw }
      if (quotaBegin) { error(e, 429, "owner_quota"); return }
      if (staleBegin) { error(e, 410, "stale_begin"); return }
      val begin = RelayLimits.json.decodeFromString<RelayBegin>(raw)
      val previous = transfer
      if (previous == null) {
        if (clock.wall - begin.requestedAt > RelayLimits.BEGIN_FRESHNESS_MS) {
          error(e, 410, "stale_begin"); return
        }
        transfer = RelayTransfer(UUID.randomUUID().toString(), begin.requestId, begin.kind, begin.entityId,
          begin.sha256, begin.bytes, begin.mime, begin.originalName, begin.deviceId, begin.deviceName,
          "uploading", clock.wall, clock.wall + RelayLimits.TTL_MS, clock.wall,
          RelayLimits.CHUNK_BYTES, ((begin.bytes + RelayLimits.CHUNK_BYTES - 1) / RelayLimits.CHUNK_BYTES).toInt())
      } else check(previous.requestId == begin.requestId)
      if (lostBeginOnce) {
        lostBeginOnce = false
        error(e, 500, "internal_error"); return
      }
      json(e, RelayLimits.json.encodeToString(RelayTransfer.serializer(), current()), 201)
      return
    }
    val t = transfer
    if (t == null || !path.startsWith("/v1/relay/" + t.transferId)) { error(e, 404, "not_found"); return }
    if (missingStatus) { error(e, 404, "not_found"); return }
    if (expiredStatus || clock.wall >= t.expiresAt) { error(e, 410, "expired"); return }
    if (path.endsWith("/commit")) {
      check(chunks.size == t.chunkCount)
      val whole = java.security.MessageDigest.getInstance("SHA-256")
      chunks.toSortedMap().values.forEach { whole.update(it) }
      check(whole.digest().relayHex() == t.sha256)
      transfer = t.copy(state = "ready")
      if (failCommitOnce) { failCommitOnce = false; error(e, 500, "internal_error"); return }
      json(e, RelayLimits.json.encodeToString(RelayTransfer.serializer(), current()))
    } else if (path.contains("/chunks/")) {
      val index = path.substringAfterLast('/').toInt()
      if (e.requestMethod == "PUT") {
        val bytes = e.requestBody.readBytes()
        check(bytes.size == t.chunkLength(index))
        check(e.requestHeaders.getFirst("X-Chunk-SHA256") == relayHash(bytes))
        check(e.requestHeaders.getFirst("Content-Length") == bytes.size.toString())
        puts += index
        chunks[index] = bytes
        afterPut?.invoke()
        if (failPutOnce == index) { failPutOnce = null; error(e, 500, "internal_error"); return }
        json(e, RelayLimits.json.encodeToString(RelayAck.serializer(),
          RelayAck(index, bytes.size + if (invalidAck) 1 else 0, relayHash(bytes), t.expiresAt, clock.wall)))
      } else {
        gets += index
        if (failGetOnce == index) { failGetOnce = null; error(e, 500, "internal_error"); return }
        val original = chunks.getValue(index)
        val bytes = original.copyOf()
        if (corruptGet == index) bytes[0] = (bytes[0].toInt() xor 1).toByte()
        e.responseHeaders.set("Content-Type", "application/octet-stream")
        e.responseHeaders.set("X-Chunk-SHA256", relayHash(original))
        e.responseHeaders.set("X-File-SHA256", t.sha256)
        e.responseHeaders.set("X-Expires-At", t.expiresAt.toString())
        afterGet?.invoke()
        e.sendResponseHeaders(200, (bytes.size + if (wrongLengthHeader) 1 else 0).toLong())
        e.responseBody.use { it.write(bytes) }
      }
    } else {
      json(e, RelayLimits.json.encodeToString(RelayTransfer.serializer(), current()))
    }
  }

  private fun json(e: HttpExchange, raw: String, status: Int = 200) {
    val bytes = raw.toByteArray()
    e.responseHeaders.set("Content-Type", "application/json")
    e.sendResponseHeaders(status, bytes.size.toLong())
    e.responseBody.use { it.write(bytes) }
  }

  private fun error(e: HttpExchange, status: Int, code: String) = json(e,
    JsonObject(mapOf("error" to JsonPrimitive("diagnostic"), "code" to JsonPrimitive(code))).toString(), status)

  override fun close() { server.stop(0); executor.shutdownNow() }
}

/** java.net-only fixture: Android's JVM-test compile bootclasspath excludes jdk.httpserver. */
private class HttpServer(address: InetSocketAddress) {
  private val socket = ServerSocket().apply { bind(address) }
  val address: InetSocketAddress get() = socket.localSocketAddress as InetSocketAddress
  lateinit var executor: Executor
  private lateinit var handler: (HttpExchange) -> Unit
  fun createContext(path: String, handler: (HttpExchange) -> Unit) { this.handler = handler }
  fun start() {
    executor.execute {
      while (!socket.isClosed) {
        val connection = try { socket.accept() } catch (_: java.io.IOException) { break }
        executor.execute {
          connection.use {
            runCatching { handler(HttpExchange(connection)) }
          }
        }
      }
    }
  }
  fun stop(delay: Int) { socket.close() }
}

private class TestHeaders {
  private val values = linkedMapOf<String, String>()
  fun set(name: String, value: String) { values[name.lowercase()] = value }
  fun getFirst(name: String): String? = values[name.lowercase()]
  fun lines(): String = values.entries.joinToString("") { it.key + ": " + it.value + "\r\n" }
}

private class HttpExchange(private val socket: Socket) {
  val requestHeaders = TestHeaders()
  val responseHeaders = TestHeaders()
  val requestMethod: String
  val requestURI: URI
  val requestBody: ByteArrayInputStream
  val responseBody get() = socket.getOutputStream()
  init {
    socket.soTimeout = 10_000
    val input = BufferedInputStream(socket.getInputStream())
    val first = readLine(input).split(' ')
    requestMethod = first[0]
    requestURI = URI(first[1])
    while (true) {
      val line = readLine(input)
      if (line.isEmpty()) break
      requestHeaders.set(line.substringBefore(':'), line.substringAfter(':').trim())
    }
    val size = requestHeaders.getFirst("Content-Length")?.toInt() ?: 0
    check(size in 0..RelayLimits.CHUNK_BYTES)
    val body = ByteArray(size)
    var at = 0
    while (at < size) {
      val n = input.read(body, at, size - at)
      check(n > 0)
      at += n
    }
    requestBody = ByteArrayInputStream(body)
  }
  fun sendResponseHeaders(status: Int, length: Long) {
    responseHeaders.set("Content-Length", length.toString())
    responseHeaders.set("Connection", "close")
    responseBody.write(("HTTP/1.1 $status Result\r\n" + responseHeaders.lines() + "\r\n").toByteArray())
  }
  fun close() { socket.close() }
  private fun readLine(input: BufferedInputStream): String {
    val bytes = ByteArrayOutputStream()
    while (bytes.size() < 16_384) {
      val b = input.read()
      check(b >= 0)
      if (b == 10) return bytes.toString("UTF-8").trimEnd('\r')
      bytes.write(b)
    }
    error("oversized header")
  }
}
