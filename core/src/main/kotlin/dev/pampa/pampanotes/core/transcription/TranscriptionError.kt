package dev.pampa.pampanotes.core.transcription

import dev.antigravity.fluidengine.ai.net.AiError
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Gli errori su cui la coda decide qualcosa: aspettare, riprovare, arrendersi, o dirlo all'utente.
 *
 * Il messaggio grezzo resta per la diagnostica; la frase che si legge la sceglie la UI dal tipo,
 * perche' quella del server e' quasi sempre in inglese e quasi sempre incomprensibile.
 */
sealed class TranscriptionError(message: String, cause: Throwable? = null) : Exception(message, cause) {

  /** Chiave mancante, sbagliata o revocata. Riprovare non serve: serve una chiave nuova. */
  class Unauthorized(message: String) : TranscriptionError(message)

  /** Il limite di richieste. [retryAfterSec] e' quanto il server chiede di aspettare, quando lo dice. */
  class RateLimited(val retryAfterSec: Double?, message: String) : TranscriptionError(message)

  /** Il file supera il tetto del servizio. Con il taglio in pezzi non dovrebbe succedere: se succede, il tetto e' sbagliato. */
  class FileTooLarge(val limitBytes: Long?, message: String) : TranscriptionError(message)

  /** 5xx: quasi sempre passa da solo. */
  class Server(val httpCode: Int, message: String) : TranscriptionError(message)

  class Network(message: String, cause: Throwable? = null) : TranscriptionError(message, cause)

  class Timeout(message: String, cause: Throwable? = null) : TranscriptionError(message, cause)

  /** L'audio non si e' potuto decodificare: formato che il telefono non conosce, file troncato. */
  class Decode(message: String, cause: Throwable? = null) : TranscriptionError(message, cause)

  /** Una risposta 2xx che non si capisce: JSON rotto, campi mancanti. */
  class Parse(message: String, cause: Throwable? = null) : TranscriptionError(message, cause)

  /** Il modello chiesto non esiste piu' sul servizio. */
  class UnknownModel(val model: String, message: String) : TranscriptionError(message)

  class Cancelled : TranscriptionError("annullato")

  /** Il codice con cui l'errore si salva sul lavoro, e da cui la UI ripesca la frase. */
  val code: String
    get() = when (this) {
      is Unauthorized -> "unauthorized"
      is RateLimited -> "rate_limited"
      is FileTooLarge -> "file_too_large"
      is Server -> "server"
      is Network -> "network"
      is Timeout -> "timeout"
      is Decode -> "decode"
      is Parse -> "parse"
      is UnknownModel -> "unknown_model"
      is Cancelled -> "cancelled"
    }

  /**
   * Se ha senso riprovare da soli.
   *
   * Una chiave sbagliata e un file troppo grande non cambiano riprovando: insistere brucia quota e
   * ritarda il momento in cui l'utente legge cosa e' andato storto.
   */
  val retryable: Boolean
    get() = this is RateLimited || this is Server || this is Network || this is Timeout

  companion object {
    /** Da un errore dell'engine (o da qualsiasi eccezione) a uno di questi. */
    fun from(t: Throwable): TranscriptionError = when (t) {
      is TranscriptionError -> t
      is AiError.Unauthorized -> Unauthorized(t.message.orEmpty())
      is AiError.RateLimited -> RateLimited(t.retryAfterSec, t.message.orEmpty())
      is AiError.Server -> Server(t.code, t.message.orEmpty())
      is AiError.BadRequest -> if (t.code == 413) {
        FileTooLarge(null, t.message.orEmpty())
      } else {
        Parse(t.message.orEmpty(), t)
      }
      is AiError.Network -> Network(t.message.orEmpty(), t)
      is AiError.Timeout -> Timeout(t.message.orEmpty(), t)
      is AiError.Parse -> Parse(t.message.orEmpty(), t)
      is SocketTimeoutException -> Timeout(t.message ?: "tempo scaduto", t)
      is UnknownHostException -> Network(t.message ?: "server non raggiungibile", t)
      is IOException -> Network(t.message ?: "errore di rete", t)
      is kotlinx.coroutines.CancellationException -> Cancelled()
      else -> Parse(t.message ?: t::class.java.simpleName, t)
    }
  }
}
