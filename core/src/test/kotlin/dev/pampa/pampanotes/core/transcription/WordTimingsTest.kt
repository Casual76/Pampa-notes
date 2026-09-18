package dev.pampa.pampanotes.core.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimingsTest {

  @Test
  fun `le parole si distribuiscono in proporzione ai caratteri, e coprono tutto l'intervallo`() {
    val words = WordTimings.interpolate("ciao mondo", 0, 1000)
    assertEquals(listOf("ciao", "mondo"), words.map { it.text })
    assertEquals(0L, words.first().startMs)
    assertEquals(1000L, words.last().endMs)
    // Nove caratteri: quattro alla prima parola, cinque alla seconda.
    assertEquals(444L, words[0].endMs)
    assertEquals(444L, words[1].startMs)
  }

  @Test
  fun `un testo vuoto non ha parole, e un intervallo a zero non fa dividere per zero`() {
    assertTrue(WordTimings.interpolate("   ", 0, 1000).isEmpty())
    val words = WordTimings.interpolate("una parola", 500, 500)
    assertEquals(2, words.size)
    assertTrue(words.all { it.startMs == 500L && it.endMs == 500L })
  }

  @Test
  fun `senza parole allineate si stima, e la schermata lo puo' dire`() {
    val filled = WordTimings.fill(emptyList(), "due parole", 0, 100)
    assertTrue(filled.estimated)
    assertEquals(2, filled.words.size)

    val aligned = WordTimings.fill(listOf(RawWord(10, 20, "ciao")), "ciao", 0, 100)
    assertFalse(aligned.estimated)
    assertEquals(listOf(RawWord(10, 20, "ciao")), aligned.words)
  }

  @Test
  fun `le parole senza testo non contano come allineamento`() {
    val filled = WordTimings.fill(listOf(RawWord(0, 10, "  ")), "due parole", 0, 100)
    assertTrue(filled.estimated)
    assertEquals(2, filled.words.size)
  }

  @Test
  fun `andata e ritorno dal formato salvato, con i tempi relativi al segmento`() {
    val words = listOf(RawWord(1_000, 1_400, "Fichte"), RawWord(1_400, 1_900, "nasce"))
    val encoded = WordTimings.encode(words, originMs = 1_000)
    assertEquals("0,400,Fichte\n400,900,nasce", encoded)
    assertEquals(words, WordTimings.decode(encoded, originMs = 1_000))
    // Spostata la parte, i tempi di sessione cambiano e le parole li seguono senza riscriverle.
    assertEquals(
      listOf(RawWord(5_000, 5_400, "Fichte"), RawWord(5_400, 5_900, "nasce")),
      WordTimings.decode(encoded, originMs = 5_000),
    )
  }

  @Test
  fun `una virgola dentro una parola non rompe il formato`() {
    val words = listOf(RawWord(0, 100, "vero,"))
    val encoded = WordTimings.encode(words, originMs = 0)
    assertEquals(words, WordTimings.decode(encoded, originMs = 0))
  }

  @Test
  fun `niente parole niente riga salvata, e una riga rotta si salta`() {
    assertNull(WordTimings.encode(emptyList(), 0))
    assertTrue(WordTimings.decode(null, 0).isEmpty())
    assertTrue(WordTimings.decode("", 0).isEmpty())
    assertEquals(
      listOf(RawWord(0, 10, "buona")),
      WordTimings.decode("rotta\n0,10,buona\n,,\n", 0),
    )
  }

  @Test
  fun `le parole allineate diventano intervalli di caratteri nel paragrafo`() {
    val text = "Fichte nasce nel 1752"
    val spans = WordTimings.spans(
      text,
      listOf(
        WordSource(
          range = 0 until text.length,
          startMs = 0,
          endMs = 4_000,
          words = listOf(
            RawWord(0, 500, "Fichte"),
            RawWord(500, 1_200, "nasce"),
            RawWord(1_200, 1_500, "nel"),
            RawWord(1_500, 2_000, "1752"),
          ),
        ),
      ),
    )
    assertEquals(4, spans.size)
    assertEquals(WordSpan(0, 6, 0, 500), spans[0])
    assertEquals("nasce", text.substring(spans[1].start, spans[1].end))
    assertEquals(WordSpan(17, 21, 1_500, 2_000), spans[3])
  }

  @Test
  fun `quando i conti non tornano si ripiega sulla stima dentro la frase`() {
    val text = "uno due tre"
    val spans = WordTimings.spans(
      text,
      listOf(WordSource(0 until text.length, 0, 900, listOf(RawWord(0, 100, "uno")))),
    )
    assertEquals(3, spans.size)
    assertEquals(0L, spans.first().startMs)
    assertEquals(900L, spans.last().endMs)
    assertEquals("tre", text.substring(spans[2].start, spans[2].end))
  }

  @Test
  fun `due segmenti in un paragrafo restano ognuno nel suo intervallo di caratteri`() {
    val text = "prima frase. seconda frase."
    val first = 0 until 12
    val second = 13 until text.length
    val spans = WordTimings.spans(
      text,
      listOf(
        WordSource(first, 0, 1_000),
        WordSource(second, 2_000, 3_000),
      ),
    )
    assertEquals(4, spans.size)
    assertEquals("prima", text.substring(spans[0].start, spans[0].end))
    assertEquals("seconda", text.substring(spans[2].start, spans[2].end))
    assertEquals(2_000L, spans[2].startMs)
    assertEquals(3_000L, spans[3].endMs)
  }

  @Test
  fun `un intervallo fuori dal testo non fa esplodere niente`() {
    val spans = WordTimings.spans("corto", listOf(WordSource(0 until 500, 0, 10)))
    assertEquals(1, spans.size)
    assertEquals(WordSpan(0, 5, 0, 10), spans.first())
  }
}
