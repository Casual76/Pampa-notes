package dev.pampa.pampanotes.core.transcription

import java.text.Normalizer

/**
 * «Cerca dentro una registrazione»: una parola, e dove e' stata detta.
 *
 * La ricerca dell'app (FTS) dice *quale* nota; dentro una lezione di due ore — o una registrazione
 * di diciannove — serve sapere *dove*, e poterci saltare. Qui c'e' solo la parte che si prova: il
 * confronto e l'ordine delle occorrenze. Scorrere la lista e muovere il lettore li fa la schermata.
 *
 * Il confronto ignora maiuscole e accenti — «perche» trova «perché», «citta» trova «Città» — e
 * tratta come uguali gli apostrofi dritti e tipografici: la tastiera del telefono scrive `’`,
 * Whisper `'`, e «l'uomo» deve trovare «l’uomo». Le posizioni restituite sono nel testo **originale**,
 * perche' e' quello che la schermata disegna: per questo la normalizzazione tiene una mappa carattere
 * per carattere invece di confrontare due stringhe e basta (una «é» scomposta sono due caratteri, e
 * senza mappa ogni evidenziazione dopo la prima scivolerebbe di uno).
 */
object TranscriptSearch {

  /** Sotto i due caratteri una ricerca in una lezione trova tutto, cioe' niente. */
  const val MIN_QUERY_LENGTH = 2

  /** Un'occorrenza: nel blocco [block] (un paragrafo), da [start] incluso a [end] escluso. */
  data class Match(val block: Int, val start: Int, val end: Int)

  /**
   * I blocchi gia' normalizzati, da preparare una volta per trascrizione: normalizzare un milione di
   * caratteri a ogni lettera digitata sarebbe il costo della ricerca, il confronto no.
   */
  class Index(blocks: List<String>) {
    internal val prepared: List<Normalized> = blocks.map(::normalizeMapped)
  }

  /** Un testo normalizzato, e per ogni suo carattere dove comincia e finisce nell'originale. */
  internal class Normalized(val text: String, val starts: IntArray, val ends: IntArray)

  /** Le occorrenze di [query] nei blocchi, in ordine di blocco e poi di posizione; mai sovrapposte. */
  fun find(index: Index, query: String): List<Match> {
    val needle = normalizeQuery(query)
    if (needle.length < MIN_QUERY_LENGTH) return emptyList()
    val result = mutableListOf<Match>()
    index.prepared.forEachIndexed { block, normalized ->
      var from = 0
      while (true) {
        val at = normalized.text.indexOf(needle, from)
        if (at < 0) break
        val last = at + needle.length - 1
        result += Match(block, normalized.starts[at], normalized.ends[last])
        from = at + needle.length
      }
    }
    return result
  }

  fun find(blocks: List<String>, query: String): List<Match> = find(Index(blocks), query)

  /** La forma di confronto di una ricerca: normalizzata come i testi, spazi in fila fusi in uno. */
  fun normalizeQuery(query: String): String =
    normalizeMapped(query.trim()).text.replace(WHITESPACE_RUN, " ")

  /**
   * Il momento dell'occorrenza che comincia al carattere [offset] di un paragrafo della grezza.
   *
   * La parola, se i tempi ci sono (allineati da WhisperX o stimati): chi cerca «Kant» vuole sentire
   * «Kant», non la frase di trenta secondi che lo contiene. Altrimenti l'inizio del segmento, che e'
   * comunque il punto piu' vicino che si conosce.
   */
  fun timeOf(paragraph: TranscriptParagraphs.Paragraph, offset: Int): Long {
    val ranges = paragraph.ranges
    val index = ranges.indexOfFirst { offset <= it.last }.let { if (it < 0) ranges.lastIndex else it }
    val segment = paragraph.segments[index]
    val sources = listOf(
      WordSource(
        range = ranges[index],
        startMs = segment.sessionStartMs,
        endMs = segment.sessionEndMs,
        words = WordTimings.decode(segment.wordsJson, originMs = segment.sessionStartMs),
      ),
    )
    val words = WordTimings.spans(paragraph.text, sources)
    val word = words.lastOrNull { it.start <= offset } ?: return segment.sessionStartMs
    return word.startMs
  }

  /**
   * Il Markdown di una versione ripulita come blocchi di testo semplice, per cercarci dentro.
   *
   * La raffinata a schermo e' Markdown reso da una libreria, e dentro un testo reso non si
   * evidenzia niente: mentre si cerca la si mostra cosi', un blocco per paragrafo, senza i segni
   * (`#`, `**`, `_`, `` ` ``) che resterebbero in mezzo alle parole. Gli elenchi tengono un pallino.
   */
  fun plainBlocks(markdown: String): List<String> =
    markdown.split(BLANK_LINES)
      .map { block ->
        block.lines().joinToString("\n") { line ->
          line
            .replace(HEADING, "")
            .replace(BULLET, "• ")
            .replace(EMPHASIS, "")
            .trimEnd()
        }.trim()
      }
      .filter { it.isNotEmpty() }

  // -----------------------------------------------------------------------------------------------

  internal fun normalizeMapped(text: String): Normalized {
    val out = StringBuilder(text.length)
    // Di solito un carattere resta un carattere; una sillaba coreana scomposta ne fa tre, e la mappa
    // cresce quando serve invece di contare su una misura fissa.
    var starts = IntArray(text.length + 1)
    var ends = IntArray(text.length + 1)
    var count = 0
    var i = 0
    while (i < text.length) {
      val codePoint = text.codePointAt(i)
      val width = Character.charCount(codePoint)
      // La via corta per l'ASCII, che e' quasi tutto il testo: diciannove ore di trascrizione sono
      // un milione di caratteri, e il Normalizer per ognuno si sentirebbe a ogni apertura.
      val folded = when {
        codePoint == '`'.code -> "'"
        codePoint < 0x80 -> if (Character.isWhitespace(codePoint)) " " else codePoint.toChar().lowercaseChar().toString()
        else -> fold(String(Character.toChars(codePoint)))
      }
      // Quello che nel confronto sparisce — un accento combinante staccato dalla sua lettera — o
      // che si fonde — il secondo di due spazi — allunga il carattere prima invece di perdersi:
      // cosi' l'evidenziazione di «cafe» copre anche l'accento, e «la  guerra» si trova con uno spazio.
      val merges = count > 0 && (folded.isEmpty() || (folded == " " && out[count - 1] == ' '))
      if (merges) {
        ends[count - 1] = i + width
        i += width
        continue
      }
      folded.forEach { c ->
        if (count == starts.size) {
          starts = starts.copyOf(count * 2)
          ends = ends.copyOf(count * 2)
        }
        out.append(c)
        starts[count] = i
        ends[count] = i + width
        count++
      }
      i += width
    }
    return Normalized(out.toString(), starts.copyOf(count), ends.copyOf(count))
  }

  /** Un carattere nella forma di confronto: scomposto, senza segni diacritici, minuscolo. */
  private fun fold(char: String): String {
    val base = Normalizer.normalize(char, Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
    return when {
      base.isEmpty() -> ""
      base == "’" || base == "‘" || base == "`" || base == "´" -> "'"
      base.isBlank() -> " "
      else -> base.lowercase()
    }
  }

  private val WHITESPACE_RUN = Regex("\\s+")
  private val BLANK_LINES = Regex("\\n\\s*\\n")
  private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+")
  private val BULLET = Regex("^\\s*[-*+]\\s+")
  private val EMPHASIS = Regex("\\*\\*|__|(?<!\\w)[*_](?=\\S)|(?<=\\S)[*_](?!\\w)|`")
}
