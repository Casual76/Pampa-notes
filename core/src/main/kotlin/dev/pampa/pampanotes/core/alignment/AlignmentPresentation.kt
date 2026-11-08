package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity

/** UI adapter: full exact text, UTF16 ranges, nullable session times, no interpolation. */
object AlignmentPresentation {
  data class Span(val textStart: Int, val textEnd: Int, val startMs: Long?, val endMs: Long?, val evidence: String)
  data class Text(val text: String, val spans: List<Span>) {
    /** Feed these ranges directly to a renderer; never WordTimings.fill/complete. */
    val measured: List<Span> get() = spans.filter { it.evidence == "measured" && it.startMs != null && it.endMs != null }
  }
  fun forRaw(raw: TranscriptEntity, currentParts: List<AudioPartEntity>): Text? {
    val doc = CloudSources.decode(raw.cloudSourceJson)?.takeIf { CloudSources.matches(it, raw) } ?: return null
    val ordered = currentParts.sortedWith(compareBy({ it.position }, { it.id }))
    CloudSources.bindSource(doc, ordered) ?: return null
    if (doc.parts.any { it.text == null } || doc.parts.joinToString("\n\n") { it.text!! } != raw.text) return null
    val offsets = mutableMapOf<String, Long>()
    var time = 0L
    ordered.forEach { p -> if (p.durationMs < 0) return null; offsets[p.id] = time; time = Math.addExact(time, p.durationMs) }
    val spans = mutableListOf<Span>()
    var offset = 0
    doc.parts.forEachIndexed { index, part ->
      if (index > 0) { spans += Span(offset, offset + 2, null, null, "whitespace"); offset += 2 }
      val audio = ordered.single { it.id == part.id }
      val placement = offsets.getValue(part.id)
      CloudSources.display(part, doc.overlays.find { it.partId == part.id }).forEach { span ->
        val measured = span.evidence == "measured" && span.startMs != null && span.endMs != null && span.endMs <= audio.durationMs
        spans += Span(offset + span.textStart, offset + span.textEnd,
          if (measured) Math.addExact(placement, span.startMs!!) else null,
          if (measured) Math.addExact(placement, span.endMs!!) else null,
          if (span.evidence == "measured" && !measured) "missing" else span.evidence)
      }
      offset += part.text!!.length
    }
    return Text(raw.text, spans)
  }
}
