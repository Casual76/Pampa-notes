package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.files.Hashing

/**
 * Come si divide un push in lotti, e come si chiama ciascuno. Puro: si prova in JVM.
 *
 * **Lotti limitati per numero e per peso.** Cento righe di note sono pochi kB, cento trascrizioni
 * coi loro segmenti possono essere decine di MB: un Worker legge al massimo 100 MB per richiesta e
 * D1 ha i suoi limiti per riga. Una riga che da sola supera il tetto va in un lotto suo, e se il
 * server la rifiuta (`too_large`) resta li' senza fermare le altre.
 *
 * **Il nome del lotto e' deterministico.** Se la risposta si perde dopo che il server ha scritto, il
 * giro dopo rimanda lo stesso lotto: con lo stesso nome il server risponde quello che aveva
 * risposto, invece di vedere righe gia' sue come «non aggiornate». Il nome e' l'impronta di quello
 * che il lotto dice — riga, operazione, impronta, base — piu' la revisione locale di ogni voce
 * (`sync_outbox.id`, che non si ripete mai): senza la revisione, una nota riportata al testo di
 * una settimana fa sopra la stessa base avrebbe il nome di allora, e il server risponderebbe «fatto»
 * senza scrivere niente.
 */
object PushPlanner {
  const val MAX_COUNT = 100
  const val MAX_BYTES = 3 * 1024 * 1024

  /** Una voce da mandare: la revisione dell'outbox e il cambiamento. */
  data class Item(val revision: Long, val change: WireChange)

  /** Quanto pesa una riga sul filo, in byte di JSON. */
  fun sizeOf(change: WireChange): Int =
    SyncCodec.json.encodeToString(WireChange.serializer(), change).toByteArray(Charsets.UTF_8).size

  /**
   * I lotti, nell'ordine dato. Un lotto si chiude quando la prossima riga lo porterebbe oltre
   * [maxCount] o [maxBytes]; una riga piu' grande di [maxBytes] sta da sola.
   */
  fun <T> chunk(items: List<T>, sizeOf: (T) -> Int, maxCount: Int = MAX_COUNT, maxBytes: Int = MAX_BYTES): List<List<T>> {
    val batches = mutableListOf<List<T>>()
    var current = mutableListOf<T>()
    var bytes = 0L
    for (item in items) {
      val size = sizeOf(item)
      if (current.isNotEmpty() && (current.size >= maxCount || bytes + size > maxBytes)) {
        batches += current
        current = mutableListOf()
        bytes = 0
      }
      current += item
      bytes += size
    }
    if (current.isNotEmpty()) batches += current
    return batches
  }

  fun batchId(deviceId: String, items: List<Item>): String {
    val lines = items
      .map { (revision, c) -> listOf(c.tbl, c.id, c.op, c.hash, c.baseHash, revision.toString()).joinToString("\t") }
      .sorted()
    return Hashing.sha256((listOf(deviceId) + lines).joinToString("\n"))
  }
}
