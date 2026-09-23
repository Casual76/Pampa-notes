package dev.pampa.pampanotes.core.transcription

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Il lavoro si e' fermato: da [idleMs] nessun segno di vita, o ha superato il tetto complessivo.
 * Non e' una `CancellationException`: chi la riceve deve poterla distinguere da un «Annulla».
 */
class IdleTimeoutException(val capReached: Boolean) : Exception(
  if (capReached) "il lavoro ha superato il tetto di tempo" else "nessun progresso per troppo tempo",
)

/**
 * Un tetto di tempo che si misura dal **silenzio**, non dalla partenza.
 *
 * Un `withTimeout` sull'intera sessione uccideva lezioni lunghe che stavano andando benissimo: sei
 * registrazioni sul processore del PC sono ore, e il tetto di tre ore scattava a meta' con «il
 * servizio non ha risposto in tempo». Qui il conto riparte a ogni segno di vita ([block] chiama
 * `touch`, il worker a ogni evento di progresso): si ferma un lavoro muto, non uno lento. Il tetto
 * complessivo [capMs] resta, largo, per quello che parla ma non finisce mai.
 *
 * Il watchdog annulla il lavoro, che chiude le sue connessioni come per un «Annulla»; chi chiama
 * riceve [IdleTimeoutException]. [clock] e' monotono e iniettabile per i test.
 */
suspend fun <T> withIdleTimeout(
  idleMs: Long,
  capMs: Long,
  clock: () -> Long = { System.nanoTime() / 1_000_000 },
  block: suspend (touch: () -> Unit) -> T,
): T = coroutineScope {
  val startedAt = clock()
  val lastSign = AtomicLong(startedAt)
  var expired: IdleTimeoutException? = null
  val work = async { block { lastSign.set(clock()) } }
  val watchdog = launch {
    val every = (idleMs / 10).coerceIn(1L, CHECK_EVERY_MS)
    while (isActive) {
      delay(every)
      val now = clock()
      val reason = when {
        now - startedAt >= capMs -> IdleTimeoutException(capReached = true)
        now - lastSign.get() >= idleMs -> IdleTimeoutException(capReached = false)
        else -> null
      }
      if (reason != null) {
        expired = reason
        work.cancel(CancellationException(reason.message, reason))
        break
      }
    }
  }
  try {
    work.await()
  } catch (cancelled: CancellationException) {
    // Annullato dal watchdog: si dice perche'. Annullato da fuori (un «Annulla», il worker fermato):
    // passa com'e', e chi sta sopra sa cosa farne.
    throw expired ?: cancelled
  } finally {
    watchdog.cancel()
  }
}

/** Ogni quanto il watchdog guarda l'orologio: al piu' cinque secondi di ritardo su un tetto di ore. */
private const val CHECK_EVERY_MS = 5_000L
