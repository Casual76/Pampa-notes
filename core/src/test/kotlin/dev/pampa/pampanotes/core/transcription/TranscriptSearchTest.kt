package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.db.SegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptSearchTest {

  private fun matched(blocks: List<String>, query: String): List<String> =
    TranscriptSearch.find(blocks, query).map { blocks[it.block].substring(it.start, it.end) }

  @Test
  fun `maiuscole e accenti non contano`() {
    val blocks = listOf("Perché la Città? perche no, CITTA vecchia.")
    assertEquals(listOf("Perché", "perche"), matched(blocks, "PERCHE"))
    assertEquals(listOf("Città", "CITTA"), matched(blocks, "città"))
  }

  @Test
  fun `le posizioni restano quelle del testo originale anche con gli accenti scomposti`() {
    // «é» scritta come «e» + accento combinante: due caratteri nell'originale, uno nel confronto.
    val decomposed = "Cafe\u0301 e poi cafe"
    val matches = TranscriptSearch.find(listOf(decomposed), "café")
    assertEquals(2, matches.size)
    assertEquals("Cafe\u0301", decomposed.substring(matches[0].start, matches[0].end))
    assertEquals("cafe", decomposed.substring(matches[1].start, matches[1].end))
  }

  @Test
  fun `piu' occorrenze nello stesso blocco, in ordine, e i blocchi in ordine`() {
    val blocks = listOf("Kant e ancora Kant.", "Niente qui.", "kant, infine.")
    val matches = TranscriptSearch.find(blocks, "kant")
    assertEquals(listOf(0, 0, 2), matches.map { it.block })
    assertEquals(listOf(0, 14, 0), matches.map { it.start })
    assertTrue(matches.zipWithNext().all { (a, b) -> a.block < b.block || (a.block == b.block && a.end <= b.start) })
  }

  @Test
  fun `le occorrenze non si sovrappongono`() {
    assertEquals(2, TranscriptSearch.find(listOf("aaaa"), "aa").size)
  }

  @Test
  fun `apostrofi tipografici e spazi in piu' nella ricerca`() {
    assertEquals(listOf("l’uomo"), matched(listOf("Poi l’uomo disse"), "l'uomo"))
    assertEquals(listOf("la  guerra"), matched(listOf("Dopo la  guerra"), "  la guerra "))
    // Gli spazi in fila valgono uno, da tutte e due le parti.
    assertEquals(listOf("la guerra"), matched(listOf("la guerra"), "la   guerra"))
  }

  @Test
  fun `una ricerca troppo corta non trova niente`() {
    assertEquals(emptyList<TranscriptSearch.Match>(), TranscriptSearch.find(listOf("a b c"), "a"))
    assertEquals(emptyList<TranscriptSearch.Match>(), TranscriptSearch.find(listOf("a b c"), "   "))
  }

  @Test
  fun `il momento e' quello della parola, o l'inizio del segmento se le parole non ci sono`() {
    val withWords = SegmentEntity(
      transcriptId = "t", partId = "p", indexInPart = 0,
      partStartMs = 10_000, partEndMs = 14_000, sessionStartMs = 10_000, sessionEndMs = 14_000,
      text = "Oggi parliamo di Kant",
      wordsJson = WordTimings.encode(
        listOf(
          RawWord(10_000, 10_500, "Oggi"),
          RawWord(10_500, 11_200, "parliamo"),
          RawWord(11_200, 11_400, "di"),
          RawWord(12_000, 12_600, "Kant"),
        ),
        originMs = 10_000,
      ),
    )
    val withoutWords = withWords.copy(
      partStartMs = 20_000, partEndMs = 22_000, sessionStartMs = 20_000, sessionEndMs = 22_000,
      text = "E poi Hegel", wordsJson = null,
    )
    val paragraph = TranscriptParagraphs.Paragraph(listOf(withWords, withoutWords))
    val kant = paragraph.text.indexOf("Kant")
    assertEquals(12_000L, TranscriptSearch.timeOf(paragraph, kant))
    val hegel = paragraph.text.indexOf("Hegel")
    val hegelMs = TranscriptSearch.timeOf(paragraph, hegel)
    // Senza parole salvate si stimano, dentro il segmento giusto: mai prima del suo inizio.
    assertTrue(hegelMs in 20_000L..22_000L)
  }

  @Test
  fun `la raffinata si cerca come testo semplice, senza i segni del Markdown`() {
    val blocks = TranscriptSearch.plainBlocks("## Titolo\n\nUn **punto** importante e _uno_ no.\n\n- primo\n- secondo\n\n\n")
    assertEquals(listOf("Titolo", "Un punto importante e uno no.", "• primo\n• secondo"), blocks)
    assertEquals(listOf("punto"), matched(blocks, "punto"))
  }

  @Test
  fun `una trascrizione lunga si prepara e si cerca in fretta`() {
    val paragraph = "Allora, oggi riprendiamo il discorso sulla città e sulla società. "
    val blocks = List(15_000) { paragraph } // circa un milione di caratteri, diciannove ore
    val started = System.nanoTime()
    val index = TranscriptSearch.Index(blocks)
    val matches = TranscriptSearch.find(index, "societa")
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    assertEquals(15_000, matches.size)
    assertTrue("ci ha messo $elapsedMs ms", elapsedMs < 5_000)
  }

  // --- La ricerca che salta al minuto -----------------------------------------------------------

  private fun segment(startMs: Long, endMs: Long, text: String, words: List<RawWord>? = null, partId: String = "p") = SegmentEntity(
    transcriptId = "t", partId = partId, indexInPart = 0,
    partStartMs = startMs, partEndMs = endMs, sessionStartMs = startMs, sessionEndMs = endMs,
    text = text,
    wordsJson = words?.let { WordTimings.encode(it, originMs = startMs) },
  )

  @Test
  fun `il primo momento e' quello della parola, con lo stesso confronto della sessione`() {
    val segments = listOf(
      segment(0, 4_000, "Buongiorno a tutti."),
      // Dieci secondi di pausa: un altro paragrafo.
      segment(14_000, 18_000, "Oggi parliamo della Città di Dio", listOf(
        RawWord(14_000, 14_400, "Oggi"),
        RawWord(14_400, 15_000, "parliamo"),
        RawWord(15_000, 15_300, "della"),
        RawWord(15_300, 16_100, "Città"),
        RawWord(16_100, 16_300, "di"),
        RawWord(16_300, 16_900, "Dio"),
      )),
      segment(20_000, 22_000, "e poi ancora della citta", null),
    )
    val moment = TranscriptSearch.firstMoment(segments, "CITTA")
    assertEquals(TranscriptSearch.Moment(15_300L, "CITTA"), moment)
  }

  @Test
  fun `se la frase non c'e' vale la prima parola detta, che diventa quella da evidenziare`() {
    // FTS4 trova «kant critica» anche con le due parole lontane: qui la frase non c'e'.
    val segments = listOf(
      segment(0, 3_000, "La critica della ragion pura"),
      segment(10_000, 13_000, "fu scritta da Kant"),
    )
    val paragraphs = TranscriptParagraphs.split(segments, TranscriptParagraphs.MAX_SEGMENTS_ON_SCREEN)
    val hit = TranscriptSearch.firstHit(paragraphs, "kant critica")
    assertEquals(0, hit!!.paragraph)
    assertEquals("critica", hit.query)
    assertEquals(paragraphs[0].text.indexOf("critica"), hit.offset)
    // Dentro lo stesso paragrafo vince la parola che viene prima.
    val same = TranscriptParagraphs.split(listOf(segment(0, 3_000, "Kant e la critica")), 10)
    assertEquals("kant", TranscriptSearch.firstHit(same, "critica* kant")!!.query)
    // La frase intera, quando c'e', vince anche su una parola detta prima.
    val phrase = TranscriptParagraphs.split(
      listOf(segment(0, 3_000, "Kant nacque a Konigsberg."), segment(10_000, 13_000, "La critica di Kant e' difficile")),
      10,
    )
    assertEquals(TranscriptSearch.FirstHit(1, phrase[1].text.indexOf("critica di"), "critica di"), TranscriptSearch.firstHit(phrase, "critica di"))
  }

  @Test
  fun `niente da trovare, niente momento`() {
    val segments = listOf(segment(0, 3_000, "Solo Hegel qui"))
    assertEquals(null, TranscriptSearch.firstMoment(segments, "Kant"))
    assertEquals(null, TranscriptSearch.firstMoment(emptyList(), "Kant"))
    // Le parole di una lettera sola non si cercano: «a» sarebbe dappertutto.
    assertEquals(null, TranscriptSearch.firstMoment(segments, "a"))
  }

  @Test
  fun `le parole di una ricerca sono quelle di FTS4`() {
    assertEquals(listOf("kant", "critica"), TranscriptSearch.searchTerms("\"kant\"* -critica  a"))
    assertEquals(listOf("Città"), TranscriptSearch.searchTerms("Città citta"))
  }

  @Test
  fun `arrivati nella sessione, la corrente e' l'occorrenza del momento`() {
    val segments = listOf(
      segment(0, 3_000, "Kant all'inizio"),
      segment(10_000, 13_000, "poi ancora Kant", listOf(RawWord(10_000, 10_500, "poi"), RawWord(10_500, 11_000, "ancora"), RawWord(11_500, 12_000, "Kant"))),
      segment(20_000, 23_000, "e Kant alla fine"),
    )
    val paragraphs = TranscriptParagraphs.split(segments, TranscriptParagraphs.MAX_SEGMENTS_ON_SCREEN)
    val matches = TranscriptSearch.find(paragraphs.map { it.text }, "kant")
    assertEquals(3, matches.size)
    assertEquals(1, TranscriptSearch.matchAt(paragraphs, matches, 11_500))
    assertEquals(0, TranscriptSearch.matchAt(paragraphs, matches, 0))
    // Un momento dopo tutte: l'ultima, la piu' vicina.
    assertEquals(2, TranscriptSearch.matchAt(paragraphs, matches, 60_000))
    assertEquals(0, TranscriptSearch.matchAt(paragraphs, emptyList(), 5_000))
  }
}
