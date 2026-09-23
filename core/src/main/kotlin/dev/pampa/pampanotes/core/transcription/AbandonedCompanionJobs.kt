package dev.pampa.pampanotes.core.transcription

/**
 * I lavori lasciati indietro sul computer di casa: POST abbandonate (perse, cadute, rifiutate) il
 * cui id il companion potrebbe ancora avere in mano, da fermare con una `DELETE` prima che la stessa
 * parte ci lavori due volte.
 *
 * **In memoria, per processo, non su disco.** Il caso che conta e' il riprovare subito — i tentativi
 * del runner, o la fila che riparte appena il PC risponde — e sta tutto dentro lo stesso processo.
 * Un processo che muore chiude le sue connessioni, e il companion che si accorge di una connessione
 * chiusa ferma il lavoro da se'; per il PC riavviato invece il lavoro vecchio non esiste piu'. Una
 * tabella o una chiave in DataStore scritta a ogni richiesta costerebbe piu' del caso raro che
 * coprirebbe (processo ucciso *e* companion che non se ne accorge), e porterebbe credenziali su disco.
 *
 * Non per indirizzo: la stessa parte puo' ripartire da Tailscale dopo essere partita da casa, e
 * il companion e' lo stesso. Chi prende l'elenco lo manda all'indirizzo che usa adesso; un id che il
 * companion non conosce risponde 404, e non costa niente.
 */
class AbandonedCompanionJobs(private val clock: () -> Long = RemoteJobPoller.MONOTONIC_MS) {

  /** Un id, con le credenziali della POST che l'ha creato: sono quelle che il companion accetta. */
  data class Entry(val jobId: String, val headers: Map<String, String>, val at: Long)

  private val entries = ArrayDeque<Entry>()

  @Synchronized
  fun record(jobId: String, headers: Map<String, String>) {
    entries.removeAll { it.jobId == jobId }
    entries.addLast(Entry(jobId, headers, clock()))
    while (entries.size > MAX_ENTRIES) entries.removeFirst()
  }

  /** Tutto quello che c'e' ancora da fermare, tolto dall'elenco. I troppo vecchi si lasciano andare. */
  @Synchronized
  fun drain(): List<Entry> {
    val now = clock()
    val fresh = entries.filter { now - it.at < MAX_AGE_MS }
    entries.clear()
    return fresh
  }

  /** Una `DELETE` non arrivata (PC muto): si riprova la prossima volta, se non e' troppo vecchia. */
  @Synchronized
  fun putBack(entry: Entry) {
    if (entries.none { it.jobId == entry.jobId }) entries.addFirst(entry)
    while (entries.size > MAX_ENTRIES) entries.removeFirst()
  }

  companion object {
    const val MAX_ENTRIES = 8

    /** Un lavoro abbandonato da piu' di due ore e' finito comunque, in un modo o nell'altro. */
    const val MAX_AGE_MS = 2 * 60 * 60_000L

    /** Quello del processo: i provider nascono e muoiono a ogni lavoro, l'elenco no. */
    val shared = AbandonedCompanionJobs()
  }
}
