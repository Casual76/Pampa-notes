package dev.pampa.pampanotes.core.audio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {

  private val frameMs = ChunkPlanner.DEFAULT_FRAME_MS

  /** Un'energia costante con dei silenzi veri nei punti indicati. */
  private fun energies(durationMs: Long, silencesAtMs: List<Long>, silenceLengthMs: Long = 1_000): FloatArray {
    val frames = (durationMs / frameMs).toInt()
    val array = FloatArray(frames) { 1f }
    silencesAtMs.forEach { at ->
      val from = (at / frameMs).toInt()
      val to = ((at + silenceLengthMs) / frameMs).toInt()
      for (i in from until minOf(to, frames)) array[i] = 0.01f
    }
    return array
  }

  @Test
  fun `un audio corto resta in un pezzo solo`() {
    val plan = ChunkPlanner.plan(energies(5 * 60_000, emptyList()), targetMs = 10 * 60_000)

    assertTrue(plan.isSingle)
    assertEquals(0, plan.chunks.first().startMs)
    assertEquals(5 * 60_000L, plan.chunks.first().endMs)
  }

  @Test
  fun `un'ora si divide e i pezzi coprono tutto senza buchi`() {
    val total = 60 * 60_000L
    val plan = ChunkPlanner.plan(energies(total, emptyList()), targetMs = 10 * 60_000)

    assertTrue("attesi piu' pezzi, trovati ${plan.chunks.size}", plan.chunks.size >= 5)
    assertEquals(0L, plan.chunks.first().startMs)
    assertEquals(total, plan.chunks.last().endMs)
    // Il pezzo successivo comincia prima che il precedente finisca: e' la sovrapposizione.
    plan.chunks.zipWithNext { a, b ->
      assertTrue("buco fra ${a.endMs} e ${b.startMs}", b.startMs <= a.endMs)
      assertTrue("il pezzo ${b.index} comincia prima del precedente", b.startMs > a.startMs)
    }
  }

  @Test
  fun `il taglio finisce dentro il silenzio quando ce n'e' uno vicino`() {
    val total = 30 * 60_000L
    // Un silenzio a 9 minuti e 30, dentro la finestra di ricerca attorno ai 10 minuti.
    val silence = 9 * 60_000L + 30_000L
    val plan = ChunkPlanner.plan(energies(total, listOf(silence)), targetMs = 10 * 60_000)

    val firstCut = plan.chunks[1].startMs
    assertTrue(
      "taglio a ${firstCut}ms, silenzio a ${silence}ms",
      abs(firstCut - (silence + 500)) < 1_500,
    )
  }

  @Test
  fun `senza silenzi il taglio resta vicino al bersaglio`() {
    val total = 30 * 60_000L
    val target = 10 * 60_000L
    val plan = ChunkPlanner.plan(energies(total, emptyList()), targetMs = target)

    val firstCut = plan.chunks[1].startMs
    assertTrue("taglio a ${firstCut}ms", abs(firstCut - target) <= ChunkPlanner.DEFAULT_SEARCH_WINDOW_MS)
  }

  @Test
  fun `la sovrapposizione c'e' fra tutti i pezzi tranne alla fine`() {
    val total = 45 * 60_000L
    val plan = ChunkPlanner.plan(energies(total, emptyList()), targetMs = 10 * 60_000, overlapMs = 5_000)

    plan.chunks.dropLast(1).zipWithNext().forEach { (a, b) ->
      assertEquals("sovrapposizione sbagliata fra ${a.index} e ${b.index}", 5_000L, a.endMs - b.startMs)
    }
    assertEquals(total, plan.chunks.last().endMs)
  }

  @Test
  fun `l'ultimo pezzo non e' un moncone`() {
    // 22 minuti con bersaglio 10: due pezzi da 11, non due da 10 e uno da 2.
    val total = 22 * 60_000L
    val plan = ChunkPlanner.plan(energies(total, emptyList()), targetMs = 10 * 60_000)

    assertEquals(2, plan.chunks.size)
    assertTrue("ultimo pezzo di ${plan.chunks.last().durationMs}ms", plan.chunks.last().durationMs > 60_000)
  }

  @Test
  fun `un audio vuoto non fa esplodere niente`() {
    val plan = ChunkPlanner.plan(FloatArray(0), targetMs = 10 * 60_000)

    assertTrue(plan.isSingle)
    assertEquals(0L, plan.chunks.first().durationMs)
  }

  @Test
  fun `il punto piu' silenzioso e' il centro della finestra piu' quieta`() {
    // Un frame isolato a zero a 5s, mezzo secondo di quiete a 12s: vince il secondo.
    val array = FloatArray((20_000 / frameMs).toInt()) { 1f }
    array[(5_000 / frameMs).toInt()] = 0f
    for (i in (12_000 / frameMs).toInt() until (12_600 / frameMs).toInt()) array[i] = 0.02f

    val point = ChunkPlanner.quietestPoint(array, frameMs, fromMs = 1_000, toMs = 19_000)

    assertTrue("punto trovato a ${point}ms", point in 12_000..12_800)
  }

  @Test
  fun `la stima dei pezzi serve a dire quante richieste costera'`() {
    assertEquals(1, ChunkPlanner.estimateChunkCount(totalMs = 8 * 60_000, targetMs = 10 * 60_000))
    assertEquals(6, ChunkPlanner.estimateChunkCount(totalMs = 60 * 60_000, targetMs = 10 * 60_000))
  }
}
