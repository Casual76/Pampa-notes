package dev.pampa.pampanotes.core.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Un pezzo di audio da mandare a trascrivere.
 *
 * @param startMs dove comincia nel file originale. E' anche il **confine autorevole** fra questo
 *   pezzo e il precedente: quando si ricuce, un segmento appartiene al pezzo nel cui intervallo
 *   `[startMs, startMs del successivo)` cade il suo punto medio.
 * @param endMs dove finisce. Supera l'inizio del pezzo successivo di [ChunkPlan.overlapMs], e quella
 *   sovrapposizione e' tutto il motivo per cui la cucitura funziona: Whisper sbaglia le prime e le
 *   ultime parole di un ritaglio, e senza un margine quelle parole andrebbero perse invece che
 *   scartate.
 */
data class ChunkSpec(
  val index: Int,
  val startMs: Long,
  val endMs: Long,
) {
  val durationMs: Long get() = endMs - startMs
}

data class ChunkPlan(
  val chunks: List<ChunkSpec>,
  val overlapMs: Long,
  val totalDurationMs: Long,
) {
  val isSingle: Boolean get() = chunks.size == 1
}

/**
 * Dove tagliare un audio lungo.
 *
 * Whisper accetta file corti; un'ora di lezione va divisa. **Dove** si taglia conta piu' di quanto:
 * un taglio in mezzo a una parola la perde da entrambi i lati, e in mezzo a una frase produce due
 * frammenti che nessuno dei due pezzi sa completare. Quindi il confine non si mette dove cade il
 * cronometro ma nel punto piu' silenzioso vicino a li', che in una lezione e' quasi sempre una
 * pausa di respiro o un cambio di argomento.
 *
 * Funzione pura su un vettore di energie: si prova senza audio, senza codec e senza telefono, che e'
 * l'unico modo di provarla davvero.
 */
object ChunkPlanner {

  /** Valori che vanno bene a una lezione parlata; la durata del pezzo la sceglie l'utente. */
  const val DEFAULT_FRAME_MS = 20L
  const val DEFAULT_OVERLAP_MS = 5_000L
  const val DEFAULT_SEARCH_WINDOW_MS = 30_000L
  const val DEFAULT_MIN_CHUNK_MS = 60_000L

  /**
   * @param frameEnergies l'energia (RMS, o qualsiasi misura monotona del volume) di ogni finestra
   *   di [frameMs] millisecondi, in ordine.
   * @param targetMs quanto dovrebbe durare un pezzo. Il confine vero puo' spostarsi di
   *   ±[searchWindowMs] per cadere in un silenzio.
   * @param minChunkMs sotto questa durata un pezzo non si crea: due tagli vicini producono un
   *   frammento di pochi secondi che costa una richiesta intera e non contiene una frase.
   */
  fun plan(
    frameEnergies: FloatArray,
    frameMs: Long = DEFAULT_FRAME_MS,
    targetMs: Long,
    overlapMs: Long = DEFAULT_OVERLAP_MS,
    searchWindowMs: Long = DEFAULT_SEARCH_WINDOW_MS,
    minChunkMs: Long = DEFAULT_MIN_CHUNK_MS,
  ): ChunkPlan {
    val totalMs = frameEnergies.size * frameMs
    require(targetMs > 0) { "la durata di un pezzo deve essere positiva" }

    // Un audio che ci sta in un pezzo solo non si tocca: niente decodifica, niente ricodifica,
    // niente cuciture. E' il caso piu' comune e anche il piu' veloce.
    if (totalMs <= targetMs || frameEnergies.isEmpty()) {
      return ChunkPlan(listOf(ChunkSpec(0, 0, max(totalMs, 0L))), overlapMs, totalMs)
    }

    val cuts = mutableListOf<Long>()
    var cursor = 0L
    while (true) {
      val remaining = totalMs - cursor
      // L'ultimo pezzo si allunga fino a una volta e mezzo il bersaglio invece di lasciare dietro
      // una coda di trenta secondi: una richiesta in meno, e nessun frammento che non dice niente.
      if (remaining <= targetMs * 3 / 2) break

      val ideal = cursor + targetMs
      val from = max(cursor + minChunkMs, ideal - searchWindowMs)
      val to = min(totalMs - minChunkMs, ideal + searchWindowMs)
      val cut = if (from >= to) ideal else quietestPoint(frameEnergies, frameMs, from, to)
      if (cut <= cursor) break
      cuts += cut
      cursor = cut
    }

    val boundaries = listOf(0L) + cuts + listOf(totalMs)
    val chunks = boundaries.dropLast(1).mapIndexed { index, start ->
      val nextBoundary = boundaries[index + 1]
      // La fine sconfina nel pezzo successivo: e' il margine da cui la cucitura sceglie.
      val end = min(totalMs, nextBoundary + if (index < cuts.size) overlapMs else 0L)
      ChunkSpec(index = index, startMs = start, endMs = end)
    }
    return ChunkPlan(chunks, overlapMs, totalMs)
  }

  /**
   * Il momento piu' silenzioso fra due istanti.
   *
   * Non il singolo frame minimo ma il centro della **finestra** piu' silenziosa di mezzo secondo:
   * un frame isolato a volume zero capita in mezzo a una parola (fra due consonanti occlusive), una
   * mezza secondo di quiete no.
   */
  fun quietestPoint(
    frameEnergies: FloatArray,
    frameMs: Long,
    fromMs: Long,
    toMs: Long,
    windowMs: Long = 500L,
  ): Long {
    val windowFrames = max(1, (windowMs / frameMs).toInt())
    val first = (fromMs / frameMs).toInt().coerceIn(0, frameEnergies.size - 1)
    val last = (toMs / frameMs).toInt().coerceIn(0, frameEnergies.size - 1)
    if (last - first < windowFrames) return (fromMs + toMs) / 2

    // Somma scorrevole: l'alternativa e' rifare la somma per ogni posizione, che su un'ora di audio
    // a 20 ms sono centottantamila finestre per novantamila posizioni.
    var sum = 0.0
    for (i in first until first + windowFrames) sum += frameEnergies[i]
    var bestSum = sum
    var bestStart = first

    for (start in first + 1..last - windowFrames) {
      sum += frameEnergies[start + windowFrames - 1] - frameEnergies[start - 1]
      if (sum < bestSum) {
        bestSum = sum
        bestStart = start
      }
    }
    return (bestStart + windowFrames / 2).toLong() * frameMs
  }

  /** Quante richieste servono per un audio di questa durata: il numero che la UI mostra prima di partire. */
  fun estimateChunkCount(totalMs: Long, targetMs: Long): Int {
    if (totalMs <= targetMs) return 1
    return max(1, (totalMs.toDouble() / targetMs).roundToInt())
  }
}
