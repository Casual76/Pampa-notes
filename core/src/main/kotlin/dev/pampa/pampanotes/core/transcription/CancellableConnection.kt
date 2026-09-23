package dev.pampa.pampanotes.core.transcription

import java.net.HttpURLConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Esegue [block] su una connessione, e la chiude da fuori se la coroutine viene annullata.
 *
 * Una lettura bloccata su una socket non sente la cancellazione: `withContext(io)` aspetta che il
 * blocco finisca, e il blocco aspetta il server. Col computer di casa che macina un'ora di audio,
 * o che si e' spento a meta', «Annulla» restava senza effetto fino al timeout — che per il companion
 * era zero, cioe' mai. L'unico modo di svegliare quella lettura e' `disconnect()` da un altro
 * thread: lo fa una coroutine sorella che dorme finche' qualcuno non annulla.
 *
 * Tutta la lettura della risposta deve stare dentro [block]: all'uscita la sorella si chiude, e
 * chiudendosi stacca la connessione.
 */
internal suspend fun <T> HttpURLConnection.cancellable(
  io: CoroutineDispatcher,
  block: suspend (HttpURLConnection) -> T,
): T = coroutineScope {
  val connection = this@cancellable
  val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
    try {
      awaitCancellation()
    } finally {
      connection.disconnect()
    }
  }
  try {
    withContext(io) { block(connection) }
  } catch (t: Throwable) {
    // La lettura interrotta dal `disconnect` esce come un errore di rete qualsiasi: se il motivo e'
    // la cancellazione, deve uscire come cancellazione, o chi chiama la scambia per un guasto del
    // server e magari riprova.
    currentCoroutineContext().ensureActive()
    throw t
  } finally {
    watchdog.cancel()
  }
}
