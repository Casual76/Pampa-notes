package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.db.SegmentEntity

/**
 * Da segmenti a paragrafi.
 *
 * Whisper restituisce frasi di cinque secondi. Trecento frasi in fila non sono una trascrizione, sono
 * un elenco: si va a capo dove c'era una pausa lunga, che quasi sempre e' un cambio di argomento, e
 * al confine fra due registrazioni, che e' un fatto e non un'interpretazione. Quando il computer ha
 * separato le voci, anche dove cambia chi parla, e il paragrafo sa di chi e' ([Paragraph.voice]).
 *
 * Sta qui e non nella schermata perche' la stessa divisione serve due volte, e due copie della regola
 * sono due regole destinate a divergere: la pagina la usa per fare le card, l'export per andare a
 * capo nel Markdown. Cambia solo il tetto — a schermo una card alta dieci schermi non si scorre, in
 * un documento un paragrafo lungo e' solo un paragrafo lungo.
 */
object TranscriptParagraphs {

  /** Il salto oltre il quale si va a capo: in una lezione due secondi di silenzio sono un paragrafo. */
  const val GAP_MS = 2_000L

  /** Quante frasi stanno in una card prima che diventi un muro. */
  const val MAX_SEGMENTS_ON_SCREEN = 10

  /**
   * Quante ne stanno in un paragrafo di un documento esportato.
   *
   * Non e' una questione di lunghezza ma di citazioni: il tempo si stampa una volta per paragrafo, e
   * un `[mm:ss]` ogni tre minuti non permette di citare niente di preciso. Otto frasi sono una
   * quarantina di secondi, che e' la grana giusta per dire "l'ha detto qui".
   */
  const val MAX_SEGMENTS_IN_DOCUMENT = 8

  /**
   * Da qui in su una pausa non e' piu' un a capo: e' un fatto da dire.
   *
   * Un paragrafo nuovo dopo due secondi e uno dopo sedici minuti si vedevano identici, e in una
   * registrazione di venti ore i minuti in cui non succede niente sono la meta' della storia: chi
   * legge crede che la frase dopo sia la risposta a quella prima, e un assistente anche. Un minuto e'
   * gia' molto piu' di qualunque pausa di chi parla, e molto meno di un intervallo.
   */
  const val SILENCE_MS = 60_000L

  data class Paragraph(
    val segments: List<SegmentEntity>,
    /**
     * Quanto silenzio c'e' stato prima, quando e' almeno [SILENCE_MS]; null altrimenti, e sempre
     * per il primo paragrafo. Misurato nel tempo della sessione, quindi anche a cavallo fra due
     * registrazioni: le parti si mettono in fila senza buchi, e ogni millisecondo del cronometro e'
     * audio registrato in cui nessuno ha parlato.
     */
    val silenceBeforeMs: Long? = null,
    /**
     * «Chi parla»: il numero della voce di questo paragrafo, da 1, nell'ordine in cui le voci
     * compaiono. Null quando le voci non sono state separate, o quando in tutta la trascrizione ce
     * n'e' una sola — «Voce 1» su ogni paragrafo di una lezione non direbbe niente. Vedi [voices].
     */
    val voice: Int? = null,
  ) {
    val startMs: Long get() = segments.first().sessionStartMs
    val endMs: Long get() = segments.last().sessionEndMs
    val partId: String get() = segments.first().partId

    /** Le frasi una dopo l'altra, separate da uno spazio. */
    val text: String by lazy { segments.joinToString(" ") { it.text.trim() } }

    /**
     * Dove comincia e finisce ogni segmento dentro [text], nello stesso ordine: la schermata ci
     * traduce un tocco in un segmento e le parole in caratteri. Sta accanto al testo perche' deve
     * seguirne la stessa regola di composizione, uno spazio fra una frase e l'altra.
     */
    val ranges: List<IntRange> by lazy {
      var offset = 0
      segments.mapIndexed { index, segment ->
        if (index > 0) offset += 1
        val length = segment.text.trim().length
        val range = offset until offset + length
        offset += length
        range
      }
    }
  }

  fun split(segments: List<SegmentEntity>, maxSegments: Int = Int.MAX_VALUE): List<Paragraph> {
    if (segments.isEmpty()) return emptyList()
    val voices = voices(segments)
    val result = mutableListOf<Paragraph>()
    var current = mutableListOf<SegmentEntity>()
    var silence: Long? = null

    fun close() {
      result += Paragraph(current.toList(), silence, voiceKey(current.first())?.let { voices[it] })
    }

    segments.forEachIndexed { index, segment ->
      val previous = segments.getOrNull(index - 1)
      val gap = previous?.let { segment.sessionStartMs - it.sessionEndMs } ?: 0L
      val breaks = previous != null && (
        segment.partId != previous.partId ||
          gap >= GAP_MS ||
          current.size >= maxSegments ||
          // Un'altra voce e' un altro paragrafo: una risposta attaccata alla domanda sembra detta da
          // chi ha fatto la domanda.
          segment.speaker != previous.speaker
        )
      if (breaks && current.isNotEmpty()) {
        close()
        current = mutableListOf()
        silence = gap.takeIf { it >= SILENCE_MS }
      }
      current += segment
    }
    if (current.isNotEmpty()) close()
    return result
  }

  /**
   * Da etichette del computer («SPEAKER_00») a numeri, «Voce 1», «Voce 2», nell'ordine in cui le
   * voci compaiono nella sessione.
   *
   * La chiave e' la parte **e** l'etichetta: ogni registrazione si separa per conto suo, e
   * SPEAKER_00 della seconda non e' per forza SPEAKER_00 della prima. Meglio una «Voce 3» che e' la
   * stessa persona della «Voce 1» che un numero solo dato a due persone.
   *
   * Vuota se nessuna registrazione ha almeno due voci: un monologo in due parti ha SPEAKER_00 nella
   * prima e SPEAKER_00 nella seconda, cioe' due chiavi, e diventava «Voce 1» e «Voce 2» — e l'export
   * metteva una voce davanti a ogni paragrafo di una lezione detta da una persona sola. Le voci si
   * dicono solo quando dentro una stessa registrazione qualcuno ha davvero risposto.
   */
  fun voices(segments: List<SegmentEntity>): Map<String, Int> {
    val conversation = segments.filter { it.speaker != null }
      .groupBy { it.partId }
      .values.any { part -> part.mapTo(HashSet()) { it.speaker }.size >= 2 }
    if (!conversation) return emptyMap()
    val numbers = LinkedHashMap<String, Int>()
    segments.forEach { segment ->
      val key = voiceKey(segment) ?: return@forEach
      if (key !in numbers) numbers[key] = numbers.size + 1
    }
    return numbers
  }

  internal fun voiceKey(segment: SegmentEntity): String? = segment.speaker?.let { "${segment.partId}\u0000$it" }

  /**
   * «16 min», «1 h 20 min», «2 h»: quanto e' durato un silenzio, arrotondato al minuto.
   *
   * Le unita' si passano da fuori perche' questo e' `:core` e le parole stanno nell'app; quelle di
   * ripiego sono le stesse in italiano e in inglese. Mai «0 min»: sotto il minuto non si chiama.
   */
  fun silenceDuration(millis: Long, hours: String = "h", minutes: String = "min"): String {
    val total = ((millis + 30_000) / 60_000).coerceAtLeast(1)
    val h = total / 60
    val m = total % 60
    return when {
      h == 0L -> "$m $minutes"
      m == 0L -> "$h $hours"
      else -> "$h $hours $m $minutes"
    }
  }
}
