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
import dev.pampa.pampanotes.core.transcription.cancellable

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
 *
 * Ogni chiamata si puo' annullare anche mentre aspetta il server ([cancellable]), e nessuna aspetta
 * per sempre: un PC che si spegne a meta' di un `PUT` lasciava il worker dell'archivio appeso a una
 * socket con il timeout a zero.
 */
class ArchiveHttp(
  private val userAgent: String,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) {

  suspend fun exists(url: String, token: String?): Boolean =
    open(url, "HEAD", token, readTimeoutMillis = SHORT_READ_TIMEOUT_MS).cancellable(io) { connection ->
      when (val code = connection.responseCode) {
        200 -> true
        404 -> false
        else -> throw ArchiveException(code, "risposta $code")
      }
    }

  /** Toglie un file dall'archivio. Vero se c'era. */
  suspend fun delete(url: String, token: String?): Boolean =
    open(url, "DELETE", token, readTimeoutMillis = SHORT_READ_TIMEOUT_MS).cancellable(io) { connection ->
      when (val code = connection.responseCode) {
        200 -> true
        404 -> false
        else -> throw ArchiveException(code, "risposta $code")
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
  ): Boolean {
    val total = file.length()
    // Il tempo di lettura parte quando l'ultimo byte e' uscito: da li' il server deve solo chiudere
    // il file e rispondere. Cinque minuti sono tanti per quello, e pochi per restare appesi a un
    // computer che si e' spento — prima era zero, cioe' per sempre.
    return open(url, "PUT", token, readTimeoutMillis = UPLOAD_READ_TIMEOUT_MS).cancellable(io) { connection ->
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
  ): String = open(url, "GET", token, readTimeoutMillis = DOWNLOAD_READ_TIMEOUT_MS).cancellable(io) { connection ->
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
    const val SHORT_READ_TIMEOUT_MS = 15_000
    const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
    const val UPLOAD_READ_TIMEOUT_MS = 5 * 60_000
    const val REPORT_EVERY_BYTES = 256 * 1024L
  }
}

/** Il server ha risposto, ma non bene. `code` e' l'HTTP (zero: non si e' arrivati a chiedere); 401 vuol dire token sbagliato. */
class ArchiveException(val code: Int, message: String) : IOException(message)
