package dev.pampa.pampanotes.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterMarksTest {

  @Test
  fun `i capitoli sono punti, senza lo zero`() {
    assertEquals(listOf(60_000L, 600_000L), chapterMarks(listOf(0L, 60_000L, 600_000L), durationMs = 1_800_000L))
  }

  @Test
  fun `un capitolo oltre la fine non e' un punto sulla pista`() {
    assertEquals(listOf(60_000L), chapterMarks(listOf(60_000L, 2_000_000L, 1_800_000L), durationMs = 1_800_000L))
  }

  @Test
  fun `senza durata non c'e' una pista`() {
    assertEquals(emptyList<Long>(), chapterMarks(listOf(60_000L), durationMs = 0L))
  }

  @Test
  fun `capitoli doppi si disegnano una volta`() {
    assertEquals(listOf(60_000L), chapterMarks(listOf(60_000L, 60_000L), durationMs = 600_000L))
  }

  @Test
  fun `oltre il tetto la pista non ha punti, sarebbe un tratteggio`() {
    val many = (1..MAX_CHAPTER_MARKS + 1).map { it * 1_000L }
    assertEquals(emptyList<Long>(), chapterMarks(many, durationMs = 10_000_000L))
    val few = (1..MAX_CHAPTER_MARKS).map { it * 1_000L }
    assertEquals(few, chapterMarks(few, durationMs = 10_000_000L))
  }
}
