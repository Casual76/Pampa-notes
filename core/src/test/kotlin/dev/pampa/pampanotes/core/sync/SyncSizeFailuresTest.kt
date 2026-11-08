package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.files.Hashing
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncSizeFailuresTest {
  @get:Rule val temporary = TemporaryFolder()
  private fun ledger() = java.io.File(temporary.root, "failures.json")

  @Test fun `permanent failure survives process recreation without consuming dirty outbox evidence`() {
    val hash = Hashing.sha256("public text and exact source")
    SyncSizeFailures(ledger()).record("transcripts", "raw", hash)
    assertTrue(SyncSizeFailures(ledger()).blocked("transcripts", "raw", hash))
    assertFalse(ledger().readText().contains("public text"))
    assertFalse(SyncSizeFailures(ledger()).blocked("transcripts", "raw", Hashing.sha256("edited")))
    assertFalse(SyncSizeFailures(java.io.File(temporary.root, "other-account-server.json")).blocked("transcripts", "raw", hash))
  }

  @Test fun `resolved deleted or applied rows release the durable failure`() {
    val store = SyncSizeFailures(ledger())
    store.record("transcripts", "a", "ha"); store.record("notes", "b", "hb")
    store.retain(setOf("transcripts/a"))
    assertTrue(SyncSizeFailures(ledger()).blocked("transcripts", "a", "ha"))
    assertFalse(SyncSizeFailures(ledger()).blocked("notes", "b", "hb"))
    store.remove("transcripts", "a")
    assertFalse(SyncSizeFailures(ledger()).blocked("transcripts", "a", "ha"))
  }

  @Test fun `recovery completion is durable and survives outbox pruning`() {
    val store = SyncSizeFailures(ledger())
    assertFalse(store.recovered)
    store.finishRecovery()
    store.record("transcripts", "a", "ha")
    store.retain(emptySet())
    assertTrue(SyncSizeFailures(ledger()).recovered)
    assertFalse(SyncSizeFailures(ledger()).blocked("transcripts", "a", "ha"))
  }

  @Test fun `payload limit matches worker bytes including Unicode and json string escaping`() {
    fun change(text: String) = WireChange("notes", "n", "U", 1, payload = JsonPrimitive(text))
    assertFalse(SyncSizeFailures.exceedsLimits(change("x".repeat(1_899_998))))
    assertTrue(SyncSizeFailures.exceedsLimits(change("x".repeat(1_899_999))))
    assertFalse(SyncSizeFailures.exceedsLimits(change("東京".repeat(300_000))))
    assertTrue(SyncSizeFailures.exceedsLimits(change("東京".repeat(320_000))))
    assertTrue(SyncSizeFailures.exceedsLimits(change("\"".repeat(950_000))))
  }

  @Test fun `single segment guard uses row limit and does not reject valid large chunks`() {
    val segment = SegmentEntity(transcriptId = "r", partId = "p", indexInPart = 0, partStartMs = 0, partEndMs = 1,
      sessionStartMs = 0, sessionEndMs = 1, text = "x".repeat(1_600_000))
    fun change(s: SegmentEntity) = WireChange("transcripts", "r", "U", 1, segments = listOf(s))
    assertFalse(SyncSizeFailures.exceedsLimits(change(segment)))
    assertTrue(SyncSizeFailures.exceedsLimits(change(segment.copy(text = "x".repeat(1_900_000)))))
  }

  @Test fun `durable reporting codes contain only counts and reject unrecognized error text`() {
    assertEquals(3, SyncSizeFailures.count(SyncSizeFailures.error(3)))
    assertEquals(0, SyncSizeFailures.count(null))
    assertEquals(0, SyncSizeFailures.count("unrelated error"))
    assertEquals(0, SyncSizeFailures.count("sync_row_too_large:-1"))
    assertEquals(0, SyncSizeFailures.count("sync_row_too_large:secret"))
  }
}
