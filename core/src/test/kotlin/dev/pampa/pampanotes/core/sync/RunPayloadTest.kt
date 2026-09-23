package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.TranscriptionRunEntity
import dev.pampa.pampanotes.core.sync.SyncMerge.Decision
import dev.pampa.pampanotes.core.sync.SyncMerge.LocalView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le statistiche delle trascrizioni sul filo: `transcription_runs` dalla versione 7. */
class RunPayloadTest {

  private val run = TranscriptionRunEntity(
    id = "r",
    jobId = "j",
    sessionId = "s",
    noteId = null,
    provider = "custom",
    model = "large-v3",
    device = "cuda",
    audioMs = 2_400_000,
    wallMs = 48_000,
    processingMs = 40_000,
    words = 5214,
    segments = 300,
    resumed = true,
    finishedAt = 1_758_000_000_000,
    deviceName = "Tab S9",
  )

  @Test
  fun `una corsa va e torna uguale, col suo tempo e un'impronta stabile`() {
    val encoded = RunPayload.encode(run)
    assertEquals(run, RunPayload.decode(encoded.payload))
    assertEquals(run.finishedAt, encoded.updatedAt)
    assertNull(encoded.segments)
    assertEquals(encoded.hash, RunPayload.encode(run.copy()).hash)
    // Passata per il JSON del filo, com'e' nella risposta di un pull, resta la stessa riga.
    val wire = SyncCodec.json.encodeToString(WireChange.serializer(), WireChange("transcription_runs", "r", "U", encoded.updatedAt, encoded.hash, encoded.payload))
    val back = SyncCodec.json.decodeFromString(WireChange.serializer(), wire)
    assertEquals(run, RunPayload.decode(back.payload!!))
    assertEquals(encoded.hash, SyncCodec.hash(back.payload!!))
  }

  @Test
  fun `l'impronta conta tutto, nome del dispositivo e ripresa compresi`() {
    val base = RunPayload.encode(run).hash
    assertNotEquals(base, RunPayload.encode(run.copy(deviceName = "")).hash)
    assertNotEquals(base, RunPayload.encode(run.copy(resumed = false)).hash)
    assertNotEquals(base, RunPayload.encode(run.copy(wallMs = 47_999)).hash)
  }

  @Test
  fun `un payload di un client vecchio senza nome si legge lo stesso`() {
    val payload = RunPayload.encode(run).payload as JsonObject
    val old = JsonObject(payload - "deviceName")
    assertEquals("", RunPayload.decode(old).deviceName)
    // E un campo che questa versione non conosce non la ferma.
    val newer = JsonObject(payload + ("cosaNuova" to JsonPrimitive(1)))
    assertEquals(run, RunPayload.decode(newer))
  }

  @Test
  fun `senza padre, e in fondo all'ordine`() {
    assertNull(SyncPlan.parentOf("transcription_runs", RunPayload.encode(run).payload))
    assertEquals(SyncMerge.APPLY_ORDER.lastIndex, SyncMerge.orderOf("transcription_runs"))
    val page = listOf(
      WireChange("transcription_runs", "r", "U", 1, seq = 1),
      WireChange("sessions", "s", "U", 1, seq = 2),
    )
    assertEquals(listOf("sessions", "transcription_runs"), SyncPlan.upserts(page).map { it.tbl })
  }

  @Test
  fun `il merge di una corsa - vince l'ultimo che ha scritto, senza copie`() {
    val encoded = RunPayload.encode(run)
    val remote = WireChange("transcription_runs", "r", "U", updatedAt = run.finishedAt, hash = encoded.hash, payload = encoded.payload)
    // Nuova qui: si crea.
    assertEquals(Decision.APPLY, SyncMerge.decide("transcription_runs", LocalView(exists = false, dirty = false), remote))
    // La stessa riga, gia' concordata: si applica senza danni.
    val same = LocalView(exists = true, dirty = false, localHash = encoded.hash, localUpdatedAt = run.finishedAt, metaHash = encoded.hash)
    assertEquals(Decision.APPLY, SyncMerge.decide("transcription_runs", same, remote))
    // Rivendicata qui (nome dato) mentre altrove cambiava: niente nota di conflitto, decide il tempo.
    val claimed = RunPayload.encode(run.copy(deviceName = "Pixel"))
    val mine = LocalView(exists = true, dirty = true, localHash = claimed.hash, localUpdatedAt = run.finishedAt + 1, metaHash = "vecchia")
    val decision = SyncMerge.decide("transcription_runs", mine, remote.copy(hash = "altra"))
    assertEquals(Decision.SKIP, decision)
    assertTrue(decision != Decision.APPLY_AND_FORK && decision != Decision.KEEP_AND_FORK_REMOTE)
  }
}
