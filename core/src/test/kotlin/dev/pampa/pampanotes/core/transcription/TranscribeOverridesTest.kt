package dev.pampa.pampanotes.core.transcription

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le impostazioni di una trascrizione sola: cosa cambiano, e cosa lasciano com'e'. */
class TranscribeOverridesTest {

  private val settingsRequest = TranscribeRequest(
    model = "large-v3",
    language = "it",
    prompt = "Termidoro",
    diarize = false,
  )

  @Test
  fun `senza scelte la richiesta resta quella delle impostazioni`() {
    assertTrue(TranscribeOverrides().isEmpty)
    assertTrue(TranscribeOverrides(computer = ComputerOverrides()).isEmpty)
    assertEquals(settingsRequest, TranscribeOverrides().applyTo(settingsRequest))
  }

  @Test
  fun `la lingua scelta vince su quella della nota, e riconosci la toglie`() {
    assertEquals("en", TranscribeOverrides(language = "en").applyTo(settingsRequest).language)
    assertNull(TranscribeOverrides(language = TranscribeOverrides.LANGUAGE_AUTO).applyTo(settingsRequest).language)
    // Una lingua vuota non e' una lingua: il modello la indovina, invece di ricevere «».
    assertNull(TranscribeOverrides(language = "").applyTo(settingsRequest).language)
  }

  @Test
  fun `un vocabolario vuoto vuol dire nessuno, non quello delle impostazioni`() {
    assertNull(TranscribeOverrides(vocabulary = "").applyTo(settingsRequest).prompt)
    assertNull(TranscribeOverrides(vocabulary = "   ").applyTo(settingsRequest).prompt)
    assertEquals("Fichte, Feuerbach", TranscribeOverrides(vocabulary = " Fichte, Feuerbach ").applyTo(settingsRequest).prompt)
    assertEquals("Termidoro", TranscribeOverrides(language = "en").applyTo(settingsRequest).prompt)
  }

  @Test
  fun `le voci si accendono e si spengono per questa volta`() {
    assertTrue(TranscribeOverrides(diarize = true).applyTo(settingsRequest).diarize)
    assertFalse(TranscribeOverrides(diarize = false).applyTo(settingsRequest.copy(diarize = true)).diarize)
    assertTrue(TranscribeOverrides().applyTo(settingsRequest.copy(diarize = true)).diarize)
  }

  @Test
  fun `modello, memoria e lotto arrivano alla richiesta`() {
    val tuned = ComputerOverrides(model = "medium", vramGb = 6.0, batchMax = 4)
    assertEquals(tuned, TranscribeOverrides(computer = tuned).applyTo(settingsRequest).computer)
    assertNull(TranscribeOverrides(computer = ComputerOverrides()).applyTo(settingsRequest).computer)
  }

  @Test
  fun `si salvano e si rileggono dalla riga del lavoro`() {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val choice = TranscribeOverrides(
      language = "fr",
      vocabulary = "",
      diarize = true,
      pieces = Pieces(auto = true),
      computer = ComputerOverrides(model = "medium", vramGb = 6.5, batchMax = 2),
    )
    val back = json.decodeFromString(TranscribeOverrides.serializer(), json.encodeToString(TranscribeOverrides.serializer(), choice))
    assertEquals(choice, back)
    assertEquals("un vocabolario vuoto sopravvive: e' una scelta", "", back.vocabulary)
    // Una riga scritta da una versione che non conosceva i campi nuovi si legge lo stesso.
    assertEquals(TranscribeOverrides(), json.decodeFromString(TranscribeOverrides.serializer(), "{}"))
  }

  @Test
  fun `la memoria si scrive con il punto, qualunque sia la lingua del telefono`() {
    val previous = java.util.Locale.getDefault()
    try {
      java.util.Locale.setDefault(java.util.Locale.ITALY)
      assertEquals("6.5", OpenAiCompatProvider.vramField(6.5))
      assertEquals("12.0", OpenAiCompatProvider.vramField(12.0))
    } finally {
      java.util.Locale.setDefault(previous)
    }
  }
}
