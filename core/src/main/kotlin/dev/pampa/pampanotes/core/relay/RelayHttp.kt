package dev.pampa.pampanotes.core.relay

import dev.pampa.pampanotes.core.transcription.cancellable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** No redirects, presigned URLs, whole-file buffers or credential-bearing exception messages. */
class RelayHttp(
  private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
  internal suspend fun discover(session: RelaySession): RelayDiscovery {
    val discovery = decode<RelayDiscovery>(json(session, "", "GET"))
    relayRequire(discovery.serverTime > 0 && discovery.transfers.size <= 8, "invalid_discovery")
    discovery.transfers.forEach { it.validate() }
    return discovery
  }

  internal suspend fun owner(session: RelaySession): String {
    val raw = request(session, "/v1/sync/status", "GET") { readJson(it) }
    return try {
      RelayLimits.json.parseToJsonElement(raw).jsonObject["ownerId"]?.jsonPrimitive?.content
        ?.takeIf { it.isNotBlank() } ?: throw RelayFailure(RelayState.AUTH_REQUIRED, "missing_owner")
    } catch (_: SerializationException) { throw RelayFailure(RelayState.FAILED, "invalid_response") }
  }

  internal suspend fun begin(session: RelaySession, exactJson: String): RelayTransfer =
    decode<RelayTransfer>(json(session, "", "POST", exactJson.toByteArray())).validate(true)

  internal suspend fun status(session: RelaySession, id: String): RelayTransfer =
    decode<RelayTransfer>(json(session, "/" + safeId(id), "GET")).validate(true)

  internal suspend fun commit(session: RelaySession, id: String): RelayTransfer =
    decode<RelayTransfer>(json(session, "/" + safeId(id) + "/commit", "POST", ByteArray(0))).validate(true)

  internal suspend fun put(session: RelaySession, transfer: RelayTransfer, chunk: RelayChunk, bytes: ByteArray): RelayAck {
    val ack = decode<RelayAck>(json(session, "/" + safeId(transfer.transferId) + "/chunks/" + chunk.index,
      "PUT", bytes, chunk.sha256))
    relayRequire(ack.index == chunk.index && ack.bytes == chunk.bytes && ack.sha256 == chunk.sha256 &&
      ack.expiresAt == transfer.expiresAt, "invalid_ack")
    if (ack.serverTime >= ack.expiresAt) throw RelayFailure(RelayState.EXPIRED, "expired")
    return ack
  }

  internal suspend fun download(
    session: RelaySession, transfer: RelayTransfer, chunk: RelayChunk, destination: File,
    onBytes: (Long) -> Unit,
  ) {
    try {
      request(session, "/v1/relay/" + safeId(transfer.transferId) + "/chunks/" + chunk.index, "GET") { connection ->
        relayRequire(connection.getHeaderFieldLong("Content-Length", -1) == chunk.bytes.toLong() &&
          connection.getHeaderField("X-Chunk-SHA256") == chunk.sha256 &&
          connection.getHeaderField("X-File-SHA256") == transfer.sha256 &&
          connection.getHeaderFieldLong("X-Expires-At", -1) == transfer.expiresAt, "invalid_chunk_headers")
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        connection.inputStream.use { input ->
          FileOutputStream(destination).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              currentCoroutineContext().ensureActive()
              val n = input.read(buffer)
              if (n < 0) break
              count += n
              relayRequire(count <= chunk.bytes, "chunk_size_mismatch")
              output.write(buffer, 0, n)
              digest.update(buffer, 0, n)
              onBytes(count)
            }
            output.fd.sync()
          }
        }
        relayRequire(count == chunk.bytes.toLong() && digest.digest().relayHex() == chunk.sha256, "chunk_hash_mismatch")
      }
    } catch (t: Throwable) {
      destination.delete()
      throw t
    }
  }

  suspend fun delete(session: RelaySession, transferId: String) {
    json(session, "/" + safeId(transferId), "DELETE")
  }

  private suspend fun json(
    session: RelaySession, suffix: String, method: String, body: ByteArray? = null, hash: String? = null,
  ): String = request(session, "/v1/relay$suffix", method, body, hash) { readJson(it) }

  private suspend fun <T> request(
    session: RelaySession, path: String, method: String, body: ByteArray? = null, hash: String? = null,
    read: suspend (HttpURLConnection) -> T,
  ): T {
    if (session.token.isBlank() || session.token.any { it.isISOControl() }) {
      throw RelayFailure(RelayState.AUTH_REQUIRED, "invalid_session")
    }
    val connection = openConnection(URL(normalizedBase(session.baseUrl) + path))
    connection.requestMethod = method
    connection.instanceFollowRedirects = false
    connection.useCaches = false
    connection.connectTimeout = 15_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("Authorization", "Bearer " + session.token)
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("Cache-Control", "no-store")
    connection.setRequestProperty("User-Agent", "PampaNotes-Relay/1")
    return try {
      connection.cancellable(Dispatchers.IO) { c ->
        if (body != null) {
          c.doOutput = true
          c.setFixedLengthStreamingMode(body.size)
          c.setRequestProperty("Content-Type", if (hash == null) "application/json" else "application/octet-stream")
          if (hash != null) c.setRequestProperty("X-Chunk-SHA256", hash)
          c.outputStream.use { output ->
            var offset = 0
            while (offset < body.size) {
              currentCoroutineContext().ensureActive()
              val n = minOf(64 * 1024, body.size - offset)
              output.write(body, offset, n)
              offset += n
            }
          }
        }
        val status = c.responseCode
        if (status !in 200..299) {
          val code = runCatching {
            c.errorStream?.use { input ->
              val bytes = boundedRead(input)
              if (bytes.size > MAX_JSON) null else RelayLimits.json.parseToJsonElement(bytes.decodeToString())
                .jsonObject["code"]?.jsonPrimitive?.content
            }
          }.getOrNull()?.takeIf { it.matches(Regex("[a-z_]{1,64}")) } ?: "http_$status"
          val state = when {
            status == 404 || status == 410 -> RelayState.EXPIRED
            status == 401 || status == 403 -> RelayState.AUTH_REQUIRED
            status == 429 -> RelayState.QUOTA
            status == 413 -> RelayState.UNSUPPORTED
            status == 422 -> RelayState.INTEGRITY_ERROR
            status == 409 && code in setOf("incomplete", "not_ready", "not_uploading") -> RelayState.RETRY
            status >= 500 || status == 408 -> RelayState.RETRY
            else -> RelayState.FAILED
          }
          throw RelayFailure(state, code)
        }
        read(c)
      }
    } catch (error: IOException) {
      if (error is RelayFailure) throw error
      throw RelayFailure(RelayState.RETRY, "network")
    } finally { connection.disconnect() }
  }

  private fun readJson(connection: HttpURLConnection): String {
    val bytes = connection.inputStream.use { boundedRead(it) }
    relayRequire(bytes.size <= MAX_JSON, "response_too_large")
    return bytes.decodeToString()
  }

  private inline fun <reified T> decode(raw: String): T = try {
    RelayLimits.json.decodeFromString<T>(raw)
  } catch (_: SerializationException) { throw RelayFailure(RelayState.FAILED, "invalid_response") }

  private fun boundedRead(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() <= MAX_JSON) {
      val n = input.read(buffer, 0, minOf(buffer.size, MAX_JSON + 1 - output.size()))
      if (n < 0) break
      output.write(buffer, 0, n)
    }
    return output.toByteArray()
  }

  private fun safeId(id: String): String {
    relayRequire(RelayLimits.id.matches(id), "invalid_id")
    return id
  }

  companion object {
    private const val MAX_JSON = 128 * 1024
    fun normalizedBase(raw: String): String {
      val uri = try { URI(raw.trim().trimEnd('/')) } catch (_: Exception) {
        throw RelayFailure(RelayState.AUTH_REQUIRED, "invalid_server")
      }
      val local = uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
      if ((uri.scheme != "https" && !(uri.scheme == "http" && local)) || uri.host.isNullOrBlank() ||
        uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
        throw RelayFailure(RelayState.AUTH_REQUIRED, "invalid_server")
      }
      return uri.toString().trimEnd('/')
    }
  }
}
