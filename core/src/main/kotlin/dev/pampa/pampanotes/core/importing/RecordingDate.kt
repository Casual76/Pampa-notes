package dev.pampa.pampanotes.core.importing

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Da dove si e' capito il giorno di una registrazione, dalla fonte piu' affidabile alla meno. */
enum class RecordingDateSource {
  /** La data scritta dal registratore dentro il file (`MediaMetadataRetriever.METADATA_KEY_DATE`). */
  METADATA,

  /** Una data nel nome del file: «Voce 001_250922_1015», «AUD-20250922-WA0001». */
  FILE_NAME,

  /** L'ultima modifica del file secondo chi lo condivide: la copia nostra e' di adesso, e non dice niente. */
  FILE_MODIFIED,

  /** Non si e' capito niente: il giorno dell'import, come prima. */
  TODAY,
}

/** Il giorno di una registrazione, con la sua provenienza: la schermata dice l'una e l'altra. */
data class RecordedOn(val date: LocalDate, val source: RecordingDateSource) {
  val isoDate: String get() = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
}

/**
 * In che giorno e' stata fatta una registrazione.
 *
 * Prima una lezione importata il giorno dopo finiva datata il giorno dopo, e tre registrazioni di
 * una settimana condivise la domenica erano tre lezioni della domenica. Il giorno vero di solito
 * c'e', solo in posti diversi a seconda di chi ha registrato: nei metadati del contenitore (il
 * registratore di Samsung, la maggior parte delle app), nel nome del file (WhatsApp, i registratori
 * che numerano coi giorni), o almeno nella data di modifica del file sul dispositivo che lo manda.
 * Si provano in quest'ordine, dal piu' affidabile.
 *
 * Una data impossibile — nel futuro, o prima del 2000 — si scarta e si passa alla fonte dopo: un
 * m4a senza data scrive spesso il 1904 (lo zero di MP4) o il 1970 (lo zero di Unix), e un orologio
 * sbagliato del telefono scrive l'anno prossimo.
 *
 * Puro, senza Android: si prova in JVM.
 */
object RecordingDate {

  private val EARLIEST: LocalDate = LocalDate.of(2000, 1, 1)

  /**
   * Il giorno e la sua provenienza.
   *
   * @param metadataDate il valore grezzo di `METADATA_KEY_DATE`, se c'e'.
   * @param fileName il nome del file come l'ha dato chi lo condivide.
   * @param lastModifiedMillis l'ultima modifica secondo il provider, letta all'ispezione.
   * @param zone il fuso del telefono: `20250922T231500.000Z` in Italia e' gia' il 23.
   */
  fun resolve(
    metadataDate: String?,
    fileName: String,
    lastModifiedMillis: Long?,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
  ): RecordedOn {
    parseMetadata(metadataDate, zone)?.takeIf { plausible(it, today) }?.let { return RecordedOn(it, RecordingDateSource.METADATA) }
    fromFileName(fileName)?.takeIf { plausible(it, today) }?.let { return RecordedOn(it, RecordingDateSource.FILE_NAME) }
    lastModifiedMillis?.takeIf { it > 0 }
      ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
      ?.takeIf { plausible(it, today) }
      ?.let { return RecordedOn(it, RecordingDateSource.FILE_MODIFIED) }
    return RecordedOn(today, RecordingDateSource.TODAY)
  }

  /** Dal 2000 a oggi compreso. */
  fun plausible(date: LocalDate, today: LocalDate): Boolean = !date.isBefore(EARLIEST) && !date.isAfter(today)

  // --- Metadati ---------------------------------------------------------------------------------

  /** `20250922T101500.000Z`, `20250922T101500`, `2025-09-22T10:15:00Z`, `2025:09:22 10:15:00`. */
  private val METADATA_DATE_TIME = Regex(
    """^\s*(\d{4})[-: ]?(\d{2})[-: ]?(\d{2})[T ](\d{2}):?(\d{2}):?(\d{2})(?:[.,]\d+)?\s*(Z|[+-]\d{2}:?\d{2})?\s*$""",
    RegexOption.IGNORE_CASE,
  )

  /** Solo il giorno: `2025 09 22`, `2025-09-22`, `20250922`. */
  private val METADATA_DATE_ONLY = Regex("""^\s*(\d{4})[-: ]?(\d{2})[-: ]?(\d{2})\s*$""")

  /**
   * La data dei metadati, nel giorno del telefono.
   *
   * `METADATA_KEY_YEAR` da solo non si usa: un anno non dice di che lezione si tratta, e trattarlo
   * come il primo gennaio sarebbe peggio del giorno dell'import.
   */
  fun parseMetadata(raw: String?, zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    METADATA_DATE_TIME.matchEntire(value)?.let { match ->
      val (y, mo, d, h, mi, s) = match.destructured
      val local = runCatching { LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()) }.getOrNull() ?: return null
      val offset = match.groupValues[7]
      // Con un fuso dichiarato (di regola la Z del contenitore MP4) si converte nel giorno del
      // telefono; senza, l'ora e' gia' quella locale di chi ha registrato.
      if (offset.isEmpty()) return local.toLocalDate()
      val zoneOffset = if (offset.equals("Z", ignoreCase = true)) ZoneOffset.UTC else runCatching { ZoneOffset.of(offset.normalizeOffset()) }.getOrNull() ?: return local.toLocalDate()
      return local.atOffset(zoneOffset).atZoneSameInstant(zone).toLocalDate()
    }
    METADATA_DATE_ONLY.matchEntire(value)?.let { match ->
      val (y, mo, d) = match.destructured
      return date(y.toInt(), mo.toInt(), d.toInt())
    }
    return null
  }

  private fun String.normalizeOffset(): String = if (length == 5 && this[3] != ':') substring(0, 3) + ":" + substring(3) else this

  // --- Nome del file ----------------------------------------------------------------------------

  /**
   * I modi in cui un nome di file porta una data, dal piu' esplicito al piu' ambiguo.
   *
   * Le cifre si guardano sempre intere (`(?<!\d)`, `(?!\d)`): «Voce 001» non deve diventare un
   * giorno, e un numero di telefono in mezzo al nome nemmeno.
   */
  private val FILE_NAME_PATTERNS: List<Pair<Regex, (MatchResult) -> LocalDate?>> = listOf(
    // 2025-09-22 10.15.00, 2025_09_22, 2025.09.22
    Regex("""(?<!\d)(\d{4})[-_. ](\d{2})[-_. ](\d{2})(?!\d)""") to { m -> date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) },
    // 20250922_101500, Registrazione_20250922_101500, AUD-20250922-WA0001, PTT-20250922-WA0001,
    // 20250922101500
    Regex("""(?<!\d)((?:19|20)\d{2})(\d{2})(\d{2})(?:\d{6})?(?!\d)""") to { m -> date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) },
    // 22-09-2025, 22.09.2025: giorno prima del mese, come si scrive in Italia.
    Regex("""(?<!\d)(\d{2})[-_.](\d{2})[-_.](\d{4})(?!\d)""") to { m -> date(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) },
    // Il registratore vocale di Samsung: «Voce 001_250922_1015», anno su due cifre davanti. Solo
    // con l'ora dietro: sei cifre da sole sono troppo spesso qualcos'altro.
    Regex("""(?<!\d)(\d{2})(\d{2})(\d{2})[_-](\d{4}|\d{6})(?!\d)""") to { m -> date(2000 + m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) },
  )

  /** La prima data valida nel nome, o null. L'estensione non conta. */
  fun fromFileName(name: String): LocalDate? {
    val stem = name.substringBeforeLast('.')
    FILE_NAME_PATTERNS.forEach { (regex, build) ->
      regex.findAll(stem).forEach { match -> build(match)?.let { return it } }
    }
    return null
  }

  private fun date(year: Int, month: Int, day: Int): LocalDate? = runCatching { LocalDate.of(year, month, day) }.getOrNull()

  /**
   * Il giorno delle sessioni di un import: una per giorno di registrazione, in ordine.
   *
   * Registrazioni dello stesso giorno stanno nella stessa sessione — la lezione interrotta e ripresa;
   * registrazioni di giorni diversi sono lezioni diverse, come fa l'aggiornamento di una nota di
   * Samsung Notes. Chi non ha una data va col giorno piu' vecchio che c'e' (il primo gruppo), non
   * con oggi: una parte senza data in mezzo a una lezione di ieri e' quasi sempre di ieri.
   */
  fun <T> groupByDay(items: List<T>, dateOf: (T) -> LocalDate?, today: LocalDate = LocalDate.now()): List<Pair<LocalDate, List<T>>> {
    if (items.isEmpty()) return emptyList()
    val earliest = items.mapNotNull(dateOf).minOrNull() ?: today
    return items.groupBy { dateOf(it) ?: earliest }.toSortedMap().map { (day, group) -> day to group }
  }
}
