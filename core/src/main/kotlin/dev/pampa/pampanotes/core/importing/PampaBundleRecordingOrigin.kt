package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.AudioPartEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Retains one complete evidence tuple, never a field-by-field mixture of two provenances.
 * Call AFTER identity reconciliation: only the same final part ID and a nonblank matching audio
 * hash can inherit local evidence. More precise/coherent evidence wins; incoming wins ties, so
 * an equally precise correction can replace the old tuple without borrowing its zone or instant.
 * Legacy unknown/partial origins cannot downgrade a known origin for the same recording. */
internal object PampaBundleRecordingOrigin {
  fun retain(local: AudioPartEntity?, incoming: AudioPartEntity): AudioPartEntity {
    if (local == null || local.id != incoming.id || local.sha256.isBlank() || incoming.sha256.isBlank() ||
      !local.sha256.equals(incoming.sha256, ignoreCase = true)) return incoming
    val old = origin(local)
    val next = origin(incoming)
    if (next != RecordingOrigin() && quality(next) >= quality(old)) return incoming
    return incoming.copy(recordedAtEpochMs = old.epochMs, recordedAtLocal = old.local,
      recordedAtZoneId = old.zoneId, recordedAtSource = old.source, recordedAtPrecision = old.precision)
  }

  private fun origin(part: AudioPartEntity) = RecordingOrigin(part.recordedAtEpochMs,
    part.recordedAtLocal, part.recordedAtZoneId, part.recordedAtSource, part.recordedAtPrecision)

  /** Ranks only independently usable tuples. No timezone, precision, date or timestamp is inferred. */
  private fun quality(origin: RecordingOrigin): Int {
    if (origin.source.isNullOrBlank()) return 0
    if (origin.precision == "day") {
      return if (origin.epochMs == null && origin.zoneId == null &&
        runCatching { LocalDate.parse(origin.local) }.isSuccess) 10 else 0
    }
    val precision = when (origin.precision) {
      "minute" -> 20
      "second" -> 30
      "millisecond" -> 40
      else -> return 0
    }
    if (origin.source == "file_modified") return 0
    val wall = origin.local?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() ?: return 0 }
    if (wall == null && origin.epochMs == null) return 0
    val zone = origin.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() ?: return 0 }
    if (wall != null && zone != null && origin.epochMs != null &&
      runCatching { Instant.ofEpochMilli(origin.epochMs).atZone(zone).toLocalDateTime() }.getOrNull() != wall) return 0
    return precision + (if (origin.epochMs != null) 4 else 0) +
      (if (wall != null) 2 else 0) + (if (zone != null) 1 else 0)
  }
}
