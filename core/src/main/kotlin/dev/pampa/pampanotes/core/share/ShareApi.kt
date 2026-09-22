package dev.pampa.pampanotes.core.share

import dev.pampa.pampanotes.core.sync.SyncApi
import dev.pampa.pampanotes.core.sync.SyncCodec
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** Una condivisione com'e' sul server: il link, e come sta andando. */
@Serializable
data class ShareInfo(
  val shareId: String,
  val noteId: String,
  val title: String,
  val url: String,
  val createdAt: Long,
  val openedAt: Long? = null,
  val opens: Int = 0,
  val audioBytes: Long = 0,
)

@Serializable
data class UploadedPart(val partNumber: Int, val etag: String)

/**
 * Le chiamate del Worker per le condivisioni. Stesso stile di [SyncApi]: JSON, un bearer, un
 * codice quando va male.
 *
 * L'audio sale in due modi. Un file piccolo va in una `PUT` sola; uno grande a blocchi da venti
 * megabyte, perche' una richiesta a un Worker ne porta al massimo cento e una lezione da due ore
 * e' di piu'. Il server li ricompone in R2 (multipart); se qualcosa si rompe a meta' si chiede di
 * buttare i blocchi, cosi' non restano a occupare spazio senza un file.
 */
class ShareApi(
  private val userAgent: String,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) {

  suspend fun create(baseUrl: String, token: String, noteId: String, title: String): ShareInfo =
    call(baseUrl, "/v1/shares", token, "POST", SyncCodec.json.encodeToString(CreateBody.serializer(), CreateBody(noteId, title)), ShareInfo.serializer())

  suspend fun list(baseUrl: String, token: String): List<ShareInfo> =
    call(baseUrl, "/v1/shares", token, "GET", null, SharesBody.serializer()).shares

  suspend fun revoke(baseUrl: String, token: String, shareId: String) {
    call(baseUrl, "/v1/shares/${enc(shareId)}", token, "DELETE", null, Revoked.serializer())
  }

  /** Il server ha gia' l'audio di questa parte? Un `HEAD`, costa niente, e un caricamento interrotto riparte da dove era. */
  suspend fun hasAudio(baseUrl: String, token: String, shareId: String, partId: String): Boolean = withContext(io) {
    val connection = open("${SyncApi.normalize(baseUrl)}/v1/shares/${enc(shareId)}/audio/${enc(partId)}", "HEAD", token)
    try {
      when (val code = connection.responseCode) {
        200 -> true
        404 -> false
        else -> throw ShareException(code, "risposta $code")
      }
    } finally {
      connection.disconnect()
    }
  }

  /** @return i byte che il server ha scritto. */
  suspend fun uploadAudio(
    baseUrl: String,
    token: String,
    shareId: String,
    partId: String,
    file: File,
    mime: String,
    onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
  ): Long = withContext(io) {
    val total = file.length()
    val base = "${SyncApi.normalize(baseUrl)}/v1/shares/${enc(shareId)}/audio/${enc(partId)}"
    if (total <= WHOLE_LIMIT) {
      return@withContext send(base, "PUT", token, mime, file, 0L, total, Bytes.serializer()) { sent -> onProgress(sent, total) }.bytes
    }

    val begun = call(base, "/multipart", token, "POST", SyncCodec.json.encodeToString(BeginBody.serializer(), BeginBody(mime)), Begun.serializer())
    val parts = mutableListOf<UploadedPart>()
    try {
      var offset = 0L
      var number = 1
      while (offset < total) {
        val length = minOf(CHUNK, total - offset)
        val from = offset
        parts += send("$base/multipart/${enc(begun.uploadId)}/$number", "PUT", token, "application/octet-stream", file, from, length, UploadedPart.serializer()) { sent ->
          onProgress(from + sent, total)
        }
        offset += length
        number++
      }
      val done = call(base, "/multipart/${enc(begun.uploadId)}/complete", token, "POST", SyncCodec.json.encodeToString(CompleteBody.serializer(), CompleteBody(parts)), Bytes.serializer())
      onProgress(total, total)
      done.bytes
    } catch (error: Throwable) {
      // I blocchi gia' saliti non hanno piu' un file che li aspetta: via, o restano a pagare spazio.
      runCatching { call(base, "/multipart/${enc(begun.uploadId)}", token, "DELETE", null, Aborted.serializer()) }
      throw error
    }
  }

  /** Manda [length] byte del file da [from], a lunghezza fissa, con il progresso. */
  private suspend fun <T> send(
    url: String,
    method: String,
    token: String,
    contentType: String,
    file: File,
    from: Long,
    length: Long,
    serializer: KSerializer<T>,
    onProgress: (sent: Long) -> Unit,
  ): T = withContext(io) {
    // Zero: si aspetta finche' serve. Il limite vero lo mette chi chiama, con una cancellazione.
    val connection = open(url, method, token, readTimeoutMillis = 0)
    try {
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", contentType)
      connection.setFixedLengthStreamingMode(length)
      connection.outputStream.use { out ->
        file.inputStream().use { input ->
          var skipped = 0L
          while (skipped < from) skipped += input.skip(from - skipped).also { if (it <= 0) throw IOException("non riesco a posizionarmi nel file") }
          val buffer = ByteArray(64 * 1024)
          var sent = 0L
          var lastReported = 0L
          while (sent < length) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - sent).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            sent += read
            if (sent - lastReported >= REPORT_EVERY_BYTES) {
              lastReported = sent
              onProgress(sent)
            }
          }
        }
        out.flush()
      }
      onProgress(length)
      read(connection, serializer)
    } finally {
      connection.disconnect()
    }
  }

  private suspend fun <T> call(baseUrl: String, path: String, token: String, method: String, body: String?, serializer: KSerializer<T>): T = withContext(io) {
    val target = if (path.startsWith("/v1/")) SyncApi.normalize(baseUrl) + path else baseUrl + path
    val connection = open(target, method, token)
    try {
      connection.setRequestProperty("Accept", "application/json")
      if (body != null) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val bytes = body.toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
      }
      read(connection, serializer)
    } finally {
      connection.disconnect()
    }
  }

  private fun <T> read(connection: HttpURLConnection, serializer: KSerializer<T>): T {
    val code = connection.responseCode
    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    if (code !in 200..299) throw ShareException(code, errorMessage(text, code))
    return SyncCodec.json.decodeFromString(serializer, text)
  }

  private fun open(url: String, method: String, token: String, readTimeoutMillis: Int = READ_TIMEOUT_MS): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = readTimeoutMillis
    connection.setRequestProperty("User-Agent", userAgent)
    connection.setRequestProperty("Authorization", "Bearer $token")
    return connection
  }

  private fun errorMessage(text: String, code: Int): String =
    runCatching { SyncCodec.json.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()?.takeIf { it.isNotBlank() } ?: "risposta $code"

  private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

  @Serializable private data class CreateBody(val noteId: String, val title: String)
  @Serializable private data class SharesBody(val shares: List<ShareInfo> = emptyList())
  @Serializable private data class Revoked(val revoked: Boolean = false)
  @Serializable private data class Aborted(val aborted: Boolean = false)
  @Serializable private data class BeginBody(val mime: String)
  @Serializable private data class Begun(val uploadId: String)
  @Serializable private data class CompleteBody(val parts: List<UploadedPart>)
  @Serializable private data class Bytes(val bytes: Long = 0)
  @Serializable private data class ErrorBody(val error: String = "")

  companion object {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 60_000
    const val REPORT_EVERY_BYTES = 256 * 1024L

    /** Fin qui una richiesta sola; oltre, a blocchi. Un Worker accetta cento megabyte per richiesta: si sta larghi. */
    const val WHOLE_LIMIT = 20L * 1024 * 1024
    const val CHUNK = 20L * 1024 * 1024
  }
}

/** Il server ha risposto, ma non bene. `code` zero: non si e' arrivati a chiedere. */
class ShareException(val code: Int, message: String) : IOException(message)
