package dev.pampa.pampanotes.core.alignment

/** Bounded views of the original UTF16 string, never a reconstruction from words/segments. */
object AlignmentReadingBlocks {
  const val MAX_UTF16 = 1_600
  const val MAX_LINE_BREAKS = 24

  data class Block(val textStart: Int, val text: String, val measured: List<AlignmentPresentation.Span>) {
    val textEnd: Int get() = textStart + text.length

    /** No nearest-word/segment fallback: whitespace, missing words and end offsets have no time. */
    fun timeAt(offset: Int): Long? = measured.firstOrNull { offset >= it.textStart && offset < it.textEnd }?.startMs
  }

  fun split(source: AlignmentPresentation.Text, maxUtf16: Int = MAX_UTF16, maxLineBreaks: Int = MAX_LINE_BREAKS): List<Block> {
    require(maxUtf16 >= 2 && maxLineBreaks >= 1)
    val text = source.text
    if (text.isEmpty()) return emptyList()
    val words = source.measured.sortedBy { it.textStart }
    val boundaries = source.partBoundaries.filter { it in 1 until text.length && safe(text, it) }.distinct().sorted()
    val blocks = ArrayList<Block>()
    var start = 0
    var wordIndex = 0
    var boundaryIndex = 0
    while (start < text.length) {
      var end = (start.toLong() + maxUtf16).coerceAtMost(text.length.toLong()).toInt()
      if (!safe(text, end)) end--
      // A newline-heavy result also has a bounded height, even when the text is mostly blank.
      var lines = 0
      for (i in start until end) {
        if (text[i] == '\n' || (text[i] == '\r' && text.getOrNull(i + 1) != '\n')) lines++
        if (lines >= maxLineBreaks) { end = i + 1; break }
      }
      while (boundaryIndex < boundaries.size && boundaries[boundaryIndex] <= start) boundaryIndex++
      val partEnd = boundaries.getOrNull(boundaryIndex)?.takeIf { it <= end }
      if (partEnd != null) end = partEnd
      else if (end < text.length) {
        // Prefer a whitespace boundary in the latter half. A huge unbroken token still splits.
        val floor = start + (end - start) / 2
        for (i in end downTo (floor + 1)) {
          if (text[i - 1].isWhitespace() && safe(text, i)) { end = i; break }
        }
      }
      if (!safe(text, end)) end--
      // With a two-unit minimum cap a surrogate pair / CRLF can always make progress intact.
      if (end <= start) end = (start + 2).coerceAtMost(text.length)
      while (wordIndex < words.size && words[wordIndex].textEnd <= start) wordIndex++
      var index = wordIndex
      val local = ArrayList<AlignmentPresentation.Span>()
      while (index < words.size && words[index].textStart < end) {
        val word = words[index++]
        // A hard split inside an unusually long word retains the SAME measured interval in both
        // fragments. It never allocates/interpolates a new time to a character or fragment.
        local += word.copy(textStart = maxOf(start, word.textStart) - start, textEnd = minOf(end, word.textEnd) - start)
      }
      blocks += Block(start, text.substring(start, end), local)
      start = end
    }
    return blocks
  }

  private fun safe(text: String, offset: Int): Boolean = offset <= 0 || offset >= text.length ||
    !(Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset])) &&
      !(text[offset - 1] == '\r' && text[offset] == '\n')
}
