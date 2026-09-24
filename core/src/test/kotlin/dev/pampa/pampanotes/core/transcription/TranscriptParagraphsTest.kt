package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.db.SegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptParagraphsTest {

  private fun segment(startMs: Long, endMs: Long, text: String, partId: String = "p1") = SegmentEntity(
    transcriptId = "grezza",
    partId = partId,
    indexInPart = 0,
    partStartMs = startMs,
    partEndMs = endMs,
    sessionStartMs = startMs,
    sessionEndMs = endMs,
    text = text,
  )

  @Test
  fun `una pausa breve va a capo, una lunga si dice`() {
    val paragraphs = TranscriptParagraphs.split(
      listOf(
        segment(0, 2_000, "Prima."),
        segment(2_200, 4_000, "Stessa frase."),
        segment(10_000, 12_000, "Dopo un respiro."),
        // Sedici minuti di niente: 12 s -> 16 min 12 s.
        segment(972_000, 975_000, "Dopo un quarto d'ora."),
      ),
    )

    assertEquals(3, paragraphs.size)
    assertNull("il primo paragrafo non ha niente prima", paragraphs[0].silenceBeforeMs)
    assertNull("sei secondi sono un a capo, non un silenzio", paragraphs[1].silenceBeforeMs)
    assertEquals(960_000L, paragraphs[2].silenceBeforeMs)
  }

  @Test
  fun `il silenzio conta anche a cavallo fra due registrazioni`() {
    val paragraphs = TranscriptParagraphs.split(
      listOf(segment(0, 2_000, "Fine della prima."), segment(92_000, 95_000, "Inizio della seconda.", partId = "p2")),
    )
    assertEquals(90_000L, paragraphs[1].silenceBeforeMs)
  }

  @Test
  fun `un paragrafo spezzato per lunghezza non ha silenzio davanti`() {
    val segments = (0 until 4).map { segment(it * 1_000L, it * 1_000L + 900, "Frase $it.") }
    val paragraphs = TranscriptParagraphs.split(segments, maxSegments = 2)
    assertEquals(2, paragraphs.size)
    assertNull(paragraphs[1].silenceBeforeMs)
  }

  @Test
  fun `gli intervalli dei segmenti puntano dentro il testo del paragrafo`() {
    val paragraph = TranscriptParagraphs.split(listOf(segment(0, 1_000, "  Ciao. "), segment(1_100, 2_000, "Come va?"))).single()

    assertEquals("Ciao. Come va?", paragraph.text)
    assertEquals("Ciao.", paragraph.text.substring(paragraph.ranges[0]))
    assertEquals("Come va?", paragraph.text.substring(paragraph.ranges[1]))
  }

  @Test
  fun `la durata di un silenzio si legge a colpo d'occhio`() {
    assertEquals("1 min", TranscriptParagraphs.silenceDuration(60_000))
    assertEquals("16 min", TranscriptParagraphs.silenceDuration(16 * 60_000 + 12_000))
    assertEquals("2 h", TranscriptParagraphs.silenceDuration(2 * 3_600_000))
    assertEquals("1 h 20 min", TranscriptParagraphs.silenceDuration(80 * 60_000))
    assertEquals("1 h 20 m", TranscriptParagraphs.silenceDuration(80 * 60_000, hours = "h", minutes = "m"))
  }
}
