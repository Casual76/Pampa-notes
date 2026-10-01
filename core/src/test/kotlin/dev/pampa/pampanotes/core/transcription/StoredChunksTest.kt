package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.audio.ChunkSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredChunksTest {

  private val groq = StoredChunks.signature(
    GroqWhisperProvider.ID,
    TranscriptionCapabilities(maxUploadBytes = 25L * 1024 * 1024, supportsSegments = true, needsChunking = true),
    groqChunkMinutes = 10,
  )
  private val computer30 = StoredChunks.signature(
    OpenAiCompatProvider.ID,
    TranscriptionCapabilities(maxUploadBytes = null, supportsSegments = true, needsChunking = true, maxChunkMinutes = 30),
    groqChunkMinutes = 10,
  )

  private fun chunk(spec: ChunkSpec, text: String) = ChunkTranscript(spec, listOf(RawSegment(1_000, 4_000, text, words = listOf(RawWord(1_000, 1_500, text)))))

  @Test
  fun `lo stesso pezzo dello stesso piano si rilegge com'era`() {
    val spec = ChunkSpec(1, 600_000, 1_205_000)
    val stored = StoredChunks.encode(chunk(spec, "Kant."), groq)

    val read = StoredChunks.decode(stored, spec, groq)

    assertEquals(chunk(spec, "Kant."), read)
  }

  @Test
  fun `il pezzo 0 di un altro piano non riempie il pezzo 0 di questo`() {
    // Groq a dieci minuti, poi «Riprova» sul computer con un tetto di trenta: stesso id, stessa
    // cartella. Il vecchio 0–10 al posto del nuovo 0–30 faceva sparire venti minuti di lezione.
    val old = StoredChunks.encode(chunk(ChunkSpec(0, 0, 605_000), "Primi dieci minuti."), groq)

    assertNull(StoredChunks.decode(old, ChunkSpec(0, 0, 1_805_000), groq))
    assertNull(StoredChunks.decode(old, ChunkSpec(0, 0, 605_000), computer30))
  }

  @Test
  fun `un pezzo senza piano viene da una versione di prima e si rifa'`() {
    val legacy = """{"index":0,"startMs":0,"endMs":605000,"segments":[{"startMs":0,"endMs":1000,"text":"Vecchio."}]}"""
    assertNull(StoredChunks.decode(legacy, ChunkSpec(0, 0, 605_000), groq))
    assertNull(StoredChunks.decode("non e' json", ChunkSpec(0, 0, 605_000), groq))
  }

  @Test
  fun `il piano cambia col servizio e col tetto, non con il resto`() {
    assertNotEquals(groq, computer30)
    val computer60 = StoredChunks.signature(
      OpenAiCompatProvider.ID,
      TranscriptionCapabilities(maxUploadBytes = null, supportsSegments = true, needsChunking = true, maxChunkMinutes = 60),
      groqChunkMinutes = 10,
    )
    assertNotEquals(computer30, computer60)
    // Senza pezzi (il computer senza tetto) i minuti di Groq non contano.
    val whole = TranscriptionCapabilities(maxUploadBytes = null, supportsSegments = true, needsChunking = false)
    assertEquals(
      StoredChunks.signature(OpenAiCompatProvider.ID, whole, groqChunkMinutes = 10),
      StoredChunks.signature(OpenAiCompatProvider.ID, whole, groqChunkMinutes = 15),
    )
  }

  @Test
  fun `il piano dei tagli si rilegge`() {
    val specs = listOf(ChunkSpec(0, 0, 605_000), ChunkSpec(1, 600_000, 1_200_000))
    assertEquals(specs, StoredChunks.decodePlan(StoredChunks.encodePlan(specs)))
    assertNull(StoredChunks.decodePlan("[]"))
  }
}
