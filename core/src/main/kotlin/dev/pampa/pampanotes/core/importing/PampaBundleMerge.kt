package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.transcription.VoiceNames
import java.util.UUID

/** Computes a complete, FK-consistent note graph before any graph row is persisted.
 * Incoming order wins; local-only siblings follow in their existing relative order.
 * All timelines use this final sequence, including local-only RAW histories. */
internal object PampaBundleMerge {
  fun merge(local: PampaBundleNote?, incoming: PampaBundleNote, preserveLocalNote: Boolean = false): PampaBundleNote {
    val n = incoming.note
    require(local == null || local.note.id == n.id) { "Note identity conflict" }
    val oldSessions = local?.sessions.orEmpty().associateBy { it.id }
    val incomingSessions = incoming.sessions.associateBy { it.id }
    val sessions = (incoming.sessions.sortedWith(compareBy({ it.position }, { it.id })) +
      local?.sessions.orEmpty().filter { it.id !in incomingSessions }.sortedWith(compareBy({ it.position }, { it.id })))
      .mapIndexed { index, s -> s.copy(position = index, transcribingOn = oldSessions[s.id]?.transcribingOn, transcribingSince = oldSessions[s.id]?.transcribingSince) }

    val previousParts = local?.parts.orEmpty().associateBy { it.id }
    val resolvedParts = incoming.parts.map { part ->
      val previous = previousParts[part.id]?.takeIf { it.sha256.isNotBlank() && it.sha256.equals(part.sha256, true) }
      PampaBundleMetadataMerge.audio(previous, part)
    }
    val incomingParts = resolvedParts.associateBy { it.id }
    val allParts = (local?.parts.orEmpty().associateBy { it.id } + incomingParts).values
    val parts = sessions.flatMap { session ->
      (resolvedParts.filter { it.sessionId == session.id }.sortedWith(compareBy({ it.position }, { it.id })) +
        allParts.filter { it.sessionId == session.id && it.id !in incomingParts }.sortedWith(compareBy({ it.position }, { it.id })))
        .mapIndexed { index, p -> p.copy(position = index) }
    }
    val partMap = parts.associateBy { it.id }
    require(partMap.size == allParts.size) { "Missing final session for audio" }
    val offsets = mutableMapOf<String, Long>()
    sessions.forEach { s ->
      var offset = 0L
      parts.filter { it.sessionId == s.id }.forEach { p -> offsets[p.id] = offset; offset = Math.addExact(offset, p.durationMs) }
    }

    val oldTranscripts = local?.transcripts.orEmpty().associateBy { it.id }
    val incomingTranscripts = incoming.transcripts.associate { it.id to PampaBundleMetadataMerge.transcript(oldTranscripts[it.id], it) }
    val transcripts = (local?.transcripts.orEmpty().associateBy { it.id } + incomingTranscripts).toMutableMap()
    require(transcripts.values.all { t -> sessions.any { it.id == t.sessionId } }) { "Missing transcript session" }
    // Local refinements follow their RAW parent when the same stable RAW ID moves session.
    transcripts.values.toList().filter { it.kind == TranscriptKind.REFINED && it.parentId != null }.forEach { t ->
      val parent = transcripts[t.parentId] ?: error("Missing refinement parent")
      require(parent.kind == TranscriptKind.RAW) { "Invalid refinement parent" }
      transcripts[t.id] = t.copy(sessionId = parent.sessionId)
    }

    val previousSegments = local?.segments.orEmpty()
    val retained = previousSegments.filterNot { it.transcriptId in incomingTranscripts && it.partId in incomingParts }
    val combined = retained + incoming.segments
    val hadSegments = (previousSegments + incoming.segments).mapTo(mutableSetOf()) { it.transcriptId }
    val rebased = combined.map { segment ->
      val part = partMap[segment.partId] ?: error("Missing segment part")
      var transcript = transcripts[segment.transcriptId] ?: error("Missing segment transcript")
      if (transcript.sessionId != part.sessionId) {
        // Preserve the displaced version in a deterministic RAW history in its new session.
        // Never leave timed segments referencing an audio part in another session.
        require(transcript.kind == TranscriptKind.RAW) { "Cannot relocate timed refinement" }
        val id = UUID.nameUUIDFromBytes("pampa-bundle-split:${transcript.id}:${part.sessionId}".toByteArray(Charsets.UTF_8)).toString()
        val inherited = transcript.copy(id = id, sessionId = part.sessionId, parentId = null)
        val existing = transcripts[id]
        require(existing == null || existing.sessionId == part.sessionId && existing.kind == TranscriptKind.RAW) { "RAW split identity conflict" }
        transcript = existing ?: inherited.also { transcripts[id] = it }
        hadSegments += transcript.id
      }
      val offset = offsets.getValue(part.id)
      require(segment.partStartMs >= 0 && segment.partEndMs >= segment.partStartMs && segment.partEndMs <= part.durationMs) { "Retained audio incompatible with incoming timing" }
      segment.copy(id = 0, transcriptId = transcript.id, sessionStartMs = Math.addExact(offset, segment.partStartMs), sessionEndMs = Math.addExact(offset, segment.partEndMs))
    }.distinctBy { listOf(it.transcriptId, it.partId, it.indexInPart, it.partStartMs, it.partEndMs, it.text, it.wordsJson, it.speaker) }
      .sortedWith(compareBy({ it.sessionStartMs }, { it.partId }, { it.indexInPart }))

    transcripts.values.toList().filter { it.kind == TranscriptKind.RAW && it.id in hadSegments }.forEach { t ->
      val segments = rebased.filter { it.transcriptId == t.id }
      val text = segments.joinToString(" ") { it.text }
      // Preserve exact exported RAW text if its segment sequence and layout did not change.
      val original = incomingTranscripts[t.id] ?: local?.transcripts?.find { it.id == t.id }
      val originalSegments = if (t.id in incomingTranscripts) incoming.segments.filter { it.transcriptId == t.id } else previousSegments.filter { it.transcriptId == t.id }
      val unchanged = originalSegments.map { it.copy(id = 0) } == segments.map { it.copy(id = 0) }
      transcripts[t.id] = if (unchanged && original != null) original else t.copy(text = text, wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() })
    }

    val names = (local?.sessions.orEmpty() + incoming.sessions).fold(emptyMap<String, String>()) { acc, s -> acc + VoiceNames.decode(s.voiceNames) }
    val finalSessions = sessions.map { s ->
      val preferred = incomingSessions[s.id]?.activeTranscriptId ?: oldSessions[s.id]?.activeTranscriptId
      val active = preferred?.takeIf { transcripts[it]?.sessionId == s.id }
        ?: transcripts.values.filter { it.sessionId == s.id && it.kind == TranscriptKind.RAW }.maxWithOrNull(compareBy({ it.createdAt }, { it.id }))?.id
      s.copy(activeTranscriptId = active, voiceNames = VoiceNames.encode(names.filterKeys { partMap[it.substringBefore('|')]?.sessionId == s.id }))
    }
    return incoming.copy(
      note = if (preserveLocalNote) requireNotNull(local).note else n.copy(
        folderId = if (local != null && local.note.updatedAt > n.updatedAt) local.note.folderId else n.folderId,
        title = if (local != null && local.note.updatedAt > n.updatedAt) local.note.title else n.title,
        pinned = if (local != null && local.note.updatedAt > n.updatedAt) local.note.pinned else n.pinned,
        language = if (local != null && local.note.updatedAt > n.updatedAt) local.note.language else n.language,
        updatedAt = maxOf(local?.note?.updatedAt ?: n.updatedAt, n.updatedAt),
        body = PampaBundleBodyMerge.merge(local?.note?.bundleBodyBase, local?.note?.body ?: n.body, n.body), bundleBodyBase = n.body,
        bundleNativeAdopted = when {
          n.bundleNativeAdopted == true || local?.note?.bundleNativeAdopted == true -> true
          local != null -> local.note.bundleNativeAdopted
          else -> n.bundleNativeAdopted
        }),
      tags = (local?.tags.orEmpty() + incoming.tags).distinct(),
      sessions = finalSessions, parts = parts, transcripts = transcripts.values.toList(), segments = rebased,
      sources = (local?.sources.orEmpty().associateBy { it.id } + incoming.sources.associateBy { it.id }).values.toList(),
    )
  }
}
