package dev.pampa.pampanotes.core.importing

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Source evidence for the start of ONE audio file. Never a note's sorting/import timestamp. */
data class RecordingOrigin(
  val epochMs: Long? = null,
  val local: String? = null,
  val zoneId: String? = null,
  val source: String? = null,
  val precision: String? = null,
) {
  /** Independently usable evidence only; never combine contradictory source representations.
   * Epoch storage has millisecond precision, so finer source fractions compare at that boundary. */
  fun evidenceQuality(): Int {
    if (source.isNullOrBlank()) return 0
    if (precision == "day") return if (epochMs == null && zoneId == null &&
      runCatching { LocalDate.parse(local) }.isSuccess) 10 else 0
    val rank = when (precision) { "minute" -> 20; "second" -> 30; "millisecond" -> 40; else -> return 0 }
    if (source == "file_modified") return 0
    val wall = local?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() ?: return 0 }
    if (wall == null && epochMs == null) return 0
    val zone = zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() ?: return 0 }
    if (wall != null && zone != null && epochMs != null &&
      runCatching { Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MILLIS) }
        .getOrNull() != wall.truncatedTo(ChronoUnit.MILLIS)) return 0
    return rank + (if (epochMs != null) 4 else 0) + (if (wall != null) 2 else 0) + (if (zone != null) 1 else 0)
  }

  companion object {
    private val dateTime = Regex(
      """^(\d{4})[-: ]?(\d{2})[-: ]?(\d{2})[T ](\d{2}):?(\d{2})(?::?(\d{2})(?:[.,](\d{1,9}))?)?\s*(Z|[+-]\d{2}:?\d{2})?(?:\[([^]]+)])?$""",
      RegexOption.IGNORE_CASE,
    )
    private val day = Regex("""^(\d{4})[-: ]?(\d{2})[-: ]?(\d{2})$""")
    // Only explicit recorder-style date + time tokens. WhatsApp's WA0001 is not a time.
    private val namedTime = listOf(
      Regex("""(?<!\d)((?:20)\d{2})[-_. ]?(\d{2})[-_. ]?(\d{2})[T_ -](\d{2})[:. -]?(\d{2})(?:[:. -]?(\d{2}))?(?!\d)"""),
      Regex("""(?<!\d)(\d{2})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})?(?!\d)"""),
    )

    fun fromMetadata(raw: String?): RecordingOrigin? {
      val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
      day.matchEntire(value)?.let { m ->
        val date = runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() ?: return null
        return RecordingOrigin(local = date.toString(), source = "media_metadata", precision = "day")
      }
      val m = dateTime.matchEntire(value) ?: return null
      val g = m.groupValues
      val wall = runCatching {
        LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(),
          g[6].toIntOrNull() ?: 0, g[7].padEnd(9, '0').toIntOrNull() ?: 0)
      }.getOrNull() ?: return null
      val offset = g[8].takeIf { it.isNotEmpty() }?.let {
        runCatching { ZoneOffset.of(it.uppercase()) }.getOrNull() ?: return null
      }
      val region = g[9].takeIf { it.isNotEmpty() }?.let {
        runCatching { ZoneId.of(it) }.getOrNull() ?: return null
      }
      val validOffsets = region?.rules?.getValidOffsets(wall)
      if (offset != null && validOffsets != null && offset !in validOffsets) return null
      val certainOffset = offset ?: validOffsets?.singleOrNull()
      val precision = when { g[7].isNotEmpty() -> "millisecond"; g[6].isNotEmpty() -> "second"; else -> "minute" }
      return RecordingOrigin(
        epochMs = certainOffset?.let { wall.toInstant(it).toEpochMilli() },
        local = wall.toString(), zoneId = region?.id ?: offset?.id,
        source = "media_metadata", precision = precision,
      )
    }

    fun fromFileName(name: String): RecordingOrigin? {
      // Keep the final extension out without removing dotted date/time tokens in extensionless names.
      val extension = name.substringAfterLast('.', "")
      val stem = if (extension.length in 2..5 && extension.all(Char::isLetterOrDigit) && extension.any(Char::isLetter))
        name.dropLast(extension.length + 1) else name
      namedTime.forEachIndexed { index, regex ->
        regex.findAll(stem).forEach { m ->
          val g = m.groupValues
          val wall = runCatching {
            LocalDateTime.of(g[1].toInt() + if (index == 1) 2000 else 0, g[2].toInt(), g[3].toInt(),
              g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0)
          }.getOrNull()
          if (wall != null) return RecordingOrigin(local = wall.toString(), source = "filename",
            precision = if (g[6].isEmpty()) "minute" else "second")
        }
      }
      return RecordingDate.fromFileName(name)?.let { RecordingOrigin(local = it.toString(), source = "filename", precision = "day") }
    }

    /** The legacy resolved day still controls grouping/sorting. It never supplies an exact start. */
    fun resolve(recorded: RecordedOn, metadataDate: String?, fileName: String): RecordingOrigin? = when (recorded.source) {
      RecordingDateSource.METADATA -> fromMetadata(metadataDate)
      RecordingDateSource.FILE_NAME -> fromFileName(fileName)
      RecordingDateSource.FILE_MODIFIED -> RecordingOrigin(local = recorded.isoDate, source = "file_modified", precision = "day")
      RecordingDateSource.TODAY -> null
    }

    /** mediaInfo.dat can be a rewrite time: only the embedded epoch-seconds token proves a start. */
    fun fromSamsungEntry(entryName: String, now: Long = System.currentTimeMillis()): RecordingOrigin? =
      SdocxPairing.startFromEntryName(entryName, now)?.let {
        // Unix seconds establish a UTC instant, not the recorder's local wall clock or zone.
        RecordingOrigin(epochMs = it, source = "samsung_recording", precision = "second")
      }
  }
}
