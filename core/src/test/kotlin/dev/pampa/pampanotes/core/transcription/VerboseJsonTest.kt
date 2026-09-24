package dev.pampa.pampanotes.core.transcription

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VerboseJsonTest {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private fun parse(text: String) = VerboseJson.parse(json.parseToJsonElement(text))

  @Test
  fun `una risposta di Groq si legge`() {
    val result = parse(
      """
      {
        "task": "transcribe",
        "language": "italian",
        "duration": 12.44,
        "text": "La rivoluzione francese. Comincio nel 1789.",
        "segments": [
          {"id":0,"seek":0,"start":0.0,"end":3.2,"text":" La rivoluzione francese.","avg_logprob":-0.21,"no_speech_prob":0.02},
          {"id":1,"seek":0,"start":3.4,"end":6.1,"text":" Comincio nel 1789.","avg_logprob":-0.18,"no_speech_prob":0.01}
        ]
      }
      """.trimIndent(),
    )

    assertEquals("italian", result.language)
    assertEquals(12_440L, result.durationMs)
    assertEquals(2, result.segments.size)
    assertEquals(0L, result.segments[0].startMs)
    assertEquals(3_200L, result.segments[0].endMs)
    assertEquals("La rivoluzione francese.", result.segments[0].text)
    assertEquals("La rivoluzione francese. Comincio nel 1789.", result.text)
  }

  @Test
  fun `no_speech_prob nullo o assente e' un non so, e 0 resta 0`() {
    // Il companion mandava 0.0 perche' WhisperX non lo calcola; adesso manda null. Tutti e due si leggono.
    val result = parse(
      """
      {
        "segments": [
          {"start":0.0,"end":1.0,"text":"Uno.","no_speech_prob":null,"avg_logprob":null},
          {"start":1.0,"end":2.0,"text":"Due.","no_speech_prob":0.0},
          {"start":2.0,"end":3.0,"text":"Tre."}
        ]
      }
      """.trimIndent(),
    )

    assertNull(result.segments[0].noSpeechProb)
    assertNull(result.segments[0].avgLogProb)
    assertEquals(0f, result.segments[1].noSpeechProb!!, 0f)
    assertNull(result.segments[2].noSpeechProb)
  }

  @Test
  fun `il testo si ricompone dai segmenti, non dal campo text`() {
    // I due devono raccontare la stessa cosa: se si prendesse il campo `text` grezzo, i segmenti
    // scartati piu' avanti resterebbero comunque nel testo, e il lettore evidenzierebbe la riga
    // sbagliata per tutto il resto della trascrizione.
    val result = parse(
      """
      {
        "text": "Testo che il server ha messo insieme a modo suo",
        "segments": [
          {"start":0.0,"end":1.0,"text":"Uno"},
          {"start":1.0,"end":2.0,"text":"Due"}
        ]
      }
      """.trimIndent(),
    )

    assertEquals("Uno Due", result.text)
  }

  @Test
  fun `una risposta senza segmenti vale comunque il suo testo`() {
    val result = parse("""{"text":"Solo il testo, nessun tempo."}""")

    assertEquals("Solo il testo, nessun tempo.", result.text)
    assertTrue(result.segments.isEmpty())
    assertNull(result.durationMs)
  }

  @Test
  fun `i tempi in stringa si leggono lo stesso`() {
    // Certe versioni di WhisperX serializzano i tempi come stringhe.
    val result = parse("""{"text":"x","segments":[{"start":"1.5","end":"2.25","text":"ciao"}]}""")

    assertEquals(1_500L, result.segments[0].startMs)
    assertEquals(2_250L, result.segments[0].endMs)
  }

  @Test
  fun `i segmenti vuoti si buttano`() {
    val result = parse(
      """{"text":"a","segments":[{"start":0,"end":1,"text":"  "},{"start":1,"end":2,"text":"vero"}]}""",
    )

    assertEquals(1, result.segments.size)
    assertEquals("vero", result.segments[0].text)
  }

  @Test
  fun `una risposta senza testo ne segmenti e' «nessun parlato», non un errore di formato`() {
    assertThrows(TranscriptionError.NoSpeech::class.java) { parse("""{"task":"transcribe"}""") }
  }

  @Test
  fun `il catalogo dei modelli si legge in tutte le forme`() {
    val openAi = json.parseToJsonElement("""{"object":"list","data":[{"id":"whisper-large-v3"},{"id":"llama-3.3"}]}""")
    assertEquals(listOf("whisper-large-v3", "llama-3.3"), VerboseJson.parseModels(openAi))

    val plain = json.parseToJsonElement("""["large-v3","medium"]""")
    assertEquals(listOf("large-v3", "medium"), VerboseJson.parseModels(plain))

    val named = json.parseToJsonElement("""{"models":[{"name":"large-v3"}]}""")
    assertEquals(listOf("large-v3"), VerboseJson.parseModels(named))
  }
}

class ProviderConfigTest {

  @Test
  fun `l'indirizzo del server si normalizza comunque lo si scriva`() {
    val expected = "http://192.168.1.10:8765/v1"
    assertEquals(expected, OpenAiCompatProvider.normalize("192.168.1.10:8765"))
    assertEquals(expected, OpenAiCompatProvider.normalize("http://192.168.1.10:8765"))
    assertEquals(expected, OpenAiCompatProvider.normalize("http://192.168.1.10:8765/"))
    assertEquals(expected, OpenAiCompatProvider.normalize("http://192.168.1.10:8765/v1"))
    assertEquals("https://pc.tailnet.ts.net/v1", OpenAiCompatProvider.normalize("https://pc.tailnet.ts.net"))
  }

  @Test
  fun `fra i modelli di Groq si sceglie il turbo quando c'e'`() {
    assertEquals(
      "whisper-large-v3-turbo",
      GroqWhisperProvider.pickModel(listOf("whisper-large-v3", "whisper-large-v3-turbo")),
    )
    assertEquals("whisper-large-v3", GroqWhisperProvider.pickModel(listOf("whisper-large-v3")))
    // Un catalogo che cambia nomi non deve lasciare l'app senza modello.
    assertEquals("whisper-next", GroqWhisperProvider.pickModel(listOf("llama-4", "whisper-next")))
    assertEquals(null, GroqWhisperProvider.pickModel(listOf("llama-4")))
  }
}
