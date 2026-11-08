package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind

/** UI adapter: full exact text, UTF16 ranges, nullable session times, no interpolation. */
object AlignmentPresentation {
  data class Span(val textStart: Int, val textEnd: Int, val startMs: Long?, val endMs: Long?, val evidence: String)
  data class Text(val text: String, val spans: List<Span>, val partBoundaries: List<Int> = emptyList()) {
    /** Feed these ranges directly to a renderer; never WordTimings.fill/complete. */
    val measured: List<Span> get() = spans.filter {
      it.evidence == "measured" && it.textStart >= 0 && it.textEnd > it.textStart && it.textEnd <= text.length &&
        it.startMs != null && it.endMs != null && it.startMs >= 0 && it.endMs > it.startMs
    }
    /** Also usable by global-search adapters: absent evidence always stays nullable. */
    fun timeAt(offset: Int): Long? = measured.firstOrNull { offset >= it.textStart && offset < it.textEnd }?.startMs
  }
  fun forRaw(raw: TranscriptEntity, currentParts: List<AudioPartEntity>): Text? {
    // Display validation is deliberately separate from permission to request NEW alignment.
    // Portable evidence can remain useful without attesting imported audio as an original.
    return runCatching { validated(raw, currentParts) }.getOrNull()
  }

  private fun validated(raw: TranscriptEntity, currentParts: List<AudioPartEntity>): Text? {
    if (raw.kind != TranscriptKind.RAW || !AlignmentProtocol.validUnicode(raw.text)) return null
    val doc = CloudSources.decode(raw.cloudSourceJson)?.takeIf { CloudSources.matches(it, raw) } ?: return null
    val ordered = currentParts.sortedWith(compareBy({ it.position }, { it.id }))
    if (ordered.map { it.id }.distinct().size != ordered.size || ordered.any { it.sessionId != raw.sessionId || it.position < 0 } ||
      ordered.map { it.position }.distinct().size != ordered.size) return null
    val bound = CloudSources.bindSource(doc, ordered) != null
    if (!bound && !(doc.alignmentBinding == "portable_unbound" && CloudSources.validatePortable(raw, ordered))) return null
    // An empty portable hash is an honest unknown, never evidence for a highlight.
    if (doc.parts.any { part -> !AlignmentProtocol.validHash(part.sha256) || ordered.singleOrNull { it.id == part.id }?.sha256 != part.sha256 }) return null
    if (doc.parts.any { it.text == null } || doc.parts.joinToString("\n\n") { it.text!! } != raw.text) return null
    val offsets = mutableMapOf<String, Long>()
    var time = 0L
    ordered.forEach { p -> if (p.durationMs < 0) return null; offsets[p.id] = time; time = Math.addExact(time, p.durationMs) }
    val spans = mutableListOf<Span>()
    val boundaries = mutableListOf<Int>()
    var offset = 0
    doc.parts.forEachIndexed { index, part ->
      if (index > 0) { spans += Span(offset, offset + 2, null, null, "whitespace"); offset += 2 }
      if (index > 0) boundaries += offset
      val audio = ordered.single { it.id == part.id }
      val placement = offsets.getValue(part.id)
      CloudSources.display(part, doc.overlays.find { it.partId == part.id }).forEach { span ->
        val measured = span.evidence == "measured" && span.startMs != null && span.endMs != null &&
          span.startMs >= 0 && span.endMs > span.startMs && span.endMs <= audio.durationMs
        if (span.evidence == "measured" && !measured) return null
        spans += Span(offset + span.textStart, offset + span.textEnd,
          if (measured) Math.addExact(placement, span.startMs!!) else null,
          if (measured) Math.addExact(placement, span.endMs!!) else null,
          if (span.evidence == "measured" && !measured) "missing" else span.evidence)
      }
      offset += part.text!!.length
    }
    return Text(raw.text, spans, boundaries)
  }
}
