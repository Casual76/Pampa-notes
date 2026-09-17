package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.db.SegmentEntity

/**
 * Da segmenti a paragrafi.
 *
 * Whisper restituisce frasi di cinque secondi. Trecento frasi in fila non sono una trascrizione, sono
 * un elenco: si va a capo dove c'era una pausa lunga, che quasi sempre e' un cambio di argomento, e
 * al confine fra due registrazioni, che e' un fatto e non un'interpretazione.
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

  data class Paragraph(val segments: List<SegmentEntity>) {
    val startMs: Long get() = segments.first().sessionStartMs
    val endMs: Long get() = segments.last().sessionEndMs
    val partId: String get() = segments.first().partId

    /** Le frasi una dopo l'altra, separate da uno spazio. */
    val text: String get() = segments.joinToString(" ") { it.text.trim() }
  }

  fun split(segments: List<SegmentEntity>, maxSegments: Int = Int.MAX_VALUE): List<Paragraph> {
    if (segments.isEmpty()) return emptyList()
    val result = mutableListOf<Paragraph>()
    var current = mutableListOf<SegmentEntity>()

    segments.forEachIndexed { index, segment ->
      val previous = segments.getOrNull(index - 1)
      val breaks = previous != null && (
        segment.partId != previous.partId ||
          segment.sessionStartMs - previous.sessionEndMs >= GAP_MS ||
          current.size >= maxSegments
        )
      if (breaks && current.isNotEmpty()) {
        result += Paragraph(current.toList())
        current = mutableListOf()
      }
      current += segment
    }
    if (current.isNotEmpty()) result += Paragraph(current.toList())
    return result
  }
}
