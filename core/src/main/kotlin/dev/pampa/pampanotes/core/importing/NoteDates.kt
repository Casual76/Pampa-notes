package dev.pampa.pampanotes.core.importing

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Le date vere di una nota importata: quando e' nata e quando e' cambiata l'ultima volta **dove
 * e' stata scritta**, non quando e' arrivata qui.
 *
 * Prima una nota di Samsung Notes condivisa oggi era «di oggi» anche se l'ultima parola l'aveva
 * avuta una settimana fa, e la home, ordinata per `updatedAt`, metteva in cima l'ultima importata
 * invece dell'ultima lezione. Le fonti sono due: le date del `.sdocx` ([SdocxDates]) e i momenti in
 * cui sono state fatte le registrazioni ([RecordingDate.momentOf]). Una modifica fatta nell'app
 * resta «adesso»: e' una modifica vera, e questa regola non la tocca.
 *
 * Puro: si prova in JVM, e due dispositivi che fanno lo stesso conto arrivano agli stessi numeri.
 */
object NoteDates {

  /**
   * Quanto dopo l'import una nota puo' essere stata toccata senza che qualcuno l'abbia cambiata:
   * l'import stesso scrive in piu' passi (testo, registrazioni, pagine a mano), e l'ultimo cade
   * qualche secondo dopo `importedAt`.
   */
  const val UNTOUCHED_SLACK_MS: Long = 10 * 60_000L

  data class Choice(val createdAt: Long, val updatedAt: Long)

  /**
   * Le date da dare a una nota.
   *
   * - la nascita e' quella del `.sdocx`, o la prima registrazione;
   * - l'ultima modifica e' la piu' recente fra la modifica del `.sdocx` e l'ultima registrazione:
   *   una lezione registrata ieri dentro una nota scritta a marzo e' una nota di ieri;
   * - niente di tutto questo: valgono [fallbackCreated] e [fallbackUpdated], cioe' il momento
   *   dell'import.
   *
   * Nessuna data oltre [now] (un nome di file letto come mezzogiorno di oggi, alle nove di mattina),
   * e la nascita mai dopo l'ultima modifica.
   */
  fun choose(
    created: Long?,
    modified: Long?,
    recordings: Collection<Long>,
    fallbackCreated: Long,
    fallbackUpdated: Long,
    now: Long = System.currentTimeMillis(),
  ): Choice {
    val updated = listOfNotNull(modified, recordings.maxOrNull()).maxOrNull()?.coerceAtMost(now) ?: fallbackUpdated
    val born = (created ?: recordings.minOrNull())?.coerceAtMost(now) ?: fallbackCreated
    return Choice(createdAt = minOf(born, updated), updatedAt = updated)
  }

  /**
   * La nota non e' stata cambiata da quando e' arrivata: si puo' ridatare senza cancellare una
   * modifica di qualcuno.
   *
   * Vale se l'ultima modifica cade entro [UNTOUCHED_SLACK_MS] dall'import, oppure se coincide al
   * millisecondo con la nascita di una sua trascrizione: prima di questo giro salvare una
   * trascrizione toccava la nota con lo stesso istante, e una lezione trascritta un'ora dopo
   * l'import non e' una nota che qualcuno ha riscritto.
   */
  fun untouchedSinceImport(noteUpdatedAt: Long, importedAt: Long, transcriptTimes: Collection<Long> = emptyList()): Boolean =
    noteUpdatedAt <= importedAt + UNTOUCHED_SLACK_MS || noteUpdatedAt in transcriptTimes

  /**
   * La sessione ha ancora la data che le ha dato l'import: il suo giorno e' quello in cui la sua
   * prima parte e' entrata nel database. Una sessione datata a mano — o gia' datata dalle
   * registrazioni — ha un giorno diverso, e non si tocca.
   */
  fun sessionDatedAtImport(sessionDate: String, firstPartCreatedAt: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    if (firstPartCreatedAt <= 0) return false
    val importDay = Instant.ofEpochMilli(firstPartCreatedAt).atZone(zone).toLocalDate()
    return sessionDate == importDay.toString()
  }

  /** Il giorno nuovo di una sessione, o null se non cambia niente. */
  fun redate(sessionDate: String, recorded: LocalDate?): String? {
    val next = recorded?.toString() ?: return null
    return next.takeIf { it != sessionDate }
  }
}
