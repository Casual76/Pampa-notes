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

  /**
   * [pieces] pezzi **uguali**, ognuno tagliato nel silenzio piu' vicino al suo confine ideale.
   *
   * Il confine k-esimo si cerca attorno a `k * totale / pieces`, non a «dove e' finito il pezzo
   * prima piu' la durata»: cosi' gli scarti della ricerca nel silenzio non si sommano, e l'ultimo
   * pezzo non diventa ne' un moncone ne' il doppio degli altri. Quaranta minuti con un tetto di
   * trenta sono due pezzi da venti, non trenta piu' dieci: dieci minuti da soli sono il pezzo in cui
   * Whisper ha meno contesto, e la cucitura in piu' non la paga nessuno.
   *
   * Con un pezzo solo il file si ricodifica intero (un formato che il servizio non prende, o troppo
   * pesante com'e'), senza tagli.
   */
  fun planEqual(
    frameEnergies: FloatArray,
    frameMs: Long = DEFAULT_FRAME_MS,
    pieces: Int,
    overlapMs: Long = DEFAULT_OVERLAP_MS,
    searchWindowMs: Long = DEFAULT_SEARCH_WINDOW_MS,
    minChunkMs: Long = DEFAULT_MIN_CHUNK_MS,
  ): ChunkPlan {
    val totalMs = frameEnergies.size * frameMs
    require(pieces > 0) { "almeno un pezzo" }
    // Pezzi che durerebbero meno del minimo non si fanno: meglio meno pezzi, un po' piu' lunghi.
    val count = if (minChunkMs > 0) min(pieces.toLong(), max(1L, totalMs / minChunkMs)).toInt() else pieces
    if (count <= 1 || frameEnergies.isEmpty()) {
      return ChunkPlan(listOf(ChunkSpec(0, 0, max(totalMs, 0L))), overlapMs, totalMs)
    }

    val cuts = mutableListOf<Long>()
    for (k in 1 until count) {
      val ideal = totalMs * k / count
      val previous = cuts.lastOrNull() ?: 0L
      // La finestra non scavalca il taglio prima ne' la fine: ogni pezzo resta lungo almeno il minimo.
      val from = max(previous + minChunkMs, ideal - searchWindowMs)
      val to = min(totalMs - minChunkMs, ideal + searchWindowMs)
      val cut = if (from >= to) ideal else quietestPoint(frameEnergies, frameMs, from, to)
      if (cut <= previous || cut >= totalMs) continue
      cuts += cut
    }

    val boundaries = listOf(0L) + cuts + listOf(totalMs)
    val chunks = boundaries.dropLast(1).mapIndexed { index, start ->
      val end = min(totalMs, boundaries[index + 1] + if (index < cuts.size) overlapMs else 0L)
      ChunkSpec(index = index, startMs = start, endMs = end)
    }
    return ChunkPlan(chunks, overlapMs, totalMs)
  }

  /** Quante richieste servono per un audio di questa durata: il numero che la UI mostra prima di partire. */
  fun estimateChunkCount(totalMs: Long, targetMs: Long): Int {
    if (totalMs <= targetMs) return 1
    return max(1, (totalMs.toDouble() / targetMs).roundToInt())
  }
}

/** Cosa fare di un file prima di mandarlo: intero com'e', oppure decodificato e in [pieces] pezzi uguali. */
sealed interface ChunkDecision {
  /** Il file va cosi' com'e': niente decodifica, niente ricodifica, niente cuciture. */
  data object Whole : ChunkDecision

  /**
   * Si decodifica e si ricodifica in [pieces] pezzi uguali, tagliati nei silenzi. Con un pezzo solo
   * e' una ricodifica senza tagli: il formato non va bene al servizio, o il file pesa troppo.
   */
  data class Split(val pieces: Int, val pieceMs: Long) : ChunkDecision
}

/**
 * Quando mandare un file intero e in quanti pezzi dividerlo.
 *
 * Una regola sola per i due servizi, con due numeri diversi. Il tetto (`capMs`) e' quanto l'utente
 * vuole al massimo in una richiesta; la **tolleranza** e' quanto si puo' sforare pur di non tagliare:
 * una lezione da quaranta minuti con un tetto di trenta va intera, perche' dieci minuti in piu' sul
 * computer di casa costano solo tempo, e un taglio costa contesto e una cucitura. Oltre, pezzi
 * **uguali** (vedi [ChunkPlanner.planEqual]): `ceil(durata / tetto)`, mai un moncone in fondo.
 *
 * Per Groq la tolleranza e' piccola e c'e' un limite che non si discute, i byte per richiesta: un
 * file intero ci deve stare com'e', e i pezzi ricodificati ci devono stare con margine. Si decide
 * **per parte**: ogni file si trascrive per conto suo, e le parti si mettono in fila solo per
 * ascoltarle.
 *
 * Puro: si prova in JVM.
 */
object ChunkPolicy {

  /** Il computer di casa: dieci minuti oltre il tetto costano solo attesa. */
  const val COMPUTER_TOLERANCE_MS = 10 * 60_000L

  /** Groq: un paio di minuti, che su un pezzo da dieci sono gia' un quinto in piu' di quota per richiesta. */
  const val GROQ_TOLERANCE_MS = 2 * 60_000L

  /**
   * Quanto pesa un secondo ricodificato da [ChunkEncoder]: AAC a 48 kbps, piu' il contenitore. Il
   * margine sul limite ([BYTES_SAFETY]) copre il resto: la busta multipart, e un encoder che sfora.
   */
  const val ENCODED_BYTES_PER_SECOND = 6_200L
  const val BYTES_SAFETY = 0.9

  /**
   * @param capMs il pezzo piu' lungo che si vuole.
   * @param toleranceMs quanto oltre il tetto un file resta intero.
   * @param maxUploadBytes il tetto per richiesta del servizio, se ne ha uno.
   * @param acceptedAsIs il servizio prende questo formato cosi' com'e'.
   */
  fun decide(
    durationMs: Long,
    sizeBytes: Long,
    capMs: Long,
    toleranceMs: Long,
    maxUploadBytes: Long?,
    acceptedAsIs: Boolean,
  ): ChunkDecision {
    require(capMs > 0) { "il tetto di un pezzo deve essere positivo" }
    val fitsBytes = maxUploadBytes == null || sizeBytes <= maxUploadBytes
    val withinLength = durationMs <= capMs + toleranceMs
    if (acceptedAsIs && fitsBytes && withinLength) return ChunkDecision.Whole

    // Da qui si ricodifica comunque. Se la durata ci sta, un pezzo solo: tagliare un file da
    // undici minuti in due solo perche' e' un .amr non ha senso.
    var pieces = if (withinLength) 1 else ceilDiv(durationMs, capMs).toInt()

    // E un pezzo ricodificato deve stare nel limite di byte, con margine. Con i tetti di Groq
    // (minuti, non ore) non capita quasi mai, ma e' un limite vero e un 413 fa perdere il lavoro.
    if (maxUploadBytes != null && durationMs > 0) {
      val budget = (maxUploadBytes * BYTES_SAFETY).toLong().coerceAtLeast(1L)
      val encodedTotal = durationMs * ENCODED_BYTES_PER_SECOND / 1000L
      pieces = max(pieces, ceilDiv(encodedTotal, budget).toInt())
    }
    pieces = max(1, pieces)
    val pieceMs = if (durationMs > 0) ceilDiv(durationMs, pieces.toLong()) else capMs
    return ChunkDecision.Split(pieces, pieceMs)
  }

  private fun ceilDiv(a: Long, b: Long): Long = if (a <= 0) 0 else (a + b - 1) / b
}
