package dev.pampa.pampanotes.core.transcription

/**
 * Quello che il computer di casa dice di se' in fondo a una trascrizione: su cosa ha girato e
 * quanto ci ha messo. Tutti facoltativi — Groq non ne manda nessuno, e un companion di una versione
 * precedente solo `device_used`.
 */
data class ServerReport(
  /** `processing_s`: il tempo di lavoro del server, senza l'invio del file. */
  val processingMs: Long? = null,
  /** `audio_s`: quanto audio il server ha ascoltato. */
  val audioMs: Long? = null,
  /** `device_used`: "cuda" o "cpu" — la scheda piena scende sul processore, e va detto. */
  val device: String? = null,
) {
  companion object {
    /**
     * Piu' risposte dello stesso lavoro (una lezione tagliata a pezzi) in un resoconto solo: i tempi
     * si sommano, e basta un pezzo sul processore perche' il lavoro sia stato «sul processore» — e'
     * quello che spiega perche' ci ha messo di piu'.
     */
    fun merge(reports: List<ServerReport>): ServerReport? {
      if (reports.isEmpty()) return null
      val devices = reports.mapNotNull { it.device }
      return ServerReport(
        processingMs = reports.mapNotNull { it.processingMs }.takeIf { it.size == reports.size }?.sum(),
        audioMs = reports.mapNotNull { it.audioMs }.takeIf { it.size == reports.size }?.sum(),
        device = when {
          devices.isEmpty() -> null
          "cpu" in devices -> "cpu"
          else -> devices.first()
        },
      )
    }
  }
}

/**
 * I resoconti arrivati di recente, in memoria.
 *
 * Esiste per non dover far passare questi tre numeri attraverso il motore della trascrizione, che
 * li butterebbe via insieme al resto della risposta (e che da un pezzo riletto dal disco non li
 * avrebbe comunque). La coda del computer di casa ha concorrenza uno: i resoconti arrivati fra
 * l'inizio e la fine di un lavoro sono di quel lavoro, e nessun altro ne scrive.
 */
object ServerReports {
  private const val KEEP = 64

  private val entries = ArrayDeque<Pair<Long, ServerReport>>()

  fun publish(report: ServerReport, atMillis: Long = System.currentTimeMillis()) {
    synchronized(entries) {
      entries.addLast(atMillis to report)
      while (entries.size > KEEP) entries.removeFirst()
    }
  }

  /** Quelli arrivati da [sinceMillis] in poi, tolti dall'elenco: un lavoro li legge una volta. */
  fun drainSince(sinceMillis: Long): List<ServerReport> = synchronized(entries) {
    val taken = entries.filter { it.first >= sinceMillis }.map { it.second }
    entries.removeAll { it.first >= sinceMillis }
    taken
  }
}
