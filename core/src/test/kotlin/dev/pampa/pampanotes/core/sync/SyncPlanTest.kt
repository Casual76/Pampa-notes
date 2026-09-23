package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.SegmentEntity
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {

  private fun up(tbl: String, id: String, seq: Long) = WireChange(tbl, id, WireChange.OP_UPSERT, updatedAt = 1, seq = seq)
  private fun del(tbl: String, id: String, seq: Long) = WireChange(tbl, id, WireChange.OP_DELETE, updatedAt = 1, seq = seq)

  @Test
  fun `prima gli upsert da padre a figlio, poi le cancellazioni da figlio a padre`() {
    // Altrove: la nota esce dalla cartella A, poi A si cancella. Il server li ha in quest'ordine di seq.
    val page = listOf(
      del("folders", "A", 12),
      up("notes", "n", 10),
      up("folders", "B", 11),
      del("sessions", "s", 13),
      up("transcripts", "t", 9),
    )
    assertEquals(listOf("folders/B", "notes/n", "transcripts/t"), SyncPlan.upserts(page).map { "${it.tbl}/${it.id}" })
    assertEquals(listOf("sessions/s", "folders/A"), SyncPlan.deletes(page).map { "${it.tbl}/${it.id}" })
  }

  @Test
  fun `a parita' di tabella vale l'ordine in cui sono state scritte`() {
    val page = listOf(del("folders", "figlia", 5), del("folders", "madre", 3), up("folders", "y", 8), up("folders", "x", 2))
    assertEquals(listOf("x", "y"), SyncPlan.upserts(page).map { it.id })
    assertEquals(listOf("madre", "figlia"), SyncPlan.deletes(page).map { it.id })
  }

  @Test
  fun `di una riga ripresentata vale solo l'ultima versione`() {
    val parked = up("sessions", "s", 4)
    val newer = del("sessions", "s", 20)
    val latest = SyncPlan.latestPerRow(listOf(parked, up("notes", "n", 5), newer))
    assertEquals(2, latest.size)
    assertEquals(newer, latest.single { it.tbl == "sessions" })
  }

  @Test
  fun `il padre si legge dal payload, nota compresa`() {
    val folder = buildJsonObject { put("id", "f2"); put("parentId", "f1") }
    val top = buildJsonObject { put("id", "f1"); put("parentId", kotlinx.serialization.json.JsonNull) }
    val note = buildJsonObject { put("note", buildJsonObject { put("id", "n"); put("folderId", "f1") }); put("tags", kotlinx.serialization.json.JsonArray(emptyList())) }
    val session = buildJsonObject { put("id", "s"); put("noteId", "n") }
    val part = buildJsonObject { put("id", "p"); put("sessionId", "s") }
    val transcript = buildJsonObject { put("id", "t"); put("sessionId", "s"); put("parentId", "t0") }
    val source = buildJsonObject { put("id", "x"); put("noteId", "n"); put("derivedFromId", "y") }

    assertEquals(SyncPlan.RowRef("folders", "f1"), SyncPlan.parentOf("folders", folder))
    assertNull(SyncPlan.parentOf("folders", top))
    assertEquals(SyncPlan.RowRef("folders", "f1"), SyncPlan.parentOf("notes", note))
    assertEquals(SyncPlan.RowRef("notes", "n"), SyncPlan.parentOf("sessions", session))
    assertEquals(SyncPlan.RowRef("notes", "n"), SyncPlan.parentOf("sources", source))
    assertEquals(SyncPlan.RowRef("sessions", "s"), SyncPlan.parentOf("audio_parts", part))
    // `parentId` di una trascrizione non e' una chiave esterna: il padre e' la sessione.
    assertEquals(SyncPlan.RowRef("sessions", "s"), SyncPlan.parentOf("transcripts", transcript))
    assertNull(SyncPlan.parentOf("export_presets", buildJsonObject { put("id", "e") }))
    assertNull(SyncPlan.parentOf("notes", JsonPrimitive("rotto")))
    assertNull(SyncPlan.parentOf("notes", null))
  }

  // --- i figli di una riga rinata ---

  @Test
  fun `sotto una nota rinata ci sono le sue sessioni, le loro parti e trascrizioni, non la nota stessa`() {
    val parents = mapOf(
      "notes/n" to "folders/f",
      "sessions/s" to "notes/n",
      "audio_parts/p" to "sessions/s",
      "transcripts/t" to "sessions/s",
      "sources/x" to "notes/n",
      "sessions/altra" to "notes/m",
      "audio_parts/q" to "sessions/altra",
    )
    val roots = setOf("notes/n")
    listOf("sessions/s", "audio_parts/p", "transcripts/t", "sources/x").forEach {
      assertTrue(it, SyncPlan.descendsFrom(it, roots, parents))
    }
    listOf("notes/n", "folders/f", "sessions/altra", "audio_parts/q", "notes/sconosciuta").forEach {
      assertTrue(it, !SyncPlan.descendsFrom(it, roots, parents))
    }
  }

  @Test
  fun `una cartella rinata porta con se' sottocartelle e note, e un ciclo nei payload non gira per sempre`() {
    val parents = mapOf("folders/sotto" to "folders/f", "notes/n" to "folders/sotto", "sessions/s" to "notes/n", "folders/a" to "folders/b", "folders/b" to "folders/a")
    assertTrue(SyncPlan.descendsFrom("sessions/s", setOf("folders/f"), parents))
    assertTrue(!SyncPlan.descendsFrom("folders/a", setOf("folders/f"), parents))
    assertEquals("notes/n", SyncPlan.key("notes", "n"))
  }

  // --- gli orfani in fondo al pull ---

  @Test
  fun `un orfano tiene fermo lastPullSeq appena prima di lui`() {
    val verdict = OrphanLedger.settle(listOf(up("sessions", "s", 40), up("sources", "x", 55)), emptyMap(), pageSeq = 90)
    assertEquals(39, verdict.resumeFrom)
    assertEquals(mapOf("sessions/s" to 1, "sources/x" to 1), verdict.attempts)
    assertTrue(verdict.abandoned.isEmpty())
  }

  @Test
  fun `dopo tre giri l'orfano si lascia andare e il seq lo scavalca`() {
    val orphan = up("sessions", "s", 40)
    var attempts = emptyMap<String, Int>()
    repeat(OrphanLedger.MAX_RUNS - 1) {
      val verdict = OrphanLedger.settle(listOf(orphan), attempts, pageSeq = 90)
      assertEquals(39, verdict.resumeFrom)
      attempts = verdict.attempts
    }
    val last = OrphanLedger.settle(listOf(orphan), attempts, pageSeq = 90)
    assertEquals(listOf(orphan), last.abandoned)
    assertEquals(90, last.resumeFrom)
    assertTrue(last.attempts.isEmpty())
  }

  @Test
  fun `un orfano che entra esce dai conti, e il secondo pull dello stesso giro non consuma tentativi`() {
    val resolved = OrphanLedger.settle(emptyList(), mapOf("sessions/s" to 2), pageSeq = 90)
    assertTrue(resolved.attempts.isEmpty())
    assertEquals(90, resolved.resumeFrom)

    val again = OrphanLedger.settle(listOf(up("sessions", "s", 40)), mapOf("sessions/s" to 2), pageSeq = 90, count = false)
    assertEquals(mapOf("sessions/s" to 2), again.attempts)
    assertEquals(39, again.resumeFrom)
  }

  @Test
  fun `il punto di ripresa non va mai oltre la pagina ne' sotto zero`() {
    assertEquals(50, OrphanLedger.resumePoint(listOf(up("notes", "n", 80)), pageSeq = 50))
    assertEquals(0, OrphanLedger.resumePoint(listOf(up("notes", "n", 0)), pageSeq = 50))
    assertEquals(50, OrphanLedger.resumePoint(emptyList(), pageSeq = 50))
  }

  // --- il push ---

  @Test
  fun `i lotti si chiudono per numero e per peso, e una riga enorme sta da sola`() {
    val sizes = listOf(10, 10, 10, 50, 10, 200, 10)
    val batches = PushPlanner.chunk(sizes.indices.toList(), sizeOf = { sizes[it] }, maxCount = 3, maxBytes = 60)
    assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4), listOf(5), listOf(6)), batches)
  }

  @Test
  fun `lo stesso lotto ha lo stesso nome, in qualunque ordine`() {
    val a = PushPlanner.Item(7, up("notes", "n", 0).copy(hash = "h1", baseHash = "h0"))
    val b = PushPlanner.Item(9, del("sources", "x", 0).copy(baseHash = "h9"))
    assertEquals(PushPlanner.batchId("dev", listOf(a, b)), PushPlanner.batchId("dev", listOf(b, a)))
    assertNotEquals(PushPlanner.batchId("dev", listOf(a, b)), PushPlanner.batchId("altro", listOf(a, b)))
  }

  @Test
  fun `una revisione nuova della stessa riga e' un lotto nuovo, anche con lo stesso contenuto`() {
    // Il testo riportato a quello di prima sopra la stessa base: stesso contenuto, altra revisione.
    val change = up("notes", "n", 0).copy(hash = "h1", baseHash = "h0")
    assertNotEquals(
      PushPlanner.batchId("dev", listOf(PushPlanner.Item(7, change))),
      PushPlanner.batchId("dev", listOf(PushPlanner.Item(12, change))),
    )
    assertNotEquals(
      PushPlanner.batchId("dev", listOf(PushPlanner.Item(7, change))),
      PushPlanner.batchId("dev", listOf(PushPlanner.Item(7, change.copy(baseHash = "h2")))),
    )
  }

  // --- l'impronta della trascrizione ---

  private fun segment(start: Long, part: String = "p", index: Int = 0, text: String = "ciao", id: Long = 0) =
    SegmentEntity(id = id, transcriptId = "t", partId = part, indexInPart = index, partStartMs = start, partEndMs = start + 1, sessionStartMs = start, sessionEndMs = start + 1, text = text)

  @Test
  fun `i segmenti entrano nell'impronta della trascrizione`() {
    val base = "impronta-del-payload"
    val before = SyncCodec.transcriptHash(base, listOf(segment(0), segment(1_000, index = 1)))
    // Stesso testo, tempi di sessione nuovi: e' quello che fa un riordino delle parti.
    val reordered = SyncCodec.transcriptHash(base, listOf(segment(0), segment(2_000, index = 1)))
    assertNotEquals(before, reordered)
    assertNotEquals(base, before)
  }

  @Test
  fun `l'impronta non dipende dagli id locali ne' dall'ordine di lettura`() {
    val base = "impronta-del-payload"
    val here = SyncCodec.transcriptHash(base, listOf(segment(0, id = 5), segment(1_000, index = 1, id = 6)))
    val there = SyncCodec.transcriptHash(base, listOf(segment(1_000, index = 1, id = 91), segment(0, id = 90)))
    assertEquals(here, there)
  }

  @Test
  fun `senza segmenti l'impronta resta quella di prima`() {
    assertEquals("impronta-del-payload", SyncCodec.transcriptHash("impronta-del-payload", emptyList()))
  }
}
