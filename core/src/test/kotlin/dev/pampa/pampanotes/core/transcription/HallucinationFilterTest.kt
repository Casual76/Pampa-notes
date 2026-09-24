package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.audio.ChunkSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le tre difese contro quello che Whisper inventa nei silenzi, con i casi della registrazione vera
 * di venti ore («Napoli 18h») e — altrettanto importanti — i casi in cui non devono toccare niente.
 */
class HallucinationFilterTest {

  private fun seg(startMs: Long, endMs: Long, text: String, words: List<RawWord> = emptyList()) =
    StitchedSegment(startMs, endMs, text, noSpeechProb = null, avgLogProb = null, chunkIndex = 0, words = words)

  /** Una parola ogni secondo, a partire da [startMs]: una per token, come le allinea WhisperX. */
  private fun wordsOf(text: String, startMs: Long): List<RawWord> =
    text.split(" ").mapIndexed { index, word -> RawWord(startMs + index * 1_000L, startMs + index * 1_000L + 900, word) }

  // -----------------------------------------------------------------------------------------------
  // I giri a vuoto
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `un giro a vuoto diventa una sola occorrenza`() {
    val looped = seg(0, 30_000, "18h 18h 18h 18h 18h 18h 18h 18h")
    assertEquals("18h", HallucinationFilter.collapseLoops(looped).text)
  }

  @Test
  fun `la frase prima del giro resta`() {
    val looped = seg(0, 30_000, "Allora oggi parliamo di Napoli, di Napoli, di Napoli, di Napoli, di Napoli.")
    assertEquals("Allora oggi parliamo di Napoli,", HallucinationFilter.collapseLoops(looped).text)
  }

  @Test
  fun `una ripetizione detta davvero non e' un giro`() {
    // Tre «no» sono enfasi; il giro comincia quando la stessa cosa copre almeno sei parole.
    val real = seg(0, 3_000, "No, no, no, non e' cosi'.")
    assertEquals(real, HallucinationFilter.collapseLoops(real))
    val twice = seg(0, 5_000, "e poi e poi e allora")
    assertEquals(twice, HallucinationFilter.collapseLoops(twice))
  }

  @Test
  fun `le parole allineate seguono il testo accorciato`() {
    val text = "Vediamo adesso adesso adesso adesso adesso adesso adesso"
    val collapsed = HallucinationFilter.collapseLoops(seg(0, 8_000, text, wordsOf(text, 0)))

    assertEquals("Vediamo adesso", collapsed.text)
    assertEquals(listOf("Vediamo", "adesso"), collapsed.words.map { it.text })
    // Sono le parole vere, coi loro tempi: la prima «adesso», non l'ultima.
    assertEquals(1_000L, collapsed.words[1].startMs)
    // I tempi del segmento non si toccano: dove sia finito il giro non lo sa nessuno.
    assertEquals(8_000L, collapsed.endMs)
  }

  @Test
  fun `parole che non corrispondono al testo si lasciano stimare`() {
    val text = "di di di di di di di"
    val collapsed = HallucinationFilter.collapseLoops(seg(0, 7_000, text, wordsOf("di di di", 0)))
    assertEquals("di", collapsed.text)
    assertTrue(collapsed.words.isEmpty())
  }

  @Test
  fun `tre segmenti uguali di fila sono un giro, e resta il primo`() {
    val segments = listOf(
      seg(0, 2_000, "Una frase vera."),
      seg(10_000, 14_000, "E questo e' quanto."),
      seg(40_000, 44_000, "E questo e' quanto."),
      seg(70_000, 74_000, "E questo e' quanto."),
      seg(80_000, 82_000, "Ripartiamo."),
    )
    val result = HallucinationFilter.collapseRepeatedSegments(segments)
    assertEquals(listOf("Una frase vera.", "E questo e' quanto.", "Ripartiamo."), result.map { it.text })
    assertEquals(10_000L, result[1].startMs)
  }

  @Test
  fun `due segmenti uguali non sono un giro`() {
    val segments = listOf(seg(0, 2_000, "Esatto, proprio cosi'."), seg(3_000, 5_000, "Esatto, proprio cosi'."))
    assertEquals(segments, HallucinationFilter.collapseRepeatedSegments(segments))
  }

  // -----------------------------------------------------------------------------------------------
  // L'eco del prompt
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `un segmento fatto solo del prompt e' un'eco`() {
    val prompt = HallucinationFilter.tokensOf("Termidoro, Robespierre, Napoli 18h")
    assertTrue(HallucinationFilter.isPromptEcho("18h.", prompt))
    assertTrue(HallucinationFilter.isPromptEcho("Termidoro, Robespierre.", prompt))
  }

  @Test
  fun `una frase che usa il vocabolario non e' un'eco`() {
    val prompt = HallucinationFilter.tokensOf("Termidoro, Robespierre")
    assertFalse(HallucinationFilter.isPromptEcho("Il 9 Termidoro cade Robespierre.", prompt))
    // Anche fatta di sole parole del vocabolario, se e' piu' lunga dell'elenco e' un discorso.
    assertFalse(HallucinationFilter.isPromptEcho("Robespierre Termidoro Robespierre", prompt))
  }

  @Test
  fun `senza prompt non si toglie niente per eco`() {
    val segments = listOf(seg(0, 2_000, "18h"))
    assertEquals(segments, HallucinationFilter.clean(segments, prompt = null))
    assertEquals(segments, HallucinationFilter.clean(segments, prompt = "   "))
  }

  // -----------------------------------------------------------------------------------------------
  // I saluti nel silenzio
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `un grazie isolato nel silenzio se ne va`() {
    val segments = listOf(
      seg(0, 5_000, "E con questo chiudiamo il discorso sulla rivoluzione."),
      seg(600_000, 601_000, "Grazie."),
      seg(1_200_000, 1_201_000, "Buonanotte."),
      seg(1_800_000, 1_805_000, "Riprendiamo da dove eravamo rimasti."),
    )
    val result = HallucinationFilter.dropSilencePhrases(segments)
    assertEquals(listOf(segments[0], segments[3]), result)
  }

  @Test
  fun `un grazie detto in mezzo al discorso resta`() {
    val segments = listOf(
      seg(0, 5_000, "Mi passi il gesso?"),
      seg(5_500, 6_200, "Grazie."),
      seg(6_500, 10_000, "Allora, dicevamo."),
    )
    assertEquals(segments, HallucinationFilter.dropSilencePhrases(segments))
  }

  @Test
  fun `un gruppo di saluti vicini e' isolato come gruppo`() {
    // Nel quarto d'ora muto i «Grazie.» sono vicini fra loro: misurati uno per uno si sarebbero
    // protetti a vicenda.
    val segments = listOf(
      seg(0, 5_000, "Facciamo una pausa."),
      seg(300_000, 301_000, "Grazie."),
      seg(301_500, 302_500, "Grazie mille."),
      seg(303_000, 304_000, "Grazie a tutti."),
      seg(900_000, 905_000, "Eccoci di nuovo."),
    )
    assertEquals(listOf(segments[0], segments[4]), HallucinationFilter.dropSilencePhrases(segments))
  }

  @Test
  fun `in fondo alla registrazione la pausa dopo si misura dalla fine`() {
    val segments = listOf(seg(0, 5_000, "Ultima frase."), seg(20_000, 21_000, "Grazie."))
    // Registrazione lunga 22 s: dopo il «Grazie.» c'e' un secondo solo, non e' isolato.
    assertEquals(segments, HallucinationFilter.dropSilencePhrases(segments, upperMs = 22_000))
    // Lunga un minuto: quaranta secondi di niente dopo.
    assertEquals(listOf(segments[0]), HallucinationFilter.dropSilencePhrases(segments, upperMs = 60_000))
  }

  @Test
  fun `i titoli di coda se ne vanno sempre`() {
    val segments = listOf(
      seg(0, 5_000, "Una frase vera."),
      seg(5_200, 8_000, "Sottotitoli creati dalla comunità Amara.org"),
      seg(8_100, 10_000, "E un'altra frase vera."),
    )
    assertEquals(listOf(segments[0], segments[2]), HallucinationFilter.dropSilencePhrases(segments))
  }

  @Test
  fun `un discorso lungo con dentro un grazie non si tocca`() {
    val speech = seg(60_000, 75_000, "Grazie a tutti per essere venuti oggi, cominciamo subito con la lezione sul Risorgimento.")
    val segments = listOf(speech)
    assertEquals(segments, HallucinationFilter.clean(segments, prompt = "Risorgimento"))
  }

  // -----------------------------------------------------------------------------------------------
  // Tutto insieme, dentro la cucitura
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `la registrazione di Napoli si ripulisce`() {
    val raw = listOf(
      RawSegment(0, 6_000, "Siamo arrivati a Napoli e adesso ceniamo."),
      RawSegment(120_000, 122_000, "18h"),
      RawSegment(300_000, 330_000, "18h 18h 18h 18h 18h 18h 18h 18h 18h"),
      RawSegment(600_000, 601_000, "Grazie."),
      RawSegment(1_560_000, 1_566_000, "Buongiorno, si riparte."),
    )
    val result = TranscriptStitcher.stitch(
      listOf(ChunkTranscript(ChunkSpec(0, 0, 1_600_000), raw)),
      prompt = "Napoli 18h",
    )
    assertEquals(
      listOf("Siamo arrivati a Napoli e adesso ceniamo.", "Buongiorno, si riparte."),
      result.segments.map { it.text },
    )
    assertEquals("Siamo arrivati a Napoli e adesso ceniamo.\n\nBuongiorno, si riparte.", result.text)
  }
}
