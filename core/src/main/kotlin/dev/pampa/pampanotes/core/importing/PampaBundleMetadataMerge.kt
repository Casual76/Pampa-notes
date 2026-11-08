package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity

/** Unknown fields in older portable snapshots cannot erase already acquired public evidence. */
internal object PampaBundleMetadataMerge {
  fun transcript(local: TranscriptEntity?, incoming: TranscriptEntity): TranscriptEntity {
    val previous = local?.cloudSourceJson
    val supplied = incoming.cloudSourceJson
    require(previous == null || supplied == null || previous == supplied) { "Immutable cloud source conflict" }
    return incoming.copy(cloudSourceJson = supplied ?: previous)
  }

  fun audio(local: AudioPartEntity?, incoming: AudioPartEntity): AudioPartEntity {
    if (local == null || unknown(local)) return incoming
    if (unknown(incoming)) return copyOrigin(incoming, local)
    val sameInstant = local.recordedAtEpochMs != null && local.recordedAtEpochMs == incoming.recordedAtEpochMs
    require(local.recordedAtEpochMs == null || incoming.recordedAtEpochMs == null || sameInstant) { "Recording origin conflict" }
    val localText = local.recordedAtLocal
    val incomingText = incoming.recordedAtLocal
    val compatibleText = localText == null || incomingText == null || localText == incomingText ||
      extends(localText, incomingText) || extends(incomingText, localText)
    require(sameInstant || compatibleText) { "Recording origin conflict" }
    require(sameInstant || local.recordedAtZoneId == null || incoming.recordedAtZoneId == null ||
      local.recordedAtZoneId == incoming.recordedAtZoneId) { "Recording zone conflict" }
    // Prefer the stronger complete observation. A date-only snapshot cannot downgrade an instant.
    val primary = if (strength(local) > strength(incoming)) local else incoming
    val secondary = if (primary === local) incoming else local
    // A missing local text does not make it transferable to another zone. Keep one coherent
    // observation whenever a zoned/unknown-zone local representation differs between sources.
    val differentZone = local.recordedAtZoneId != incoming.recordedAtZoneId
    if (!compatibleText || differentZone && (localText != null || incomingText != null)) return copyOrigin(incoming, primary)
    return incoming.copy(
      recordedAtEpochMs = primary.recordedAtEpochMs ?: secondary.recordedAtEpochMs,
      recordedAtLocal = primary.recordedAtLocal ?: secondary.recordedAtLocal,
      recordedAtZoneId = primary.recordedAtZoneId ?: secondary.recordedAtZoneId,
      recordedAtSource = primary.recordedAtSource ?: secondary.recordedAtSource,
      recordedAtPrecision = primary.recordedAtPrecision ?: secondary.recordedAtPrecision,
    )
  }

  private fun unknown(value: AudioPartEntity) = value.recordedAtEpochMs == null && value.recordedAtLocal == null &&
    value.recordedAtZoneId == null && value.recordedAtSource == null && value.recordedAtPrecision == null

  private fun extends(short: String, long: String) = long.startsWith(short) && long.length > short.length && long[short.length] in "T:."

  private fun strength(value: AudioPartEntity): Int = (if (value.recordedAtEpochMs != null) 100 else 0) +
    when (value.recordedAtPrecision) { "millisecond" -> 40; "second" -> 30; "minute" -> 20; "day" -> 10; else -> 0 } +
    (if (value.recordedAtZoneId != null) 2 else 0) + (if (value.recordedAtLocal != null) 1 else 0)

  private fun copyOrigin(target: AudioPartEntity, origin: AudioPartEntity) = target.copy(
    recordedAtEpochMs = origin.recordedAtEpochMs, recordedAtLocal = origin.recordedAtLocal,
    recordedAtZoneId = origin.recordedAtZoneId, recordedAtSource = origin.recordedAtSource,
    recordedAtPrecision = origin.recordedAtPrecision,
  )
}
