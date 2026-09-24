package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.model.wordCount

/**
 * «Capitoli automatici»: l'indice dei tratti in cui si parla, in una registrazione lunga.
 *
 * Diciannove ore di audio sono dieci conversazioni separate da ore di niente, e la trascrizione le
 * mette una dopo l'altra: per ritrovare «quella della sera» si scorre per mezz'ora. Un capitolo e' un
 * tratto di parlato fra due silenzi lunghi, e di lui si dicono solo **fatti**: dove comincia e dove
 * finisce, quanto si e' parlato, quante parole, chi parla (se il computer ha separato le voci) e le
 * prime parole cosi' come sono state dette, fra virgolette.
 *
 * **Non e' un riassunto, e non lo diventera'.** L'app non riassume (vedi CLAUDE.md): niente titoli
 * inventati, niente «di cosa parla». Le prime parole sono una citazione — la si riconosce, non la si
 * interpreta — e i confini vengono dai tempi della trascrizione grezza, non da un modello.
 *
 * La regola, in tre passi:
 * 1. **si taglia dove si tace a lungo**: un silenzio di almeno [thresholdFor] (un trentesimo della
 *    registrazione, fra un minuto e cinque) apre un capitolo nuovo. Misurato nel tempo della
 *    sessione, quindi anche a cavallo fra due registrazioni; il confine fra due file da solo non
 *    taglia, perche' un registratore che spezza ogni due ore non dice niente su chi parla;
 * 2. **un tratto con meno di [MIN_SPEECH_MS] di parlato si attacca al vicino** dal lato del silenzio
 *    piu' corto: un «pronto?» isolato nel cuore della notte non e' un capitolo, ma nemmeno si butta —
 *    un indice deve coprire tutto;
 * 3. **al massimo [MAX_CHAPTERS]**: se sono di piu', si tolgono i confini dei silenzi piu' corti,
 *    cosi' quelli che restano sono sempre le pause piu' lunghe.
 *
 * Si mostra solo da [MIN_SHOWN] capitoli in su ([index]): una lezione con l'intervallo in mezzo ne ha
 * due, e un indice di due voci e' rumore sopra il testo.
 *
 * Puro, e si calcola al volo da chi lo mostra (schermata, export): nessuna colonna, niente da
 * sincronizzare, e un riordino delle parti lo rifa' da se'.
 */
object Chapters {

  /** Da quanti capitoli in su l'indice si mostra. */
  const val MIN_SHOWN = 3

  /** Il tetto: quaranta righe sono gia' una pagina da scorrere. */
  const val MAX_CHAPTERS = 40

  /** Sotto un minuto di parlato un tratto non fa un capitolo da solo. */
  const val MIN_SPEECH_MS = 60_000L

  /** Il silenzio che taglia, ai due estremi: mai sotto un minuto, mai sopra cinque. */
  const val MIN_THRESHOLD_MS = 60_000L
  const val MAX_THRESHOLD_MS = 5 * 60_000L

  /** Quante parole al massimo nella citazione, e quante almeno prima di fermarsi a un punto. */
  const val QUOTE_WORDS = 8
  private const val QUOTE_MIN_WORDS = 3

  data class Chapter(
    /** Da 1, nell'ordine della sessione. */
    val number: Int,
    /** Inizio della prima frase, nel tempo della sessione. */
    val startMs: Long,
    /** Fine dell'ultima frase. */
    val endMs: Long,
    /** Quanto si e' parlato davvero: i silenzi dentro il capitolo non contano. */
    val speechMs: Long,
    val words: Int,
    /** Le prime parole, cosi' come sono state trascritte, senza virgolette: chi mostra le mette. */
    val quote: String,
    /** Il silenzio che lo separa dal capitolo prima; null per il primo. */
    val silenceBeforeMs: Long?,
    /** «Chi parla»: i numeri delle voci presenti, nell'ordine in cui compaiono; vuota senza voci. */
    val voices: List<Int>,
    /** Il primo e l'ultimo segmento, come indici nella lista data. */
    val firstSegment: Int,
    val lastSegment: Int,
  )

  /**
   * Il silenzio che apre un capitolo: un trentesimo della registrazione, fra [MIN_THRESHOLD_MS] e
   * [MAX_THRESHOLD_MS]. Una lezione di un'ora taglia a due minuti — l'intervallo si', il professore
   * che cerca una slide no —; da due ore e mezza in su a cinque, che in una registrazione lasciata
   * accesa separa una conversazione dall'altra senza spezzare chi si ferma a pensare.
   */
  fun thresholdFor(totalMs: Long): Long = (totalMs / 30).coerceIn(MIN_THRESHOLD_MS, MAX_THRESHOLD_MS)

  /** I capitoli da mostrare: vuota sotto [MIN_SHOWN]. */
  fun index(segments: List<SegmentEntity>, totalMs: Long? = null): List<Chapter> =
    build(segments, totalMs).takeIf { it.size >= MIN_SHOWN }.orEmpty()

  /** Il capitolo in cui cade [positionMs]: l'ultimo cominciato, o il primo prima di tutti. */
  fun currentIndex(chapters: List<Chapter>, positionMs: Long): Int =
    chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

  /**
   * Tutti i capitoli, anche uno solo. [totalMs] e' quanto dura la sessione (le parti in fila); senza,
   * la fine dell'ultima frase.
   */
  fun build(segments: List<SegmentEntity>, totalMs: Long? = null): List<Chapter> {
    if (segments.isEmpty()) return emptyList()
    val threshold = thresholdFor(totalMs ?: segments.maxOf { it.sessionEndMs })

    // 1. I tagli: dove il silenzio dalla fine della voce (la piu' tarda finora, se due frasi si
    //    sovrappongono) all'inizio della frase dopo e' almeno la soglia.
    val groups = mutableListOf<Group>()
    var from = 0
    var gapBefore: Long? = null
    var voiceEnd = segments[0].sessionEndMs
    for (i in 1 until segments.size) {
      val gap = segments[i].sessionStartMs - voiceEnd
      if (gap >= threshold) {
        groups += group(segments, from, i - 1, gapBefore)
        from = i
        gapBefore = gap
      }
      voiceEnd = maxOf(voiceEnd, segments[i].sessionEndMs)
    }
    groups += group(segments, from, segments.lastIndex, gapBefore)

    // 2. I tratti troppo brevi si attaccano al vicino, cominciando dal piu' breve: dal lato del
    //    silenzio piu' corto, che e' il confine meno forte.
    while (groups.size > 1) {
      val shortest = groups.indices.filter { groups[it].speechMs < MIN_SPEECH_MS }.minByOrNull { groups[it].speechMs } ?: break
      val left = if (shortest > 0) groups[shortest].gapBefore ?: Long.MAX_VALUE else Long.MAX_VALUE
      val right = if (shortest < groups.lastIndex) groups[shortest + 1].gapBefore ?: Long.MAX_VALUE else Long.MAX_VALUE
      merge(segments, groups, if (left <= right) shortest - 1 else shortest)
    }

    // 3. Il tetto: via i confini dei silenzi piu' corti finche' non si sta dentro.
    while (groups.size > MAX_CHAPTERS) {
      val weakest = (1 until groups.size).minBy { groups[it].gapBefore ?: Long.MAX_VALUE }
      merge(segments, groups, weakest - 1)
    }

    val voices = TranscriptParagraphs.voices(segments)
    return groups.mapIndexed { index, g ->
      val slice = segments.subList(g.from, g.to + 1)
      Chapter(
        number = index + 1,
        startMs = slice.first().sessionStartMs,
        endMs = slice.maxOf { it.sessionEndMs },
        speechMs = g.speechMs,
        words = slice.sumOf { it.text.wordCount() },
        quote = quoteOf(slice.asSequence().take(QUOTE_SEGMENTS).joinToString(" ") { it.text.trim() }),
        silenceBeforeMs = g.gapBefore,
        voices = if (voices.isEmpty()) emptyList() else slice.mapNotNull { TranscriptParagraphs.voiceKey(it)?.let(voices::get) }.distinct(),
        firstSegment = g.from,
        lastSegment = g.to,
      )
    }
  }

  /**
   * Le prime parole di un testo: la prima frase se finisce entro [QUOTE_WORDS] parole (e ne ha almeno
   * tre: un «Eh.» da solo non dice niente), altrimenti le prime [QUOTE_WORDS] e «…».
   *
   * Si tocca solo il contorno — spazi, trattini d'apertura, virgolette che finirebbero dentro altre
   * virgolette —, mai le parole: e' una citazione.
   */
  fun quoteOf(text: String): String {
    val words = text
      .replace(Regex("""[“”«»"]"""), "")
      .trim()
      .trimStart('-', '–', '—', ' ')
      .split(Regex("""\s+"""))
      .filter { it.isNotEmpty() }
    if (words.isEmpty()) return ""
    var count = minOf(words.size, QUOTE_WORDS)
    var sentenceEnded = false
    for (k in 0 until count) {
      if (k + 1 >= QUOTE_MIN_WORDS && words[k].last() in SENTENCE_END) {
        count = k + 1
        sentenceEnded = true
        break
      }
    }
    val taken = words.take(count).joinToString(" ")
    if (sentenceEnded || count == words.size) return taken
    return taken.trimEnd(',', ';', ':', '.', ' ') + "…"
  }

  // -----------------------------------------------------------------------------------------------

  /** Un capitolo in costruzione: i suoi segmenti (da, a, compresi) e il silenzio prima. */
  private data class Group(val from: Int, val to: Int, val gapBefore: Long?, val speechMs: Long)

  private fun group(segments: List<SegmentEntity>, from: Int, to: Int, gapBefore: Long?) =
    Group(from, to, gapBefore, speechOf(segments, from, to))

  /** Unisce il capitolo [at] col successivo. */
  private fun merge(segments: List<SegmentEntity>, groups: MutableList<Group>, at: Int) {
    val a = groups[at]
    val b = groups[at + 1]
    groups[at] = group(segments, a.from, b.to, a.gapBefore)
    groups.removeAt(at + 1)
  }

  /** Il parlato: l'unione degli intervalli, cosi' due frasi che si accavallano non contano doppio. */
  private fun speechOf(segments: List<SegmentEntity>, from: Int, to: Int): Long {
    var total = 0L
    var coveredTo = Long.MIN_VALUE
    for (i in from..to) {
      val s = segments[i]
      val start = maxOf(s.sessionStartMs, coveredTo)
      if (s.sessionEndMs > start) total += s.sessionEndMs - start
      coveredTo = maxOf(coveredTo, s.sessionEndMs)
    }
    return total
  }

  /** Per la citazione bastano le prime frasi: una frase sola puo' essere un «Eh.». */
  private const val QUOTE_SEGMENTS = 4

  private val SENTENCE_END = charArrayOf('.', '!', '?', '…')
}
