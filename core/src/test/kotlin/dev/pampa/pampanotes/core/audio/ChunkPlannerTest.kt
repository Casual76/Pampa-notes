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

  // --- pezzi uguali ---

  @Test
  fun `quaranta minuti in due pezzi sono due da venti, non trenta piu' dieci`() {
    val total = 41 * 60_000L
    val plan = ChunkPlanner.planEqual(energies(total, emptyList()), pieces = 2)

    assertEquals(2, plan.chunks.size)
    val cut = plan.chunks[1].startMs
    assertTrue("taglio a ${cut}ms", abs(cut - total / 2) <= ChunkPlanner.DEFAULT_SEARCH_WINDOW_MS)
    assertEquals(total, plan.chunks.last().endMs)
  }

  @Test
  fun `i pezzi uguali tagliano nel silenzio vicino al confine ideale`() {
    val total = 95 * 60_000L
    // Il confine ideale del primo pezzo e' a 23:45; un silenzio a 23:30 lo attira.
    val silence = 23 * 60_000L + 30_000L
    val plan = ChunkPlanner.planEqual(energies(total, listOf(silence)), pieces = 4)

    assertEquals(4, plan.chunks.size)
    assertTrue("taglio a ${plan.chunks[1].startMs}ms", abs(plan.chunks[1].startMs - (silence + 500)) < 1_500)
    // Nessun pezzo si allontana di molto dal quarto: gli scarti non si sommano.
    val boundaries = plan.chunks.map { it.startMs } + total
    boundaries.zipWithNext { a, b ->
      assertTrue("pezzo di ${b - a}ms", abs((b - a) - total / 4) <= 2 * ChunkPlanner.DEFAULT_SEARCH_WINDOW_MS)
    }
    plan.chunks.dropLast(1).zipWithNext().forEach { (a, b) -> assertEquals(ChunkPlanner.DEFAULT_OVERLAP_MS, a.endMs - b.startMs) }
  }

  @Test
  fun `un pezzo solo e' il file intero, ricodificato`() {
    val total = 8 * 60_000L
    val plan = ChunkPlanner.planEqual(energies(total, emptyList()), pieces = 1)
    assertTrue(plan.isSingle)
    assertEquals(total, plan.chunks.single().endMs)
  }

  @Test
  fun `troppi pezzi per un audio corto diventano meno pezzi, non monconi`() {
    val plan = ChunkPlanner.planEqual(energies(3 * 60_000L, emptyList()), pieces = 10)
    assertEquals(3, plan.chunks.size)
    assertTrue(plan.chunks.all { it.durationMs >= 30_000 })
  }

  // --- intero o a pezzi ---

  private val minute = 60_000L

  private fun computer(durationMin: Long) = ChunkPolicy.decide(
    durationMs = durationMin * minute,
    sizeBytes = durationMin * 1_000_000,
    capMs = 30 * minute,
    toleranceMs = ChunkPolicy.COMPUTER_TOLERANCE_MS,
    maxUploadBytes = null,
    acceptedAsIs = true,
  )

  @Test
  fun `computer di casa col tetto a trenta - quaranta minuti vanno interi`() {
    assertEquals(ChunkDecision.Whole, computer(30))
    assertEquals(ChunkDecision.Whole, computer(40))
  }

  @Test
  fun `computer di casa col tetto a trenta - oltre la tolleranza, pezzi uguali`() {
    assertEquals(ChunkDecision.Split(2, 1_230_000), computer(41)) // 2 x 20,5
    val ninetyFive = computer(95) as ChunkDecision.Split
    assertEquals(4, ninetyFive.pieces)
    assertEquals(1_425_000L, ninetyFive.pieceMs) // circa 24 minuti
    assertEquals(ChunkDecision.Split(4, 30 * minute), computer(120))
  }

  private val groqLimit = 25L * 1024 * 1024

  private fun groq(durationMs: Long, sizeBytes: Long, capMin: Long = 10, accepted: Boolean = true) = ChunkPolicy.decide(
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    capMs = capMin * minute,
    toleranceMs = ChunkPolicy.GROQ_TOLERANCE_MS,
    maxUploadBytes = groqLimit,
    acceptedAsIs = accepted,
  )

  @Test
  fun `Groq - due minuti di tolleranza, e mai oltre il limite di byte`() {
    assertEquals(ChunkDecision.Whole, groq(12 * minute, 5_000_000))
    assertEquals(ChunkDecision.Split(2, 390_000), groq(13 * minute, 5_000_000))
    // Dentro la durata ma troppo pesante: si ricodifica, e un pezzo ricodificato ci sta.
    assertEquals(ChunkDecision.Split(1, 11 * minute), groq(11 * minute, 30_000_000))
  }

  @Test
  fun `Groq - un tetto che ricodificato sforerebbe i byte si divide di piu'`() {
    // Con un tetto di due ore, due ore ricodificate sono ~45 MB: il limite chiede due pezzi, non uno.
    val decision = groq(120 * minute, 200_000_000, capMin = 120) as ChunkDecision.Split
    assertTrue("pezzi: ${decision.pieces}", decision.pieces >= 2)
    val encodedPiece = decision.pieceMs * ChunkPolicy.ENCODED_BYTES_PER_SECOND / 1000
    assertTrue("pezzo da $encodedPiece byte", encodedPiece <= groqLimit)
  }

  @Test
  fun `un formato che il servizio non prende si ricodifica senza tagli`() {
    assertEquals(ChunkDecision.Split(1, 5 * minute), groq(5 * minute, 1_000_000, accepted = false))
  }
}
