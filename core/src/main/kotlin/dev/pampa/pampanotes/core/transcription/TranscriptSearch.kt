package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.db.SegmentEntity
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
    val index = segmentAt(paragraph, offset)
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

  /** Quale segmento del paragrafo contiene il carattere [offset]: l'ultimo, se l'offset va oltre. */
  fun segmentAt(paragraph: TranscriptParagraphs.Paragraph, offset: Int): Int {
    val ranges = paragraph.ranges
    return ranges.indexOfFirst { offset <= it.last }.let { if (it < 0) ranges.lastIndex else it }
  }

  // --- La ricerca che salta al minuto -----------------------------------------------------------
  //
  // La ricerca di tutte le note (FTS4) dice che «Kant» sta nella trascrizione della lezione del 12;
  // qui si trova *dove*, per aprire la registrazione in quel momento. Con lo stesso confronto della
  // ricerca dentro la sessione, e sugli stessi paragrafi della schermata: cosi' l'occorrenza da cui si
  // parte e' una di quelle che la barra della sessione conta, e il «3 di 12» comincia da li'.

  /**
   * La prima occorrenza di una ricerca dell'app: nel paragrafo [paragraph], al carattere [offset], e
   * [query] e' quello che la ha trovata — la ricerca intera, o una sua parola.
   */
  data class FirstHit(val paragraph: Int, val offset: Int, val query: String)

  /** Un momento della sessione in cui si dice quello che si cercava, e le parole da evidenziare. */
  data class Moment(val timeMs: Long, val query: String)

  /**
   * Dove [query] compare la prima volta nei [paragraphs].
   *
   * Prima la ricerca intera, come frase; se non c'e', la prima parola che compare. Serve il ripiego
   * perche' FTS4 trova una trascrizione con «Kant» e «critica» anche lontane fra loro (ogni parola e'
   * un prefisso, e basta che ci siano tutte), mentre qui si cerca una sequenza di caratteri: senza, un
   * risultato dell'elenco porterebbe a una registrazione in cui «non si trova niente». Fra le parole
   * vince la prima detta, e quella diventa la parola evidenziata nella sessione.
   *
   * Si normalizza un paragrafo alla volta e ci si ferma al primo che contiene qualcosa: di una
   * registrazione di diciannove ore di solito basta leggere l'inizio.
   */
  fun firstHit(paragraphs: List<TranscriptParagraphs.Paragraph>, query: String): FirstHit? {
    if (paragraphs.isEmpty()) return null
    val normalized = arrayOfNulls<Normalized>(paragraphs.size)
    fun prepared(index: Int): Normalized = normalized[index] ?: normalizeMapped(paragraphs[index].text).also { normalized[index] = it }

    val whole = normalizeQuery(query)
    if (whole.length >= MIN_QUERY_LENGTH) {
      paragraphs.indices.forEach { index ->
        val text = prepared(index)
        val at = text.text.indexOf(whole)
        if (at >= 0) return FirstHit(index, text.starts[at], query.trim())
      }
    }

    val terms = searchTerms(query).map { it to normalizeQuery(it) }.filter { (_, needle) -> needle != whole }
    if (terms.isEmpty()) return null
    paragraphs.indices.forEach { index ->
      val text = prepared(index)
      val best = terms
        .map { (term, needle) -> term to text.text.indexOf(needle) }
        .filter { (_, at) -> at >= 0 }
        .minByOrNull { (_, at) -> at }
      if (best != null) return FirstHit(index, text.starts[best.second], best.first)
    }
    return null
  }

  /**
   * Il momento della prima occorrenza di [query] in una grezza: i paragrafi della schermata
   * ([TranscriptParagraphs.MAX_SEGMENTS_ON_SCREEN]), [firstHit], e il tempo della parola ([timeOf]).
   */
  fun firstMoment(segments: List<SegmentEntity>, query: String): Moment? {
    val paragraphs = TranscriptParagraphs.split(segments, TranscriptParagraphs.MAX_SEGMENTS_ON_SCREEN)
    val hit = firstHit(paragraphs, query) ?: return null
    return Moment(timeOf(paragraphs[hit.paragraph], hit.offset), hit.query)
  }

  /**
   * Le parole di una ricerca dell'app, come le vede FTS4: separate da spazi e dai segni che
   * `SearchRepository.prepareQuery` toglie, e solo quelle abbastanza lunghe da cercarle.
   */
  fun searchTerms(query: String): List<String> =
    query.split(FTS_SEPARATORS)
      .map { it.trim() }
      .filter { normalizeQuery(it).length >= MIN_QUERY_LENGTH }
      .distinctBy { normalizeQuery(it) }

  /**
   * Quale delle [matches] diventa la corrente quando si arriva nella sessione al momento [atMs]: la
   * prima detta in quel momento o dopo (la stessa da cui [firstMoment] ha preso il tempo, se il testo
   * non e' cambiato nel frattempo), altrimenti l'ultima prima. Si calcola il tempo solo delle
   * occorrenze nei paragrafi che finiscono dopo [atMs], e ci si ferma alla prima buona.
   */
  fun matchAt(paragraphs: List<TranscriptParagraphs.Paragraph>, matches: List<Match>, atMs: Long): Int {
    if (matches.isEmpty()) return 0
    matches.forEachIndexed { index, match ->
      val paragraph = paragraphs.getOrNull(match.block) ?: return@forEachIndexed
      if (paragraph.endMs < atMs - MOMENT_TOLERANCE_MS) return@forEachIndexed
      if (timeOf(paragraph, match.start) >= atMs - MOMENT_TOLERANCE_MS) return index
    }
    return matches.lastIndex
  }

  /**
   * Quanto prima di [matchAt] un'occorrenza vale ancora «in quel momento»: i tempi stimati delle
   * parole si ricalcolano uguali, ma un arrotondamento non deve far scegliere l'occorrenza dopo.
   */
  private const val MOMENT_TOLERANCE_MS = 250L

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
  private val FTS_SEPARATORS = Regex("""[\s"*:()^\-]+""")
  private val BLANK_LINES = Regex("\\n\\s*\\n")
  private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+")
  private val BULLET = Regex("^\\s*[-*+]\\s+")
  private val EMPHASIS = Regex("\\*\\*|__|(?<!\\w)[*_](?=\\S)|(?<=\\S)[*_](?!\\w)|`")
}
