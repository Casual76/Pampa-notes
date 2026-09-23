package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.SessionEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Una sessione sul filo, col segno «in trascrizione su» dalla versione 8 del database. */
class SessionPayloadTest {

  private val session = SessionEntity(
    id = "s",
    noteId = "n",
    title = "Lezione 7",
    date = "2026-09-23",
    position = 0,
    activeTranscriptId = null,
    createdAt = 1,
    updatedAt = 2,
  )

  private fun encode(value: SessionEntity) = SyncPayloads.encode(SessionEntity.serializer(), value, value.updatedAt)

  @Test
  fun `il segno va e torna col resto della sessione`() {
    val marked = session.copy(transcribingOn = "Pixel 8", transcribingSince = 1_758_000_000_000)
    val encoded = encode(marked)
    assertEquals("Pixel 8", encoded.payload.jsonObject["transcribingOn"]?.jsonPrimitive?.content)
    assertEquals(marked, SyncCodec.json.decodeFromJsonElement(SessionEntity.serializer(), encoded.payload))
  }

  @Test
  fun `una sessione arrivata da un'app di prima si legge senza segno`() {
    val old = JsonObject(encode(session).payload.jsonObject.filterKeys { it != "transcribingOn" && it != "transcribingSince" })
    assertEquals(session, SyncCodec.json.decodeFromJsonElement(SessionEntity.serializer(), old))
  }

  @Test
  fun `senza segno l'impronta e' quella di prima dell'aggiornamento`() {
    // Se le colonne vuote contassero, l'aggiornamento farebbe sembrare cambiate tutte le sessioni,
    // su ogni dispositivo: tutte nell'outbox, e tutte a vincere o perdere contro l'indice.
    val before = JsonObject(encode(session).payload.jsonObject.filterKeys { it != "transcribingOn" && it != "transcribingSince" })
    assertEquals(SyncCodec.hash(before), encode(session).hash)
  }

  @Test
  fun `il segno cambia l'impronta, cosi' sale, e toglierlo la riporta com'era`() {
    val marked = encode(session.copy(transcribingOn = "Pixel 8", transcribingSince = 5))
    assertNotEquals(encode(session).hash, marked.hash)
    assertNotEquals(marked.hash, encode(session.copy(transcribingOn = "Pixel 8", transcribingSince = 6)).hash)
    assertEquals(encode(session).hash, encode(session.copy(transcribingOn = null, transcribingSince = null)).hash)
  }

  @Test
  fun `il segno non alza il tempo della sessione`() {
    // Per le sessioni vale l'ultimo che ha scritto: un segno non deve vincere su un titolo cambiato altrove.
    val marked = encode(session.copy(transcribingOn = "Pixel 8", transcribingSince = 5))
    assertEquals(session.updatedAt, marked.updatedAt)
    assertEquals(session.updatedAt, SyncCodec.updatedAtOf(marked.payload))
  }
}
