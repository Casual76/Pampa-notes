package dev.pampa.pampanotes.core.settings

/**
 * L'ultima sessione ascoltata su questo dispositivo, e dove ci si era fermati: la scheda «Riprendi
 * ad ascoltare» della home.
 *
 * Per dispositivo e non sincronizzata: sul tablet si ascolta un'altra lezione che sul telefono, e
 * «riprendi» vuol dire riprendere qui. Fa anche da protezione: una sessione che si sta ascoltando
 * non e' una sessione da togliere dal telefono per fare spazio.
 *
 * @param positionMs il punto, in tempo di sessione (vedi `SessionPlayer`).
 * @param at quando e' stata salvata, in millisecondi epoch.
 * @param durationMs quanto dura la sessione, per sapere se e' finita senza aprire il database; zero
 *   se non si sapeva.
 */
data class LastListened(
  val sessionId: String,
  val positionMs: Long,
  val at: Long,
  val durationMs: Long = 0,
) {
  /** Ascoltata quasi tutta: non c'e' niente da riprendere. */
  val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * FINISHED_FRACTION

  internal fun encode(): String = "$sessionId|$positionMs|$at|$durationMs"

  companion object {
    /** Oltre il 95% la lezione e' finita: gli ultimi secondi sono saluti e sedie che si spostano. */
    const val FINISHED_FRACTION = 0.95

    internal fun decode(raw: String?): LastListened? {
      val parts = raw?.split('|') ?: return null
      if (parts.size < 3 || parts[0].isBlank()) return null
      return LastListened(
        sessionId = parts[0],
        positionMs = parts[1].toLongOrNull() ?: return null,
        at = parts[2].toLongOrNull() ?: return null,
        durationMs = parts.getOrNull(3)?.toLongOrNull() ?: 0,
      )
    }
  }
}
