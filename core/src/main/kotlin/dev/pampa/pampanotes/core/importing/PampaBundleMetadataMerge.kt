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
    if (unknown(incoming)) return PampaBundleRecordingOrigin.prefer(local, incoming)
    val sameInstant = local.recordedAtEpochMs != null && local.recordedAtEpochMs == incoming.recordedAtEpochMs
    require(local.recordedAtEpochMs == null || incoming.recordedAtEpochMs == null || sameInstant) { "Recording origin conflict" }
    val localText = local.recordedAtLocal
    val incomingText = incoming.recordedAtLocal
    val compatibleText = localText == null || incomingText == null || localText == incomingText ||
      extends(localText, incomingText) || extends(incomingText, localText)
    require(sameInstant || compatibleText) { "Recording origin conflict" }
    require(sameInstant || local.recordedAtZoneId == null || incoming.recordedAtZoneId == null ||
      local.recordedAtZoneId == incoming.recordedAtZoneId) { "Recording zone conflict" }
    // Use native import's evidenceQuality policy AFTER sync's conservative conflict checks.
    // A partial tuple must never acquire the other observation's time, precision or provenance.
    return PampaBundleRecordingOrigin.prefer(local, incoming)
  }

  private fun unknown(value: AudioPartEntity) = value.recordedAtEpochMs == null && value.recordedAtLocal == null &&
    value.recordedAtZoneId == null && value.recordedAtSource == null && value.recordedAtPrecision == null

  private fun extends(short: String, long: String) = long.startsWith(short) && long.length > short.length && long[short.length] in "T:."
}
