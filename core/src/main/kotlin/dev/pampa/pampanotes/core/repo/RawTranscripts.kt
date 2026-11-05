package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind

/**
 * La grezza da mostrare fra le versioni conservate di una sessione.
 *
 * La regola c'era gia' ([SessionRepository.saveTranscription] sostituisce la grezza di prima), ma non
 * reggeva a due dispositivi che trascrivono la stessa sessione insieme: col sync ne arrivavano due, e
 * ognuno sceglieva a modo suo — il repository la piu' recente, la schermata della sessione la prima
 * dell'elenco, cioe' la piu' vecchia. Si leggeva una lezione, se ne ascoltava un'altra, e si
 * ritrascriveva quella sbagliata. Qui la scelta e' una sola, per tutti. Puro, per provarlo in JVM.
 */
object RawTranscripts {

  /** L'ordine delle grezze: la piu' recente vince, e a pari ora l'id, perche' tutti scelgano la stessa. */
  private val NEWEST_LAST = compareBy<TranscriptEntity>({ it.createdAt }, { it.id })

  /** La grezza che vale: la piu' recente. */
  fun newest(transcripts: List<TranscriptEntity>): TranscriptEntity? =
    transcripts.filter { it.kind == TranscriptKind.RAW }.maxWithOrNull(NEWEST_LAST)

  /** The timing source follows the selected version, even when newer RAW histories are retained. */
  fun forSelection(transcripts: List<TranscriptEntity>, activeId: String?): TranscriptEntity? {
    val selected = transcripts.firstOrNull { it.id == activeId } ?: return newest(transcripts)
    if (selected.kind == TranscriptKind.RAW) return selected
    return transcripts.firstOrNull { it.id == selected.parentId && it.kind == TranscriptKind.RAW && it.sessionId == selected.sessionId }
  }

}
