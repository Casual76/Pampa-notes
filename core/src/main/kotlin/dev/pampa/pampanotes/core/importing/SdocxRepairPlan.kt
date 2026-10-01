package dev.pampa.pampanotes.core.importing

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Una parte della nota, com'e' adesso. */
data class RepairPart(
  val id: String,
  val sessionId: String,
  val position: Int,
  val originalName: String,
  val durationMs: Long,
  val sizeBytes: Long,
  val sha256: String,
)

/** Una sessione della nota, com'e' adesso. */
data class RepairSession(
  val id: String,
  val date: String,
  val position: Int,
  /** Ha una versione ripulita: spostarle parti la butterebbe ([SdocxRepair.Plan.touchesRefined]). */
  val hasRefined: Boolean = false,
)

/**
 * Una registrazione del `.sdocx`, in ordine di registrazione ([SdocxParser]): la voce ZIP, quando e'
 * cominciata, quanto pesa, e l'impronta scritta in `mediaInfo.dat` (quella del file cosi' com'e'
 * nello ZIP, cioe' della parte estratta da *quella* versione della nota).
 */
data class RepairRecording(
  val entryName: String,
  val startMillis: Long?,
  val sizeBytes: Long,
  val sha256: String?,
)

/**
 * La riparazione delle note importate da Samsung Notes prima del 28/09, quando i nomi delle
 * registrazioni si accoppiavano ai file per posizione (e «Voce 003» di Impressionismo aveva il file
 * di «Voce 004») e ogni registrazione era datata al momento della condivisione.
 *
 * Cosa si puo' sistemare senza ritrascrivere niente, perche' i segmenti stanno con la parte (vedi
 * «Sessioni, parti, segmenti»): il nome di ogni parte, e la sessione in cui sta — quella del giorno
 * in cui e' stata registrata. Qui si decide soltanto; scrivere e' di
 * [dev.pampa.pampanotes.core.repo.SessionRepository.applyRepair]. Puro: si prova in JVM.
 *
 * Due regole tengono tutto al sicuro:
 *  * **una parte che non si riconosce non si tocca**: niente nome, niente spostamento;
 *  * **idempotente**: sul risultato, un secondo piano e' vuoto. Le sessioni nuove hanno un id che
 *    dipende da nota e giorno, cosi' due dispositivi che riparano la stessa nota scrivono le stesse
 *    righe invece di due «21/09».
 */
object SdocxRepair {

  data class Plan(
    /** Parte → nome nuovo (con l'estensione della parte). */
    val renames: Map<String, String> = emptyMap(),
    /** Sessione → data nuova, `yyyy-MM-dd`. */
    val redates: Map<String, String> = emptyMap(),
    /** Le sessioni da creare: id deterministico ([sessionId]) e data. */
    val newSessions: List<Pair<String, String>> = emptyList(),
    /** Solo le sessioni che cambiano parti: sessione → parti, in ordine. */
    val layout: Map<String, List<String>> = emptyMap(),
    /** Sessioni che perdono o ricevono parti e hanno una versione ripulita. */
    val touchesRefined: Set<String> = emptySet(),
  ) {
    val isEmpty: Boolean get() = renames.isEmpty() && redates.isEmpty() && newSessions.isEmpty() && layout.isEmpty()

    /** Sposta parti: rifa' le grezze coinvolte, e le raffinate di quelle sessioni se ne vanno. */
    val movesParts: Boolean get() = layout.isNotEmpty()

    /**
     * Solo quello che non costa niente: nomi, e date delle sessioni che non perdono ne' ricevono
     * parti. E' quello che il giro automatico applica quando spostare butterebbe una raffinata.
     */
    fun withoutMoves(): Plan = copy(
      redates = redates.filterKeys { it !in layout },
      newSessions = emptyList(),
      layout = emptyMap(),
      touchesRefined = emptySet(),
    )
  }

  /** L'id della sessione che la riparazione crea per un giorno: lo stesso su ogni dispositivo. */
  fun sessionId(noteId: String, day: LocalDate): String =
    UUID.nameUUIDFromBytes("$noteId/riparazione/$day".toByteArray()).toString()

  /** L'id della grezza che nasce in una sessione creata dalla riparazione, per la stessa ragione. */
  fun rawId(sessionId: String): String = UUID.nameUUIDFromBytes("$sessionId/grezza/riparazione".toByteArray()).toString()

  /** Quanto puo' cambiare il peso di un file che Samsung Notes ha riscritto condividendo. */
  private const val SIZE_SLACK_BYTES = 64L * 1024

  /**
   * Quale registrazione e' ogni parte: la stessa impronta, oppure — il file riscritto da un'altra
   * condivisione — il peso piu' vicino entro [SIZE_SLACK_BYTES]. Una registrazione va a una parte
   * sola; due parti uguali (la stessa lezione importata due volte) le prende la prima per impronta,
   * l'altra resta com'e'.
   */
  fun match(parts: List<RepairPart>, recordings: List<RepairRecording>): Map<String, Int> {
    val result = mutableMapOf<String, Int>()
    val used = mutableSetOf<Int>()
    parts.forEach { part ->
      val index = recordings.indexOfFirst { it.sha256 != null && it.sha256.equals(part.sha256, ignoreCase = true) }
      if (index >= 0 && index !in used) {
        result[part.id] = index
        used += index
      }
    }
    parts.filter { it.id !in result }.forEach { part ->
      val index = recordings.indices
        .filter { it !in used && recordings[it].sizeBytes >= 0 && kotlin.math.abs(recordings[it].sizeBytes - part.sizeBytes) <= SIZE_SLACK_BYTES }
        .minByOrNull { kotlin.math.abs(recordings[it].sizeBytes - part.sizeBytes) }
      if (index != null) {
        result[part.id] = index
        used += index
      }
    }
    return result
  }

  /**
   * Il piano per una nota.
   *
   * @param voices i nomi e le durate di `note.note`; i nomi si ridanno con [SdocxPairing.assign] sulle
   *   durate misurate delle parti, cosi' una registrazione ha il nome della sua durata.
   */
  fun plan(
    noteId: String,
    sessions: List<RepairSession>,
    parts: List<RepairPart>,
    recordings: List<RepairRecording>,
    voices: List<SdocxVoice>,
    zone: ZoneId,
  ): Plan {
    if (recordings.isEmpty() || parts.isEmpty()) return Plan()
    val matched = match(parts, recordings)
    val byRecording = matched.entries.associate { (partId, index) -> index to parts.first { it.id == partId } }

    // 1. I nomi: la voce la cui durata e' quella della parte.
    val voiceOf = SdocxPairing.assign(voices.map { it.durationMs }, recordings.indices.map { byRecording[it]?.durationMs?.takeIf { d -> d > 0 } })
    val renames = buildMap {
      matched.forEach { (partId, index) ->
        val part = parts.first { it.id == partId }
        val title = voiceOf.getOrNull(index)?.let { voices.getOrNull(it)?.title }?.takeIf { it.isNotBlank() } ?: return@forEach
        val extension = part.originalName.substringAfterLast('.', "").takeIf { it.isNotEmpty() && '.' in part.originalName }
          ?: recordings[index].entryName.substringAfterLast('.', "m4a")
        val name = "$title.$extension"
        if (name != part.originalName) put(partId, name)
      }
    }

    // 2. Il giorno di ogni parte riconosciuta, dal suo inizio.
    val dayOf: Map<String, LocalDate> = matched.mapNotNull { (partId, index) ->
      recordings[index].startMillis?.let { partId to Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    }.toMap()
    val partsBySession = parts.groupBy { it.sessionId }.mapValues { (_, list) -> list.sortedBy { it.position } }

    // 3. Il giorno di ogni sessione: il suo, se ha parti non riconosciute o parti di quel giorno;
    //    altrimenti quello che dicono le sue parti (il piu' frequente, e a parita' il primo).
    val home = mutableMapOf<String, String>()
    sessions.forEach { session ->
      val own = partsBySession[session.id].orEmpty()
      val days = own.mapNotNull { dayOf[it.id] }
      val keepsOwn = days.isEmpty() || own.any { it.id !in dayOf } || days.any { it.toString() == session.date }
      home[session.id] = if (keepsOwn) {
        session.date
      } else {
        days.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<LocalDate, Int>> { it.value }.thenBy { it.key }).first().key.toString()
      }
    }
    val redates = sessions.filter { home[it.id] != it.date }.associate { it.id to home.getValue(it.id) }

    // 4. Dove va ogni parte: resta se il giorno e' quello della sua sessione, altrimenti nella
    //    sessione di quel giorno — quella che la nota ha gia' (l'ultima, come fa l'aggiornamento) o
    //    una nuova.
    val sessionForDay = sessions.groupBy { home.getValue(it.id) }.mapValues { (_, list) -> list.maxBy { it.position }.id }.toMutableMap()
    val newSessions = mutableListOf<Pair<String, String>>()
    val destination = mutableMapOf<String, String>()
    parts.forEach { part ->
      val day = dayOf[part.id] ?: return@forEach
      if (home[part.sessionId] == day.toString()) return@forEach
      val target = sessionForDay.getOrPut(day.toString()) {
        sessionId(noteId, day).also { newSessions += it to day.toString() }
      }
      destination[part.id] = target
    }

    // 5. Le sessioni che cambiano parti, con le parti in ordine di registrazione (quelle senza ora in
    //    fondo, nell'ordine che avevano).
    val changed = (destination.keys.mapNotNull { id -> parts.firstOrNull { it.id == id }?.sessionId } + destination.values).toSet()
    val startOf = matched.mapNotNull { (partId, index) -> recordings[index].startMillis?.let { partId to it } }.toMap()
    val layout = changed.associateWith { sessionId ->
      val staying = partsBySession[sessionId].orEmpty().filter { it.id !in destination }
      val arriving = parts.filter { destination[it.id] == sessionId }
      (staying + arriving)
        .withIndex()
        .sortedWith(compareBy<IndexedValue<RepairPart>, Long?>(nullsLast()) { startOf[it.value.id] }.thenBy { it.index })
        .map { it.value.id }
    }
    val refined = sessions.filter { it.hasRefined && it.id in changed }.map { it.id }.toSet()
    return Plan(renames, redates, newSessions, layout, refined)
  }
}
