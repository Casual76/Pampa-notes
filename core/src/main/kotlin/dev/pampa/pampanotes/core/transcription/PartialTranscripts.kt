package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.db.SegmentEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * «Il testo che arriva a pezzi»: quello che il computer di casa ha gia' trascritto di una sessione,
 * mentre fa il resto.
 *
 * Una lezione lunga che il computer divide da se' (`max_minutes`) finisce un pezzo ogni tanto, ma la
 * risposta arriva tutta alla fine: per un'ora la sessione mostrava solo una barra. Il companion tiene
 * i pezzi finiti (`GET /v1/jobs/<id>/partial`), [OpenAiCompatProvider] li chiede, [TranscriptionRunner]
 * li mette nel tempo della sessione ([PartialCollector]) e il worker li pubblica qui; la schermata
 * della sessione li mostra «in arrivo», sotto la scheda del lavoro.
 *
 * **Niente di questo si salva o si sincronizza**: sta in memoria, per processo, e se ne va quando il
 * lavoro finisce, fallisce o si annulla ([clear]) — o quando il processo muore, che e' lo stesso.
 * E' provvisorio: la risposta finale passa ancora dal filtro sulla lezione intera e dalla separazione
 * delle voci, e il testo vero che si salva e' il suo.
 */
@Singleton
class PartialTranscripts @Inject constructor() {

  private val state = MutableStateFlow<Map<String, SessionPartial>>(emptyMap())

  /** Per sessione: quello che e' arrivato finora. Una sessione che non c'e' non ha niente in arrivo. */
  val bySession: StateFlow<Map<String, SessionPartial>> = state.asStateFlow()

  fun publish(sessionId: String, partial: SessionPartial) {
    state.update { it + (sessionId to partial) }
  }

  fun clear(sessionId: String) {
    state.update { if (sessionId in it) it - sessionId else it }
  }
}

/**
 * Il testo provvisorio di una sessione.
 *
 * @property segments nel tempo della sessione, come quelli salvati (cosi' il lettore ci salta dentro
 *   allo stesso modo), in ordine; `transcriptId` e' [PartialCollector.TRANSCRIPT_ID] e gli id sono
 *   negativi, per non confondersi mai con una riga vera.
 * @property piecesDone i pezzi finiti della registrazione che il computer sta facendo adesso.
 */
data class SessionPartial(
  val segments: List<SegmentEntity>,
  val piecesDone: Int,
  val piecesTotal: Int,
  val partIndex: Int,
  val partCount: Int,
)

/** Quanti pezzi il computer ha di sicuro finito, dallo stato che racconta. */
object PartialPieces {
  /**
   * Il companion comincia il pezzo `k` dopo aver messo da parte i `k - 1` di prima, quindi quando
   * dice «pezzo 3 di 5» i primi due si possono chiedere. Mentre separa le voci li ha finiti tutti.
   * Zero con un pezzo solo (non c'e' niente da mostrare prima della risposta) e a lavoro finito (la
   * risposta sta arrivando, e il companion i provvisori li ha gia' buttati).
   */
  fun ready(progress: RemoteProgress?): Int {
    val remote = progress ?: return 0
    val chunks = remote.chunks ?: return 0
    if (chunks <= 1) return 0
    return when (remote.stage) {
      RemoteStage.DIARIZING -> chunks
      RemoteStage.DONE, RemoteStage.FAILED -> 0
      else -> ((remote.chunk ?: 1) - 1).coerceIn(0, chunks)
    }
  }
}

/**
 * Raccoglie i pezzi provvisori di una sessione, parte per parte, e li mette nel tempo della sessione.
 *
 * I pezzi stanno dentro una registrazione sola, coi tempi della registrazione: il tempo della sessione
 * e' quello della parte piu' la durata delle parti prima ([SessionAssembler.offsets]), la stessa
 * regola dei segmenti salvati, cosi' un tocco porta il lettore dove deve. Una parte finita del tutto
 * (la sua risposta e' arrivata, ma la sessione ha altre parti da fare) resta visibile col suo testo
 * finale: il salvataggio vero arriva solo alla fine della sessione.
 *
 * Puro, e non thread-safe: lo usa il runner, una parte alla volta.
 */
class PartialCollector(parts: List<SessionAssembler.Part>) {

  private val order = parts.map { it.id }
  private val offsets = SessionAssembler.offsets(parts)
  private data class Line(override val startMs: Long, override val endMs: Long, override val text: String) : TimedText

  private val byPart = LinkedHashMap<String, List<TimedText>>()

  /**
   * Un gruppo di pezzi della parte [partId]. `from = 0` ricomincia da capo (un secondo tentativo, o
   * un lavoro del companion agganciato a uno gia' avviato: i pezzi arrivano tutti di nuovo).
   */
  fun offer(partId: String, partIndex: Int, partial: RemotePartial): SessionPartial {
    val before = if (partial.from == 0) emptyList() else byPart[partId].orEmpty()
    byPart[partId] = before + partial.segments.map { Line(it.startMs, it.endMs, it.text) }
    return snapshot(partIndex, partial.piecesDone, partial.piecesTotal)
  }

  /** La parte [partId] e' finita: al posto dei suoi pezzi, il suo testo finale. */
  fun complete(partId: String, partIndex: Int, segments: List<TimedText>): SessionPartial {
    byPart[partId] = segments
    return snapshot(partIndex, 0, 0)
  }

  /** C'e' qualcosa da mostrare? */
  val isEmpty: Boolean get() = byPart.values.all { it.isEmpty() }

  private fun snapshot(partIndex: Int, piecesDone: Int, piecesTotal: Int): SessionPartial {
    val segments = mutableListOf<SegmentEntity>()
    order.forEach { partId ->
      val offset = offsets[partId] ?: return@forEach
      byPart[partId].orEmpty().sortedBy { it.startMs }.forEachIndexed { index, raw ->
        segments += SegmentEntity(
          id = -(segments.size + 1).toLong(),
          transcriptId = TRANSCRIPT_ID,
          partId = partId,
          indexInPart = index,
          partStartMs = raw.startMs,
          partEndMs = raw.endMs,
          sessionStartMs = offset + raw.startMs,
          sessionEndMs = offset + raw.endMs,
          text = raw.text.trim(),
        )
      }
    }
    return SessionPartial(segments, piecesDone, piecesTotal, partIndex, order.size)
  }

  companion object {
    /** Il `transcriptId` dei segmenti provvisori: nessuna trascrizione vera si chiama cosi'. */
    const val TRANSCRIPT_ID = "partial"
  }
}
