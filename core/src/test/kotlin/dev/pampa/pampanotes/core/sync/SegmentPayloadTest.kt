package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.SegmentEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Chi parla» (database 10) aggiunge `speaker` ai segmenti, che entrano nell'impronta della
 * trascrizione. Vuota, l'impronta deve restare byte per byte quella di prima: altrimenti
 * l'aggiornamento sporcherebbe tutte le trascrizioni di tutti.
 */
class SegmentPayloadTest {

  /** Il segmento com'era fino al database 9, campo per campo e nello stesso ordine. */
  @Serializable
  private data class SegmentBefore(
    val id: Long = 0,
    val transcriptId: String,
    val partId: String,
    val indexInPart: Int,
    val partStartMs: Long,
    val partEndMs: Long,
    val sessionStartMs: Long,
    val sessionEndMs: Long,
    val text: String,
    val noSpeechProb: Float? = null,
    val avgLogProb: Float? = null,
    val wordsJson: String? = null,
    val wordsEstimated: Boolean = false,
  )

  private val segments = listOf(
    SegmentEntity(
      id = 42, transcriptId = "t", partId = "p1", indexInPart = 0, partStartMs = 0, partEndMs = 1_500,
      sessionStartMs = 0, sessionEndMs = 1_500, text = "Perché \"virgolette\" e\nun a capo — àèì",
      noSpeechProb = 0.1f, avgLogProb = -0.3333f, wordsJson = "0,400,Perché\n400,900,virgolette", wordsEstimated = false,
    ),
    SegmentEntity(
      id = 7, transcriptId = "t", partId = "p1", indexInPart = 1, partStartMs = 1_500, partEndMs = 3_000,
      sessionStartMs = 1_500, sessionEndMs = 3_000, text = "Seconda.", wordsEstimated = true,
    ),
  )

  @Test
  fun `senza voci i segmenti si scrivono come prima`() {
    val before = segments.map {
      SegmentBefore(
        0, it.transcriptId, it.partId, it.indexInPart, it.partStartMs, it.partEndMs, it.sessionStartMs,
        it.sessionEndMs, it.text, it.noSpeechProb, it.avgLogProb, it.wordsJson, it.wordsEstimated,
      )
    }
    assertEquals(
      SyncCodec.json.encodeToString(ListSerializer(SegmentBefore.serializer()), before),
      SyncCodec.canonicalSegments(segments),
    )
  }

  @Test
  fun `con le voci l'impronta cambia, e la voce viaggia`() {
    val voiced = segments.map { it.copy(speaker = "SPEAKER_00") }
    assertTrue(SyncCodec.canonicalSegments(voiced).contains("\"speaker\":\"SPEAKER_00\""))
    assertNotEquals(SyncCodec.transcriptHash("h", segments), SyncCodec.transcriptHash("h", voiced))
    val wire = SyncCodec.json.encodeToString(ListSerializer(SegmentEntity.serializer()), voiced)
    assertEquals(voiced, SyncCodec.json.decodeFromString(ListSerializer(SegmentEntity.serializer()), wire))
  }
}
