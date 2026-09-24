package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.db.SegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {

  private val min = 60_000L
  private val hour = 60 * min

  private fun segment(startMs: Long, endMs: Long, text: String = "una due tre quattro cinque", partId: String = "p1", speaker: String? = null) =
    SegmentEntity(
      transcriptId = "grezza",
      partId = partId,
      indexInPart = 0,
      partStartMs = startMs,
      partEndMs = endMs,
      sessionStartMs = startMs,
      sessionEndMs = endMs,
      text = text,
      speaker = speaker,
    )

  /** Un tratto di parlato: frasi da cinque secondi con un secondo di respiro in mezzo. */
  private fun talk(fromMs: Long, toMs: Long, partId: String = "p1", first: String? = null): List<SegmentEntity> {
    val result = mutableListOf<SegmentEntity>()
    var t = fromMs
    while (t + 5_000 <= toMs) {
      result += segment(t, t + 5_000, if (result.isEmpty() && first != null) first else "una due tre quattro cinque", partId)
      t += 6_000
    }
    return result
  }

  @Test
  fun `diciannove ore con silenzi lunghi fanno un capitolo per conversazione`() {
    val segments = buildList {
      addAll(talk(0, 40 * min, first = "No, certo che no. Poi si vede."))
      // Tre ore di niente.
      addAll(talk(3 * hour + 40 * min, 4 * hour + 20 * min))
      // Tre minuti di pausa: sotto la soglia di una registrazione cosi' lunga (cinque minuti).
      addAll(talk(4 * hour + 23 * min, 5 * hour))
      // Due ore, poi un «pronto?» di mezzo minuto, poi quattro ore.
      addAll(talk(7 * hour, 7 * hour + 30_000))
      addAll(talk(11 * hour, 11 * hour + 30 * min))
      // Sei minuti: sopra la soglia.
      addAll(talk(11 * hour + 36 * min, 12 * hour + 30 * min))
      addAll(talk(18 * hour + 30 * min, 19 * hour))
    }

    val chapters = Chapters.index(segments, totalMs = 19 * hour)

    assertEquals(5, chapters.size)
    assertEquals(listOf(0L, 3 * hour + 40 * min, 11 * hour, 11 * hour + 36 * min, 18 * hour + 30 * min), chapters.map { it.startMs })
    // Il mezzo minuto isolato non e' un capitolo: va col vicino dal lato del silenzio piu' corto
    // (due ore prima, quattro dopo), e il capitolo arriva fin li'.
    assertEquals(7 * hour + 29_000, chapters[1].endMs)
    assertNull(chapters[0].silenceBeforeMs)
    assertEquals(3 * hour + 40 * min - chapters[0].endMs, chapters[1].silenceBeforeMs)
    assertEquals("No, certo che no.", chapters[0].quote)
    assertTrue(chapters.all { it.voices.isEmpty() })
    // Il parlato non conta i silenzi dentro il capitolo: il secondo copre tre ore e mezza, ma si
    // parla per poco piu' di un'ora.
    assertTrue(chapters[1].speechMs in 60 * min..70 * min)
    assertEquals(chapters.map { it.number }, (1..5).toList())
  }

  @Test
  fun `una lezione di un'ora senza silenzi lunghi non ha capitoli`() {
    val segments = talk(0, 20 * min) + talk(21 * min + 30_000, 60 * min)
    assertEquals(1, Chapters.build(segments).size)
    assertTrue(Chapters.index(segments).isEmpty())
    assertEquals(2 * min, Chapters.thresholdFor(hour))
  }

  @Test
  fun `due capitoli non si mostrano, tre si'`() {
    val two = talk(0, 20 * min) + talk(30 * min, 60 * min)
    assertEquals(2, Chapters.build(two).size)
    assertTrue(Chapters.index(two).isEmpty())
    val three = two + talk(70 * min, 90 * min)
    assertEquals(3, Chapters.index(three).size)
  }

  @Test
  fun `i confini fra registrazioni contano solo per il silenzio che portano`() {
    // p1 dura un'ora e si parla fino a 45 min; p2 comincia a 60 e si parla da 62 fino alla fine;
    // p3 comincia a 120 e si parla subito, con una pausa di dieci minuti a 130.
    val segments = talk(0, 45 * min, partId = "p1") +
      talk(62 * min, 120 * min, partId = "p2") +
      talk(120 * min, 130 * min, partId = "p3") +
      talk(140 * min, 150 * min, partId = "p3")

    val chapters = Chapters.index(segments, totalMs = 150 * min)

    assertEquals(3, chapters.size)
    // Diciassette minuti a cavallo fra la prima e la seconda: un confine.
    assertEquals(62 * min, chapters[1].startMs)
    assertEquals(62 * min - chapters[0].endMs, chapters[1].silenceBeforeMs)
    // Fra la seconda e la terza non si tace: lo stesso capitolo.
    assertTrue(chapters[1].endMs in 129 * min..130 * min)
    assertEquals(140 * min, chapters[2].startMs)
  }

  @Test
  fun `oltre il tetto restano i silenzi piu' lunghi`() {
    // Sessanta tratti da due minuti, separati da silenzi di dieci minuti e qualche secondo, sempre
    // piu' lunghi: i venti confini piu' deboli sono i primi.
    val segments = mutableListOf<SegmentEntity>()
    var t = 0L
    val starts = mutableListOf<Long>()
    for (i in 0 until 60) {
      starts += t
      segments += talk(t, t + 2 * min)
      t += 2 * min + 10 * min + (i + 1) * 1_000L
    }

    val chapters = Chapters.index(segments)

    assertEquals(Chapters.MAX_CHAPTERS, chapters.size)
    assertEquals(0L, chapters[0].startMs)
    assertEquals(starts[21], chapters[1].startMs)
    assertEquals(starts[59], chapters.last().startMs)
  }

  @Test
  fun `le voci sono quelle della trascrizione, nell'ordine in cui parlano`() {
    val segments = listOf(
      segment(0, 60_000, "Buongiorno a tutti voi.", speaker = "SPEAKER_01"),
      segment(61_000, 120_000, "Buongiorno.", speaker = "SPEAKER_00"),
      segment(20 * min, 22 * min, "Di nuovo qui, allora.", speaker = "SPEAKER_00"),
      segment(40 * min, 42 * min, "Ultima parte della serata.", speaker = "SPEAKER_01"),
    )
    val chapters = Chapters.index(segments)
    assertEquals(listOf(listOf(1, 2), listOf(2), listOf(1)), chapters.map { it.voices })
    assertEquals(listOf(5, 4, 4), chapters.map { it.words })
  }

  @Test
  fun `la citazione sono le prime parole, e basta`() {
    assertEquals("No, certo che no.", Chapters.quoteOf("No, certo che no. E poi?"))
    assertEquals("Allora oggi parliamo della rivoluzione francese e dei…", Chapters.quoteOf("Allora oggi parliamo della rivoluzione francese e dei suoi antefatti."))
    // Un «Eh.» da solo non e' una citazione: si va avanti fino alla frase dopo.
    assertEquals("Eh. Allora cominciamo.", Chapters.quoteOf("Eh. Allora cominciamo. Dove eravamo?"))
    // Le virgolette dentro sparirebbero fra le virgolette di chi mostra.
    assertEquals("Ha detto proprio così: basta.", Chapters.quoteOf("- Ha detto proprio “così”: basta."))
    assertEquals("Ciao", Chapters.quoteOf("  Ciao "))
    assertEquals("", Chapters.quoteOf("   "))
  }

  @Test
  fun `il capitolo di adesso e' l'ultimo cominciato`() {
    val chapters = Chapters.index(talk(0, 20 * min) + talk(30 * min, 50 * min) + talk(60 * min, 80 * min))
    assertEquals(0, Chapters.currentIndex(chapters, 0))
    assertEquals(0, Chapters.currentIndex(chapters, 25 * min))
    assertEquals(1, Chapters.currentIndex(chapters, 30 * min))
    assertEquals(2, Chapters.currentIndex(chapters, 99 * min))
  }
}
