package dev.pampa.pampanotes.core.files

import dev.pampa.pampanotes.core.db.SizeTotal

/**
 * Dove sta un file: la domanda a cui la pagina Archiviazione deve rispondere prima di ogni altra.
 *
 * Con l'indice in cloud le righe viaggiano e i file no, quindi «ho questa registrazione» vuol dire
 * due cose indipendenti: il file e' su questo dispositivo, e il computer di casa ne ha una copia
 * (`archivedAt > 0`). Le quattro combinazioni sono i quattro stati che l'utente deve poter
 * leggere senza sapere niente di sync, archivio e mirror.
 */
enum class FilePlace {
  /** Qui e sul computer: al sicuro, e si puo' togliere da qui senza perdere niente. */
  BOTH,

  /** Solo qui: se si perde il telefono, si perde il file. */
  ONLY_HERE,

  /** Solo sul computer: la riga e' arrivata, il file si scarica quando serve. */
  ONLY_COMPUTER,

  /**
   * Ne' qui ne' sul computer: l'ha solo il dispositivo che l'ha registrato o importato, finche'
   * quello non lo archivia. Da qui non c'e' modo di averlo.
   */
  ELSEWHERE;

  companion object {
    fun of(here: Boolean, archived: Boolean): FilePlace = when {
      here && archived -> BOTH
      here -> ONLY_HERE
      archived -> ONLY_COMPUTER
      else -> ELSEWHERE
    }
  }
}

/**
 * Un file come lo vede il riassunto: il peso dalla riga (un file che non c'e' non si puo' misurare,
 * e la riga il peso lo porta sempre), la presenza dal disco, l'archivio dalla riga.
 */
data class FileFact(val sizeBytes: Long, val here: Boolean, val archived: Boolean) {
  val place: FilePlace get() = FilePlace.of(here, archived)
}

/** Un tipo di file (registrazioni, o originali) diviso per posto. */
data class PlaceSummary(
  val both: SizeTotal = EMPTY,
  val onlyHere: SizeTotal = EMPTY,
  val onlyComputer: SizeTotal = EMPTY,
  val elsewhere: SizeTotal = EMPTY,
) {
  operator fun get(place: FilePlace): SizeTotal = when (place) {
    FilePlace.BOTH -> both
    FilePlace.ONLY_HERE -> onlyHere
    FilePlace.ONLY_COMPUTER -> onlyComputer
    FilePlace.ELSEWHERE -> elsewhere
  }

  val total: SizeTotal get() = FilePlace.entries.map(::get).reduce(::plus)

  /** Quello che c'e' qui, in qualunque modo. */
  val here: SizeTotal get() = plus(both, onlyHere)

  /** I posti con dentro qualcosa, nell'ordine in cui la pagina li elenca. */
  val nonEmpty: List<FilePlace> get() = FilePlace.entries.filter { get(it).count > 0 }

  companion object {
    private val EMPTY = SizeTotal(0, 0)

    private fun plus(a: SizeTotal, b: SizeTotal) = SizeTotal(a.count + b.count, a.bytes + b.bytes)

    fun of(facts: Iterable<FileFact>): PlaceSummary {
      val grouped = facts.groupBy { it.place }
      fun total(place: FilePlace) = grouped[place].orEmpty().let { list -> SizeTotal(list.size, list.sumOf { it.sizeBytes }) }
      return PlaceSummary(
        both = total(FilePlace.BOTH),
        onlyHere = total(FilePlace.ONLY_HERE),
        onlyComputer = total(FilePlace.ONLY_COMPUTER),
        elsewhere = total(FilePlace.ELSEWHERE),
      )
    }
  }
}

/**
 * Registrazioni e originali, ognuno diviso per posto. Separati perche' valgono in modo diverso:
 * un PDF si ritrova in un attimo, una lezione registrata no.
 */
data class FileLocations(
  val recordings: PlaceSummary = PlaceSummary(),
  val originals: PlaceSummary = PlaceSummary(),
) {
  /** Quanti file dipendono solo da questo dispositivo: la cifra che dice se c'e' da preoccuparsi. */
  val atRisk: SizeTotal get() = SizeTotal(
    recordings.onlyHere.count + originals.onlyHere.count,
    recordings.onlyHere.bytes + originals.onlyHere.bytes,
  )

  val onlyComputer: SizeTotal get() = SizeTotal(
    recordings.onlyComputer.count + originals.onlyComputer.count,
    recordings.onlyComputer.bytes + originals.onlyComputer.bytes,
  )

  val isEmpty: Boolean get() = recordings.total.count == 0 && originals.total.count == 0

  companion object {
    fun of(recordings: Iterable<FileFact>, originals: Iterable<FileFact>) =
      FileLocations(PlaceSummary.of(recordings), PlaceSummary.of(originals))
  }
}

/**
 * Quando salira' sul computer quello che sta solo qui, detto con le impostazioni di adesso.
 *
 * Non si indovina dall'orologio: si legge dai lavori in coda (il giro periodico e quello
 * «adesso»), che sono l'unica cosa che poi succede davvero. Chi chiama traduce `WorkInfo` in questi
 * numeri, cosi' la scelta si prova in JVM.
 */
sealed interface ArchiveOutlook {
  /** Nessun computer di casa: l'archivio non ha dove andare. */
  data object NoComputer : ArchiveOutlook

  /** Archivio spento: non sale niente finche' non lo si accende. */
  data object Off : ArchiveOutlook

  /** Un giro sta caricando adesso. */
  data object Running : ArchiveOutlook

  /** Un giro e' in coda e aspetta la rete (o il Wi-Fi): parte appena c'e'. */
  data object WaitingNetwork : ArchiveOutlook

  /** L'ultimo giro non ha trovato il computer: si riprova a quest'ora. */
  data class Retry(val at: Long) : ArchiveOutlook

  /** Il prossimo giro periodico. */
  data class Scheduled(val at: Long) : ArchiveOutlook

  /** Acceso, ma nessun giro in coda (succede per un attimo, appena acceso o dopo un aggiornamento). */
  data object Unknown : ArchiveOutlook

  companion object {
    /**
     * @param oneShotNextAt il giro «adesso» in coda, se c'e': quando WorkManager lo farebbe partire.
     *   Nel passato vuol dire che il tempo c'e' e manca solo la rete.
     * @param oneShotAttempts quante volte quel giro e' gia' partito: piu' di zero, e' un nuovo tentativo.
     * @param periodicNextAt il prossimo giro periodico, o null se non c'e'.
     */
    fun of(
      hasComputer: Boolean,
      enabled: Boolean,
      running: Boolean,
      oneShotNextAt: Long?,
      oneShotAttempts: Int,
      periodicNextAt: Long?,
      now: Long,
    ): ArchiveOutlook = when {
      !hasComputer -> NoComputer
      !enabled -> Off
      running -> Running
      oneShotNextAt != null && oneShotAttempts > 0 && oneShotNextAt > now -> Retry(oneShotNextAt)
      oneShotNextAt != null -> WaitingNetwork
      periodicNextAt != null && periodicNextAt > 0 && periodicNextAt != Long.MAX_VALUE ->
        // Un periodico «scaduto» aspetta solo la rete: dirne l'ora passata sarebbe una bugia.
        if (periodicNextAt <= now) WaitingNetwork else Scheduled(periodicNextAt)
      else -> Unknown
    }
  }
}
