package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.transcription.TranscriptPlacement.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dove va il risultato di una trascrizione quando la sessione e' cambiata mentre il computer
 * lavorava: ogni parte ancora viva nella sessione in cui sta adesso, quella del lavoro per ultima.
 */
class TranscriptPlacementTest {

  private val snapshot = listOf("a", "b", "c")

  @Test
  fun `niente e' cambiato, tutto nella sessione del lavoro`() {
    val plan = TranscriptPlacement.plan("s", snapshot, mapOf("a" to "s", "b" to "s", "c" to "s"))

    assertEquals(listOf(Target("s", listOf("a", "b", "c"))), plan)
  }

  @Test
  fun `una parte cancellata non riceve niente`() {
    val plan = TranscriptPlacement.plan("s", snapshot, mapOf("a" to "s", "c" to "s"))

    assertEquals(listOf(Target("s", listOf("a", "c"))), plan)
  }

  @Test
  fun `una parte spostata o separata va dove sta, e la sessione del lavoro viene per ultima`() {
    // «b» e «c» separate in una sessione nuova mentre si trascriveva.
    val plan = TranscriptPlacement.plan("s", snapshot, mapOf("a" to "s", "b" to "nuova", "c" to "nuova"))

    assertEquals(listOf(Target("nuova", listOf("b", "c")), Target("s", listOf("a"))), plan)
  }

  @Test
  fun `unita a un'altra sessione, il risultato va in quella che resta`() {
    val plan = TranscriptPlacement.plan("s", snapshot, mapOf("a" to "prima", "b" to "prima", "c" to "prima"))

    assertEquals(listOf(Target("prima", listOf("a", "b", "c"))), plan)
  }

  @Test
  fun `sessione cancellata con tutte le parti, niente da scrivere`() {
    assertTrue(TranscriptPlacement.plan("s", snapshot, emptyMap()).isEmpty())
  }

  @Test
  fun `le parti restano nell'ordine del lavoro, l'ordine vero lo rifa' la ricomposizione`() {
    // Riordinate nel frattempo: qui l'ordine non conta, i tempi li ricalcola `rebuildRaw`.
    val plan = TranscriptPlacement.plan("s", listOf("a", "b", "a"), mapOf("a" to "s", "b" to "s"))

    assertEquals(listOf(Target("s", listOf("a", "b"))), plan)
  }
}
