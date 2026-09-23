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
 * Il companion ha risposto a `GET /health`: e' vivo. [instance] e' l'id del suo processo (nuovo a
 * ogni avvio), null su un companion che non lo dice (1.0.0) o su una risposta d'errore — che e' pur
 * sempre una risposta.
 */
internal data class CompanionPulse(val instance: String?)

/**
 * Chiede al computer di casa a che punto e' una trascrizione, finche' la risposta non arriva.
 *
 * Mandato l'ultimo byte, la richiesta resta aperta per tutto il lavoro — dieci minuti, un'ora — e
 * dal telefono era un'attesa muta. Il companion tiene lo stato di ogni lavoro sotto l'id che l'app
 * gli ha dato (`X-Pampa-Job`) e lo dice a `GET /v1/jobs/<id>`: questo lo chiede ogni secondo, su una
 * coroutine sua, accanto alla POST.
 *
 * **La POST e' quella che conta.** Queste domande sono un di piu', e l'unica cosa che possono fare
 * oltre a raccontare e' lanciare [RemoteJobLost] — che chi le usa trasforma in un errore di rete da
 * riprovare — quando sono sicure che la POST aspetta per niente. Sicure vuol dire confermate da una
 * sonda indipendente ([probe], `GET /health` con un tempo lungo), perche' abbandonare un lavoro
 * buono costa una lezione intera da rifare:
 *
 *  - **Perso** ([RemoteJobLost.Reason.FORGOTTEN]): il lavoro l'aveva visto, ora due 404 di fila, e
 *    `/health` risponde con un `instance` diverso da quello del lavoro: il companion e' ripartito.
 *    Stesso `instance`: il processo e' quello di prima e la POST e' ancora viva — si smette di
 *    chiedere del lavoro e si guarda solo che il computer resti acceso (vedi sotto). Un companion
 *    senza `instance` (1.0.0) non si puo' confermare: due 404 bastano, come prima.
 *  - **Muto** ([RemoteJobLost.Reason.SILENT]): da [silenceLimitMs] nessuna domanda riceve una
 *    risposta HTTP qualunque, **e** anche `/health` non risponde. Se `/health` risponde il PC c'e'
 *    (e' solo lento a dire lo stato): il conto riparte e si aspetta.
 *
 * Quando chiedere del lavoro non serve piu' — il lavoro e' finito e la risposta sta arrivando, il
 * companion l'ha dimenticato ma e' lo stesso processo, uno stato che non si capisce — si passa alla
 * **sola sonda**: `/health` ogni [blindIntervalMs], che non racconta niente ma continua ad accorgersi
 * di un PC spento o riavviato. Senza sonda ([probe] null) si smette e basta, come prima.
 *
 * Due 404 prima della prima risposta buona sono un server che non conosce i lavori (un companion
 * vecchio, un altro server compatibile) — a meno che `/health` abbia un `instance`: allora e' un
 * companion nuovo che non ha ancora registrato il lavoro (il biglietto si sta verificando), e si
 * continua a chiedere per [NOT_REGISTERED_PATIENCE_MS].
 *
 * Il tempo si misura con [clock], che deve andare solo avanti (`System.nanoTime`): l'orologio del
 * telefono puo' saltare di un'ora a meta' lezione, e un silenzio misurato con quello sarebbe falso.
 */
internal class RemoteJobPoller(
  private val fetch: suspend () -> JsonElement?,
  private val intervalMs: Long = DEFAULT_INTERVAL_MS,
  /** Un orologio monotono, in millisecondi; i test gli passano il tempo virtuale. */
  private val clock: () -> Long = MONOTONIC_MS,
  private val silenceLimitMs: Long = LOST_AFTER_SILENCE_MS,
  /** `GET /health`: null se non ha risposto. Senza, niente conferme e niente sola sonda. */
  private val probe: (suspend () -> CompanionPulse?)? = null,
  private val blindIntervalMs: Long = BLIND_INTERVAL_MS,
  private val onUpdate: (RemoteProgress) -> Unit,
) {

  /**
   * @param ready vero quando l'audio e' partito tutto: prima non c'e' niente da chiedere, e quindi
   *   nemmeno un silenzio da misurare — un caricamento lento non e' un PC spento.
   * @throws RemoteJobLost quando il lavoro sul computer non c'e' piu' (vedi la classe).
   */
  suspend fun run(ready: () -> Boolean = { true }) {
    var seen = false
    var misses = 0
    var gone = 0
    var failures = 0
    // L'id del processo del companion che ha il lavoro: dalla prima risposta che lo dice.
    var instance: String? = null
    // Da quando nessuno risponde (domande sul lavoro, o sonde nella sola sonda). Null se qualcuno ha risposto.
    var silentSince: Long? = null
    // Da quando il companion (nuovo) dice di non conoscere un lavoro che non si e' ancora visto.
    var unregisteredSince: Long? = null
    var blind = false

    /** Alla sola sonda, se c'e' una sonda e qualcosa con cui confrontarla. Falso: si smette. */
    fun goBlind(): Boolean {
      if (probe == null || instance == null) return false
      blind = true
      return true
    }

    fun restarted(pulse: CompanionPulse): Boolean =
      instance != null && pulse.instance != null && pulse.instance != instance

    while (true) {
      if (blind) {
        delay(blindIntervalMs)
        val pulse = probe!!.invoke()
        val now = clock()
        if (pulse == null) {
          val since = silentSince ?: now.also { silentSince = it }
          // La sonda e' gia' la conferma: novanta secondi di sonde mute.
          if (now - since >= silenceLimitMs) throw RemoteJobLost(RemoteJobLost.Reason.SILENT)
        } else {
          silentSince = null
          if (restarted(pulse)) throw RemoteJobLost(RemoteJobLost.Reason.FORGOTTEN)
        }
        continue
      }

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

          code == 404 && seen -> if (++gone >= MAX_MISSES) {
            gone = 0
            // Un companion che non dice chi e' (1.0.0): due 404 di fila bastano, come prima.
            if (instance == null || probe == null) throw RemoteJobLost(RemoteJobLost.Reason.FORGOTTEN)
            val pulse = probe.invoke()
            when {
              // Nemmeno /health: si misura il silenzio da qui, con le sonde.
              pulse == null -> {
                silentSince = clock()
                blind = true
              }
              restarted(pulse) -> throw RemoteJobLost(RemoteJobLost.Reason.FORGOTTEN)
              // Lo stesso processo: il lavoro non e' perso, e' la sua scheda che non c'e'. Si
              // aspetta la POST, guardando solo che il computer resti acceso.
              else -> blind = true
            }
          }

          code == 404 -> {
            val now = clock()
            val since = unregisteredSince ?: now.also { unregisteredSince = it }
            if (instance != null) {
              // Un companion nuovo che il lavoro non l'ha ancora registrato: si pazienta, non per sempre.
              if (now - since >= NOT_REGISTERED_PATIENCE_MS && !goBlind()) return
            } else if (++misses >= MAX_MISSES) {
              // Chi e'? Un companion con `instance` conosce i lavori; gli altri no, e si smette.
              val pulse = probe?.invoke()
              instance = pulse?.instance ?: return
            }
          }

          // Un altro 4xx: un server che non sa di cosa si parla, o che non vuole dirlo.
          code != null && code in 400..499 -> if ((seen || ++misses >= MAX_MISSES) && !goBlind()) return

          // 5xx: il computer c'e', e la POST se ne accorgera' per conto suo. Si insiste, non per sempre.
          code != null -> if (++failures >= MAX_FAILURES && !goBlind()) return

          // Nessuna risposta: rete, PC spento, tempo scaduto. Si conta il tempo e non i tentativi,
          // perche' un tentativo puo' durare da un millisecondo a quindici secondi.
          else -> {
            val now = clock()
            val since = silentSince ?: now.also { silentSince = it }
            if (now - since >= silenceLimitMs) {
              val pulse = probe?.invoke()
              when {
                pulse == null -> throw RemoteJobLost(RemoteJobLost.Reason.SILENT)
                restarted(pulse) -> throw RemoteJobLost(RemoteJobLost.Reason.FORGOTTEN)
                // Il PC c'e': e' solo lento a dire lo stato. Si ricomincia a contare.
                else -> silentSince = null
              }
            }
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
      unregisteredSince = null
      if (instance == null) instance = instanceOf(body)
      onUpdate(progress)
      // Finito: la risposta della POST sta arrivando. Se si puo', si resta a guardare che il PC non
      // si spenga proprio adesso; altrimenti si smette.
      if (progress.stage.finished && !goBlind()) return
    }
  }

  companion object {
    const val DEFAULT_INTERVAL_MS = 1_000L
    const val MAX_MISSES = 2
    const val MAX_FAILURES = 30

    /**
     * Novanta secondi senza una risposta qualunque — e senza `/health` — e il computer si da' per
     * perso: abbastanza per un singhiozzo del Wi-Fi o di Tailscale, poco rispetto ai novanta minuti
     * che la POST aspetterebbe da sola.
     */
    const val LOST_AFTER_SILENCE_MS = 90_000L

    /** Nella sola sonda, una `/health` ogni dieci secondi. */
    const val BLIND_INTERVAL_MS = 10_000L

    /** Quanto un companion nuovo puo' dire di non conoscere un lavoro appena mandato. */
    const val NOT_REGISTERED_PATIENCE_MS = 60_000L

    /** Monotono: non salta quando l'utente, o la rete, cambia l'ora del telefono. */
    val MONOTONIC_MS: () -> Long = { System.nanoTime() / 1_000_000 }

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

    /** L'`instance` di una risposta del companion (`/health` o lo stato di un lavoro), se c'e'. */
    fun instanceOf(body: JsonElement?): String? =
      (body as? JsonObject)?.string("instance")?.trim()?.takeIf { it.isNotEmpty() }

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
