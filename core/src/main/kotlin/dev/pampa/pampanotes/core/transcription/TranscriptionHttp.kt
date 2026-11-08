package dev.pampa.pampanotes.core.transcription

import dev.antigravity.fluidengine.ai.net.AiErrorMapper
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
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
  private val cloudGate: CloudRequestGate? = null,
  private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /** Keep multi-request metadata/setup on its initial account/key, including between requests. */
  suspend fun <T> cloudSession(url: String, headers: Map<String, String>, block: suspend () -> T): T {
    val permit = cloudGate?.permit(url, headers) ?: return block()
    return kotlinx.coroutines.withContext(CloudRequestContext(permit)) {
      block().also { permit.checkIdentity() }
    }
  }

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
    onResponse: ((JsonElement?) -> Unit)? = null,
  ): JsonElement? {
    val upload = currentCoroutineContext()[CloudUploadContext]
    val accounting = currentCoroutineContext()[CloudAttemptAccounting]
    val permit = try { cloudGate?.permit(url, headers) } catch (error: Throwable) {
      withContext(NonCancellable + io) { accounting?.refusedBeforeDispatch() }
      throw error
    }
    val boundary = "----PampaNotes${System.nanoTime().toString(16)}"
    val prelude = buildPrelude(boundary, fields, fileName, fileMime)
    val epilogue = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
    val total = prelude.size.toLong() + file.length() + epilogue.size

    val connection = open(url, "POST", headers, readTimeoutMillis.coerceAtLeast(1))
    return connection.cancellable(io) { exchange(connection, onResponse, permit, upload, accounting) { boundUpload, boundAccounting ->
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
      readBody(connection, onResponse, permit, boundUpload, boundAccounting)
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
    val permit = cloudGate?.permit(url, headers)
    val boundary = "----PampaNotes${System.nanoTime().toString(16)}"
    val body = (fieldsPart(boundary, fields) + "--$boundary--\r\n").toByteArray(Charsets.UTF_8)
    val connection = open(url, "POST", headers, readTimeoutMillis.coerceAtLeast(1))
    return connection.cancellable(io) {
      exchange(connection, permit = permit) { _, _ ->
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        readBody(connection, permit = permit)
      }
    }
  }

  suspend fun getJson(url: String, headers: Map<String, String>, readTimeoutMillis: Int = 15_000): JsonElement? {
    val permit = cloudGate?.permit(url, headers)
    val connection = open(url, "GET", headers, readTimeoutMillis)
    return connection.cancellable(io) { exchange(connection, permit = permit) { _, _ -> readBody(connection, permit = permit) } }
  }

  /**
   * Una `DELETE` breve: chi la manda ha fretta (sta annullando) e non puo' aspettare i quindici
   * secondi di connessione di una richiesta normale, quindi [timeoutMillis] vale per connettersi e
   * per leggere.
   */
  suspend fun delete(url: String, headers: Map<String, String>, timeoutMillis: Int): JsonElement? {
    val permit = cloudGate?.permit(url, headers)
    val connection = open(url, "DELETE", headers, timeoutMillis.coerceAtLeast(1))
    connection.connectTimeout = timeoutMillis.coerceAtLeast(1)
    return connection.cancellable(io) { exchange(connection, permit = permit) { _, _ -> readBody(connection, permit = permit) } }
  }

  /** Un JSON piccolo in andata e in ritorno: il biglietto per il computer di casa, chiesto al Worker. */
  suspend fun postJson(url: String, headers: Map<String, String>, body: String, readTimeoutMillis: Int = 30_000): JsonElement? {
    val permit = cloudGate?.permit(url, headers)
    val connection = open(url, "POST", headers, readTimeoutMillis)
    return connection.cancellable(io) {
      exchange(connection, permit = permit) { _, _ ->
        val bytes = body.toByteArray(Charsets.UTF_8)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        readBody(connection, permit = permit)
      }
    }
  }

  /**
   * Gli errori tradotti una volta sola, e la connessione chiusa comunque vada.
   *
   * Annullati, non si traduce niente: [mapHttp] chiede il codice di risposta, e su una connessione
   * appena staccata `responseCode` rifarebbe la richiesta da capo.
   */
  private suspend inline fun <T> exchange(connection: HttpURLConnection, noinline onResponse: ((JsonElement?) -> Unit)? = null, permit: CloudRequestGate.Permit? = null, upload: CloudUploadContext? = null, accounting: CloudAttemptAccounting? = null, block: (CloudUploadContext?, CloudAttemptAccounting?) -> T): T {
    // A failed local gate must never fall through to mapHttp's responseCode network probe.
    var boundAccounting: CloudAttemptAccounting? = null
    val boundUpload = try {
      permit?.beforeRequest()
      boundAccounting = accounting?.dispatch(connection.url.toString())
      upload?.dispatch(connection.url.toString())
    } catch (t: Throwable) {
      withContext(NonCancellable + io) { accounting?.refusedBeforeDispatch() }
      connection.disconnect()
      throw t
    }
    return try {
      block(boundUpload, boundAccounting)
    } catch (t: Throwable) {
      if (t is kotlinx.coroutines.CancellationException) throw t
      // readBody already persisted any observed 429. Never probe responseCode on a cancelled socket.
      kotlinx.coroutines.currentCoroutineContext().ensureActive()
      throw TranscriptionError.from(mapHttp(connection, t, onResponse, permit, boundUpload, boundAccounting))
    } finally {
      connection.disconnect()
    }
  }

  private fun open(url: String, method: String, headers: Map<String, String>, readTimeoutMillis: Int): HttpURLConnection {
    val connection = connectionFactory(URL(url))
    connection.requestMethod = method
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = readTimeoutMillis
    connection.setRequestProperty("User-Agent", userAgent)
    connection.setRequestProperty("Accept", "application/json")
    headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
    return connection
  }

  private suspend fun readBody(connection: HttpURLConnection, onResponse: ((JsonElement?) -> Unit)? = null, permit: CloudRequestGate.Permit? = null, upload: CloudUploadContext? = null, accounting: CloudAttemptAccounting? = null): JsonElement? {
    val code = connection.responseCode
    // Record headers immediately, before body IO, usage callbacks or cancellation can fail.
    val quota = observed(connection, code, permit, upload, accounting)
    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
    // Capture usage.cost on both success and error responses before transcript/error parsing.
    // Failure to persist accounting propagates; the durable reservation remains conservative.
    accountResponse(onResponse, parsed, upload)
    if (code !in 200..299) {
      throw (quota ?: httpFailure(code, safeHeaders(connection), text))
    }
    permit?.checkIdentity()
    return parsed
  }

  /** Un errore durante la scrittura puo' nascondere una risposta gia' pronta: 413 arriva cosi'. */
  private suspend fun mapHttp(connection: HttpURLConnection, t: Throwable, onResponse: ((JsonElement?) -> Unit)? = null, permit: CloudRequestGate.Permit? = null, upload: CloudUploadContext? = null, accounting: CloudAttemptAccounting? = null): Throwable {
    if (t is kotlinx.coroutines.CancellationException) return t
    // Gia' tradotto da [readBody], col corpo della risposta: rileggerlo qui dava un corpo vuoto (lo
    // stream e' gia' consumato) e un «HTTP 404» al posto di quello che il server aveva detto.
    if (t is dev.antigravity.fluidengine.ai.net.AiError || t is TranscriptionError) return t
    val code = runCatching { connection.responseCode }.getOrNull() ?: return AiErrorMapper.wrap(t)
    val quota = observed(connection, code, permit, upload, accounting)
    if (code in 200..299) return AiErrorMapper.wrap(t)
    val body = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
    accountResponse(onResponse, runCatching { json.parseToJsonElement(body) }.getOrNull(), upload)
    return quota ?: httpFailure(code, safeHeaders(connection), body)
  }

  private suspend fun accountResponse(onResponse: ((JsonElement?) -> Unit)?, body: JsonElement?, upload: CloudUploadContext?) {
    try { onResponse?.invoke(body) }
    catch (error: Throwable) {
      withContext(NonCancellable + io) { upload?.uncertain() }
      throw error
    }
  }

  /** Known status is durable before fallible header IO. Missing/unreadable timing uses an
   * explicitly unverified local wait; a readable Retry-After extends it without truncation.
   * Only after all required writes succeed may the exact intent become retryable.
   */
  private suspend fun observed(connection: HttpURLConnection, code: Int, permit: CloudRequestGate.Permit?, upload: CloudUploadContext?, accounting: CloudAttemptAccounting?): TranscriptionError.RateLimited? {
    if (code != 401 && code != 402 && code != 429) return null
    return withContext(NonCancellable + io) {
      upload?.observedRefusal(code)
      var quota = if (code == 429) TranscriptionError.RateLimited(null, "Retry-After unavailable; local retry wait") else null
      if (quota != null && permit != null) quota = permit.observed(quota)
      accounting?.rejected()
      // No header accessor runs until the known refusal and its accounting are saved.
      var headerCancellation: kotlinx.coroutines.CancellationException? = null
      val headers = if (code == 429) try { safeHeaders(connection) }
        catch (stop: kotlinx.coroutines.CancellationException) { headerCancellation = stop; emptyMap() }
      else emptyMap()
      if (code == 429) {
        val readable = httpFailure(code, headers, "") as TranscriptionError.RateLimited
        if (readable.retryAtMillis != null || readable.retryAfterSec != null)
          quota = permit?.observed(readable) ?: readable
      }
      if (upload != null) upload.rejected()
      else {
        // A metadata refusal proves nothing about a dispatched audio POST. It can only
        // settle this context's NEW intent while dispatch is still impossible. The CAS
        // also closes dispatch before disk IO, including concurrent metadata callbacks.
        val refusal = when (code) {
          401 -> TranscriptionError.Unauthorized("Cloud authentication refused before audio upload")
          402 -> TranscriptionError.CreditRequired("Cloud credit required before audio upload")
          else -> quota!!
        }
        currentCoroutineContext()[CloudUploadContext]?.refusedBeforeDispatch(refusal)
      }
      headerCancellation?.let { throw it }
      quota
    }
  }

  private fun safeHeaders(connection: HttpURLConnection): Map<String?, List<String>> =
    try { connection.headerFields.orEmpty() } catch (_: java.io.IOException) { emptyMap() }

  /**
   * Da una risposta d'errore all'eccezione. Come l'engine, tranne il 503: l'engine legge il
   * `Retry-After` solo per il 429, e un 503 del companion che si sta riavviando dice proprio quanto
   * aspettare. Si tiene in [TranscriptionError.Server.retryAfterSec].
   */
  private fun httpFailure(code: Int, headers: Map<String?, List<String>>, body: String): Throwable {
    val mapped = AiErrorMapper.map(code, headers, body)
    if (code == 402) return TranscriptionError.CreditRequired("Cloud account credit required")
    if (code == 429) {
      val now = System.currentTimeMillis()
      val deadline = CloudRetry.parseRetryAfter(headers.entries.firstOrNull { it.key.equals("retry-after", true) }?.value?.firstOrNull(), now)
      val seconds = deadline?.let { (it - now).toDouble() / 1000 }
        ?: (mapped as? dev.antigravity.fluidengine.ai.net.AiError.RateLimited)?.retryAfterSec
      return TranscriptionError.RateLimited(seconds, "Cloud request rate limited", deadline)
    }
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
