package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind

/**
 * Una grezza per sessione, e quale.
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

  /**
   * Le grezze di troppo, che si possono togliere senza perdere niente: tutte quelle che non sono la
   * piu' recente e le cui parti la piu' recente copre gia'. Una grezza con una parte che l'altra non
   * ha (una registrazione arrivata qui prima che l'altro dispositivo trascrivesse) resta: le sue
   * parole sono le sole di quella parte, e la schermata mostra lo stesso la piu' recente.
   *
   * @param partsOf le parti coperte da ogni grezza, per id: quelle dei suoi segmenti.
   */
  fun redundant(raws: List<TranscriptEntity>, partsOf: Map<String, Set<String>>): List<TranscriptEntity> {
    val winner = newest(raws) ?: return emptyList()
    val covered = partsOf[winner.id].orEmpty()
    return raws.filter { it.kind == TranscriptKind.RAW && it.id != winner.id && covered.containsAll(partsOf[it.id].orEmpty()) }
  }
}
