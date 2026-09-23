package dev.pampa.pampanotes.core.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * In che ordine si applica una pagina, e chi e' il padre di chi. Puro: si prova in JVM.
 *
 * **Prima tutti gli upsert, da padre a figlio; poi tutte le cancellazioni, da figlio a padre.**
 * Con un ordine solo per tabella una cartella cancellata altrove passava prima della nota che,
 * nella stessa pagina, ne usciva: la cascata si portava via la nota, e l'upsert dopo non trovava
 * piu' la cartella di prima ne' — se la nuova arrivava dopo — quella nuova. Spostare e poi
 * cancellare, invece, e' l'ordine in cui le cose sono successe dall'altra parte.
 */
object SyncPlan {

  /** Una riga di una tabella: il padre di qualcuno. */
  data class RowRef(val tbl: String, val id: String)

  /**
   * Una riga sola per elemento, l'ultima. Gli orfani di una pagina si ripresentano con quella dopo,
   * e quella dopo puo' portare una versione piu' nuova della stessa riga: applicarle tutte e due
   * farebbe passare la vecchia per ultima, se fosse di una tabella che viene dopo.
   */
  fun latestPerRow(changes: List<WireChange>): List<WireChange> =
    changes.groupBy { it.tbl to it.id }.values.map { versions -> versions.maxBy { it.seq } }

  /** Gli upsert, padre prima di figlio; a parita' di tabella, nell'ordine in cui sono stati scritti. */
  fun upserts(changes: List<WireChange>): List<WireChange> =
    changes.filter { !it.isDelete }.sortedWith(compareBy({ SyncMerge.orderOf(it.tbl) }, { it.seq }))

  /** Le cancellazioni, figlio prima di padre: quando tocca alla nota, le sue sessioni sono gia' decise. */
  fun deletes(changes: List<WireChange>): List<WireChange> =
    changes.filter { it.isDelete }.sortedWith(compareByDescending<WireChange> { SyncMerge.orderOf(it.tbl) }.thenBy { it.seq })

  /**
   * Il padre che la chiave esterna chiede, letto dal payload. Null se la riga non ne ha uno (una
   * cartella in cima, un preset) o se il payload non si capisce: in quel caso decide il database.
   *
   * `transcripts.parentId` e `sources.derivedFromId` non ci sono apposta: non sono chiavi esterne.
   */
  fun parentOf(tbl: String, payload: JsonElement?): RowRef? {
    val obj = payload as? JsonObject ?: return null
    return when (tbl) {
      "folders" -> obj.string("parentId")?.let { RowRef("folders", it) }
      "notes" -> ((obj["note"] as? JsonObject) ?: obj).string("folderId")?.let { RowRef("folders", it) }
      "sessions", "sources" -> obj.string("noteId")?.let { RowRef("notes", it) }
      "audio_parts", "transcripts" -> obj.string("sessionId")?.let { RowRef("sessions", it) }
      else -> null
    }
  }

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}

/**
 * Le righe rimaste senza padre in fondo a un pull. Puro: si prova in JVM.
 *
 * Il punto da cui ripartire **non le scavalca**: il giro dopo le riscarica, e se nel frattempo il
 * padre e' arrivato entrano. Ma una riga davvero senza padre — sul server non c'e' piu', o non e'
 * mai salito — terrebbe fermo `lastPullSeq` per sempre, e con lui ogni pagina dopo di lei riscaricata
 * a ogni giro. Per questo ognuna ha un contatore: dopo [MAX_RUNS] giri si lascia andare, e lo si
 * scrive nel log.
 */
object OrphanLedger {
  const val MAX_RUNS = 3

  data class Verdict(
    /** I contatori da tenere: solo le righe ancora in attesa. */
    val attempts: Map<String, Int>,
    /** Quelle lasciate andare in questo giro. */
    val abandoned: List<WireChange>,
    /** Il `lastPullSeq` da scrivere. */
    val resumeFrom: Long,
  )

  fun key(change: WireChange): String = "${change.tbl}/${change.id}"

  /**
   * @param count se questo giro conta. Il secondo pull dello stesso giro di sync (quello dopo un push
   *  rifiutato) non deve consumare un tentativo: tre giri sono tre occasioni vere per il padre.
   */
  fun settle(leftovers: List<WireChange>, previous: Map<String, Int>, pageSeq: Long, count: Boolean = true): Verdict {
    val attempts = mutableMapOf<String, Int>()
    val kept = mutableListOf<WireChange>()
    val abandoned = mutableListOf<WireChange>()
    for (change in leftovers) {
      val runs = (previous[key(change)] ?: 0) + if (count) 1 else 0
      if (runs >= MAX_RUNS) {
        abandoned += change
      } else {
        attempts[key(change)] = runs
        kept += change
      }
    }
    return Verdict(attempts, abandoned, resumePoint(kept, pageSeq))
  }

  /** Il punto sicuro: appena prima dell'orfano piu' vecchio, e mai oltre la pagina. */
  fun resumePoint(parked: List<WireChange>, pageSeq: Long): Long =
    parked.minOfOrNull { it.seq - 1 }?.coerceAtMost(pageSeq)?.coerceAtLeast(0) ?: pageSeq
}
