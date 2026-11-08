package dev.pampa.pampanotes.ui.session

import dev.pampa.pampanotes.core.alignment.AlignmentPresentation
import dev.pampa.pampanotes.core.alignment.AlignmentReadingBlocks
import dev.pampa.pampanotes.core.alignment.CloudSources
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.transcription.TranscriptSearch

/** Exact cloud-only reading/navigation. The legacy Paragraph/WordTimings path stays independent. */
internal class CloudTranscript private constructor(
  val source: TranscriptEntity,
  val currentParts: List<AudioPartEntity>,
  val blocks: List<AlignmentReadingBlocks.Block>,
) {
  val text: String get() = source.text
  private data class LocatedTime(val block: Int, val startMs: Long, val endMs: Long)
  private val times = blocks.flatMapIndexed { index, block ->
    block.measured.map { LocatedTime(index, it.startMs!!, it.endMs!!) }
  }.distinctBy { it.startMs to it.endMs }.sortedBy { it.startMs }
  val hasMeasured: Boolean get() = times.isNotEmpty()

  fun timeAt(block: Int, offset: Int): Long? = blocks.getOrNull(block)?.timeAt(offset)

  /** Follow only while an evidenced word is playing. A gap/untimed block has no invented start. */
  fun activeBlock(positionMs: Long): Int {
    var low = 0
    var high = times.lastIndex
    var found = -1
    while (low <= high) {
      val middle = (low + high) ushr 1
      if (times[middle].startMs <= positionMs) { found = middle; low = middle + 1 }
      else high = middle - 1
    }
    return times.getOrNull(found)?.takeIf { positionMs < it.endMs }?.block ?: -1
  }

  /** Search the entire exact string, so phrases and long tokens can cross a reading boundary. */
  fun locate(matches: List<TranscriptSearch.Match>): List<TranscriptSearch.Match> = matches.mapNotNull { match ->
    val block = blockAt(match.start)
    if (block < 0 || match.end > text.length || match.end <= match.start) null
    else blocks[block].let { TranscriptSearch.Match(block, match.start - it.textStart, match.end - it.textStart) }
  }

  /** A cross-block result is counted once, with its paint range clipped separately in each block. */
  fun highlights(block: Int, matches: List<TranscriptSearch.Match>): List<IntRange> {
    val target = blocks.getOrNull(block) ?: return emptyList()
    return matches.mapNotNull { match ->
      val origin = blocks.getOrNull(match.block)?.textStart ?: return@mapNotNull null
      val start = maxOf(target.textStart, origin + match.start)
      val end = minOf(target.textEnd, origin + match.end)
      if (start < end) (start - target.textStart) until (end - target.textStart) else null
    }
  }

  /** Build once with the search results, off the UI thread; visible cards then do O(1) lookup. */
  fun highlightIndex(matches: List<TranscriptSearch.Match>): Map<Int, List<IntRange>> {
    val result = linkedMapOf<Int, MutableList<IntRange>>()
    matches.forEach { match ->
      val origin = blocks.getOrNull(match.block)?.textStart ?: return@forEach
      val from = origin + match.start
      val to = origin + match.end
      var index = match.block
      while (index in blocks.indices && blocks[index].textStart < to) {
        val block = blocks[index]
        val start = maxOf(from, block.textStart) - block.textStart
        val end = minOf(to, block.textEnd) - block.textStart
        if (start < end) result.getOrPut(index) { mutableListOf() } += start until end
        index++
      }
    }
    return result
  }

  fun matchAt(matches: List<TranscriptSearch.Match>, positionMs: Long): Int {
    // Unknown matches remain searchable but never participate in audio-time selection.
    var last = 0
    matches.forEachIndexed { index, match ->
      val time = timeAt(match.block, match.start) ?: return@forEachIndexed
      if (time >= positionMs - 250) return index
      last = index
    }
    return last
  }

  private fun blockAt(offset: Int): Int {
    if (offset !in text.indices) return -1
    var low = 0
    var high = blocks.lastIndex
    while (low <= high) {
      val middle = (low + high) ushr 1
      val block = blocks[middle]
      when {
        offset < block.textStart -> high = middle - 1
        offset >= block.textEnd -> low = middle + 1
        else -> return middle
      }
    }
    return -1
  }

  companion object {
    fun appliesTo(transcript: TranscriptEntity?): Boolean = transcript?.kind == TranscriptKind.RAW &&
      (CloudSources.cloud(transcript.provider) || transcript.cloudSourceJson != null)

    fun prepare(raw: TranscriptEntity, parts: List<AudioPartEntity>): CloudTranscript {
      require(appliesTo(raw))
      // Malformed, legacy unknown and stale source metadata never changes the visible RAW text.
      val presentation = AlignmentPresentation.forRaw(raw, parts) ?: AlignmentPresentation.Text(raw.text, emptyList())
      return CloudTranscript(raw, parts.toList(), AlignmentReadingBlocks.split(presentation))
    }
  }
}
