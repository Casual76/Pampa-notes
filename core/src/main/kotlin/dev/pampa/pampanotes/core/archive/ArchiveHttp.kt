package dev.pampa.pampanotes.core.archive

import java.security.MessageDigest
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Le due chiamate dell'archivio sul computer di casa: «ce l'hai?» e «tieni».
 *
 * Non passa da `TranscriptionHttp` perche' quella parla multipart, e qui il corpo *e'* il file:
 * un `PUT` con il contenuto cosi' com'e', a lunghezza fissa, cosi' il server conosce la
 * dimensione prima di cominciare e la barra ha un totale. Il nome originale viaggia in un header
 * percent-encoded — gli header sono ASCII, «Voce 001 è.m4a» no.
 *
 * L'hash lo dice l'indirizzo (`/v1/files/<sha256>`), e il server lo ricalcola mentre scrive: un
 * caricamento interrotto o corrotto non lascia niente con il nome di quello buono.
 */
class ArchiveHttp(
  private val userAgent: String,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) {

  suspend fun exists(url: String, token: String?): Boolean = withContext(io) {
    val connection = open(url, "HEAD", token, readTimeoutMillis = 15_000)
    try {
      when (val code = connection.responseCode) {
        200 -> true
        404 -> false
        else -> throw ArchiveException(code, "risposta $code")
      }
    } finally {
      connection.disconnect()
    }
  }

  /** Toglie un file dall'archivio. Vero se c'era. */
  suspend fun delete(url: String, token: String?): Boolean = withContext(io) {
    val connection = open(url, "DELETE", token, readTimeoutMillis = 15_000)
    try {
      when (val code = connection.responseCode) {
        200 -> true
        404 -> false
        else -> throw ArchiveException(code, "risposta $code")
      }
    } finally {
      connection.disconnect()
    }
  }

  /** @return vero se il server l'ha scritto adesso, falso se ce l'aveva gia'. */
  suspend fun upload(
    url: String,
    token: String?,
    file: File,
    mime: String,
    originalName: String,
    onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
  ): Boolean = withContext(io) {
    val total = file.length()
    // Zero: si aspetta finche' serve. Un `.sdocx` da mezzo giga su una rete lenta e' un tempo che
    // non si indovina, e il limite vero lo mette chi chiama, con una cancellazione.
    val connection = open(url, "PUT", token, readTimeoutMillis = 0)
    try {
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", mime)
      // `URLEncoder` e' fatto per i form e scrive lo spazio come `+`; un nome di file lo vuole `%20`,
      // o dall'altra parte «Voce 001» arriva «Voce+001».
      connection.setRequestProperty("X-Pampa-Name", URLEncoder.encode(originalName, "UTF-8").replace("+", "%20"))
      connection.setFixedLengthStreamingMode(total)
      connection.outputStream.use { out ->
        file.inputStream().use { input ->
          val buffer = ByteArray(64 * 1024)
          var sent = 0L
          var lastReported = 0L
          while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            sent += read
            if (sent - lastReported >= REPORT_EVERY_BYTES) {
              lastReported = sent
              onProgress(sent, total)
            }
          }
        }
        out.flush()
      }
      onProgress(total, total)
      val code = connection.responseCode
      if (code !in 200..299) {
        val detail = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        throw ArchiveException(code, detail.ifBlank { "risposta $code" })
      }
      val body = connection.inputStream.bufferedReader().use { it.readText() }
      // `{"stored": true, ...}` oppure `{"stored": false, "existed": true}`: non serve un parser.
      body.contains("\"stored\": true") || body.contains("\"stored\":true")
    } finally {
      connection.disconnect()
    }
  }

  /**
   * Scarica in [target], calcolando l'impronta mentre scrive. Torna la sha256 esadecimale di
   * quello che ha scritto: chi chiama la confronta con quella attesa, e un file diverso non prende
   * mai il nome di quello buono.
   */
  suspend fun download(
    url: String,
    token: String?,
    target: File,
    onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
  ): String = withContext(io) {
    val connection = open(url, "GET", token, readTimeoutMillis = 60_000)
    try {
      val code = connection.responseCode
      if (code !in 200..299) {
        val detail = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        throw ArchiveException(code, if (code == 404) "non e' nell'archivio del computer" else detail.ifBlank { "risposta $code" })
      }
      val total = connection.contentLengthLong
      val digest = MessageDigest.getInstance("SHA-256")
      var received = 0L
      var lastReported = 0L
      target.outputStream().use { out ->
        connection.inputStream.use { input ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            received += read
            if (received - lastReported >= REPORT_EVERY_BYTES) {
              lastReported = received
              onProgress(received, total)
            }
          }
        }
      }
      onProgress(received, if (total > 0) total else received)
      digest.digest().joinToString("") { "%02x".format(it) }
    } finally {
      connection.disconnect()
    }
  }

  private fun open(url: String, method: String, token: String?, readTimeoutMillis: Int): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = readTimeoutMillis
    connection.setRequestProperty("User-Agent", userAgent)
    token?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
    return connection
  }

  private suspend fun currentCoroutineContext() = kotlinx.coroutines.currentCoroutineContext()

  private companion object {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val REPORT_EVERY_BYTES = 256 * 1024L
  }
}

/** Il server ha risposto, ma non bene. `code` e' l'HTTP (zero: non si e' arrivati a chiedere); 401 vuol dire token sbagliato. */
class ArchiveException(val code: Int, message: String) : IOException(message)
