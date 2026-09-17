package dev.pampa.pampanotes.core.refinement

import dev.pampa.pampanotes.core.settings.RefinementPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefinementTest {

  // -----------------------------------------------------------------------------------------------
  // Il prompt
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `la guardia c'e' sempre, qualunque preset`() {
    // E' l'unica cosa che impedisce al modello di fare quello che sa fare meglio: rispondere.
    RefinementPreset.entries.forEach { preset ->
      val system = RefinementPrompts.system(preset, customPrompt = "fai come ti pare")
      assertTrue(preset.name, system.startsWith(RefinementPrompts.GUARD))
      assertTrue(preset.name, system.contains("NON riassumere"))
      assertTrue(preset.name, system.contains("NON aggiungere fatti"))
      assertTrue(preset.name, system.contains("NON tradurre"))
    }
  }

  @Test
  fun `un preset personalizzato vuoto ricade sulla pulizia`() {
    // Un campo lasciato in bianco non deve diventare "nessuna istruzione", che per un modello vuol
    // dire "fai quello che vuoi con questo testo".
    assertEquals(
      RefinementPrompts.system(RefinementPreset.CLEAN),
      RefinementPrompts.system(RefinementPreset.CUSTOM, customPrompt = "   "),
    )
  }

  @Test
  fun `il preset personalizzato viene dopo la guardia, non al posto suo`() {
    val system = RefinementPrompts.system(RefinementPreset.CUSTOM, customPrompt = "scrivi in rima")

    assertTrue(system.indexOf("NON riassumere") < system.indexOf("scrivi in rima"))
  }

  @Test
  fun `il pezzo precedente entra come contesto e si dice di non ripeterlo`() {
    val message = RefinementPrompts.user("Seconda parte.", "…finiva così.")

    assertTrue(message.contains("NON ripeterlo"))
    assertTrue(message.contains("…finiva così."))
    assertTrue(message.endsWith("Seconda parte."))
  }

  @Test
  fun `senza pezzo precedente si manda il testo e basta`() {
    assertEquals("Il testo.", RefinementPrompts.user("Il testo.", null))
    assertEquals("Il testo.", RefinementPrompts.user("Il testo.", "   "))
  }

  // -----------------------------------------------------------------------------------------------
  // I pezzi
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `un testo corto resta un pezzo solo`() {
    val chunks = TextChunker.split("Poche parole in croce.")

    assertEquals(1, chunks.size)
    assertEquals("Poche parole in croce.", chunks.single().text)
  }

  @Test
  fun `si taglia ai paragrafi e nessun pezzo supera il tetto`() {
    val paragraph = (1..50).joinToString(" ") { "parola$it" }
    val text = (1..10).joinToString("\n\n") { paragraph }

    val chunks = TextChunker.split(text, maxWords = 120)

    assertTrue(chunks.size > 1)
    chunks.forEach { assertTrue("${it.words} parole", it.words <= 120) }
    // Niente si perde per strada: e' il difetto che un taglio sbagliato produce in silenzio.
    assertEquals(500, chunks.sumOf { it.words })
  }

  @Test
  fun `un paragrafo piu' lungo del tetto si taglia per frasi`() {
    val sentence = (1..30).joinToString(" ") { "parola$it" } + "."
    val text = (1..6).joinToString(" ") { sentence }

    val chunks = TextChunker.split(text, maxWords = 70)

    assertTrue(chunks.size > 1)
    chunks.forEach { assertTrue("${it.words} parole", it.words <= 70) }
    // Ogni pezzo comincia a inizio frase, non a meta': un frammento senza soggetto il modello lo
    // completa da solo, ed e' esattamente quello che non deve fare.
    chunks.forEach { assertTrue(it.text.startsWith("parola1")) }
  }

  @Test
  fun `la coda di un pezzo sono le sue ultime parole`() {
    val text = (1..100).joinToString(" ") { "parola$it" }

    val tail = TextChunker.tailOf(text, words = 5)

    assertEquals("parola96 parola97 parola98 parola99 parola100", tail)
  }

  @Test
  fun `il conto delle parole ignora gli spazi in piu'`() {
    assertEquals(3, TextChunker.countWords("  una   due\n\ntre  "))
    assertEquals(0, TextChunker.countWords("   "))
  }

  @Test
  fun `un testo vuoto non produce pezzi`() {
    assertTrue(TextChunker.split("").isEmpty())
    assertTrue(TextChunker.split("   \n\n  ").isEmpty())
  }

  // -----------------------------------------------------------------------------------------------
  // La ripulitura di quello che il modello aggiunge lo stesso
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `il blocco di codice intorno al testo se ne va`() {
    val service = RefinementService()

    assertEquals("Il testo vero.", service.cleanUp("```markdown\nIl testo vero.\n```"))
    assertEquals("Il testo vero.", service.cleanUp("```\nIl testo vero.\n```"))
  }

  @Test
  fun `la frase di servizio in apertura se ne va`() {
    val service = RefinementService()

    assertEquals(
      "Buongiorno a tutti, oggi parliamo del trattato.",
      service.cleanUp("Ecco il testo ripulito:\n\nBuongiorno a tutti, oggi parliamo del trattato."),
    )
  }

  @Test
  fun `un primo paragrafo vero non viene scambiato per un preambolo`() {
    val service = RefinementService()
    // Corto e con dentro "ecco", ma e' il testo: senza il controllo sulle parole di servizio, un
    // taglio qui perderebbe la prima frase della lezione.
    val text = "Ecco, allora, ricominciamo da dove eravamo.\n\nIl trattato di Tordesillas."

    assertEquals(text, service.cleanUp(text))
  }

  // -----------------------------------------------------------------------------------------------
  // Il modello
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `si preferisce gpt-oss-120b quando c'e'`() {
    val available = listOf("llama-3.1-8b-instant", "openai/gpt-oss-120b", "whisper-large-v3")

    assertEquals("openai/gpt-oss-120b", RefinementService.pickModel(available))
  }

  @Test
  fun `senza nessuno dei preferiti si prende il primo che non trascrive`() {
    val available = listOf("whisper-large-v3", "qualche-modello-nuovo")

    assertEquals("qualche-modello-nuovo", RefinementService.pickModel(available))
    assertNull(RefinementService.pickModel(listOf("whisper-large-v3-turbo")))
    assertNull(RefinementService.pickModel(emptyList()))
  }

  // -----------------------------------------------------------------------------------------------
  // Il sospetto
  // -----------------------------------------------------------------------------------------------

  @Test
  fun `un testo dimezzato o gonfiato si segnala`() {
    fun result(ratio: Float, truncated: Boolean = false) = RefinementResult(
      text = "x",
      model = "m",
      preset = RefinementPreset.CLEAN,
      wordRatio = ratio,
      truncated = truncated,
    )

    assertFalse(result(1.0f).suspicious)
    assertFalse(result(0.75f).suspicious)
    // Sotto il 60%: ha riassunto, o la risposta si e' fermata a meta'.
    assertTrue(result(0.4f).suspicious)
    // Sopra il 130%: ha aggiunto roba sua.
    assertTrue(result(1.6f).suspicious)
    // Il troncamento si sa dal provider e vale di per se', qualunque sia il rapporto.
    assertTrue(result(1.0f, truncated = true).suspicious)
  }
}
