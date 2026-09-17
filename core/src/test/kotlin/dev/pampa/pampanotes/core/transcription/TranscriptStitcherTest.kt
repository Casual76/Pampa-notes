package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.audio.ChunkSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptStitcherTest {

  private fun segment(startMs: Long, endMs: Long, text: String, noSpeech: Float? = null, logProb: Float? = null) =
    RawSegment(startMs, endMs, text, noSpeech, logProb)

  @Test
  fun `i tempi tornano dove stavano nel file intero`() {
    val chunks = listOf(
      ChunkTranscript(ChunkSpec(0, 0, 65_000), listOf(segment(1_000, 3_000, "Primo pezzo."))),
      ChunkTranscript(ChunkSpec(1, 60_000, 125_000), listOf(segment(5_000, 7_000, "Secondo pezzo."))),
    )

    val result = TranscriptStitcher.stitch(chunks)

    assertEquals(2, result.segments.size)
    assertEquals(1_000L, result.segments[0].startMs)
    // 60s di inizio pezzo + 5s dentro il pezzo.
    assertEquals(65_000L, result.segments[1].startMs)
  }

  @Test
  fun `nella sovrapposizione ogni segmento va a un pezzo solo`() {
    // I due pezzi si sovrappongono fra 60s e 65s, e tutti e due hanno trascritto quella frase.
    val chunks = listOf(
      ChunkTranscript(
        ChunkSpec(0, 0, 65_000),
        listOf(
          segment(50_000, 52_000, "Prima della sovrapposizione."),
          segment(61_000, 63_000, "Dentro la sovrapposizione."),
        ),
      ),
      ChunkTranscript(
        ChunkSpec(1, 60_000, 125_000),
        listOf(
          // Lo stesso pezzo di audio, visto dal secondo ritaglio: 61s assoluti = 1s relativo.
          segment(1_000, 3_000, "Dentro la sovrapposizione."),
          segment(10_000, 12_000, "Dopo la sovrapposizione."),
        ),
      ),
    )

    val result = TranscriptStitcher.stitch(chunks)

    val occurrences = result.segments.count { it.text.contains("Dentro la sovrapposizione") }
    assertEquals("la frase in comune compare $occurrences volte", 1, occurrences)
    assertEquals(3, result.segments.size)
  }

  @Test
  fun `il segmento nella sovrapposizione resta al pezzo che lo contiene per primo`() {
    val chunks = listOf(
      ChunkTranscript(ChunkSpec(0, 0, 65_000), listOf(segment(61_000, 63_000, "A cavallo."))),
      ChunkTranscript(ChunkSpec(1, 60_000, 125_000), listOf(segment(1_000, 3_000, "A cavallo."))),
    )

    val result = TranscriptStitcher.stitch(chunks)

    // Il punto medio e' a 62s, che sta dopo il confine (60s): vince il secondo pezzo.
    assertEquals(1, result.segments.size)
    assertEquals(1, result.segments.first().chunkIndex)
  }

  @Test
  fun `le allucinazioni sul silenzio spariscono`() {
    val chunks = listOf(
      ChunkTranscript(
        ChunkSpec(0, 0, 60_000),
        listOf(
          segment(1_000, 3_000, "Una frase vera.", noSpeech = 0.1f, logProb = -0.3f),
          segment(50_000, 55_000, "Sottotitoli e revisione a cura di QTSS", noSpeech = 0.97f, logProb = -1.8f),
        ),
      ),
    )

    val result = TranscriptStitcher.stitch(chunks)

    assertEquals(1, result.segments.size)
    assertFalse(result.text.contains("Sottotitoli"))
  }

  @Test
  fun `un segmento a bassa confidenza ma con voce resta`() {
    // Solo una delle due condizioni: e' una frase difficile, non un'invenzione.
    val chunks = listOf(
      ChunkTranscript(
        ChunkSpec(0, 0, 60_000),
        listOf(segment(1_000, 3_000, "Parola difficile.", noSpeech = 0.2f, logProb = -1.5f)),
      ),
    )

    assertEquals(1, TranscriptStitcher.stitch(chunks).segments.size)
  }

  @Test
  fun `le parole ripetute al confine si tolgono`() {
    val cleaned = TranscriptStitcher.dropRepeatedPrefix(
      previous = "e quindi l'assemblea costituente decise",
      next = "L'assemblea costituente decise di sciogliersi.",
    )

    assertEquals("di sciogliersi.", cleaned)
  }

  @Test
  fun `una ripetizione lunga non e' una ripetizione di confine`() {
    // Quindici parole uguali non sono un margine di taglio: e' contenuto che si ripete davvero.
    val long = "uno due tre quattro cinque sei sette otto nove dieci undici dodici tredici quattordici quindici"
    assertEquals(long, TranscriptStitcher.dropRepeatedPrefix(long, long))
  }

  @Test
  fun `il confronto ignora punteggiatura e maiuscole`() {
    val cleaned = TranscriptStitcher.dropRepeatedPrefix(
      previous = "la Rivoluzione francese,",
      next = "La rivoluzione Francese comincio' nel 1789.",
    )

    assertEquals("comincio' nel 1789.", cleaned)
  }

  @Test
  fun `una pausa lunga diventa un paragrafo`() {
    val chunks = listOf(
      ChunkTranscript(
        ChunkSpec(0, 0, 60_000),
        listOf(
          segment(0, 2_000, "Prima parte."),
          segment(2_200, 4_000, "Stessa parte."),
          segment(10_000, 12_000, "Nuovo argomento."),
        ),
      ),
    )

    val result = TranscriptStitcher.stitch(chunks)

    assertTrue(result.text.contains("Prima parte. Stessa parte."))
    assertTrue("manca l'a capo:\n${result.text}", result.text.contains("\n\nNuovo argomento."))
  }

  @Test
  fun `nessun pezzo produce un testo vuoto invece di un errore`() {
    val result = TranscriptStitcher.stitch(emptyList())

    assertEquals("", result.text)
    assertTrue(result.segments.isEmpty())
  }
}
