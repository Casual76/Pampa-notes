package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.importing.PampaBundleMetadataMerge
import dev.pampa.pampanotes.core.alignment.CloudSources
import dev.pampa.pampanotes.core.alignment.AlignmentProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Use the portable graph's monotonic evidence policy on sync's incoming rows as well. */
internal object SyncMetadataMerge {
  fun audio(local: AudioPartEntity?, incoming: AudioPartEntity): AudioPartEntity =
    if (local != null && local.id == incoming.id && local.sizeBytes == incoming.sizeBytes &&
      local.sha256.length == 64 && local.sha256.all { it in "0123456789abcdefABCDEF" } && local.sha256.equals(incoming.sha256, true))
      PampaBundleMetadataMerge.audio(local, incoming)
    else incoming

  fun transcript(local: TranscriptEntity?, incoming: TranscriptEntity): TranscriptEntity {
    // This typed boundary accepts expanded entities only. The wire codec must run BEFORE
    // decoding, hashes and upserts (including the local-wins provenance-adoption path).
    val previous = local?.cloudSourceJson
    val supplied = incoming.cloudSourceJson
    require(previous?.startsWith(SyncCloudSourceWire.PREFIX) != true &&
      supplied?.startsWith(SyncCloudSourceWire.PREFIX) != true) { "Unexpanded cloud source sync encoding" }
    fun coherent(row: TranscriptEntity) {
      val source = row.cloudSourceJson ?: return
      val doc = CloudSources.decode(source)
      if (doc != null) {
        require(CloudSources.matches(doc, row)) { "Cloud source RAW identity/text mismatch" }
      } else if (source.length <= CloudSources.MAX_JSON) {
        // Future typed documents retain their bytes, but their declared identity still cannot
        // be moved onto another RAW by an older client omitting cloudSourceJson.
        val obj = runCatching { AlignmentProtocol.json.parseToJsonElement(source) as? JsonObject }.getOrNull()
        if (obj != null && setOf("rawId", "rawVersion", "textSha256", "originals").all { it in obj }) {
          require((obj["rawId"] as? JsonPrimitive)?.takeIf { it.isString }?.content == row.id &&
            (obj["rawVersion"] as? JsonPrimitive)?.longOrNull == CloudSources.version(row) &&
            (obj["textSha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content == AlignmentProtocol.sha256(row.text)) {
            "Cloud source RAW identity/text mismatch"
          }
        }
      }
    }
    coherent(incoming)
    if (local == null || previous == null) return incoming
    coherent(local)
    val merged = when {
      supplied == null -> previous
      previous == supplied -> supplied
      else -> CloudSources.mergeOverlayEvidence(previous, supplied)
    }
    // Even an older client's omitted field cannot attach typed evidence to another RAW.
    return incoming.copy(cloudSourceJson = merged).also(::coherent)
  }
}
