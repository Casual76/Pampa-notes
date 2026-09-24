package dev.pampa.pampanotes.core.transcription

/**
 * Quello che Whisper scrive quando non c'e' niente da scrivere.
 *
 * Whisper non sa stare zitto: davanti a minuti di silenzio o di rumore produce testo lo stesso, e
 * sempre lo stesso genere di testo. Una registrazione vera di venti ore («Napoli 18h») ne ha dati
 * tre esempi, uno per difesa:
 *
 *  1. **Il giro a vuoto.** 78 segmenti fatti della stessa parola o della stessa frase ripetuta
 *     decine di volte: il modello si aggancia al proprio testo e non se ne stacca piu'. Si tiene una
 *     sola occorrenza — quello che c'era prima e dopo il giro resta.
 *  2. **L'eco del prompt.** 407 segmenti che dicevano solo «18h»: Whisper legge il prompt come il
 *     testo appena detto, e quando l'audio non gli da' niente lo ripete. Un segmento fatto soltanto
 *     di parole del prompt, e non piu' lungo del prompt, e' un'eco. (Il prompt adesso e' il solo
 *     vocabolario — vedi [TranscriptionPrompt] — ma un vocabolario si puo' ripetere anche lui.)
 *  3. **I saluti nel silenzio.** Un centinaio di «Grazie.» e «Buonanotte.» sparsi nelle pause: sono
 *     le ultime parole di migliaia di video su cui il modello e' stato addestrato. Si tolgono solo
 *     quando sono isolati — almeno [ISOLATION_GAP_MS] di pausa prima e dopo — perche' un «grazie»
 *     detto in mezzo a un discorso e' un grazie vero. Fanno eccezione i titoli di coda («Sottotitoli
 *     creati dalla comunita' Amara.org»), che in una lezione non si dicono mai.
 *
 * Tutto conservativo per costruzione: nessuna regola tocca un discorso lungo. Le frasi di silenzio
 * sono corte per definizione, un'eco non puo' essere piu' lunga del prompt, e un giro a vuoto si
 * riconosce solo quando la stessa unita' torna almeno tre volte di fila e copre almeno
 * [MIN_LOOP_TOKENS] parole — «no, no, no» si dice davvero, sei «no» di fila no.
 */
object HallucinationFilter {

  /** Quanta pausa deve esserci prima e dopo perche' un «Grazie.» sia silenzio e non lezione. */
  const val ISOLATION_GAP_MS = 3_000L

  /** Quante volte di fila deve tornare la stessa unita' perche' sia un giro a vuoto. */
  const val MIN_REPEATS = 3

  /** Quante parole deve coprire il giro, in tutto. */
  const val MIN_LOOP_TOKENS = 6

  /** Oltre questa lunghezza un segmento non e' una frase di silenzio, qualunque parola contenga. */
  private const val MAX_PHRASE_TOKENS = 12

  /** Normalizzati come [TranscriptStitcher.normalize]: minuscole, senza punteggiatura. */
  private val CLOSERS = setOf(
    "grazie",
    "grazie mille",
    "grazie a tutti",
    "grazie a voi",
    "grazie e buonanotte",
    "buonanotte",
    "buona notte",
    "grazie per la visione",
    "sottotitoli",
  )

  /** Le parole dei titoli di coda, dopo un «Sottotitoli» in apertura. */
  private val CREDIT_WORDS = setOf("cura", "creati", "revisione", "comunità", "comunita", "qtss")

  /**
   * I tre passaggi, in ordine: prima si stringono i giri a vuoto (un «18h 18h 18h» diventa «18h», e
   * solo cosi' si riconosce come eco), poi si tolgono le eco, poi le frasi di silenzio, perche' un
   * «Grazie.» e' isolato davvero solo quando attorno non c'e' piu' niente di inventato.
   *
   * @param segments gia' ordinati per tempo, nel tempo della parte.
   * @param lowerMs dove comincia l'audio: un «Grazie.» all'inizio ha la pausa prima da qui.
   * @param upperMs dove finisce, oppure [Long.MAX_VALUE] se non si sa.
   */
  fun clean(
    segments: List<StitchedSegment>,
    prompt: String?,
    lowerMs: Long = 0L,
    upperMs: Long = Long.MAX_VALUE,
  ): List<StitchedSegment> {
    if (segments.isEmpty()) return segments
    val collapsed = collapseRepeatedSegments(segments.map(::collapseLoops).filter { it.text.isNotBlank() })
    val promptTokens = tokensOf(prompt.orEmpty())
    val withoutEchoes = if (promptTokens.isEmpty()) collapsed else collapsed.filterNot { isPromptEcho(it.text, promptTokens) }
    return dropSilencePhrases(withoutEchoes, lowerMs, upperMs)
  }

  // -----------------------------------------------------------------------------------------------
  // 1. I giri a vuoto
  // -----------------------------------------------------------------------------------------------

  /**
   * Un segmento con dentro la stessa unita' ripetuta: ne resta una.
   *
   * Si guarda ogni punto del testo, non solo l'inizio: il giro comincia spesso dopo una frase vera
   * («Allora oggi parliamo di Napoli Napoli Napoli Napoli Napoli Napoli»), e quella frase resta.
   * L'unita' piu' corta vince, cosi' «di di di di di di» diventa «di» e non «di di».
   *
   * Le parole allineate seguono il testo: se erano una per token si tengono quelle delle parole
   * rimaste, altrimenti si lasciano stimare (`wordsEstimated`) invece di accendere le parole sbagliate.
   * I tempi del segmento non cambiano: dove sia finito davvero il giro non lo sa nessuno.
   */
  fun collapseLoops(segment: StitchedSegment): StitchedSegment {
    val tokens = segment.text.split(WHITESPACE).filter { it.isNotEmpty() }
    val kept = loopFreeIndices(tokens.map(TranscriptStitcher::normalize))
    if (kept.size == tokens.size) return segment
    return segment.copy(
      text = kept.joinToString(" ") { tokens[it] },
      words = if (segment.words.size == tokens.size) kept.map { segment.words[it] } else emptyList(),
    )
  }

  /** Gli indici dei token che restano, togliendo le ripetizioni di ogni giro. */
  internal fun loopFreeIndices(normalized: List<String>): List<Int> {
    val kept = mutableListOf<Int>()
    var index = 0
    while (index < normalized.size) {
      val loop = loopAt(normalized, index)
      if (loop == null) {
        kept += index
        index++
      } else {
        val (unit, repeats) = loop
        for (offset in 0 until unit) kept += index + offset
        index += unit * repeats
      }
    }
    return kept
  }

  /** Il giro che comincia qui, se c'e': quanto e' lunga l'unita' e quante volte torna. */
  private fun loopAt(tokens: List<String>, start: Int): Pair<Int, Int>? {
    val remaining = tokens.size - start
    for (unit in 1..remaining / MIN_REPEATS) {
      var repeats = 1
      while (start + (repeats + 1) * unit <= tokens.size && sameUnit(tokens, start, start + repeats * unit, unit)) repeats++
      if (repeats >= MIN_REPEATS && unit * repeats >= MIN_LOOP_TOKENS) return unit to repeats
    }
    return null
  }

  private fun sameUnit(tokens: List<String>, first: Int, second: Int, length: Int): Boolean {
    for (offset in 0 until length) if (tokens[first + offset] != tokens[second + offset]) return false
    return true
  }

  /**
   * Lo stesso giro, fatto di segmenti invece che di parole: tre o piu' segmenti di fila con lo
   * stesso testo, che in tutto fanno almeno [MIN_LOOP_TOKENS] parole. Resta il primo.
   */
  internal fun collapseRepeatedSegments(segments: List<StitchedSegment>): List<StitchedSegment> {
    if (segments.size < MIN_REPEATS) return segments
    val keys = segments.map { tokensOf(it.text).joinToString(" ") }
    val result = mutableListOf<StitchedSegment>()
    var index = 0
    while (index < segments.size) {
      var end = index + 1
      while (end < segments.size && keys[end] == keys[index]) end++
      val run = end - index
      val tokens = keys[index].split(' ').count { it.isNotEmpty() }
      result += segments[index]
      if (!(run >= MIN_REPEATS && tokens * run >= MIN_LOOP_TOKENS)) {
        for (other in index + 1 until end) result += segments[other]
      }
      index = end
    }
    return result
  }

  // -----------------------------------------------------------------------------------------------
  // 2. L'eco del prompt
  // -----------------------------------------------------------------------------------------------

  /**
   * Un segmento fatto solo di parole del prompt, e non piu' lungo del prompt.
   *
   * Il secondo limite e' quello che lo rende sicuro anche con un vocabolario pieno di parole comuni:
   * una frase vera che usa i termini del vocabolario ne usa anche altri, o e' piu' lunga dell'elenco.
   */
  fun isPromptEcho(text: String, promptTokens: List<String>): Boolean {
    val tokens = tokensOf(text)
    if (tokens.isEmpty() || promptTokens.isEmpty()) return false
    if (tokens.size > promptTokens.size) return false
    val vocabulary = promptTokens.toSet()
    return tokens.all { it in vocabulary }
  }

  // -----------------------------------------------------------------------------------------------
  // 3. I saluti nel silenzio
  // -----------------------------------------------------------------------------------------------

  private enum class Phrase {
    /** «Grazie.», «Buonanotte.»: si toglie solo se e' isolato. */
    CLOSER,

    /** «Sottotitoli a cura di…», «Amara.org»: si toglie sempre, in una lezione non si dice. */
    CREDIT,
  }

  private fun phraseOf(text: String): Phrase? {
    val tokens = tokensOf(text)
    if (tokens.isEmpty() || tokens.size > MAX_PHRASE_TOKENS) return null
    if (tokens.any { it.contains("amaraorg") }) return Phrase.CREDIT
    if (tokens.first() == "sottotitoli" && tokens.any { it in CREDIT_WORDS }) return Phrase.CREDIT
    // «Grazie. Grazie.» e' ancora un grazie: le parole ripetute di fila contano una volta.
    val squeezed = tokens.filterIndexed { index, token -> index == 0 || token != tokens[index - 1] }
    return if (squeezed.joinToString(" ") in CLOSERS) Phrase.CLOSER else null
  }

  /**
   * Toglie le frasi di silenzio.
   *
   * L'isolamento si misura sul gruppo, non sulla singola frase: dieci «Grazie.» uno dopo l'altro in
   * un quarto d'ora muto sono un gruppo solo, e la pausa che conta e' quella fra il gruppo e il
   * discorso vero prima e dopo. Misurata frase per frase, due «Grazie.» vicini si sarebbero protetti
   * a vicenda.
   */
  fun dropSilencePhrases(
    segments: List<StitchedSegment>,
    lowerMs: Long = 0L,
    upperMs: Long = Long.MAX_VALUE,
  ): List<StitchedSegment> {
    if (segments.isEmpty()) return segments
    val phrases = segments.map { phraseOf(it.text) }
    val drop = BooleanArray(segments.size)

    var index = 0
    while (index < segments.size) {
      if (phrases[index] == null) {
        index++
        continue
      }
      var end = index
      while (end + 1 < segments.size && phrases[end + 1] != null) end++

      val before = segments.getOrNull(index - 1)?.endMs ?: lowerMs
      val after = segments.getOrNull(end + 1)?.startMs ?: upperMs
      val isolated = segments[index].startMs - before >= ISOLATION_GAP_MS &&
        after - segments[end].endMs >= ISOLATION_GAP_MS
      for (member in index..end) {
        if (isolated || phrases[member] == Phrase.CREDIT) drop[member] = true
      }
      index = end + 1
    }
    return segments.filterIndexed { position, _ -> !drop[position] }
  }

  // -----------------------------------------------------------------------------------------------

  /** Le parole di un testo, normalizzate e senza quelle fatte di sola punteggiatura. */
  fun tokensOf(text: String): List<String> =
    text.split(WHITESPACE).map(TranscriptStitcher::normalize).filter { it.isNotEmpty() }

  private val WHITESPACE = Regex("\\s+")
}
