package dev.pampa.pampanotes.core.transcription

/** Dove sta una parola dentro un testo, e quando viene detta. Tempi in millisecondi di sessione. */
data class WordSpan(val start: Int, val end: Int, val startMs: Long, val endMs: Long)

/** Un pezzo di testo con i suoi tempi e, se ci sono, le parole gia' allineate. */
data class WordSource(
  val range: IntRange,
  val startMs: Long,
  val endMs: Long,
  val words: List<RawWord> = emptyList(),
)

/**
 * I tempi parola per parola: quelli veri quando il servizio li da', una stima quando no.
 *
 * Serve a una cosa sola, ed e' la ragione per cui esiste: il testo che si accende mentre l'audio
 * va. WhisperX allinea ogni parola con un modello fonetico; Groq no, da' solo i tempi di ogni
 * frase. Invece di avere due strade nella UI — una che sa illuminare le parole e una che illumina
 * la frase intera — si stima: le parole di una frase si distribuiscono nel suo intervallo in
 * proporzione ai caratteri. Non e' precisione, e' plausibilita', e l'app dice quale delle due sta
 * usando, perche' una parola che si accende e' una promessa di precisione.
 *
 * L'interpolazione si applica **in scrittura**, quando la trascrizione arriva: il database ha
 * sempre le parole, e la UI ha una strada sola.
 */
object WordTimings {

  /**
   * Le parole di un testo, distribuite nell'intervallo in proporzione ai caratteri.
   *
   * Ai caratteri e non al numero di parole: «un'interpretazione» dura piu' di «e», e dividere in
   * parti uguali fa correre l'evidenziazione sulle parole lunghe e aspettare su quelle corte, che
   * si vede subito perche' e' il contrario di come si parla.
   */
  fun interpolate(text: String, startMs: Long, endMs: Long): List<RawWord> {
    val tokens = tokenize(text)
    if (tokens.isEmpty()) return emptyList()
    val span = (endMs - startMs).coerceAtLeast(0L)
    val total = tokens.sumOf { it.last - it.first + 1 }.coerceAtLeast(1)
    var consumed = 0
    return tokens.map { token ->
      val length = token.last - token.first + 1
      val from = startMs + span * consumed / total
      consumed += length
      val to = startMs + span * consumed / total
      RawWord(startMs = from, endMs = to, text = text.substring(token.first, token.last + 1))
    }
  }

  /**
   * Le parole da salvare per un segmento: quelle allineate se ci sono, altrimenti la stima.
   *
   * Ritorna anche se sono state stimate, perche' e' quello che la schermata dice all'utente.
   */
  fun fill(words: List<RawWord>, text: String, startMs: Long, endMs: Long): Filled {
    val usable = words.filter { it.text.isNotBlank() }
    if (usable.isEmpty()) return Filled(interpolate(text, startMs, endMs), estimated = true)
    return Filled(usable, estimated = false)
  }

  data class Filled(val words: List<RawWord>, val estimated: Boolean)

  /**
   * Le parole in una riga di testo, con i tempi relativi a [originMs].
   *
   * Relativi perche' i tempi di un segmento dentro la sua parte non cambiano mai, mentre quelli di
   * sessione cambiano appena si riordinano le parti: salvare gli assoluti vorrebbe dire riscrivere
   * ogni parola di ogni segmento a ogni riordino. Il formato e' `inizio,fine,testo` per riga, con
   * il testo per ultimo perche' cosi' una virgola dentro una parola non rompe niente.
   */
  fun encode(words: List<RawWord>, originMs: Long): String? {
    if (words.isEmpty()) return null
    return words.joinToString("\n") { word ->
      "${word.startMs - originMs},${word.endMs - originMs},${word.text.replace("\n", " ")}"
    }
  }

  fun decode(encoded: String?, originMs: Long): List<RawWord> {
    if (encoded.isNullOrBlank()) return emptyList()
    return encoded.lineSequence().mapNotNull { line ->
      if (line.isBlank()) return@mapNotNull null
      val firstComma = line.indexOf(',')
      val secondComma = line.indexOf(',', firstComma + 1)
      if (firstComma <= 0 || secondComma <= firstComma) return@mapNotNull null
      val start = line.substring(0, firstComma).toLongOrNull() ?: return@mapNotNull null
      val end = line.substring(firstComma + 1, secondComma).toLongOrNull() ?: return@mapNotNull null
      val text = line.substring(secondComma + 1)
      if (text.isBlank()) null else RawWord(originMs + start, originMs + end, text)
    }.toList()
  }

  /**
   * Da parole a intervalli di caratteri dentro un testo composto.
   *
   * Un paragrafo e' fatto di piu' segmenti incollati, e le parole arrivano per segmento: qui si
   * cammina sul testo vero e si appaiano ai suoi token. Quando i conti non tornano — il servizio
   * spezza «dell'idealismo» in due, o ne unisce due — si ripiega sulla distribuzione proporzionale
   * dentro l'intervallo del segmento: meglio una stima su una frase che nessuna evidenziazione.
   */
  fun spans(text: String, sources: List<WordSource>): List<WordSpan> {
    val result = mutableListOf<WordSpan>()
    sources.forEach { source ->
      val from = source.range.first.coerceIn(0, text.length)
      val to = (source.range.last + 1).coerceIn(from, text.length)
      val tokens = tokenize(text, from, to)
      if (tokens.isEmpty()) return@forEach
      val words = source.words
      if (words.size == tokens.size) {
        tokens.forEachIndexed { index, token ->
          result += WordSpan(token.first, token.last + 1, words[index].startMs, words[index].endMs)
        }
      } else {
        val span = (source.endMs - source.startMs).coerceAtLeast(0L)
        val total = tokens.sumOf { it.last - it.first + 1 }.coerceAtLeast(1)
        var consumed = 0
        tokens.forEach { token ->
          val length = token.last - token.first + 1
          val start = source.startMs + span * consumed / total
          consumed += length
          result += WordSpan(token.first, token.last + 1, start, source.startMs + span * consumed / total)
        }
      }
    }
    return result
  }

  /** I token di un testo: le sequenze senza spazi, con i loro estremi (inclusivi). */
  private fun tokenize(text: String, from: Int = 0, to: Int = text.length): List<IntRange> {
    val result = mutableListOf<IntRange>()
    var index = from
    while (index < to) {
      while (index < to && text[index].isWhitespace()) index++
      if (index >= to) break
      val start = index
      while (index < to && !text[index].isWhitespace()) index++
      result += start until index
    }
    return result.map { it.first..it.last }
  }
}
