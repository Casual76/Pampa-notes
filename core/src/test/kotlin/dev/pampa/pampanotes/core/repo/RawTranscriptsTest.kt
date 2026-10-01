package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RawTranscriptsTest {

  private fun transcript(id: String, createdAt: Long, kind: TranscriptKind = TranscriptKind.RAW, parentId: String? = null) = TranscriptEntity(
    id = id,
    sessionId = "s1",
    kind = kind,
    provider = "custom",
    model = "large-v3",
    text = "",
    parentId = parentId,
    wordCount = 0,
    createdAt = createdAt,
  )

  @Test
  fun `vale la grezza piu' recente, non la prima dell'elenco`() {
    // L'elenco arriva in ordine di creazione: la prima e' la piu' vecchia, ed e' quella che la
    // schermata mostrava mentre il repository ritrascriveva l'altra.
    val list = listOf(transcript("vecchia", 100), transcript("ripulita", 150, TranscriptKind.REFINED, "vecchia"), transcript("nuova", 200))
    assertEquals("nuova", RawTranscripts.newest(list)?.id)
    assertEquals(null, RawTranscripts.newest(list.filter { it.kind == TranscriptKind.REFINED }))
  }

  @Test
  fun `a pari ora decide l'id, uguale su tutti i dispositivi`() {
    val list = listOf(transcript("b", 100), transcript("a", 100))
    assertEquals("b", RawTranscripts.newest(list)?.id)
    assertEquals("b", RawTranscripts.newest(list.reversed())?.id)
  }

  @Test
  fun `di troppo sono le grezze che la piu' recente copre gia'`() {
    val raws = listOf(transcript("vecchia", 100), transcript("parziale", 150), transcript("nuova", 200))
    val parts = mapOf(
      "vecchia" to setOf("p1", "p2"),
      // Ha una registrazione che la nuova non ha: le sue parole sono le sole di quella parte, e resta.
      "parziale" to setOf("p3"),
      "nuova" to setOf("p1", "p2"),
    )
    assertEquals(listOf("vecchia"), RawTranscripts.redundant(raws, parts).map { it.id })
  }

  @Test
  fun `una grezza sola non ha niente di troppo`() {
    assertEquals(emptyList<TranscriptEntity>(), RawTranscripts.redundant(listOf(transcript("sola", 100)), mapOf("sola" to setOf("p1"))))
  }
}
