package dev.pampa.pampanotes.core.transcription

/**
 * Dove va il risultato di una trascrizione, quando arriva.
 *
 * Il worker fotografa le parti della sessione alla partenza, e una lezione lunga sono minuti o ore:
 * nel frattempo chi guarda la sessione puo' riordinare le parti, spostarne una in un'altra sessione,
 * separare, unire, o cancellarne una. Scrivere tutto nella sessione di partenza, nell'ordine della
 * fotografia, voleva dire tempi sbagliati dopo un riordino, parole rimaste nella sessione vecchia
 * dopo uno spostamento, segmenti di una parte che non esiste piu', e — dopo un'unione — un risultato
 * buttato perche' la sessione non c'era piu'.
 *
 * La regola del modello (i segmenti appartengono alla parte, non alla trascrizione) dice gia' come
 * si fa: ogni parte ancora viva riceve i suoi segmenti **nella sessione in cui sta adesso**, e ogni
 * sessione toccata si ricompone dall'ordine che ha adesso. Qui si decide solo chi e cosa; scrivere lo
 * fa `SessionRepository.saveTranscription`.
 */
object TranscriptPlacement {

  /** Una sessione da scrivere, con le parti del lavoro che adesso stanno li', nell'ordine del lavoro. */
  data class Target(val sessionId: String, val partIds: List<String>)

  /**
   * @param jobSessionId la sessione per cui il lavoro e' partito; puo' non esistere piu'.
   * @param snapshot le parti che il lavoro ha trascritto, nel suo ordine.
   * @param currentSession dove sta adesso ogni parte; una parte che manca e' stata cancellata.
   * @return le sessioni da scrivere, nell'ordine in cui le parti le incontrano, con quella del lavoro
   *   **per ultima**: e' l'ordine di `SessionRepository` («prima chi riceve, poi chi perde»), cosi'
   *   se chi perde restasse senza parole la sua ricomposizione non si porterebbe dietro niente di
   *   quello che e' gia' stato scritto altrove.
   */
  fun plan(jobSessionId: String, snapshot: List<String>, currentSession: Map<String, String>): List<Target> {
    val bySession = LinkedHashMap<String, MutableList<String>>()
    snapshot.distinct().forEach { partId ->
      val sessionId = currentSession[partId] ?: return@forEach
      bySession.getOrPut(sessionId) { mutableListOf() } += partId
    }
    val targets = bySession.map { (sessionId, parts) -> Target(sessionId, parts) }
    return targets.filter { it.sessionId != jobSessionId } + targets.filter { it.sessionId == jobSessionId }
  }
}
