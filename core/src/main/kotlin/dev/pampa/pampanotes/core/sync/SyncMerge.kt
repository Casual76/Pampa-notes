package dev.pampa.pampanotes.core.sync

/**
 * Chi vince, quando la stessa riga e' cambiata qui e altrove. Puro: si prova in JVM.
 *
 * Il confronto e' a **tre vie**: la riga locale, quella remota, e com'era la riga l'ultima volta
 * che i due erano d'accordo ([LocalView.metaHash]). E' quella terza vista che rende innocuo
 * `notes.touch()`: una riga nell'outbox il cui contenuto ha ancora l'impronta di allora non e'
 * cambiata davvero, e il remoto vince senza perdere niente.
 *
 * Quando invece e' cambiata davvero da tutte e due le parti, decide chi e' piu' recente. Con
 * un'eccezione, che e' l'unica cosa che rende accettabile far decidere a una macchina quale
 * versione di una nota vale: **il testo scritto a mano non si perde mai**, da nessuna delle due
 * parti. Chi perde finisce in una nota nuova, «(conflitto)», e la scelta resta a chi l'ha scritto.
 * Se a perdere e' il locale, la copia e' del locale e il remoto si applica; se a perdere e' il
 * remoto, la copia e' del remoto e il locale resta — e sara' il push a portarlo, con la versione
 * remota come base dichiarata.
 */
object SyncMerge {

  enum class Decision {
    /** Il remoto si applica sopra al locale. */
    APPLY,

    /** Il locale resta: e' cambiato davvero ed e' piu' recente. Sara' il push a portarlo. */
    SKIP,

    /** Il remoto si applica, ma prima il locale si salva in una nota di conflitto. */
    APPLY_AND_FORK,

    /** Il locale resta, e il remoto si salva in una nota di conflitto: e' testo di qualcuno. */
    KEEP_AND_FORK_REMOTE,
  }

  /** Quello che si sa della riga locale, prima di decidere. */
  data class LocalView(
    val exists: Boolean,
    /** C'e' una voce nell'outbox. */
    val dirty: Boolean,
    /** L'impronta del contenuto locale adesso. Null se la riga non c'e'. */
    val localHash: String? = null,
    val localUpdatedAt: Long? = null,
    /** L'impronta dell'ultima versione concordata col server. Null se mai sincronizzata. */
    val metaHash: String? = null,
  ) {
    /** Cambiata qui da quando server e dispositivo erano d'accordo: sporca *e* con un contenuto diverso. */
    val locallyChanged: Boolean get() = exists && dirty && localHash != null && localHash != metaHash
  }

  fun decide(table: String, local: LocalView, remote: WireChange): Decision {
    // Cancellare quello che non c'e': niente da fare (l'eventuale voce fantasma la toglie l'applier).
    if (!local.exists) return if (remote.isDelete) Decision.SKIP else Decision.APPLY

    if (!local.locallyChanged) return Decision.APPLY

    // Stesso contenuto da tutte e due le parti: l'unica differenza e' chi l'ha scritto prima.
    if (!remote.isDelete && remote.hash.isNotEmpty() && remote.hash == local.localHash) return Decision.APPLY

    val localNewer = (local.localUpdatedAt ?: 0L) > remote.updatedAt

    // Per tutto quello che non e' una nota vale l'ultimo che ha scritto, senza copie: sono righe
    // che si rifanno (una sessione si rinomina, una parte si riordina), non testo.
    if (table != "notes") return if (localNewer) Decision.SKIP else Decision.APPLY

    // Una cancellazione remota non porta testo da salvare: se il locale e' piu' recente resta
    // (e il push lo fara' rinascere), altrimenti se ne va, ma in una copia.
    if (remote.isDelete) return if (localNewer) Decision.SKIP else Decision.APPLY_AND_FORK

    return if (localNewer) Decision.KEEP_AND_FORK_REMOTE else Decision.APPLY_AND_FORK
  }

  /** L'ordine in cui si applicano le tabelle: un figlio non arriva mai prima del padre. */
  val APPLY_ORDER: List<String> = listOf("folders", "notes", "sources", "sessions", "audio_parts", "transcripts", "export_presets")

  fun orderOf(table: String): Int = APPLY_ORDER.indexOf(table).let { if (it < 0) APPLY_ORDER.size else it }
}
