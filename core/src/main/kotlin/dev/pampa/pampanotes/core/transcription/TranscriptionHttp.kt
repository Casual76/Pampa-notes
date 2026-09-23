package dev.pampa.pampanotes.core.transcription

import dev.antigravity.fluidengine.ai.net.AiErrorMapper
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Le due richieste che servono a trascrivere, fatte come servono a noi.
 *
 * `AiHttp` dell'engine sa gia' fare multipart, e all'inizio bastava. Non basta piu' per tre motivi,
 * e sono tutti e tre conseguenze del fatto che qui i file sono grandi e le attese lunghe:
 *
 *  1. **Si deve poter annullare.** Una socket bloccata in scrittura o in lettura non si interrompe:
 *     l'unico modo e' chiamare `disconnect()` da fuori. Qui la cancellazione della coroutine lo fa
 *     ([cancellable]), anche mentre si aspetta la risposta.
 *  2. **Serve il progresso.** Mandare venti megabyte su una rete mobile dura un minuto, e un minuto
 *     davanti a uno spinner fermo e' un minuto in cui l'app sembra bloccata.
 *  3. **Il tempo di lettura non e' uno solo.** Groq risponde in secondi; il computer di casa che
 *     macina un'ora di audio puo' metterci mezz'ora, e sessanta secondi di timeout la ucciderebbero
 *     sul nascere.
 */
class TranscriptionHttp(
  private val userAgent: String,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /**
   * Manda un file e restituisce il JSON della risposta.
   *
   * @param readTimeoutMillis quanto aspettare la risposta dopo aver finito di mandare. Mai zero:
   *   zero vuol dire per sempre, e un computer spento a meta' lavoro lasciava la coda appesa a una
   *   socket che nessuno chiudeva. Il limite vero resta il `withTimeout` di chi chiama, che adesso
   *   stacca anche la connessione; questo e' il paracadute.
   */
  suspend fun postAudio(
    url: String,
    headers: Map<String, String>,
    fields: Map<String, String>,
    file: File,
    fileMime: String,
    fileName: String = file.name,
    readTimeoutMillis: Int,
    onProgress: (UploadProgress) -> Unit = {},
  ): JsonElement? {
    val boundary = "----PampaNotes${System.nanoTime().toString(16)}"
    val prelude = buildPrelude(boundary, fields, fileName, fileMime)
    val epilogue = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
    val total = prelude.size.toLong() + file.length() + epilogue.size

    val connection = open(url, "POST", headers, readTimeoutMillis.coerceAtLeast(1))
    return connection.cancellable(io) { exchange(connection) {
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
      // A lunghezza fissa e non a blocchi: cosi' la barra ha un totale da cui calcolare, e il server
      // conosce la dimensione prima di cominciare a ricevere.
      connection.setFixedLengthStreamingMode(total)

      connection.outputStream.use { out ->
        val counting = CountingOutputStream(out, total, onProgress)
        counting.write(prelude)
        file.inputStream().use { input ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            // Il posto giusto per accorgersi di una cancellazione: fra un blocco e l'altro, quando
            // la socket non e' in attesa e chiudere non lascia niente a meta'.
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            counting.write(buffer, 0, read)
          }
        }
        counting.write(epilogue)
        counting.flush()
      }
      readBody(connection)
    } }
  }

  /**
   * Lo stesso multipart di [postAudio], ma senza file: i soli campi. E' la trascrizione per
   * riferimento, in cui il computer di casa il file ce l'ha gia' e il telefono manda solo l'impronta.
   * Multipart e non JSON perche' dall'altra parte e' lo stesso endpoint, che legge un form.
   */
  suspend fun postForm(
    url: String,
    headers: Map<String, String>,
    fields: Map<String, String>,
    readTimeoutMillis: Int,
  ): JsonElement? {
    val boundary = "----PampaNotes${System.nanoTime().toString(16)}"
    val body = (fieldsPart(boundary, fields) + "--$boundary--\r\n").toByteArray(Charsets.UTF_8)
    val connection = open(url, "POST", headers, readTimeoutMillis.coerceAtLeast(1))
    return connection.cancellable(io) {
      exchange(connection) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        readBody(connection)
      }
    }
  }

  suspend fun getJson(url: String, headers: Map<String, String>, readTimeoutMillis: Int = 15_000): JsonElement? {
    val connection = open(url, "GET", headers, readTimeoutMillis)
    return connection.cancellable(io) { exchange(connection) { readBody(connection) } }
  }

  /**
   * Una `DELETE` breve: chi la manda ha fretta (sta annullando) e non puo' aspettare i quindici
   * secondi di connessione di una richiesta normale, quindi [timeoutMillis] vale per connettersi e
   * per leggere.
   */
  suspend fun delete(url: String, headers: Map<String, String>, timeoutMillis: Int): JsonElement? {
    val connection = open(url, "DELETE", headers, timeoutMillis.coerceAtLeast(1))
    connection.connectTimeout = timeoutMillis.coerceAtLeast(1)
    return connection.cancellable(io) { exchange(connection) { readBody(connection) } }
  }

  /** Un JSON piccolo in andata e in ritorno: il biglietto per il computer di casa, chiesto al Worker. */
  suspend fun postJson(url: String, headers: Map<String, String>, body: String, readTimeoutMillis: Int = 30_000): JsonElement? {
    val connection = open(url, "POST", headers, readTimeoutMillis)
    return connection.cancellable(io) {
      exchange(connection) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        readBody(connection)
      }
    }
  }

  /**
   * Gli errori tradotti una volta sola, e la connessione chiusa comunque vada.
   *
   * Annullati, non si traduce niente: [mapHttp] chiede il codice di risposta, e su una connessione
   * appena staccata `responseCode` rifarebbe la richiesta da capo.
   */
  private suspend inline fun <T> exchange(connection: HttpURLConnection, block: () -> T): T = try {
    block()
  } catch (t: Throwable) {
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    throw TranscriptionError.from(mapHttp(connection, t))
  } finally {
    connection.disconnect()
  }

  private fun open(url: String, method: String, headers: Map<String, String>, readTimeoutMillis: Int): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = readTimeoutMillis
    connection.setRequestProperty("User-Agent", userAgent)
    connection.setRequestProperty("Accept", "application/json")
    headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
    return connection
  }

  private fun readBody(connection: HttpURLConnection): JsonElement? {
    val code = connection.responseCode
    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    if (code !in 200..299) {
      throw httpFailure(code, connection.headerFields.orEmpty(), text)
    }
    return runCatching { json.parseToJsonElement(text) }.getOrNull()
  }

  /** Un errore durante la scrittura puo' nascondere una risposta gia' pronta: 413 arriva cosi'. */
  private fun mapHttp(connection: HttpURLConnection, t: Throwable): Throwable {
    if (t is kotlinx.coroutines.CancellationException) return t
    // Gia' tradotto da [readBody], col corpo della risposta: rileggerlo qui dava un corpo vuoto (lo
    // stream e' gia' consumato) e un «HTTP 404» al posto di quello che il server aveva detto.
    if (t is dev.antigravity.fluidengine.ai.net.AiError || t is TranscriptionError) return t
    val code = runCatching { connection.responseCode }.getOrNull() ?: return AiErrorMapper.wrap(t)
    if (code in 200..299) return AiErrorMapper.wrap(t)
    val body = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
    return httpFailure(code, connection.headerFields.orEmpty(), body)
  }

  /**
   * Da una risposta d'errore all'eccezione. Come l'engine, tranne il 503: l'engine legge il
   * `Retry-After` solo per il 429, e un 503 del companion che si sta riavviando dice proprio quanto
   * aspettare. Si tiene in [TranscriptionError.Server.retryAfterSec].
   */
  private fun httpFailure(code: Int, headers: Map<String?, List<String>>, body: String): Throwable {
    val mapped = AiErrorMapper.map(code, headers, body)
    if (code != 503) return mapped
    val retryAfter = AiErrorMapper.parseRetryAfter(AiErrorMapper.normalize(headers), null, "")
    return TranscriptionError.Server(code, mapped.message ?: body, retryAfter)
  }

  private fun buildPrelude(
    boundary: String,
    fields: Map<String, String>,
    fileName: String,
    fileMime: String,
  ): ByteArray {
    val builder = StringBuilder(fieldsPart(boundary, fields))
    builder.append("--").append(boundary).append("\r\n")
    builder.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
      .append(sanitize(fileName)).append("\"\r\n")
    builder.append("Content-Type: ").append(fileMime).append("\r\n\r\n")
    return builder.toString().toByteArray(Charsets.UTF_8)
  }

  private fun fieldsPart(boundary: String, fields: Map<String, String>): String = buildString {
    fields.forEach { (name, value) ->
      append("--").append(boundary).append("\r\n")
      append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
      append(value).append("\r\n")
    }
  }

  /** Un nome con virgolette o a capo dentro rompe l'intestazione multipart, e certi server lo rifiutano. */
  private fun sanitize(name: String): String = name.replace(Regex("[\"\\r\\n]"), "_")

  private companion object {
    const val CONNECT_TIMEOUT_MS = 15_000
  }
}

/** Conta i byte mentre passano e lo dice, non piu' spesso di quanto serva a muovere una barra. */
private class CountingOutputStream(
  private val delegate: OutputStream,
  private val total: Long,
  private val onProgress: (UploadProgress) -> Unit,
) : OutputStream() {
  private var sent = 0L
  private var lastReported = 0L

  override fun write(b: Int) {
    delegate.write(b)
    advance(1)
  }

  override fun write(b: ByteArray, off: Int, len: Int) {
    delegate.write(b, off, len)
    advance(len.toLong())
  }

  override fun flush() {
    delegate.flush()
    onProgress(UploadProgress(sent, total))
  }

  private fun advance(count: Long) {
    sent += count
    // Una segnalazione ogni 128 kB: sessanta aggiornamenti per otto megabyte sono gia' piu' di
    // quanti fotogrammi una barra riesca a mostrare.
    if (sent - lastReported >= REPORT_EVERY_BYTES) {
      lastReported = sent
      onProgress(UploadProgress(sent, total))
    }
  }

  private companion object {
    const val REPORT_EVERY_BYTES = 128 * 1024
  }
}

/** `ensureActive` su un contesto: la cancellazione si controlla dentro un ciclo di scrittura. */
private fun kotlin.coroutines.CoroutineContext.ensureActive() {
  val job = this[kotlinx.coroutines.Job] ?: return
  if (!job.isActive) throw kotlinx.coroutines.CancellationException("trascrizione annullata")
}
