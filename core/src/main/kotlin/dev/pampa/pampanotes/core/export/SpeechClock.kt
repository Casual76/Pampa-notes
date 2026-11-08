package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.importing.RecordingOrigin
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.transcription.WordTimings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formats source evidence without upgrading a day, minute or unknown zone into exact seconds. */
object SpeechClock {
  /** Real first-word timing can refine a segment boundary; interpolated or missing words cannot. */
  fun partOffset(segment: SegmentEntity): Long {
    if (!segment.wordsEstimated) {
      val word = WordTimings.decode(segment.wordsJson, segment.partStartMs).firstOrNull()
      if (word != null) {
        val text = segment.text.trimStart()
        val token = word.text.trim()
        val boundary = text.getOrNull(token.length)
        if (token.isNotEmpty() && text.startsWith(token) && (boundary == null || !boundary.isLetterOrDigit()) &&
          word.startMs >= segment.partStartMs && word.endMs > word.startMs && word.endMs <= segment.partEndMs) return word.startMs
      }
    }
    return segment.partStartMs
  }

  fun label(origin: RecordingOrigin?, partOffsetMs: Long?, labels: ExportLabels = ExportLabels()): String {
    if (origin == null) return labels.clockUnknown
    val date = origin.local?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val precision = origin.precision
    if (origin.source == "file_modified" || precision == "day" || precision !in setOf("minute", "second", "millisecond")) {
      return date?.let { "$it · ${labels.clockTimeUnknown}" } ?: labels.clockUnknown
    }
    if (partOffsetMs == null || partOffsetMs < 0) return labels.clockUnknown
    val zone = origin.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    val pattern = when (precision) {
      "minute" -> "uuuu-MM-dd HH:mm"
      "millisecond" -> "uuuu-MM-dd HH:mm:ss.SSS"
      else -> "uuuu-MM-dd HH:mm:ss"
    }
    val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
    val clock = runCatching {
      if (origin.epochMs != null) {
        val instant = Instant.ofEpochMilli(Math.addExact(origin.epochMs, partOffsetMs))
        val value = instant.atZone(zone ?: ZoneOffset.UTC)
        val offset = if (value.offset == ZoneOffset.UTC) "UTC" else "UTC${value.offset.id}"
        val region = zone?.id?.takeUnless { zone is ZoneOffset || it == "UTC" || it == "Z" }?.let { " ($it)" }.orEmpty()
        value.format(formatter) + " " + offset + region + if (zone == null) " · ${labels.clockZoneUnknown}" else ""
      } else {
        val wall = LocalDateTime.parse(origin.local ?: return@runCatching null).plusNanos(Math.multiplyExact(partOffsetMs, 1_000_000))
        // An unzoned wall clock cannot establish a real instant or a DST transition.
        wall.format(formatter) + " · " + if (zone == null) labels.clockZoneUnknown
          else "${labels.clockInstantUnknown} (${zone.id})"
      }
    }.getOrNull() ?: return date?.let { "$it · ${labels.clockTimeUnknown}" } ?: labels.clockUnknown
    return if (precision == "minute") "≈ $clock · ${labels.clockMinutePrecision}" else clock
  }
}
