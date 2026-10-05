package dev.pampa.pampanotes.core.stats

import kotlin.math.log10
import kotlinx.serialization.Serializable

/**
 * Il volume di una registrazione, misurato sul file: un'aggiunta per ridere («la lezione di Storia:
 * 64 dB, come una conversazione»), ma misurata davvero.
 *
 * Si guarda l'audio a finestre da un secondo ([WINDOW_MS]): di ognuna la potenza media (il quadrato
 * dei campioni), da cui la media, la finestra piu' forte e quanto tempo sta sotto la soglia del
 * silenzio. La media e' quella dell'energia (il «livello equivalente» dei fonometri), non quella dei
 * decibel: un minuto di applausi pesa per quello che e', e un'ora di silenzio non la porta a zero.
 *
 * Il numero vero e' in **dBFS**, decibel sotto il fondo scala del file: dice quanto e' forte il
 * file, non la stanza, perche' il registratore del telefono alza e abbassa il guadagno da se'. I
 * «dB» che la schermata mostra accanto sono una stima a orecchio ([estimatedSpl]) con uno scarto
 * fisso, e la schermata lo dice.
 *
 * Puro, niente Android: la decodifica sta in `LoudnessMeter`, che consegna qui i campioni.
 */
object Loudness {
  const val WINDOW_MS = 1_000L

  /** Sotto questa, una finestra e' silenzio: un'aula vuota registrata da un telefono sta li' intorno. */
  const val QUIET_DBFS = -50f

  /** Il pavimento: un file di zeri non ha un logaritmo. */
  const val FLOOR_DBFS = -120f

  /**
   * Da dBFS a decibel «della stanza», a occhio: un telefono che registra una lezione dal banco
   * sta intorno ai −30 dBFS, e una voce a qualche metro sono 60 dB. Non e' una taratura (ogni
   * microfono e ogni guadagno automatico e' diverso): e' il numero che rende il paragone sensato.
   */
  const val SPL_OFFSET = 90f

  /**
   * La versione del calcolo: un risultato salvato con un'altra si rifa'. Da alzare quando cambia
   * qualcosa di quello che si misura (finestra, soglia).
   */
  const val VERSION = 1

  fun dbfs(meanPower: Double): Float =
    if (meanPower <= 1e-12) FLOOR_DBFS else (10.0 * log10(meanPower)).toFloat().coerceAtLeast(FLOOR_DBFS)

  fun estimatedSpl(dbfs: Float): Int = (dbfs + SPL_OFFSET).toInt().coerceAtLeast(0)

  /** A cosa somiglia un volume, in decibel stimati: la riga che fa ridere. */
  fun comparisonOf(spl: Int): LoudnessComparison = LoudnessComparison.entries.last { spl >= it.fromDb }

  /**
   * Le parti di una nota messe insieme, nell'ordine in cui stanno.
   *
   * @param parts per ogni parte misurata, la sessione, il punto in cui la parte comincia nella
   *   sessione (vedi `SessionAssembler.offsets`) e la misura.
   */
  fun aggregate(parts: List<PlacedLoudness>): NoteLoudness? {
    val measured = parts.filter { it.loudness.windows > 0 }
    if (measured.isEmpty()) return null
    val windows = measured.sumOf { it.loudness.windows.toLong() }
    val power = measured.sumOf { it.loudness.powerSum }
    val loudest = measured.maxBy { it.loudness.peakDbfs }
    return NoteLoudness(
      meanDbfs = dbfs(power / windows),
      peakDbfs = loudest.loudness.peakDbfs,
      peakSessionId = loudest.sessionId,
      peakAtMs = loudest.offsetMs + loudest.loudness.peakAtMs,
      quietMs = measured.sumOf { it.loudness.quietWindows.toLong() } * WINDOW_MS,
      measuredMs = measured.sumOf { it.loudness.durationMs },
      parts = measured.size,
    )
  }
}

/** Dal piu' piano al piu' forte: [Loudness.comparisonOf] prende l'ultimo che il volume raggiunge. */
enum class LoudnessComparison(val fromDb: Int) {
  WHISPER(0),
  LIBRARY(35),
  QUIET_OFFICE(45),
  CONVERSATION(55),
  BREAK_TIME(65),
  TRAFFIC(75),
  BLENDER(85),
  CONCERT(95),
}

/**
 * La misura di un file, quella che si salva (per impronta del file: lo stesso audio ha lo stesso
 * volume su ogni dispositivo, e non si decodifica due volte).
 */
@Serializable
data class PartLoudness(
  val durationMs: Long,
  /** Le finestre misurate, l'ultima anche se incompleta. */
  val windows: Int,
  /** La somma delle potenze medie delle finestre: diviso [windows] da' la media dell'energia. */
  val powerSum: Double,
  val peakDbfs: Float,
  /** Dove comincia la finestra piu' forte, dall'inizio del file. */
  val peakAtMs: Long,
  val quietWindows: Int,
  val version: Int = Loudness.VERSION,
)

/** Una misura al suo posto nella nota. */
data class PlacedLoudness(val sessionId: String, val offsetMs: Long, val loudness: PartLoudness)

/** Il volume di una nota intera. */
data class NoteLoudness(
  val meanDbfs: Float,
  val peakDbfs: Float,
  /** La sessione in cui sta il momento piu' forte, e quando, in tempo di sessione. */
  val peakSessionId: String,
  val peakAtMs: Long,
  val quietMs: Long,
  val measuredMs: Long,
  val parts: Int,
)

/**
 * Raccoglie i campioni di un file, un blocco alla volta, e alla fine da' la sua [PartLoudness].
 *
 * La frequenza puo' cambiare a meta' (il decoder la corregge al primo blocco): la finestra si conta
 * in campioni della frequenza del blocco, cosi' resta un secondo comunque.
 */
class LoudnessAccumulator {
  private var windowSum = 0.0
  private var windowSamples = 0L
  private var samplesPerWindow = 0L
  private var elapsedMs = 0.0
  private var windowStartMs = 0.0

  private var windows = 0
  private var powerSum = 0.0
  private var peakPower = -1.0
  private var peakAtMs = 0L
  private var quiet = 0

  fun add(samples: ShortArray, sampleRate: Int) {
    if (sampleRate <= 0) return
    samplesPerWindow = sampleRate * Loudness.WINDOW_MS / 1000L
    val msPerSample = 1000.0 / sampleRate
    for (sample in samples) {
      val normalized = sample / 32768.0
      windowSum += normalized * normalized
      windowSamples++
      elapsedMs += msPerSample
      if (windowSamples >= samplesPerWindow) closeWindow()
    }
  }

  fun finish(): PartLoudness {
    // L'ultima finestra incompleta conta lo stesso, se e' abbastanza lunga da dire qualcosa.
    if (windowSamples > 0 && windowSamples * 10 >= samplesPerWindow) closeWindow()
    return PartLoudness(
      durationMs = elapsedMs.toLong(),
      windows = windows,
      powerSum = powerSum,
      peakDbfs = if (windows == 0) Loudness.FLOOR_DBFS else Loudness.dbfs(peakPower),
      peakAtMs = peakAtMs,
      quietWindows = quiet,
    )
  }

  private fun closeWindow() {
    val power = windowSum / windowSamples
    windows++
    powerSum += power
    if (power > peakPower) {
      peakPower = power
      peakAtMs = windowStartMs.toLong()
    }
    if (Loudness.dbfs(power) < Loudness.QUIET_DBFS) quiet++
    windowSum = 0.0
    windowSamples = 0
    windowStartMs = elapsedMs
  }
}
