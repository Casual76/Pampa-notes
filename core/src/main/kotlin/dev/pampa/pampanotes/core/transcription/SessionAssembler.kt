package dev.pampa.pampanotes.core.transcription

/**
 * Da parti in fila a una sessione sola.
 *
 * E' la ragione per cui esistono le sessioni. Il telefono che si ferma a meta' di una lezione e
 * riparte lascia due file; qui tornano a essere un testo con un cronometro solo, e il lettore ci
 * salta dentro come se fosse sempre stato una registrazione unica.
 *
 * Il punto delicato e' quale tempo si conserva. Un segmento porta **due** tempi: quello dentro la
 * sua parte, che e' un fatto sul file audio e non cambia mai, e quello dentro la sessione, che e'
 * una conseguenza di quali parti vengono prima. Spostare una parte cambia il secondo e lascia il
 * primo dov'e': per questo una sessione riordinata non si ritrascrive, si ricompone.
 */
object SessionAssembler {

  /** Quello che serve sapere di una parte per collocarla: chi e', e quanto dura. */
  data class Part(val id: String, val durationMs: Long)

  /** Il testo e i segmenti di una sessione, gia' nel tempo della sessione. */
  data class Assembled(
    val text: String,
    val segments: List<SessionSegment>,
  )

  private data class Timed(
    override val startMs: Long,
    override val endMs: Long,
    override val text: String,
  ) : TimedText

  /**
   * A che millisecondo della sessione comincia ogni parte, nell'ordine in cui sono date.
   *
   * La somma delle durate di quelle prima: due registrazioni separate da dieci minuti di pausa
   * restano attaccate, perche' la pausa non e' stata registrata e quindi non esiste nel tempo della
   * sessione. Tenere conto dell'ora di inizio dei file darebbe un cronometro con dentro dei buchi
   * su cui il lettore non puo' saltare.
   */
  fun offsets(parts: List<Part>): Map<String, Long> {
    var offset = 0L
    val result = LinkedHashMap<String, Long>(parts.size)
    parts.forEach { part ->
      result[part.id] = offset
      offset += part.durationMs
    }
    return result
  }

  /** La somma delle durate: quanto dura la sessione intera. */
  fun totalDurationMs(parts: List<Part>): Long = parts.sumOf { it.durationMs }

  /**
   * Quale parte sta suonando a un certo punto della sessione, e a che millisecondo dentro di lei.
   *
   * L'inverso di [offsets]: il lettore ha una playlist di file e riceve una richiesta in tempo di
   * sessione, e questa e' la traduzione. Oltre la fine torna l'ultima parte alla sua fine, perche'
   * un lettore trascinato in fondo deve fermarsi in fondo, non rifiutare la richiesta.
   */
  fun locate(parts: List<Part>, sessionMs: Long): Position? {
    if (parts.isEmpty()) return null
    val target = sessionMs.coerceAtLeast(0L)
    var offset = 0L
    parts.forEachIndexed { index, part ->
      if (target < offset + part.durationMs) return Position(index, part.id, target - offset)
      offset += part.durationMs
    }
    val last = parts.lastIndex
    return Position(last, parts[last].id, parts[last].durationMs)
  }

  data class Position(val partIndex: Int, val partId: String, val offsetInPartMs: Long)

  /**
   * Le parti appena trascritte, una dopo l'altra.
   *
   * Chiamata una volta sola, alla fine di un lavoro: da qui in poi la sessione si ricompone da
   * [reassemble], che non ha bisogno di nessuna richiesta di rete.
   */
  fun assemble(parts: List<PartTranscript>, providerId: String, model: String): SessionTranscript {
    val offsets = offsets(parts.map { Part(it.part.id, it.part.durationMs) })
    val segments = mutableListOf<SessionSegment>()
    val texts = mutableListOf<String>()

    parts.forEach { transcript ->
      val offset = offsets[transcript.part.id] ?: 0L
      transcript.segments.forEachIndexed { index, segment ->
        // Le parole si riempiono qui, una volta sola: chi non le ha se le fa stimare, e da questo
        // punto in poi il database ha sempre le parole e la UI ha una strada sola.
        val filled = WordTimings.fill(segment.words, segment.text, segment.startMs, segment.endMs)
        segments += SessionSegment(
          partId = transcript.part.id,
          indexInPart = index,
          partStartMs = segment.startMs,
          partEndMs = segment.endMs,
          sessionStartMs = offset + segment.startMs,
          sessionEndMs = offset + segment.endMs,
          text = segment.text,
          noSpeechProb = segment.noSpeechProb,
          avgLogProb = segment.avgLogProb,
          wordsEncoded = WordTimings.encode(filled.words, originMs = segment.startMs),
          wordsEstimated = filled.estimated,
          speaker = segment.speaker,
        )
      }
      if (transcript.text.isNotBlank()) texts += transcript.text
    }

    return SessionTranscript(
      text = texts.joinToString(PART_SEPARATOR),
      segments = segments,
      language = parts.firstNotNullOfOrNull { it.language },
      model = model,
      provider = providerId,
    )
  }

  /**
   * Rimette i segmenti nel tempo della sessione dopo che le parti hanno cambiato ordine, o casa.
   *
   * [parts] sono le parti che la sessione ha **adesso**, nell'ordine giusto; [segments] tutto quello
   * che si conosce, anche di parti che non sono piu' qui: quelle vengono scartate, e chi e' arrivato
   * da un'altra sessione si colloca da solo perche' i suoi tempi di parte non sono mai cambiati.
   *
   * Il testo si rifa' da capo. Riordinare le parti e lasciare il vecchio testo vorrebbe dire una
   * pagina in cui i tempi dicono una cosa e le parole un'altra.
   */
  fun reassemble(parts: List<Part>, segments: List<SessionSegment>): Assembled {
    if (parts.isEmpty()) return Assembled("", emptyList())
    val offsets = offsets(parts)
    val byPart = segments.groupBy { it.partId }

    val placed = mutableListOf<SessionSegment>()
    val texts = mutableListOf<String>()

    parts.forEach { part ->
      val offset = offsets.getValue(part.id)
      val own = byPart[part.id].orEmpty().sortedWith(compareBy({ it.partStartMs }, { it.indexInPart }))
      if (own.isEmpty()) return@forEach

      own.forEachIndexed { index, segment ->
        placed += segment.copy(
          indexInPart = index,
          sessionStartMs = offset + segment.partStartMs,
          sessionEndMs = offset + segment.partEndMs,
        )
      }
      val paragraphs = TranscriptStitcher.joinIntoParagraphs(
        own.map { Timed(it.partStartMs, it.partEndMs, it.text) },
      )
      if (paragraphs.isNotBlank()) texts += paragraphs
    }

    return Assembled(text = texts.joinToString(PART_SEPARATOR), segments = placed)
  }

  /** Fra una parte e la successiva si va a capo due volte: sono due registrazioni, non due frasi. */
  private const val PART_SEPARATOR = "\n\n"
}
