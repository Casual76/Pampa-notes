package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind

/** Pure transaction decision, shared by the Room adapter and adversarial JVM fixtures. */
object AlignmentApplication {
  fun plan(expected: AlignmentSnapshot, current: AlignmentSnapshot?, raw: TranscriptEntity, response: AlignmentResponse,
    accountKey: String, cancelled: Boolean, jobId: String): TranscriptEntity? {
    if (cancelled || accountKey != expected.accountKey || current != expected) return null
    val doc = CloudSources.decode(raw.cloudSourceJson) ?: return null
    if (doc.alignmentBinding != "source_snapshot" || doc.originals.any { it.contentScope != "complete_original" }) return null
    if (raw.kind != TranscriptKind.RAW || raw.language != expected.rawLanguage || !CloudSources.matches(doc, raw) || raw.id != expected.request.source.rawId) return null
    if (doc.parts.map { AlignmentPart(it.id, it.sha256) } != expected.request.source.parts) return null
    val part = doc.parts.find { it.id == response.partId } ?: return null
    if (part.text != expected.request.text || AlignmentLanguage.code(part.language) != expected.request.language) return null
    val duration = expected.placement.singleOrNull { it.id == response.partId }?.durationMs ?: return null
    AlignmentProtocol.validate(response, expected.request, duration, jobId)
    // The overlay carries the validated protocol code. RAW/part/original labels stay exact.
    val overlay = CloudOverlay(response.partId, response.textSha256, response.language, response.timings)
    return raw.copy(cloudSourceJson = CloudSources.encode(doc.copy(overlays = doc.overlays.filterNot { it.partId == response.partId } + overlay)))
  }
}
