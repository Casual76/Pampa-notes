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
  fun `selected older RAW and refinement parent keep their own timing source`() {
    val list = listOf(transcript("author", 100), transcript("local", 200), transcript("refined", 300, TranscriptKind.REFINED, "author"))
    assertEquals("author", RawTranscripts.forSelection(list, "author")?.id)
    assertEquals("author", RawTranscripts.forSelection(list, "refined")?.id)
    assertEquals("local", RawTranscripts.forSelection(list, null)?.id)
    assertEquals("local", RawTranscripts.forSelection(list, "missing")?.id)
  }

  @Test
  fun `missing or unrelated refinement parent never borrows another RAW timing`() {
    val selected = transcript("refined", 300, TranscriptKind.REFINED, "other")
    assertEquals(null, RawTranscripts.forSelection(listOf(transcript("local", 200), selected), selected.id))
    assertEquals(null, RawTranscripts.forSelection(listOf(transcript("other", 100).copy(sessionId = "elsewhere"), selected), selected.id))
  }

}
