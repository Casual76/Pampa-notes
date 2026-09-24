package dev.pampa.pampanotes.core.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptionPromptTest {

  @Test
  fun `il prompt e' il vocabolario e basta`() {
    // Niente titolo della nota ne' della sessione: «Napoli 18h» nel prompt diventava 407 segmenti «18h».
    assertEquals("Termidoro, Robespierre", TranscriptionPrompt.of("  Termidoro, Robespierre \n"))
  }

  @Test
  fun `senza vocabolario non si manda niente`() {
    assertNull(TranscriptionPrompt.of(""))
    assertNull(TranscriptionPrompt.of("   "))
    assertNull(TranscriptionPrompt.of(null))
  }

  @Test
  fun `un vocabolario lungo si taglia al limite di Whisper`() {
    val long = "parola ".repeat(500)
    assertEquals(GroqWhisperProvider.PROMPT_MAX_CHARS, TranscriptionPrompt.of(long)!!.length)
  }
}
