package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.audio.ChunkSpec

/** Cosa e' tornato indietro per un pezzo: la sua posizione nell'originale e i segmenti che contiene. */
data class ChunkTranscript(
  val spec: ChunkSpec,
  val segments: List<RawSegment>,
)

/**
 * Qualsiasi cosa abbia un inizio, una fine e delle parole.
 *
 * Esiste perche' il testo si compone allo stesso modo due volte: quando la trascrizione arriva, e
 * quando le parti di una sessione cambiano ordine e va rifatta. Due copie della regola dei
 * paragrafi sarebbero due regole destinate a divergere.
 */
interface TimedText {
  val startMs: Long
  val endMs: Long
  val text: String
}

/** Un segmento gia' collocato nel tempo del file intero. */
data class StitchedSegment(
  override val startMs: Long,
  override val endMs: Long,
  override val text: String,
  val noSpeechProb: Float?,
  val avgLogProb: Float?,
  /** Da quale pezzo viene: serve solo a capire i difetti, non alla UI. */
  val chunkIndex: Int,
  /** Le parole, gia' traslate come il segmento. Vuota quando il servizio non le da'. */
  val words: List<RawWord> = emptyList(),
  /** La voce («SPEAKER_00»), quando il computer le ha separate. Vedi [TranscriptStitcher.stitch]. */
  val speaker: String? = null,
) : TimedText

data class StitchedTranscript(
  val text: String,
  val segments: List<StitchedSegment>,
)

/**
 * Da N risposte a un testo solo.
 *
 * Tre cose, in quest'ordine, e ognuna risolve un modo concreto in cui una trascrizione a pezzi si
 * rompe (e prima del punto 3 se ne va quello che il modello ha inventato nei silenzi: giri a vuoto,
 * eco del prompt, «Grazie.» isolati — vedi [HallucinationFilter]; vale anche per il computer di
 * casa, che risponde con un pezzo solo):
 *
 *  1. **I tempi si traslano.** Ogni pezzo risponde con tempi che partono da zero; qui tornano dove
 *     stavano nel file originale.
 *  2. **La sovrapposizione si risolve con il confine, non con il testo.** Due pezzi contigui si
 *     sovrappongono di qualche secondo, e in quel margine le stesse parole compaiono due volte. Il
 *     criterio e' geometrico: un segmento appartiene al pezzo nel cui intervallo cade il suo punto
 *     medio. Confrontare le stringhe avrebbe voluto dire decidere quale delle due versioni di
 *     "l'assemblea costituente" e' quella giusta, che e' una domanda senza risposta.
 *  3. **Il confine si ripulisce lo stesso.** Anche dopo il punto 2 capita che l'ultima frase di un
 *     pezzo e la prima del successivo condividano qualche parola, perche' i due modelli hanno messo
 *     il confine del segmento in punti diversi. Si toglie il prefisso ripetuto, e solo se e' corto.
 */
object TranscriptStitcher {

  /** Oltre questo numero di parole in comune non e' piu' una ripetizione di confine: e' contenuto. */
  private const val MAX_OVERLAP_WORDS = 12

  /** Sopra questa probabilita' di silenzio, e con un punteggio cosi' basso, Whisper sta inventando. */
  private const val NO_SPEECH_THRESHOLD = 0.9f
  private const val LOW_CONFIDENCE = -1.0f

  /** Il salto oltre il quale si va a capo: in una lezione due secondi di silenzio sono un paragrafo. */
  private const val PARAGRAPH_GAP_MS = 2_000L

  /**
   * @param prompt quello che si e' mandato al modello come vocabolario: un segmento che lo ripete e
   *   basta e' un'eco, e se ne va (vedi [HallucinationFilter]).
   */
  fun stitch(chunks: List<ChunkTranscript>, prompt: String? = null): StitchedTranscript {
    if (chunks.isEmpty()) return StitchedTranscript("", emptyList())

    val ordered = chunks.sortedBy { it.spec.index }
    val placed = mutableListOf<StitchedSegment>()

    ordered.forEachIndexed { position, chunk ->
      // Il confine: fin dove questo pezzo ha l'ultima parola. L'ultimo arriva in fondo.
      val upperBound = ordered.getOrNull(position + 1)?.spec?.startMs ?: Long.MAX_VALUE
      val lowerBound = chunk.spec.startMs

      chunk.segments.asSequence()
        .filterNot { isHallucination(it) }
        .map { segment ->
          StitchedSegment(
            startMs = chunk.spec.startMs + segment.startMs,
            endMs = chunk.spec.startMs + segment.endMs,
            text = segment.text.trim(),
            noSpeechProb = segment.noSpeechProb,
            avgLogProb = segment.avgLogProb,
            chunkIndex = chunk.spec.index,
            // Le parole portano i tempi del pezzo, come il segmento: stesso offset, stessa regola.
            words = segment.words.map { word ->
              word.copy(
                startMs = chunk.spec.startMs + word.startMs,
                endMs = chunk.spec.startMs + word.endMs,
              )
            },
            // Le voci di due pezzi mandati separatamente non si parlano: SPEAKER_00 del secondo non e'
            // per forza SPEAKER_00 del primo. Con piu' pezzi l'etichetta porta il pezzo, e diventano
            // voci diverse — meglio due numeri per una persona che un numero per due persone.
            speaker = segment.speaker?.let { if (ordered.size > 1) "${chunk.spec.index}:$it" else it },
          )
        }
        .filter { it.text.isNotEmpty() }
        .filter { segment ->
          val middle = (segment.startMs + segment.endMs) / 2
          middle >= lowerBound && middle < upperBound
        }
        .forEach(placed::add)
    }

    val sorted = placed.sortedBy { it.startMs }
    // Prima quello che il modello ha inventato, poi le cuciture: un giro a vuoto in coda a un pezzo
    // confonderebbe il confronto delle parole al confine. I bordi servono all'isolamento di un
    // «Grazie.»: all'inizio e in fondo alla registrazione la pausa si misura da li'.
    val lowerMs = ordered.first().spec.startMs
    val upperMs = ordered.last().spec.endMs.takeIf { it > lowerMs } ?: Long.MAX_VALUE
    val filtered = HallucinationFilter.clean(sorted, prompt, lowerMs, upperMs)
    val cleaned = removeBoundaryRepeats(filtered)
    return StitchedTranscript(text = joinIntoParagraphs(cleaned), segments = cleaned)
  }

  /**
   * Un segmento che il modello ha prodotto dal nulla.
   *
   * Whisper, messo davanti a qualche secondo di silenzio o di rumore, produce il testo che ha visto
   * piu' spesso in coda ai video: "Sottotitoli e revisione a cura di...". I due numeri che lo
   * distinguono da una frase vera li dice il modello stesso.
   *
   * Solo quando li dice tutti e due. Un `no_speech_prob` che manca (`null`) e' «non lo so», non
   * «c'era voce»: WhisperX non lo calcola, e il companion ha mandato a lungo uno `0.0` che voleva
   * dire la stessa cosa. In quel caso decidono le difese sul testo ([HallucinationFilter]), che non
   * hanno bisogno dei numeri del modello.
   */
  fun isHallucination(segment: RawSegment): Boolean {
    val noSpeech = segment.noSpeechProb ?: return false
    val confidence = segment.avgLogProb ?: return false
    return noSpeech > NO_SPEECH_THRESHOLD && confidence < LOW_CONFIDENCE
  }

  /**
   * Toglie le parole che il segmento seguente ripete dal precedente, quando i due vengono da pezzi
   * diversi.
   *
   * Solo al confine fra pezzi: dentro lo stesso pezzo una ripetizione e' quasi sempre vera — un
   * professore che ripete una parola per enfasi, o una correzione a voce.
   */
  fun removeBoundaryRepeats(segments: List<StitchedSegment>): List<StitchedSegment> {
    if (segments.size < 2) return segments
    val result = mutableListOf(segments.first())

    for (i in 1 until segments.size) {
      val previous = result.last()
      val current = segments[i]
      if (current.chunkIndex == previous.chunkIndex) {
        result += current
        continue
      }
      val trimmed = dropRepeatedPrefix(previous.text, current.text)
      if (trimmed.isBlank()) continue
      if (trimmed == current.text) {
        result += current
        continue
      }
      // Le parole allineate seguono il testo: via le prime, quante erano le parole tolte, se erano
      // una per token. Lasciate tutte, la schermata le avrebbe appaiate al testo accorciato e ogni
      // parola si sarebbe accesa coi tempi di quella prima.
      val before = current.text.split(WHITESPACE).count { it.isNotEmpty() }
      val after = trimmed.split(WHITESPACE).count { it.isNotEmpty() }
      val words = if (current.words.size == before) current.words.drop(before - after) else emptyList()
      result += current.copy(text = trimmed, words = words)
    }
    return result
  }

  /**
   * Se `next` comincia ripetendo la coda di `previous`, torna `next` senza quella parte.
   *
   * Confronto su parole normalizzate — senza punteggiatura e senza maiuscole — perche' i due
   * spezzoni vengono da due richieste diverse e la punteggiatura e' la prima cosa su cui non si
   * accordano.
   */
  fun dropRepeatedPrefix(previous: String, next: String): String {
    val previousWords = previous.split(WHITESPACE).filter { it.isNotBlank() }
    val nextWords = next.split(WHITESPACE).filter { it.isNotBlank() }
    if (previousWords.isEmpty() || nextWords.isEmpty()) return next

    val maxOverlap = minOf(MAX_OVERLAP_WORDS, previousWords.size, nextWords.size)
    for (length in maxOverlap downTo 2) {
      val tail = previousWords.takeLast(length).map(::normalize)
      val head = nextWords.take(length).map(::normalize)
      if (tail == head) return nextWords.drop(length).joinToString(" ")
    }
    return next
  }

  /**
   * Il testo, con un a capo dove c'era una pausa.
   *
   * Senza, la trascrizione di un'ora e' un muro di tremila parole che nessuno — ne' una persona ne'
   * un assistente — legge volentieri.
   *
   * Un silenzio lungo ([TranscriptParagraphs.SILENCE_MS]) qui e' un a capo come gli altri, e non
   * «— 16 min di silenzio —»: questo testo va nella ricerca, nel conto delle parole, nel sync, nel
   * raffinamento e nell'export senza tempi, e una frase scritta dall'app in mezzo alle parole della
   * lezione sarebbe trovata, contata, riscritta da un modello e letta come detta dal professore —
   * nella lingua del telefono che ha trascritto, per sempre. Il segno si ricava dai tempi dei
   * segmenti al momento di mostrarli (schermata, export coi tempi), dove e' chiaro chi lo dice.
   */
  fun joinIntoParagraphs(segments: List<TimedText>): String {
    if (segments.isEmpty()) return ""
    val builder = StringBuilder()
    var previousEnd = segments.first().startMs

    segments.forEachIndexed { index, segment ->
      val gap = segment.startMs - previousEnd
      when {
        index == 0 -> Unit
        gap >= PARAGRAPH_GAP_MS -> builder.append("\n\n")
        else -> builder.append(' ')
      }
      builder.append(segment.text.trim())
      previousEnd = segment.endMs
    }
    return builder.toString().trim()
  }

  private val WHITESPACE = Regex("\\s+")
  private val PUNCTUATION = Regex("[\\p{Punct}\\u00AB\\u00BB\\u2018\\u2019\\u201C\\u201D\\u2026]")

  /** Una parola senza maiuscole e senza punteggiatura: e' la forma in cui si confrontano. */
  internal fun normalize(word: String): String = word.lowercase().replace(PUNCTUATION, "")
}
