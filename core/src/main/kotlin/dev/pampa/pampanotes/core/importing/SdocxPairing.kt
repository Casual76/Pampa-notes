package dev.pampa.pampanotes.core.importing

/**
 * Quale registrazione e' quale, dentro un `.sdocx`.
 *
 * Samsung Notes chiama le voci dello ZIP `media/<slot>@<inizio>_<caso>.m4a`: lo slot e' un posto
 * libero, non un ordine («Voce 001» di Fichte e' `3@…`, «Voce 002» e' `0@…`), e `<inizio>` sono i
 * secondi epoch in esadecimale del momento in cui si e' premuto «registra». E' l'unica ora vera che il
 * file porta: quella di `mediaInfo.dat` e' l'ultima volta che Samsung Notes ha riscritto il file, e
 * condividendo la nota le riscrive tutte nello stesso istante (Impressionismo, 28/09: quattro lezioni
 * di quattro giorni diversi, tutte «12:01:37»). Anche l'ordine dei record di `mediaInfo.dat` non e'
 * quello delle registrazioni: li' era 0, 1, 3, 2, e accoppiati per posizione ai nomi di `note.note`
 * «Voce 003» e «Voce 004» si erano scambiati il file.
 *
 * I nomi si ricontrollano con la durata, che `note.note` scrive accanto a ogni nome («00:48:30») e
 * che il file misurato conferma entro il secondo. Puro: si prova in JVM.
 */
object SdocxPairing {

  /** Quanto possono differire la durata scritta da Samsung Notes e quella misurata sul file. */
  const val TOLERANCE_MS = 5_000L

  private val ENTRY = Regex("""(?:^|/)\d+@([0-9a-fA-F]{8})_[0-9a-fA-F]+\.\w+$""")

  /** L'inizio di una registrazione dal nome della sua voce nello ZIP, in millisecondi epoch; null se il nome non lo dice o non e' un'ora credibile. */
  fun startFromEntryName(entryName: String, now: Long): Long? {
    val hex = ENTRY.find(entryName)?.groupValues?.get(1) ?: return null
    val millis = hex.toLong(16) * 1000
    return millis.takeIf { it in EPOCH_2010_MS..(now + FUTURE_SLACK_MS) }
  }

  /**
   * Le ore di `mediaInfo.dat` sono tutte lo stesso istante: Samsung Notes le ha riscritte insieme,
   * condividendo, e non dicono niente di quando si e' registrato. Con una registrazione sola non si
   * puo' sapere, e vale l'ora che c'e'.
   */
  fun shareStamped(stamps: List<Long?>): Boolean {
    val known = stamps.filterNotNull()
    return known.size >= 2 && known.max() - known.min() <= SAME_INSTANT_MS
  }

  /** L'inizio: dal nome della voce, poi dall'ora di `mediaInfo.dat` se non e' quella della condivisione, altrimenti non si sa. */
  fun startOf(entryName: String, mediaStamp: Long?, stampedAtShare: Boolean, now: Long): Long? =
    startFromEntryName(entryName, now) ?: mediaStamp?.takeUnless { stampedAtShare }

  /**
   * Per ogni registrazione (nell'ordine in cui sono state fatte) l'indice della voce di `note.note`
   * che le corrisponde, o null.
   *
   * Prima la posizione: se tutte le durate misurate tornano, e' quella (anche quando qualche file non
   * si e' potuto misurare). Se una non torna, ogni voce va alla registrazione di durata piu' vicina,
   * cominciando dalle coppie piu' vicine, e solo entro [toleranceMs]: un nome che non trova la sua
   * registrazione resta senza, e chi chiama scrive «Registrazione 03» invece di un nome sbagliato.
   *
   * @param voiceMs le durate che `note.note` scrive accanto ai nomi, nel suo ordine.
   * @param actualMs le durate misurate sui file; null se il file non si e' potuto misurare.
   */
  fun assign(voiceMs: List<Long>, actualMs: List<Long?>, toleranceMs: Long = TOLERANCE_MS): List<Int?> {
    if (voiceMs.isEmpty()) return actualMs.map { null }
    val positional = voiceMs.size == actualMs.size && actualMs.indices.all { index ->
      val actual = actualMs[index] ?: return@all true
      kotlin.math.abs(actual - voiceMs[index]) <= toleranceMs
    }
    if (positional) return actualMs.indices.map { it }

    val result = arrayOfNulls<Int>(actualMs.size)
    val usedVoices = BooleanArray(voiceMs.size)
    val pairs = buildList {
      actualMs.forEachIndexed { recording, actual ->
        if (actual == null) return@forEachIndexed
        voiceMs.forEachIndexed { voice, declared ->
          val distance = kotlin.math.abs(actual - declared)
          if (distance <= toleranceMs) add(Triple(distance, recording, voice))
        }
      }
    }.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
    for ((_, recording, voice) in pairs) {
      if (result[recording] != null || usedVoices[voice]) continue
      result[recording] = voice
      usedVoices[voice] = true
    }
    return result.toList()
  }

  private const val EPOCH_2010_MS = 1_262_304_000_000L
  private const val FUTURE_SLACK_MS = 24 * 60 * 60_000L
  private const val SAME_INSTANT_MS = 1_000L
}
