package dev.pampa.pampanotes.core.transcription

import dev.antigravity.fluidengine.ai.net.AiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Chiede al computer di casa a che punto e' una trascrizione, finche' la risposta non arriva.
 *
 * Mandato l'ultimo byte, la richiesta resta aperta per tutto il lavoro — dieci minuti, un'ora — e
 * dal telefono era un'attesa muta. Il companion adesso tiene lo stato di ogni lavoro sotto l'id che
 * l'app gli ha dato (`X-Pampa-Job`) e lo dice a `GET /v1/jobs/<id>`: questo lo chiede ogni secondo,
 * su una coroutine sua, accanto alla POST.
 *
 * **Non fa mai fallire niente.** E' un di piu': un companion vecchio, o un altro server compatibile
 * OpenAI, risponde 404 e si smette in silenzio; un errore di rete si lascia alla POST, che e' quella
 * che decide se il lavoro e' andato. Due 404 prima della prima risposta buona e non uno: la prima
 * domanda puo' partire un soffio prima che il server abbia registrato il lavoro.
 */
internal class RemoteJobPoller(
  private val fetch: suspend () -> JsonElement?,
  private val intervalMs: Long = DEFAULT_INTERVAL_MS,
  private val onUpdate: (RemoteProgress) -> Unit,
) {

  /** @param ready vero quando l'audio e' partito tutto: prima non c'e' niente da chiedere. */
  suspend fun run(ready: () -> Boolean = { true }) {
    var seen = false
    var misses = 0
    var failures = 0
    while (true) {
      delay(intervalMs)
      if (!ready()) continue
      val body = try {
        fetch()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        when (val code = httpCode(error)) {
          // Credenziali rifiutate a meta' lavoro: la POST ha gia' le sue, e chiedere ancora non serve.
          401, 403 -> return
          // Un 404 dopo una risposta buona e' il lavoro scaduto; prima, un server che non sa di cosa
          // si parla. In tutti e due i casi non c'e' altro da chiedere.
          in 400..499 -> if (seen || ++misses >= MAX_MISSES) return
          // Rete o 5xx: la POST se ne accorgera' per conto suo. Qui si insiste, ma non per sempre.
          else -> if (++failures >= MAX_FAILURES) return
        }
        continue
      }
      val progress = parse(body)
      if (progress == null) {
        // Un 200 che non e' il nostro JSON: un server che a quell'indirizzo risponde altro.
        if (!seen && ++misses >= MAX_MISSES) return
        continue
      }
      seen = true
      failures = 0
      onUpdate(progress)
      if (progress.stage.finished) return
    }
  }

  companion object {
    const val DEFAULT_INTERVAL_MS = 1_000L
    const val MAX_MISSES = 2
    const val MAX_FAILURES = 30

    /** Dal JSON del companion. Null se non e' quello, o se lo stadio e' uno che non conosciamo. */
    fun parse(body: JsonElement?): RemoteProgress? {
      val obj = body as? JsonObject ?: return null
      val stage = RemoteStage.fromCode(obj.string("state")) ?: return null
      return RemoteProgress(
        stage = stage,
        fraction = (obj.number("fraction") ?: 0.0).toFloat().coerceIn(0f, 1f),
        position = (obj["position"] as? JsonPrimitive)?.intOrNull,
        audioSeconds = obj.number("audio_s"),
        etaSeconds = obj.number("eta_s"),
        device = obj.string("device"),
        detail = obj.string("detail"),
        // Solo quando la registrazione la divide il computer (`max_minutes`).
        chunk = (obj["chunk"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
        chunks = (obj["chunks"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
      )
    }

    /** Il codice HTTP dentro un errore, se c'e': `TranscriptionHttp` lo avvolge. */
    fun httpCode(error: Throwable): Int? {
      var current: Throwable? = error
      while (current != null) {
        when (current) {
          is AiError.Unauthorized -> return 401
          is AiError.BadRequest -> return current.code
          is AiError.Server -> return current.code
          is AiError.RateLimited -> return 429
          else -> Unit
        }
        if (current is TranscriptionError.Unauthorized) return 401
        if (current is TranscriptionError.Server) return current.httpCode
        current = current.cause?.takeIf { it !== current }
      }
      return null
    }

    private fun JsonObject.string(key: String): String? =
      (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun JsonObject.number(key: String): Double? =
      (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
  }
}
