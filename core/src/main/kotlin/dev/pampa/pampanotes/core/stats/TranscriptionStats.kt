package dev.pampa.pampanotes.core.stats

import dev.pampa.pampanotes.core.db.TranscribedSessionRow
import dev.pampa.pampanotes.core.db.TranscriptionRunEntity
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Le statistiche delle trascrizioni, calcolate da righe e basta: niente database, niente Android,
 * provate in JVM (`TranscriptionStatsTest`).
 *
 * Divertenti ma oneste. Ogni numero che finisce sulla home deve reggere se qualcuno lo rifa' a mano:
 * una tessera che manca e' meglio di una che dice «200× il tempo reale» perche' meta' dei pezzi era
 * gia' su disco. Per questo ogni record ha una soglia sotto la quale non si mostra.
 */

/** Quante volte il tempo reale: 40 minuti trascritti in 48 secondi fanno 50. */
fun realtimeFactor(audioMs: Long, wallMs: Long): Double? =
  if (audioMs <= 0 || wallMs <= 0) null else audioMs.toDouble() / wallMs

/** Una corsa si presta a misurare la velocita'? Vedi [TranscriptionStats.MIN_RUN_AUDIO_MS]. */
val TranscriptionRunEntity.measurable: Boolean
  get() = !resumed && wallMs > 0 && audioMs >= TranscriptionStats.MIN_RUN_AUDIO_MS

/** La velocita' di questa corsa, se ha senso dirla. */
val TranscriptionRunEntity.speed: Double? get() = if (measurable) realtimeFactor(audioMs, wallMs) else null

/** Quanto e' durata una lezione: le parti coperte, o l'ultimo segmento se le durate mancano. */
val TranscribedSessionRow.lessonMs: Long get() = if (audioMs > 0) audioMs else spokenEndMs

/** Il nome con cui una lezione si riconosce: il titolo della sessione, se ne ha uno, se no la nota. */
val TranscribedSessionRow.displayTitle: String get() = sessionTitle.ifBlank { noteTitle }

/** Il ritmo di una lezione: parole al minuto sull'audio intero, pause comprese. */
fun wordsPerMinute(words: Int, audioMs: Long): Int? =
  if (words <= 0 || audioMs <= 0) null else (words * 60_000.0 / audioMs).roundToInt()

data class SpeedStats(
  /** Pesata sull'audio: tutto l'audio diviso tutto il tempo, non la media dei rapporti. */
  val average: Double,
  val best: Double,
  /** Su cosa ha girato il record: "groq", "cuda", "cpu", o null. */
  val bestDevice: String?,
  val runs: Int,
)

data class Pace(val session: TranscribedSessionRow, val wordsPerMinute: Int)

data class TranscriptionStats(
  /** Le ore di lezione trascritte, contate dalle trascrizioni: vale anche per quelle arrivate dal sync. */
  val transcribedMs: Long = 0,
  val words: Long = 0,
  val lessons: Int = 0,
  /** Null finche' questo dispositivo non ha misurato niente. */
  val speed: SpeedStats? = null,
  /** Chi parla piu' svelto. Null con meno di due lezioni da confrontare. */
  val fastestPace: Pace? = null,
  /** La lezione piu' lunga. Null con meno di due lezioni: un record senza gara non e' un record. */
  val longest: TranscribedSessionRow? = null,
) {
  val isEmpty: Boolean get() = lessons == 0 && speed == null

  companion object {
    /** Sotto il mezzo minuto il tempo fisso di una richiesta pesa piu' dell'audio: la velocita' e' rumore. */
    const val MIN_RUN_AUDIO_MS = 30_000L

    /** Il ritmo si confronta solo su lezioni vere: una nota vocale da venti secondi non e' una lezione. */
    const val MIN_PACE_AUDIO_MS = 5 * 60_000L

    /**
     * Oltre questo, e' la durata a essere sbagliata e non il professore a essere veloce: un
     * banditore d'asta arriva a 250. Una lezione cosi' si lascia fuori dal record, non si mostra.
     */
    const val MAX_PLAUSIBLE_WPM = 400

    fun aggregate(runs: List<TranscriptionRunEntity>, sessions: List<TranscribedSessionRow>): TranscriptionStats {
      val lessons = sessions.filter { it.words > 0 || it.lessonMs > 0 }

      val measured = runs.filter { it.measurable }
      val speed = if (measured.isEmpty()) {
        null
      } else {
        val best = measured.maxBy { it.audioMs.toDouble() / it.wallMs }
        SpeedStats(
          average = measured.sumOf { it.audioMs }.toDouble() / measured.sumOf { it.wallMs },
          best = best.audioMs.toDouble() / best.wallMs,
          bestDevice = best.device,
          runs = measured.size,
        )
      }

      val paced = lessons.mapNotNull { row ->
        if (row.lessonMs < MIN_PACE_AUDIO_MS) return@mapNotNull null
        val wpm = wordsPerMinute(row.words, row.lessonMs) ?: return@mapNotNull null
        if (wpm > MAX_PLAUSIBLE_WPM) null else Pace(row, wpm)
      }
      val timed = lessons.filter { it.lessonMs > 0 }

      return TranscriptionStats(
        transcribedMs = lessons.sumOf { it.lessonMs },
        words = lessons.sumOf { it.words.toLong() },
        lessons = lessons.size,
        speed = speed,
        fastestPace = paced.takeIf { it.size >= 2 }?.maxBy { it.wordsPerMinute },
        longest = timed.takeIf { it.size >= 2 }?.maxBy { it.lessonMs },
      )
    }
  }
}

/**
 * Le forme dei numeri che la notifica, la sessione e la home condividono. Le unita' (h, min, s) sono
 * le stesse in italiano e in inglese, quindi stanno qui e non nelle risorse.
 */
object StatsFormat {

  /**
   * «50×» sopra il dieci, «4,5×» sotto, «0,6×» quando il servizio e' stato piu' lento del parlato:
   * una cifra decimale in piu' sotto il dieci perche' fra 1× e 2× c'e' tutta la differenza fra
   * «ci ha messo quanto la lezione» e «la meta'».
   */
  fun factor(value: Double, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getNumberInstance(locale).apply {
      maximumFractionDigits = if (value < 10) 1 else 0
      minimumFractionDigits = 0
    }
    return format.format(if (value < 10) value else value.roundToLong().toDouble()) + "×"
  }

  /**
   * Una durata da dire in una frase: «48 s», «3 min 5 s», «40 min», «1 h 12 min». I secondi si
   * lasciano cadere oltre i dieci minuti, dove nessuno li conta piu'.
   */
  fun duration(millis: Long): String {
    val totalSeconds = ((millis + 500) / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
      hours > 0 && minutes > 0 -> "$hours h $minutes min"
      hours > 0 -> "$hours h"
      minutes >= 10 -> "$minutes min"
      minutes > 0 && seconds > 0 -> "$minutes min $seconds s"
      minutes > 0 -> "$minutes min"
      else -> "$seconds s"
    }
  }

  /** «5.214» in italiano, «5,214» in inglese: le migliaia come le scrive la lingua del telefono. */
  fun count(value: Long, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getIntegerInstance(locale).format(value)
}
