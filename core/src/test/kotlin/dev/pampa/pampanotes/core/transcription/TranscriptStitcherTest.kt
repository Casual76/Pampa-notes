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
  fun `il segmento nella sovrapposizione va al pezzo dalla parte del suo punto medio`() {
    val chunks = listOf(
      ChunkTranscript(ChunkSpec(0, 0, 65_000), listOf(segment(61_000, 63_000, "A cavallo."))),
      ChunkTranscript(ChunkSpec(1, 60_000, 125_000), listOf(segment(1_000, 3_000, "A cavallo."))),
    )

    val result = TranscriptStitcher.stitch(chunks)

    // Il confine sta a meta' della sovrapposizione (62,5 s) e il punto medio e' a 62 s: vince il primo.
    assertEquals(1, result.segments.size)
    assertEquals(0, result.segments.first().chunkIndex)
  }

  @Test
  fun `il confine sta a meta' della sovrapposizione`() {
    assertEquals(62_500L, TranscriptStitcher.seam(ChunkSpec(0, 0, 65_000), ChunkSpec(1, 60_000, 125_000)))
    // Senza sovrapposizione e' l'inizio del secondo, come sempre.
    assertEquals(60_000L, TranscriptStitcher.seam(ChunkSpec(0, 0, 60_000), ChunkSpec(1, 60_000, 120_000)))
  }

  @Test
  fun `una frase a cavallo del taglio resta intera, senza doppioni`() {
    // Il taglio e' a 600 s, il primo pezzo va avanti fino a 605 s (`ChunkPlanner`). La frase va da
    // 597 s a 605 s: il primo pezzo l'ha sentita tutta, il secondo solo dal taglio in poi. Col confine
    // al taglio il punto medio (601 s) la dava al secondo, che aveva solo la coda: tre secondi persi.
    val chunks = listOf(
      ChunkTranscript(
        ChunkSpec(0, 0, 605_000),
        listOf(
          segment(590_000, 596_000, "Prima del taglio."),
          segment(597_000, 605_000, "e allora Kant scrive la critica della ragion pura"),
        ),
      ),
      ChunkTranscript(
        ChunkSpec(1, 600_000, 1_200_000),
        listOf(
          // Lo stesso tratto visto dal secondo: solo quello che viene dopo il taglio.
          segment(0, 5_000, "la critica della ragion pura"),
          segment(8_000, 12_000, "nel milleottocentottantuno."),
        ),
      ),
    )

    val result = TranscriptStitcher.stitch(chunks)

    assertEquals(
      listOf("Prima del taglio.", "e allora Kant scrive la critica della ragion pura", "nel milleottocentottantuno."),
      result.segments.map { it.text },
    )
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
  fun `senza no_speech_prob non si decide niente`() {
    // WhisperX non lo calcola: il companion manda null (prima 0.0). «Non lo so» non toglie niente.
    assertFalse(TranscriptStitcher.isHallucination(segment(0, 1_000, "Sottotitoli", noSpeech = null, logProb = -3f)))
    assertFalse(TranscriptStitcher.isHallucination(segment(0, 1_000, "Frase", noSpeech = 0f, logProb = -3f)))
  }

  @Test
  fun `al confine le parole tolte si portano via i loro tempi`() {
    fun words(text: String, startMs: Long) =
      text.split(" ").mapIndexed { index, word -> RawWord(startMs + index * 500L, startMs + index * 500L + 400, word) }
    val nextText = "costituente decise di sciogliersi."
    val chunks = listOf(
      ChunkTranscript(
        ChunkSpec(0, 0, 65_000),
        listOf(RawSegment(55_000, 59_000, "e quindi l'assemblea costituente decise", words = words("e quindi l'assemblea costituente decise", 55_000))),
      ),
      ChunkTranscript(
        ChunkSpec(1, 60_000, 125_000),
        // Dopo il confine (a meta' della sovrapposizione, 62,5 s): e' il pezzo dopo che lo dice.
        listOf(RawSegment(2_000, 5_000, nextText, words = words(nextText, 2_000))),
      ),
    )

    val second = TranscriptStitcher.stitch(chunks).segments[1]

    assertEquals("di sciogliersi.", second.text)
    // Una parola per token, e sono quelle giuste: «di» cominciava a 60 s + 3 s.
    assertEquals(listOf("di", "sciogliersi."), second.words.map { it.text })
    assertEquals(63_000L, second.words.first().startMs)
  }

  @Test
  fun `dal computer le eco del vocabolario restano, i giri e i titoli di coda no`() {
    // Il computer le ha gia' giudicate sentendo l'audio: «Fichte.» detto due volte di fila e' una
    // risposta vera che lui ha tenuto, e qui la regola sul solo testo la toglieva.
    val chunk = ChunkTranscript(
      ChunkSpec(0, 0, 3_600_000),
      listOf(
        RawSegment(0, 4_000, "Chi ha scritto la Dottrina della scienza?"),
        RawSegment(4_500, 5_000, "Fichte."),
        RawSegment(5_200, 5_700, "Fichte."),
        RawSegment(6_000, 13_000, "Allora allora allora allora allora allora allora"),
        RawSegment(600_000, 603_000, "Sottotitoli creati dalla comunità Amara.org"),
      ),
    )

    val fromComputer = TranscriptStitcher.stitchFromComputer(chunk)
    val onPhone = TranscriptStitcher.stitch(listOf(chunk), prompt = "Fichte")

    assertEquals(
      listOf("Chi ha scritto la Dottrina della scienza?", "Fichte.", "Fichte.", "Allora"),
      fromComputer.segments.map { it.text },
    )
    // Sulla strada del telefono (Groq) la regola di sempre: le due eco uguali se ne vanno.
    assertEquals(listOf("Chi ha scritto la Dottrina della scienza?", "Allora"), onPhone.segments.map { it.text })
  }

  @Test
  fun `il computer di casa, un pezzo solo, passa dalle stesse difese`() {
    val text = "Allora allora allora allora allora allora allora"
    val words = text.split(" ").mapIndexed { index, word -> RawWord(index * 1_000L, index * 1_000L + 800, word) }
    val result = TranscriptStitcher.stitch(
      listOf(
        ChunkTranscript(
          ChunkSpec(0, 0, 3_600_000),
          listOf(
            RawSegment(0, 7_000, text, noSpeechProb = null, words = words),
            RawSegment(600_000, 601_000, "Grazie.", noSpeechProb = null),
            RawSegment(1_200_000, 1_205_000, "Riprendiamo.", noSpeechProb = null),
          ),
        ),
      ),
    )

    assertEquals(listOf("Allora", "Riprendiamo."), result.segments.map { it.text })
    assertEquals(1, result.segments.first().words.size)
  }

  @Test
  fun `nessun pezzo produce un testo vuoto invece di un errore`() {
    val result = TranscriptStitcher.stitch(emptyList())

    assertEquals("", result.text)
    assertTrue(result.segments.isEmpty())
  }
}
