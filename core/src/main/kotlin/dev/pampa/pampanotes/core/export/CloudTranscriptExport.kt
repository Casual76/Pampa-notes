package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.alignment.*
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.transcription.TranscriptParagraphs
import dev.pampa.pampanotes.core.transcription.VoiceNames

/** Human-readable exports use the same exact cloud source as the reader, never segment text. */
internal object CloudTranscriptExport {
  fun applies(session: ExportSession) = session.transcript?.let {
    it.kind == TranscriptKind.RAW && (it.cloudSourceJson != null || CloudSources.cloud(it.provider))
  } == true

  fun blocks(session: ExportSession, options: ExportOptions): List<TranscriptBlock>? {
    if (!applies(session)) return null
    val raw = requireNotNull(session.transcript)
    val doc = CloudSources.decode(raw.cloudSourceJson)?.takeIf { CloudSources.matches(it, raw) }
    val graph = session.parts.mapIndexed { index, part ->
      AudioPartEntity(part.id, session.id, index, part.fileName, part.originalName, "audio/mp4",
        part.sizeBytes, part.durationMs, part.sha256, 0)
    }
    val exactParts = doc?.parts?.takeIf { list -> list.all { it.text != null } && list.joinToString("\n\n") { it.text!! } == raw.text }
    data class Range(val part: CloudTextPart, val start: Int, val end: Int)
    var offset = 0
    val ranges = exactParts.orEmpty().mapIndexed { index, part ->
      if (index > 0) offset += 2
      Range(part, offset, offset + part.text!!.length).also { offset = it.end }
    }
    val presentation = AlignmentPresentation.forRaw(raw, graph)
      ?: AlignmentPresentation.Text(raw.text, emptyList(), ranges.drop(1).map { it.start })
    val voices = TranscriptParagraphs.voices(session.segments)
    return AlignmentReadingBlocks.split(presentation).map { block ->
      val first = block.text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 }
      val global = first?.let { block.textStart + it }
      val range = global?.let { at -> ranges.firstOrNull { at in it.start until it.end } }
      val part = range?.let { r -> session.parts.find { it.id == r.part.id } }
      val identityMatches = part != null && AlignmentProtocol.validHash(part.sha256) && part.sha256 == range?.part?.sha256
      val measured = first?.let(block::timeAt)
      val coarse = if (identityMatches && range != null && part != null && global != null) {
        val segments = session.segments.filter { it.partId == part.id }.sortedWith(compareBy({ it.partStartMs }, { it.indexInPart }))
        AlignmentProtocol.windows(range.part.text!!, segments.map { AlignmentProtocol.CoarseSegment(it.text, it.partStartMs, it.partEndMs) }, part.durationMs)
          .firstOrNull { global - range.start in it.textStart until it.textEnd }
      } else null
      val at = measured ?: coarse?.let { part!!.startMs + it.startMs }
      val end = if (at == null) null else block.measured.lastOrNull()?.endMs ?: coarse?.let { part!!.startMs + it.endMs }
      val segment = coarse?.let { window -> session.segments.firstOrNull { it.partId == part?.id && it.partStartMs == window.startMs && it.partEndMs == window.endMs } }
      val voiceKey = segment?.speaker?.let { VoiceNames.key(segment.partId, it) }
      TranscriptBlock(text = block.text, startMs = at.takeIf { options.timestamps }, endMs = end.takeIf { options.timestamps },
        partId = part?.id, voice = voiceKey?.let(voices::get), voiceName = voiceKey?.let(session.voiceNames::get),
        partOffsetMs = if (at != null && part != null) at - part.startMs else null,
        showClock = options.speechClock, clockEstimated = at != null && measured == null)
    }
  }
}
