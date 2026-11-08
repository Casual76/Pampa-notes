package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.alignment.CloudSources
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.model.wordCount
import java.util.UUID

/** Typed native boundary shared by ZIP today and the parent's PN collection/import adapters.
 * Never touches attachments: omitted/relay attachment planning remains the caller's responsibility. */
internal object PampaBundleCloudSources {
  /** Both graphs are required: destination rows cannot vouch for original audio identities. */
  fun remap(source: PampaBundleNote, destination: PampaBundleNote,
    partIds: Map<String, String> = emptyMap(), rawIds: Map<String, String> = emptyMap(),
    transfer: Boolean = true): PampaBundleNote {
    val byRaw = source.transcripts.associateBy { rawIds[it.id] ?: it.id }
    return destination.copy(transcripts = destination.transcripts.map { raw ->
      val before = byRaw[raw.id] ?: error("Missing source transcript for native remap")
      if (raw.kind != TranscriptKind.RAW) {
        require(before.cloudSourceJson.isNullOrEmpty()) { "Cloud source metadata requires RAW" }
        raw
      } else if (!transfer && raw == before && partIds.isEmpty()) raw
      else project(source, before, destination, raw, partIds)
    })
  }

  private fun project(source: PampaBundleNote, before: TranscriptEntity, destination: PampaBundleNote,
    raw: TranscriptEntity, partIds: Map<String, String> = emptyMap()): TranscriptEntity {
    val current = destination.parts.filter { it.sessionId == raw.sessionId }.associateBy { it.id }
    val selected = source.parts.filter { (partIds[it.id] ?: it.id) in current }.map { p ->
      // Position follows the destination; source hash/session remain the actual before-image.
      p.copy(position = current.getValue(partIds[p.id] ?: p.id).position, sessionId = before.sessionId)
    }
    val projected = CloudSources.portableProjection(before, selected, raw.id, partIds, CloudSources.version(raw), retainOriginals = true)
    val result = raw.copy(text = projected.text, cloudSourceJson = projected.cloudSourceJson,
      wordCount = if (raw.text == projected.text) raw.wordCount else projected.text.wordCount())
    require(CloudSources.validatePortable(result, current.values.toList())) { "Native source conflicts with destination audio graph" }
    return result
  }

  /** Merge may change offsets, split RAWs or recompose coarse segment text. Repair each RAW from
   * its OWN source document, preserving exact public text/overlays and independent local histories. */
  fun merge(local: PampaBundleNote?, incoming: PampaBundleNote, preserveLocalNote: Boolean): PampaBundleNote {
    val retained = preserveHistories(local, incoming)
    val merged = PampaBundleMerge.merge(retained, incoming, preserveLocalNote)
    val incomingById = incoming.transcripts.associateBy { it.id }
    val localById = retained?.transcripts.orEmpty().associateBy { it.id }
    return merged.copy(transcripts = merged.transcripts.map { raw ->
      if (raw.kind != TranscriptKind.RAW || raw.cloudSourceJson.isNullOrEmpty()) raw
      else {
        val owner = when {
          raw.id in incomingById -> incoming to incomingById.getValue(raw.id)
          raw.id in localById -> requireNotNull(retained) to localById.getValue(raw.id)
          else -> {
            // PampaBundleMerge's synthesized history carries the source JSON verbatim.
            val candidates = (incoming.transcripts.map { incoming to it } + retained?.transcripts.orEmpty().map { requireNotNull(retained) to it })
              .filter { it.second.cloudSourceJson == raw.cloudSourceJson }.distinctBy { it.second }
            require(candidates.size == 1) { "Ambiguous native RAW provenance" }
            candidates.single()
          }
        }
        val before = owner.second
        if (raw == before && raw.id !in incomingById && owner.first.parts == merged.parts) raw
        else project(owner.first, before, merged, raw)
      }
    })
  }

  /** A stable incoming RAW ID is not permission to erase an older local public version/evidence. */
  private fun preserveHistories(local: PampaBundleNote?, incoming: PampaBundleNote): PampaBundleNote? {
    if (local == null) return null
    val received = incoming.transcripts.associateBy { it.id }
    fun content(raw: TranscriptEntity, graph: PampaBundleNote): TranscriptEntity {
      val p = CloudSources.portableProjection(raw, graph.parts.filter { it.sessionId == raw.sessionId }, retainOriginals = true)
      return raw.copy(text = p.text, cloudSourceJson = p.cloudSourceJson)
    }
    val aliases = local.transcripts.filter { it.kind == TranscriptKind.RAW }.mapNotNull { before ->
      val after = received[before.id] ?: return@mapNotNull null
      // Legacy RAWs without source documents retain the existing native merge policy.
      if (before.cloudSourceJson.isNullOrEmpty() && after.cloudSourceJson.isNullOrEmpty()) return@mapNotNull null
      if (content(before, local) == content(after, incoming)) return@mapNotNull null
      val id = UUID.nameUUIDFromBytes("pampa-native-raw-history:${before.id}:${before.createdAt}:${before.text}:${before.cloudSourceJson}".toByteArray(Charsets.UTF_8)).toString()
      require(id !in received) { "Retained RAW identity conflict" }
      val existing = local.transcripts.find { it.id == id }
      require(existing == null || existing.copy(id = before.id, cloudSourceJson = before.cloudSourceJson) == before) { "Retained RAW history collision" }
      before.id to id
    }.toMap()
    if (aliases.isEmpty()) return local
    val moved = local.copy(
      sessions = local.sessions.map { s -> s.copy(activeTranscriptId = s.activeTranscriptId?.let { aliases[it] ?: it }) },
      transcripts = local.transcripts.filterNot { it.id in aliases.values }.map { t -> t.copy(id = aliases[t.id] ?: t.id, parentId = t.parentId?.let { aliases[it] ?: it }) },
      segments = local.segments.map { it.copy(transcriptId = aliases[it.transcriptId] ?: it.transcriptId) })
    return remap(local, moved, rawIds = aliases, transfer = false)
  }
}
