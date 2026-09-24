package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.transcription.VoiceNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Una sessione sul filo, col segno «in trascrizione su» (database 8) e i nomi delle voci (database 11). */
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
  fun `senza nomi delle voci l'impronta e' quella di prima dell'aggiornamento`() {
    // Database 11: `voiceNames` e' null su ogni sessione di prima. Scritta come null nell'impronta,
    // l'aggiornamento avrebbe fatto risalire tutte le sessioni di tutti come «cambiate».
    val before = JsonObject(encode(session).payload.jsonObject.filterKeys { it != "voiceNames" })
    assertEquals(SyncCodec.hash(before), encode(session).hash)
    assertEquals(SyncCodec.hash(before), encode(session.copy(voiceNames = null)).hash)
  }

  @Test
  fun `i nomi delle voci vanno e tornano, e cambiano l'impronta`() {
    val named = session.copy(voiceNames = VoiceNames.rename(null, VoiceNames.key("p1", "SPEAKER_00"), "Marco"), updatedAt = 3)
    val encoded = encode(named)
    assertEquals(named, SyncCodec.json.decodeFromJsonElement(SessionEntity.serializer(), encoded.payload))
    assertNotEquals(encode(session).hash, encoded.hash)
    // Un nome e' una modifica vera: alza il tempo, e vince per ultimo-che-scrive come un titolo.
    assertEquals(3L, SyncCodec.updatedAtOf(encoded.payload))
    // Tolto l'ultimo nome, la sessione torna quella di prima.
    val cleared = named.copy(voiceNames = VoiceNames.rename(named.voiceNames, VoiceNames.key("p1", "SPEAKER_00"), null))
    assertEquals(encode(session).hash, encode(cleared.copy(updatedAt = session.updatedAt)).hash)
  }

  @Test
  fun `una sessione arrivata da un'app di prima si legge senza nomi`() {
    // Un'app di prima non scrive il campo: la sessione arriva senza, e le voci restano «Voce N».
    val named = session.copy(voiceNames = "{\"p1|SPEAKER_00\":\"Marco\"}")
    val old = JsonObject(encode(named).payload.jsonObject.filterKeys { it != "voiceNames" })
    assertEquals(session, SyncCodec.json.decodeFromJsonElement(SessionEntity.serializer(), old))
  }

  @Test
  fun `il segno non alza il tempo della sessione`() {
    // Per le sessioni vale l'ultimo che ha scritto: un segno non deve vincere su un titolo cambiato altrove.
    val marked = encode(session.copy(transcribingOn = "Pixel 8", transcribingSince = 5))
    assertEquals(session.updatedAt, marked.updatedAt)
    assertEquals(session.updatedAt, SyncCodec.updatedAtOf(marked.payload))
  }
}
