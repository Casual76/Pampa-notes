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
 * **Di solito non fa fallire niente.** E' un di piu': un companion vecchio, o un altro server
 * compatibile OpenAI, risponde 404 e si smette in silenzio. Due 404 prima della prima risposta buona
 * e non uno: la prima domanda puo' partire un soffio prima che il server abbia registrato il lavoro.
 *
 * **Tranne quando sa che la POST aspetta per niente**, e allora lancia [RemoteJobLost], che chi lo
 * usa trasforma in un errore di rete da riprovare:
 *  - il lavoro l'aveva visto, e adesso il companion risponde 404 due volte di fila: e' ripartito (il
 *    PC riavviato, il processo morto) e il lavoro non esiste piu'. La POST, se la connessione non e'
 *    stata chiusa con un reset, restava appesa fino al timeout di lettura — novanta minuti, con la
 *    coda ferma dietro;
 *  - da [silenceLimitMs] nessuna domanda riceve una risposta HTTP qualunque: il PC e' spento, o la
 *    rete fra i due non c'e' piu'. Un 5xx invece e' una risposta: il computer c'e'.
 */
internal class RemoteJobPoller(
  private val fetch: suspend () -> JsonElement?,
  private val intervalMs: Long = DEFAULT_INTERVAL_MS,
  /** Un orologio che va solo avanti; i test gli passano il tempo virtuale. */
  private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
  private val silenceLimitMs: Long = LOST_AFTER_SILENCE_MS,
  private val onUpdate: (RemoteProgress) -> Unit,
) {

  /**
   * @param ready vero quando l'audio e' partito tutto: prima non c'e' niente da chiedere.
   * @throws RemoteJobLost quando il lavoro sul computer non c'e' piu' (vedi la classe).
   */
  suspend fun run(ready: () -> Boolean = { true }) {
    var seen = false
    var misses = 0
    var gone = 0
    var failures = 0
    // Da quando le domande non ricevono nessuna risposta: null finche' ne arriva una.
    var silentSince: Long? = null
    while (true) {
      delay(intervalMs)
      if (!ready()) continue
      val body = try {
        fetch()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        val code = httpCode(error)
        if (code != null) silentSince = null
        when {
          // Credenziali rifiutate a meta' lavoro: la POST ha gia' le sue, e chiedere ancora non serve.
          code == 401 || code == 403 -> return
          // Un 404 dopo una risposta buona: il companion non conosce piu' il lavoro. Uno solo puo'
          // essere un soffio (il lavoro appena chiuso, la POST che sta per tornare); due di fila no.
          code == 404 && seen -> if (++gone >= MAX_MISSES) throw RemoteJobLost(RemoteJobLost.Reason.FORGOTTEN)
          // Prima di una risposta buona, un server che non sa di cosa si parla: si smette.
          code != null && code in 400..499 -> if (seen || ++misses >= MAX_MISSES) return
          // 5xx: il computer c'e', e la POST se ne accorgera' per conto suo. Si insiste, non per sempre.
          code != null -> if (++failures >= MAX_FAILURES) return
          // Nessuna risposta: rete, PC spento, tempo scaduto. Si conta il tempo e non i tentativi,
          // perche' un tentativo puo' durare da un millisecondo a quindici secondi.
          else -> {
            val now = clock()
            val since = silentSince ?: now.also { silentSince = it }
            if (now - since >= silenceLimitMs) throw RemoteJobLost(RemoteJobLost.Reason.SILENT)
          }
        }
        continue
      }
      silentSince = null
      val progress = parse(body)
      if (progress == null) {
        // Un 200 che non e' il nostro JSON: un server che a quell'indirizzo risponde altro.
        if (!seen && ++misses >= MAX_MISSES) return
        continue
      }
      seen = true
      gone = 0
      failures = 0
      onUpdate(progress)
      if (progress.stage.finished) return
    }
  }

  companion object {
    const val DEFAULT_INTERVAL_MS = 1_000L
    const val MAX_MISSES = 2
    const val MAX_FAILURES = 30

    /**
     * Novanta secondi senza una risposta qualunque e il computer si da' per perso: abbastanza per un
     * singhiozzo del Wi-Fi o di Tailscale, poco rispetto ai novanta minuti che la POST aspetterebbe
     * da sola.
     */
    const val LOST_AFTER_SILENCE_MS = 90_000L

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

/**
 * Il lavoro sul computer di casa non c'e' piu', o il computer non risponde piu': la POST che lo
 * aspetta non tornera' (vedi [RemoteJobPoller]).
 */
internal class RemoteJobLost(val reason: Reason) : Exception(
  when (reason) {
    Reason.FORGOTTEN -> "il computer di casa non conosce piu' il lavoro: e' stato riavviato?"
    Reason.SILENT -> "il computer di casa non risponde da ${RemoteJobPoller.LOST_AFTER_SILENCE_MS / 1000} secondi"
  },
) {
  enum class Reason { FORGOTTEN, SILENT }
}
