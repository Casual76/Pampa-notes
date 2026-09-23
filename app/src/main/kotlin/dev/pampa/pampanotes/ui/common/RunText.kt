package dev.pampa.pampanotes.ui.common

import android.content.res.Resources
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.TranscriptionRunEntity
import dev.pampa.pampanotes.core.repo.StatsRepository
import dev.pampa.pampanotes.core.stats.StatsFormat
import dev.pampa.pampanotes.core.stats.speed

/**
 * Le frasi che raccontano una trascrizione finita: la notifica e la riga sotto la sessione dicono
 * le stesse cose con le stesse parole, e stanno qui perche' una delle due non si separi dall'altra.
 */
object RunText {

  /**
   * «40 min in 48 s — 50× il tempo reale · 5.214 parole».
   *
   * Senza una corsa misurabile (ripresa dopo un'interruzione, o statistiche non scritte) restano la
   * durata e le parole: meglio due fatti che un record gonfiato.
   */
  fun notification(res: Resources, run: TranscriptionRunEntity?, wordCount: Int): String {
    val words = res.getQuantityString(R.plurals.stats_words, wordCount, StatsFormat.count(wordCount.toLong()))
    val speed = run?.speed
    val head = when {
      run == null -> null
      speed != null -> {
        val timing = res.getString(
          if (run.device == StatsRepository.DEVICE_CPU) R.string.stats_run_timing_cpu else R.string.stats_run_timing,
          StatsFormat.duration(run.audioMs),
          StatsFormat.duration(run.wallMs),
        )
        timing + " — " + res.getString(R.string.stats_run_factor, StatsFormat.factor(speed))
      }
      run.audioMs > 0 -> StatsFormat.duration(run.audioMs)
      else -> null
    }
    return listOfNotNull(head, words).joinToString(" · ")
  }

  /**
   * La riga piccola sotto la trascrizione: «Trascritta in 48 s: 50× il tempo reale · si parla a
   * 148 parole al minuto». Null quando non c'e' niente di vero da dire.
   *
   * @param pace le parole al minuto della lezione; si calcola dalla trascrizione, quindi c'e' anche
   *   per quelle arrivate da un altro dispositivo, che di velocita' non sanno niente.
   */
  fun sessionLine(res: Resources, run: TranscriptionRunEntity?, pace: Int?): String? {
    val speed = run?.speed
    val timing = if (run != null && speed != null) {
      res.getString(
        if (run.device == StatsRepository.DEVICE_CPU) R.string.session_run_summary_cpu else R.string.session_run_summary,
        StatsFormat.duration(run.wallMs),
        StatsFormat.factor(speed),
      )
    } else {
      null
    }
    val spoken = pace?.let { res.getString(R.string.session_pace, it) }
    val parts = listOfNotNull(timing, spoken)
    if (parts.isEmpty()) return null
    // Da sola, la seconda frase comincia la riga: maiuscola, come ogni riga.
    return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
  }
}
