package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.AudioPartEntity

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
    // Preserve the root old-snapshot contract, but never enrich one known observation with
    // fields from a different observation. Known tuples are ranked intact below.
    if (next == RecordingOrigin()) return PampaBundleMetadataMerge.audio(local, incoming)
    if (next != RecordingOrigin() && next.evidenceQuality() >= old.evidenceQuality()) return incoming
    return incoming.copy(recordedAtEpochMs = old.epochMs, recordedAtLocal = old.local,
      recordedAtZoneId = old.zoneId, recordedAtSource = old.source, recordedAtPrecision = old.precision)
  }

  private fun origin(part: AudioPartEntity) = RecordingOrigin(part.recordedAtEpochMs,
    part.recordedAtLocal, part.recordedAtZoneId, part.recordedAtSource, part.recordedAtPrecision)

}
