package dev.pampa.pampanotes.core.transcription

import java.io.File

/** Cosa un servizio di trascrizione sa fare, e dove si ferma. */
data class TranscriptionCapabilities(
  /** Il tetto per una singola richiesta. Null quando non c'e': il server di casa accetta tutto. */
  val maxUploadBytes: Long?,
  /** Restituisce i tempi dei segmenti, non solo il testo. Senza, il lettore non puo' seguire. */
  val supportsSegments: Boolean,
  /** Serve tagliare un audio lungo prima di mandarlo. */
  val needsChunking: Boolean,
  val supportsAutoLanguage: Boolean = true,
  /**
   * Le estensioni che il servizio prende cosi' come sono. Null: tutto quello che arriva. Un file
   * fuori elenco non passa per la via breve anche se ci starebbe: si decodifica e si ricodifica.
   */
  val acceptedExtensions: Set<String>? = null,
  /**
   * Il pezzo piu' lungo che il servizio vuole, se ne vuole uno. Groq lo lascia alle impostazioni
   * (`chunkMinutes`); il computer di casa lo dichiara qui quando l'utente ne ha scelto uno.
   */
  val maxChunkMinutes: Int? = null,
) {
  /** Il file si puo' mandare com'e'? Decide l'estensione del nome, che e' quello che il servizio guarda. */
  fun acceptsAsIs(fileName: String): Boolean {
    val accepted = acceptedExtensions ?: return true
    return fileName.substringAfterLast('.', "").lowercase() in accepted
  }
}

/** Cosa si chiede a una trascrizione. */
data class TranscribeRequest(
  val model: String,
  /** ISO 639-1, oppure null per lasciare che il modello indovini. */
  val language: String? = null,
  /**
   * Il vocabolario: nomi propri e termini che il modello non conoscerebbe.
   *
   * Whisper lo usa come se fosse il testo appena precedente, quindi funziona meglio come elenco di
   * parole che come istruzione. Vale al massimo 224 token, che sono circa 800 caratteri.
   */
  val prompt: String? = null,
  val temperature: Double = 0.0,
)

/** Un pezzo di testo con i suoi tempi, come lo restituisce il servizio. */
data class RawSegment(
  val startMs: Long,
  val endMs: Long,
  val text: String,
  /** Quanto il modello crede che qui non ci fosse voce: sopra 0.9 di solito e' un'allucinazione. */
  val noSpeechProb: Float? = null,
  val avgLogProb: Float? = null,
  /**
   * Le parole con i loro tempi, quando il servizio le da'.
   *
   * Vuota di default perche' Groq non le da' mai: e' l'allineamento fonetico di WhisperX che le
   * produce. Chi non le ha se le fa stimare da [WordTimings], cosi' la UI ha una strada sola.
   */
  val words: List<RawWord> = emptyList(),
)

/** Una parola con i suoi tempi. I millisecondi sono nello stesso riferimento del segmento. */
data class RawWord(
  val startMs: Long,
  val endMs: Long,
  val text: String,
)

data class TranscriptResult(
  val text: String,
  val segments: List<RawSegment>,
  val language: String?,
  val durationMs: Long?,
)

/** A che punto e' l'invio di un file: la barra di avanzamento ha bisogno di questo, non di uno spinner. */
data class UploadProgress(val sentBytes: Long, val totalBytes: Long) {
  val fraction: Float get() = if (totalBytes <= 0) 0f else (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** Se il server risponde, e cosa dice di se'. */
data class EndpointHealth(
  val reachable: Boolean,
  val latencyMs: Long,
  val models: List<String> = emptyList(),
  val serverName: String? = null,
  val detail: String? = null,
)

/**
 * Chi trasforma un audio in testo.
 *
 * Due implementazioni con lo stesso contratto: Groq nel cloud con la chiave dell'utente, e un
 * endpoint compatibile OpenAI — di regola il computer di casa con WhisperX. La differenza che conta
 * sta in [capabilities]: il primo ha un tetto per richiesta e va servito a pezzi, il secondo si
 * prende l'ora intera.
 */
interface TranscriptionProvider {
  /** "groq" oppure "custom": lo stesso identificatore con cui i lavori si mettono in coda. */
  val id: String

  val capabilities: TranscriptionCapabilities

  suspend fun listModels(): List<String>

  suspend fun health(): EndpointHealth

  suspend fun transcribe(
    file: File,
    mime: String,
    request: TranscribeRequest,
    onProgress: (UploadProgress) -> Unit = {},
  ): TranscriptResult
}
