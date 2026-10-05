package dev.pampa.pampanotes.core.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenStatsTest {

  @Test
  fun `words split on apostrophes and ignore punctuation`() {
    assertEquals(listOf("l", "anno", "è", "finito", "cioè", "no"), SpokenStats.words("L'anno è finito. Cioè… no?"))
  }

  @Test
  fun `favourite word skips the glue and the fillers`() {
    val text = "Quindi Kant dice che la ragione, quindi, la ragione di Kant. Quindi questa ragione è Kant, quindi Kant."
    val stats = SpokenStats.of(listOf(text), speechMs = 60_000, written = "")
    assertEquals(WordCount("kant", 4), stats.favorite)
    assertEquals(WordCount("quindi", 4), stats.filler)
    assertEquals(15_000L, stats.fillerEveryMs)
  }

  @Test
  fun `a tie goes to the word first in the alphabet`() {
    val stats = SpokenStats.of(listOf("zebra albero zebra albero"), speechMs = 0, written = "")
    assertEquals("albero", stats.favorite?.word)
  }

  @Test
  fun `a filler said twice is just a word`() {
    val stats = SpokenStats.of(listOf("allora vediamo, allora"), speechMs = 10_000, written = "")
    assertNull(stats.filler)
  }

  @Test
  fun `pace needs half a minute of speech`() {
    val words = List(150) { "parola$it" }.joinToString(" ")
    assertNull(SpokenStats.of(listOf(words), speechMs = 20_000, written = "").wordsPerMinute)
    val minute = SpokenStats.of(listOf(words), speechMs = 60_000, written = "")
    assertEquals(150, minute.wordsPerMinute)
    assertEquals(SpeakingPace.BRISK, SpokenStats.paceOf(150))
    assertEquals(SpeakingPace.CALM, SpokenStats.paceOf(60))
    assertEquals(SpeakingPace.RAPPER, SpokenStats.paceOf(250))
  }

  @Test
  fun `written words are counted apart`() {
    val stats = SpokenStats.of(listOf("una due tre"), speechMs = 0, written = "## Appunti\n- Kant, *critica*")
    assertEquals(3, stats.words)
    assertEquals(3, stats.writtenWords)
  }
}
