package dev.pampa.pampanotes.core.playback

import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.transcription.SessionAssembler

/**
 * «Salta i silenzi»: il lettore passa sopra i tratti in cui nessuno parla.
 *
 * Una registrazione di diciannove ore ne ha dieci di niente, e ascoltarla vuol dire tenere il dito
 * sullo scrubber. I silenzi non si misurano sull'audio — il telefono non lo decodifica per questo —
 * ma sulla trascrizione grezza: fra la fine di un segmento e l'inizio del successivo, nel tempo della
 * sessione, non e' stato detto niente. E' la stessa misura della riga «— 16 min di silenzio —».
 *
 * Due regole tengono fuori i falsi silenzi:
 * - **solo dove la trascrizione c'e'**: una parte senza segmenti (non ancora trascritta, o
 *   importata dopo) non e' silenziosa, e' sconosciuta; i suoi millisecondi non si saltano mai;
 * - **solo le pause lunghe** ([MIN_GAP_MS]): chi parla si ferma anche cinque secondi per pensare, e
 *   un lettore che gli toglie le pause lo rende affannato.
 *
 * Dentro una pausa si entra un secondo ([TAIL_MS]) — i tempi di fine di Whisper tagliano spesso
 * l'ultima sillaba — e si atterra un secondo prima della voce dopo ([LEAD_MS]), per non cominciare a
 * meta' parola.
 *
 * **Il dito vince.** La classe tiene la memoria di una sola cosa: il silenzio in cui l'utente ha
 * scelto di andare ([onUserSeek]). Chi trascina lo scrubber dentro un silenzio vuole sentirlo — per
 * controllare che sia davvero vuoto, o perche' la trascrizione ha perso una frase — e il lettore non
 * lo riporta avanti finche' la posizione non esce da quel silenzio. Tutto il resto (arrivarci
 * suonando, la ripresa dal punto salvato, l'inizio muto di una registrazione) si salta.
 *
 * Puro, e il ViewModel lo chiama a ogni battito del lettore: niente Android, niente coroutine.
 */
class SilenceSkipper(val gaps: List<Gap>) {

  /** Un tratto muto della sessione: da [startMs] (fine della voce) a [endMs] (voce di nuovo). */
  data class Gap(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs

    /** Da qui in poi si salta: il primo secondo di silenzio si lascia suonare. */
    val skipFromMs: Long get() = startMs + TAIL_MS

    /** Dove si atterra: un secondo prima che si ricominci a parlare. */
    val resumeAtMs: Long get() = (endMs - LEAD_MS).coerceAtLeast(skipFromMs)

    operator fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs
  }

  /** Un salto deciso: da dove, a dove. Il ViewModel ne fa il seek e il «saltati 16 min». */
  data class Jump(val fromMs: Long, val toMs: Long) {
    val skippedMs: Long get() = toMs - fromMs
  }

  /** Il silenzio in cui l'utente e' andato da solo: finche' ci si sta dentro, non si salta. */
  private var respected: Gap? = null

  /** L'utente ha chiesto questo punto (scrubber, frecce, un tocco): se e' un silenzio, lo si rispetta. */
  fun onUserSeek(positionMs: Long) {
    respected = gapAt(gaps, positionMs)
  }

  /**
   * Un battito del lettore: la posizione di adesso, e se sta suonando.
   *
   * Restituisce il salto da fare, o null. In pausa non si salta mai — un lettore fermo che si sposta
   * da solo sembra rotto — ma si tiene il conto del silenzio rispettato, cosi' uscirne lo dimentica.
   */
  fun onTick(positionMs: Long, playing: Boolean): Jump? {
    val gap = gapAt(gaps, positionMs)
    respected = respected?.takeIf { positionMs in it }
    if (gap == null || !playing || gap == respected) return null
    if (positionMs < gap.skipFromMs || positionMs >= gap.resumeAtMs) return null
    return Jump(positionMs, gap.resumeAtMs)
  }

  companion object {
    /**
     * Da quanto una pausa e' un silenzio da saltare. Dodici secondi: piu' di qualunque pausa di chi
     * parla (anche il professore che scrive alla lavagna di solito borbotta), molto meno dei minuti
     * vuoti di una registrazione lasciata accesa.
     */
    const val MIN_GAP_MS = 12_000L

    /** Quanto del silenzio si lascia suonare prima di saltare. */
    const val TAIL_MS = 1_000L

    /** Quanto prima della voce si atterra. */
    const val LEAD_MS = 1_000L

    /**
     * I silenzi di una sessione, dai segmenti della grezza e dalle parti in ordine.
     *
     * Coperte sono le parti che hanno almeno un segmento; dentro di loro, silenzio e' tutto quello
     * che nessun segmento copre (l'inizio prima della prima frase compreso, e la fine dopo l'ultima).
     * Fra due parti coperte il silenzio attraversa il confine, perche' nel tempo della sessione le
     * parti stanno in fila senza buchi.
     */
    fun gapsOf(
      segments: List<SegmentEntity>,
      parts: List<SessionAssembler.Part>,
      minGapMs: Long = MIN_GAP_MS,
    ): List<Gap> {
      if (segments.isEmpty() || parts.isEmpty()) return emptyList()
      val transcribed = segments.mapTo(HashSet()) { it.partId }
      val covered = mutableListOf<Gap>()
      var offset = 0L
      parts.forEach { part ->
        if (part.id in transcribed && part.durationMs > 0) covered += Gap(offset, offset + part.durationMs)
        offset += part.durationMs
      }
      val speech = segments.map { Gap(it.sessionStartMs, maxOf(it.sessionStartMs + 1, it.sessionEndMs)) }
      return gaps(speech, covered, minGapMs)
    }

    /**
     * Il silenzio dentro [covered] meno [speech], a pezzi lunghi almeno [minGapMs].
     *
     * Le parti coperte adiacenti si fondono prima (un silenzio a cavallo fra due registrazioni e' uno
     * solo), e la voce si ordina e si fonde (due segmenti che si sovrappongono sono una voce sola).
     */
    fun gaps(speech: List<Gap>, covered: List<Gap>, minGapMs: Long = MIN_GAP_MS): List<Gap> {
      val areas = merge(covered)
      val voices = merge(speech)
      val result = mutableListOf<Gap>()
      var v = 0
      areas.forEach { area ->
        var cursor = area.startMs
        while (v < voices.size && voices[v].endMs <= area.startMs) v++
        var i = v
        while (i < voices.size && voices[i].startMs < area.endMs) {
          val voice = voices[i]
          if (voice.startMs > cursor) result += Gap(cursor, voice.startMs)
          cursor = maxOf(cursor, voice.endMs)
          i++
        }
        if (cursor < area.endMs) result += Gap(cursor, area.endMs)
      }
      return result.filter { it.durationMs >= minGapMs }
    }

    /** Il silenzio che contiene [positionMs], se ce n'e' uno: ricerca binaria, i silenzi sono in ordine. */
    fun gapAt(gaps: List<Gap>, positionMs: Long): Gap? {
      var low = 0
      var high = gaps.lastIndex
      while (low <= high) {
        val middle = (low + high) ushr 1
        val gap = gaps[middle]
        when {
          positionMs < gap.startMs -> high = middle - 1
          positionMs >= gap.endMs -> low = middle + 1
          else -> return gap
        }
      }
      return null
    }

    /**
     * Dove riprende la voce, se [positionMs] sta nel tratto da saltare di un silenzio; null altrimenti.
     * La forma senza memoria di [onTick], per chi vuole solo sapere.
     */
    fun nextSpeech(positionMs: Long, gaps: List<Gap>): Long? {
      val gap = gapAt(gaps, positionMs) ?: return null
      return gap.resumeAtMs.takeIf { positionMs >= gap.skipFromMs && positionMs < it }
    }

    private fun merge(spans: List<Gap>): List<Gap> {
      if (spans.isEmpty()) return emptyList()
      val sorted = spans.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
      val result = mutableListOf<Gap>()
      sorted.forEach { span ->
        val last = result.lastOrNull()
        if (last != null && span.startMs <= last.endMs) {
          result[result.lastIndex] = Gap(last.startMs, maxOf(last.endMs, span.endMs))
        } else {
          result += span
        }
      }
      return result
    }
  }
}
