package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.transcription.SessionTranscript

/** Lossless cloud save plan. Only runner/cache input evidence attests original audio. */
object CloudSavePlan {
  fun build(draft: TranscriptEntity, partIds: List<String>, result: SessionTranscript, originalParts: List<AudioPartEntity>,
    currentParts: List<AudioPartEntity>, written: Set<String>, selected: TranscriptEntity?, retainedSegments: List<SegmentEntity>): Pair<String, CloudSourceDocument> {
    require(originalParts.map { it.id }.distinct().size == originalParts.size && originalParts.all { it.id in partIds }) { "Invalid original audio snapshot" }
    require(result.sourceParts.map { it.partId }.distinct().size == result.sourceParts.size) { "Duplicate cloud source part" }
    require(result.sourceParts.all { it.partId in partIds }) { "Unexpected cloud source part" }
    val exact = result.sourceParts.associateBy { it.partId }
    val originalTextParts = partIds.map { id ->
      val source = exact[id]
      CloudTextPart(id, source?.audioSha256.orEmpty(), source?.text?.takeIf { source.exactTextKnown },
        source?.language, source?.provider.orEmpty(), source?.model.orEmpty(), source?.responses)
    }
    val sourceText = if (originalTextParts.all { it.text != null }) originalTextParts.joinToString("\n\n") { it.text!! } else result.text
    val created = CloudSources.create(draft.copy(language = result.language,
      provider = originalTextParts.map { it.provider }.distinct().singleOrNull().orEmpty(),
      model = originalTextParts.map { it.model }.distinct().singleOrNull().orEmpty()), sourceText, originalTextParts, result.text)
    val fresh = if (originalTextParts.any { it.text == null }) created.copy(alignmentBinding = "portable_unbound",
      originals = created.originals.map { it.copy(contentScope = "derived_presentation") }) else created
    val otherIds = currentParts.map { it.id }.toSet() - written
    val retained = selected?.let { row ->
      CloudSources.decode(row.cloudSourceJson)?.takeIf { CloudSources.matches(it, row) }
        ?: CloudSources.legacy(row, currentParts)
        ?: CloudSources.create(row, row.text, currentParts.map { p -> CloudTextPart(p.id, "",
          row.text.takeIf { currentParts.size == 1 }, row.language, row.provider, row.model) })
    }?.let { it.copy(parts = it.parts.filter { p -> p.id in otherIds }, overlays = it.overlays.filter { o -> o.partId in otherIds }) }
    val coarse = result.segments.map { s -> SegmentEntity(transcriptId = draft.id, partId = s.partId, indexInPart = s.indexInPart,
      partStartMs = s.partStartMs, partEndMs = s.partEndMs, sessionStartMs = s.sessionStartMs, sessionEndMs = s.sessionEndMs, text = s.text) }
    return CloudSources.combine(draft, currentParts, listOfNotNull(retained, fresh.copy(parts = fresh.parts.filter { it.id in written })), retainedSegments + coarse)
  }
}
