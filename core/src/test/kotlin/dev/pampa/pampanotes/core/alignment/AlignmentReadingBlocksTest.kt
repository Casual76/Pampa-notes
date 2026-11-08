package dev.pampa.pampanotes.core.alignment

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AlignmentReadingBlocksTest {
  private fun plain(text: String) = AlignmentPresentation.Text(text, emptyList())
  private fun assertExact(text: String, cap: Int) {
    val blocks = AlignmentReadingBlocks.split(plain(text), maxUtf16 = cap, maxLineBreaks = 3)
    assertEquals(text, blocks.joinToString("") { it.text })
    var cursor = 0
    blocks.forEach { block ->
      assertEquals(cursor, block.textStart)
      assertTrue(block.text.isNotEmpty() && block.text.length <= cap)
      assertEquals(text.substring(block.textStart, block.textEnd), block.text)
      assertTrue(block.text.count { it == '\n' || it == '\r' } <= 6) // CRLF counts as one break
      cursor = block.textEnd
      if (cursor < text.length) {
        assertFalse(Character.isHighSurrogate(text[cursor - 1]) && Character.isLowSurrogate(text[cursor]))
        assertFalse(text[cursor - 1] == '\r' && text[cursor] == '\n')
      }
    }
    assertEquals(text.length, cursor)
  }

  @Test fun `every character survives Unicode whitespace punctuation and surrogate boundaries`() {
    val text = " \t\r\n“Caffè”, e\u0301! 😀𝄞\u00a0中文\t\n\n  fine… \r\n\t "
    (2..80).forEach { assertExact(text.repeat(5), it) }
  }

  @Test fun `deterministic adversarial Unicode reconstruction`() {
    val random = Random(91)
    val units = listOf(" ", "\t", "\r\n", "\n", "😀", "𝄞", "é", "e\u0301", "字", ",", "a", "\u00a0")
    repeat(100) {
      val text = buildString { repeat(300) { append(units[random.nextInt(units.size)]) } }
      assertExact(text, random.nextInt(2, 80))
    }
  }

  @Test fun `blank text and long untimed text are bounded without discarding whitespace`() {
    assertTrue(AlignmentReadingBlocks.split(plain("")).isEmpty())
    assertExact("\n".repeat(10_000), 31)
    assertExact("😀".repeat(20_000) + "\t ", 101)
    val blocks = AlignmentReadingBlocks.split(plain("word ".repeat(100_000)))
    assertTrue(blocks.size > 300)
    assertTrue(blocks.all { it.measured.isEmpty() && it.timeAt(0) == null })
  }

  @Test fun `line-heavy content cannot become one giant layout`() {
    val blocks = AlignmentReadingBlocks.split(plain("a\n".repeat(500)))
    assertTrue(blocks.size > 10)
    assertTrue(blocks.all { it.text.count { c -> c == '\n' } <= AlignmentReadingBlocks.MAX_LINE_BREAKS })
  }

  @Test fun `part boundaries are preferred and remain lossless`() {
    val text = "first  \n\n  second\t"
    val blocks = AlignmentReadingBlocks.split(AlignmentPresentation.Text(text, emptyList(), listOf(9)), maxUtf16 = 30)
    assertEquals(listOf("first  \n\n", "  second\t"), blocks.map { it.text })
    assertEquals(text, blocks.joinToString("") { it.text })
  }

  @Test fun `sparse words keep original times and exact block local offsets`() {
    val text = "one missing two"
    val measured = listOf(AlignmentPresentation.Span(0, 3, 100, 300, "measured"), AlignmentPresentation.Span(12, 15, 700, 900, "measured"))
    val blocks = AlignmentReadingBlocks.split(AlignmentPresentation.Text(text, measured), maxUtf16 = 8)
    assertEquals(text, blocks.joinToString("") { it.text })
    val global = blocks.flatMap { b -> b.measured.map { it.copy(textStart = it.textStart + b.textStart, textEnd = it.textEnd + b.textStart) } }
    assertEquals(measured, global)
    assertEquals(100L, AlignmentPresentation.Text(text, measured).timeAt(0))
    assertNull(AlignmentPresentation.Text(text, measured).timeAt(4))
    assertNull(AlignmentPresentation.Text(text, measured).timeAt(text.length))
    blocks.forEach { block ->
      block.text.indices.forEach { offset ->
        val expected = measured.firstOrNull { block.textStart + offset in it.textStart until it.textEnd }?.startMs
        assertEquals(expected, block.timeAt(offset))
      }
      assertNull(block.timeAt(-1)); assertNull(block.timeAt(block.text.length))
    }
  }

  @Test fun `hard splits of a huge measured token retain whole interval without interpolating fragments`() {
    val blocks = AlignmentReadingBlocks.split(AlignmentPresentation.Text("abcdefghijk", listOf(AlignmentPresentation.Span(0, 11, 100, 900, "measured"))), maxUtf16 = 4)
    assertEquals("abcdefghijk", blocks.joinToString("") { it.text })
    assertEquals(3, blocks.size)
    assertTrue(blocks.all { it.measured.single().startMs == 100L && it.measured.single().endMs == 900L })
  }

  @Test fun `missing estimated partial and malformed timing never becomes measured`() {
    val spans = listOf(AlignmentPresentation.Span(0, 1, null, null, "missing"), AlignmentPresentation.Span(1, 2, 10, 20, "estimated"),
      AlignmentPresentation.Span(2, 3, 10, null, "measured"), AlignmentPresentation.Span(3, 4, 30, 20, "measured"),
      AlignmentPresentation.Span(4, 5, -1, 20, "measured"), AlignmentPresentation.Span(5, 9, 10, 20, "measured"))
    assertTrue(AlignmentReadingBlocks.split(AlignmentPresentation.Text("abcdef", spans)).single().measured.isEmpty())
  }
}
