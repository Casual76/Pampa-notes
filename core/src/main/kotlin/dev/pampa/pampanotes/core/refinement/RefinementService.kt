package dev.pampa.pampanotes.core.refinement

import dev.antigravity.fluidengine.ai.net.AiError
import dev.antigravity.fluidengine.ai.provider.ChatProvider
import dev.antigravity.fluidengine.ai.provider.ChatRequest
import dev.antigravity.fluidengine.ai.provider.ChatTurn
import dev.antigravity.fluidengine.ai.provider.FinishReason
import dev.antigravity.fluidengine.ai.provider.Message
import dev.antigravity.fluidengine.ai.provider.ReasoningLevel
import dev.pampa.pampanotes.core.settings.RefinementPreset
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** A che punto e' il raffinamento, per la barra e per la notifica. */
data class RefinementProgress(
  val chunkIndex: Int,
  val chunkCount: Int,
  /** Secondi di attesa imposti dal limite di Groq: la UI lo dice invece di sembrare bloccata. */
  val waitingSeconds: Int = 0,
)

data class RefinementResult(
  val text: String,
  val model: String,
  val preset: RefinementPreset,
  /** Il rapporto fra le parole ripulite e quelle grezze: fuori da [RefinementPrompts.SANE_RATIO] si segnala. */
  val wordRatio: Float,
  /** Vero quando una risposta si e' fermata per il tetto dei token invece che per fine testo. */
  val truncated: Boolean,
) {
  val suspicious: Boolean get() = truncated || wordRatio !in RefinementPrompts.SANE_RATIO
}

class RefinementError(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Da una trascrizione grezza a una leggibile.
 *
 * L'unico posto in cui questa app manda del testo a un modello di chi, ed e' voluto che faccia una
 * cosa sola: riscrivere quello che gli si da'. Non riassume, non risponde, non commenta — e il testo
 * grezzo resta nel database accanto a quello ripulito, per sempre, perche' l'unico modo di fidarsi
 * di una macchina che riscrive e' poter vedere cosa c'era prima.
 */
class RefinementService {

  suspend fun refine(
    rawText: String,
    provider: ChatProvider,
    model: String,
    preset: RefinementPreset,
    customPrompt: String = "",
    onProgress: (RefinementProgress) -> Unit = {},
  ): RefinementResult {
    val source = rawText.trim()
    if (source.isEmpty()) throw RefinementError("non c'è niente da ripulire")

    val chunks = TextChunker.split(source)
    val system = RefinementPrompts.system(preset, customPrompt)
    val pieces = mutableListOf<String>()
    var truncated = false

    chunks.forEachIndexed { index, chunk ->
      currentCoroutineContext().ensureActive()
      onProgress(RefinementProgress(index, chunks.size))

      val request = ChatRequest(
        model = model,
        messages = listOf(
          Message.System(system),
          Message.User(RefinementPrompts.user(chunk.text, pieces.lastOrNull()?.let { TextChunker.tailOf(it) })),
        ),
        // Zero non e' disponibile ovunque; questo e' abbastanza basso perche' due passaggi sullo
        // stesso testo diano quasi la stessa cosa, che su una fonte conta.
        temperature = 0.2,
        // Il tetto deve stare largo: deve tornare indietro tutto quello che e' entrato, piu' la
        // punteggiatura. Stretto, la risposta si tronca e il difetto si vede solo alla fine.
        maxOutputTokens = 8_192,
        // Non c'e' niente da ragionare: e' una riscrittura, e il ragionamento costa token e tempo.
        reasoning = ReasoningLevel.NONE,
      )

      val turn = sendWithRetry(provider, request) { seconds ->
        onProgress(RefinementProgress(index, chunks.size, waitingSeconds = seconds))
      }

      if (turn.finishReason == FinishReason.LENGTH) truncated = true
      val text = turn.message.text?.trim().orEmpty()
      if (text.isEmpty()) throw RefinementError("il modello ha risposto con un testo vuoto")
      pieces += cleanUp(text)
    }

    val refined = pieces.joinToString("\n\n").trim()
    val ratio = TextChunker.countWords(refined).toFloat() / TextChunker.countWords(source).coerceAtLeast(1)
    onProgress(RefinementProgress(chunks.size, chunks.size))

    return RefinementResult(
      text = refined,
      model = model,
      preset = preset,
      wordRatio = ratio,
      truncated = truncated,
    )
  }

  /**
   * Manda un pezzo, e aspetta quando il limite lo impone.
   *
   * Groq conta i token al minuto, e il piano gratuito ne da' ottomila. Una lezione da tremila parole
   * sono due richieste che, messe in fila, superano il limite sulla seconda — e il servizio dice
   * esattamente quanti secondi mancano al rientro. Aspettarli e' quello che serve: senza, il
   * raffinamento di qualunque lezione lunga fallisce sempre, sul secondo pezzo.
   *
   * Un errore che non e' un limite non si riprova: una chiave sbagliata o un modello che non esiste
   * falliscono uguale al quinto tentativo, cinque minuti dopo.
   */
  private suspend fun sendWithRetry(
    provider: ChatProvider,
    request: ChatRequest,
    onWaiting: (Int) -> Unit,
  ): ChatTurn {
    var attempt = 0
    while (true) {
      currentCoroutineContext().ensureActive()
      try {
        return provider.complete(request)
      } catch (limited: AiError.RateLimited) {
        attempt++
        if (attempt > MAX_RATE_LIMIT_RETRIES) {
          throw RefinementError(limited.message ?: "limite di richieste raggiunto", limited)
        }
        val seconds = (limited.retryAfterSec ?: DEFAULT_WAIT_SEC).coerceIn(1.0, MAX_WAIT_SEC)
        onWaiting(seconds.toInt() + 1)
        // Un secondo in piu' di quanto chiesto: il conto del server e quello del telefono non
        // sono lo stesso orologio, e ripartire un istante troppo presto costa un altro giro.
        delay((seconds * 1000).toLong() + 1_000L)
      } catch (error: Throwable) {
        if (error is RefinementError) throw error
        throw RefinementError(error.message ?: "il servizio non ha risposto", error)
      }
    }
  }

  /**
   * Toglie quello che il modello ha aggiunto intorno al testo nonostante gli sia stato detto di non
   * farlo.
   *
   * Due cose, e sono le due che capitano: il blocco di codice con cui certi modelli avvolgono ogni
   * cosa, e la frase di servizio in apertura. Le si tolgono qui invece di insistere nel prompt
   * perche' un prompt piu' lungo non le elimina, le rende solo piu' rare.
   */
  internal fun cleanUp(text: String): String {
    var result = text.trim()
    if (result.startsWith("```")) {
      result = result.removePrefix("```").substringAfter('\n', "").substringBeforeLast("```").trim()
    }
    val firstBreak = result.indexOf("\n\n")
    if (firstBreak in 1..PREAMBLE_MAX_CHARS) {
      val opener = result.take(firstBreak).trim()
      // Due condizioni insieme, e servono tutte e due. Le parole da sole tagliano via "Ecco,
      // allora, ricominciamo da dove eravamo", che e' la prima frase della lezione; i due punti da
      // soli tagliano "Vi faccio un esempio:". Un preambolo annuncia quello che viene dopo, e
      // annunciare finisce con i due punti.
      if (opener.endsWith(':') && PREAMBLE.containsMatchIn(opener)) {
        result = result.substring(firstBreak).trim()
      }
    }
    return result
  }

  companion object {
    /**
     * I modelli che sanno fare questo lavoro, nell'ordine in cui li si prova.
     *
     * Un modello che ragiona non serve: e' una riscrittura, non un problema. Serve invece che sappia
     * l'italiano parlato e che non abbia la tendenza ad "aiutare".
     */
    val PREFERRED_MODELS = listOf(
      "openai/gpt-oss-120b",
      "llama-3.3-70b-versatile",
      "openai/gpt-oss-20b",
      "llama-3.1-8b-instant",
    )

    /** Il primo modello disponibile fra quelli buoni, altrimenti il primo che c'e'. */
    fun pickModel(available: List<String>): String? {
      PREFERRED_MODELS.forEach { wanted ->
        available.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
      }
      return available.firstOrNull { !it.contains("whisper", ignoreCase = true) }
    }

    /** Quante volte aspettare il limite prima di arrendersi: cinque attese coprono un minuto buono. */
    private const val MAX_RATE_LIMIT_RETRIES = 5
    private const val DEFAULT_WAIT_SEC = 20.0

    /** Oltre questo non e' una pausa, e' un limite giornaliero: meglio un errore che un'app ferma. */
    private const val MAX_WAIT_SEC = 90.0

    private const val PREAMBLE_MAX_CHARS = 160
    private val PREAMBLE = Regex(
      "(ecco|di seguito|here is|here's|testo ripulito|versione ripulita|trascrizione ripulita)",
      RegexOption.IGNORE_CASE,
    )
  }
}
