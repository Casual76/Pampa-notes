package dev.pampa.pampanotes.core.importing

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sui numeri veri di «Impressionismo» (28/09): quattro lezioni, «Voce 003» e «Voce 004» scambiate. */
class SdocxRepairPlanTest {

  private val rome = ZoneId.of("Europe/Rome")
  private val note = "impressionismo"

  // In ordine di registrazione, come le da' il parser: 19, 21, 24 e 28/09.
  private val recordings = listOf(
    RepairRecording("media/0@6aae38f3_bd0c8.m4a", 1_789_802_739_000L, 34_268_413, "1c871f"),
    RepairRecording("media/1@6ab0d96e_7ef25.m4a", 1_789_974_894_000L, 49_944_354, "8f8e87"),
    RepairRecording("media/2@6ab4c01d_d9379.m4a", 1_790_230_557_000L, 47_153_042, "d619b2"),
    RepairRecording("media/3@6aba3013_63dbb.m4a", 1_790_586_899_000L, 42_930_972, "98898c"),
  )
  private val voices = listOf(
    SdocxVoice("Voce 001", 2_115_000),
    SdocxVoice("Voce 002", 3_082_000),
    SdocxVoice("Voce 003", 2_910_000),
    SdocxVoice("Voce 004", 2_650_000),
  )

  private fun part(id: String, session: String, position: Int, name: String, duration: Long, size: Long, sha: String) =
    RepairPart(id, session, position, name, duration, size, sha)

  /** Com'e' finita: «Voce 002» e' quella del 21/09 con l'intestazione di prima (14 byte in piu'). */
  private val sessions = listOf(
    RepairSession("s19", "2026-09-19", 0),
    RepairSession("s21", "2026-09-21", 1),
    RepairSession("s28", "2026-09-28", 2),
  )
  private val parts = listOf(
    part("p1", "s19", 0, "Voce 001.m4a", 2_115_316, 34_268_413, "1c871f"),
    part("p2", "s21", 0, "Voce 002.m4a", 3_082_981, 49_944_368, "ba73c3"),
    part("p3", "s28", 0, "Voce 003.m4a", 2_650_049, 42_930_972, "98898c"),
    part("p4", "s28", 1, "Voce 004.m4a", 2_910_674, 47_153_042, "d619b2"),
  )

  @Test
  fun `i nomi tornano ai loro file e la lezione del 24 va nel suo giorno`() {
    val plan = SdocxRepair.plan(note, sessions, parts, recordings, voices, rome)

    assertEquals(mapOf("p3" to "Voce 004.m4a", "p4" to "Voce 003.m4a"), plan.renames)
    val day24 = SdocxRepair.sessionId(note, LocalDate.of(2026, 9, 24))
    assertEquals(listOf(day24 to "2026-09-24"), plan.newSessions)
    assertEquals(mapOf("s28" to listOf("p3"), day24 to listOf("p4")), plan.layout)
    assertTrue(plan.redates.isEmpty())
  }

  @Test
  fun `una seconda volta non c'e' niente da fare`() {
    val plan = SdocxRepair.plan(note, sessions, parts, recordings, voices, rome)
    val (afterSessions, afterParts) = apply(plan, sessions, parts)

    assertTrue(SdocxRepair.plan(note, afterSessions, afterParts, recordings, voices, rome).isEmpty)
  }

  @Test
  fun `una sessione tutta di un altro giorno cambia solo data`() {
    val wrong = listOf(RepairSession("s", "2026-09-28", 0))
    val only = listOf(part("p1", "s", 0, "Voce 001.m4a", 2_115_316, 34_268_413, "1c871f"))

    val plan = SdocxRepair.plan(note, wrong, only, recordings, voices, rome)

    assertEquals(mapOf("s" to "2026-09-19"), plan.redates)
    assertFalse(plan.movesParts)
  }

  @Test
  fun `una parte arriva nella sessione del suo giorno se la nota ce l'ha gia'`() {
    val withDay24 = sessions + RepairSession("s24", "2026-09-24", 3)
    val plan = SdocxRepair.plan(note, withDay24, parts, recordings, voices, rome)

    assertTrue(plan.newSessions.isEmpty())
    assertEquals(listOf("p4"), plan.layout["s24"])
  }

  @Test
  fun `una parte che non si riconosce resta dov'e' e tiene la sua sessione`() {
    val stranger = part("px", "s28", 2, "Intervista.m4a", 600_000, 5_000_000, "zzz")
    val plan = SdocxRepair.plan(note, sessions, parts + stranger, recordings, voices, rome)

    assertFalse("px" in plan.renames)
    assertEquals(listOf("p3", "px"), plan.layout["s28"])
    assertFalse("s28" in plan.redates)
  }

  @Test
  fun `l'impronta vince sul peso`() {
    // Due file quasi dello stesso peso: decide l'impronta.
    val twins = listOf(
      RepairRecording("media/0@6aae38f3_1.m4a", 1_789_802_739_000L, 10_000_000, "aaa"),
      RepairRecording("media/1@6ab0d96e_2.m4a", 1_789_974_894_000L, 10_000_010, "bbb"),
    )
    val matched = SdocxRepair.match(listOf(part("p", "s", 0, "x.m4a", 1, 10_000_010, "aaa")), twins)
    assertEquals(mapOf("p" to 0), matched)
  }

  @Test
  fun `spostare in una sessione con una versione ripulita si dice`() {
    val refined = sessions.map { if (it.id == "s28") it.copy(hasRefined = true) else it }
    val plan = SdocxRepair.plan(note, refined, parts, recordings, voices, rome)

    assertEquals(setOf("s28"), plan.touchesRefined)
    val safe = plan.withoutMoves()
    assertEquals(plan.renames, safe.renames)
    assertFalse(safe.movesParts)
    assertTrue(safe.newSessions.isEmpty())
  }

  @Test
  fun `l'id di una sessione riparata e' lo stesso su ogni dispositivo`() {
    assertEquals(SdocxRepair.sessionId(note, LocalDate.of(2026, 9, 24)), SdocxRepair.sessionId(note, LocalDate.of(2026, 9, 24)))
    assertFalse(SdocxRepair.sessionId(note, LocalDate.of(2026, 9, 24)) == SdocxRepair.sessionId("altra", LocalDate.of(2026, 9, 24)))
  }

  /** Il piano applicato a un modello in memoria, come farebbe il repository. */
  private fun apply(plan: SdocxRepair.Plan, sessions: List<RepairSession>, parts: List<RepairPart>): Pair<List<RepairSession>, List<RepairPart>> {
    val newSessions = sessions.map { plan.redates[it.id]?.let { date -> it.copy(date = date) } ?: it } +
      plan.newSessions.mapIndexed { index, (id, date) -> RepairSession(id, date, sessions.size + index) }
    val placed = plan.layout.flatMap { (session, ids) -> ids.mapIndexed { position, id -> id to (session to position) } }.toMap()
    val newParts = parts.map { part ->
      val renamed = plan.renames[part.id]?.let { part.copy(originalName = it) } ?: part
      placed[part.id]?.let { (session, position) -> renamed.copy(sessionId = session, position = position) } ?: renamed
    }
    return newSessions to newParts
  }
}
