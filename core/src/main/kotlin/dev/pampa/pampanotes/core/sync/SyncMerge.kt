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
 *
 * La terza vista vale anche dall'altra parte: un remoto che ha **la stessa impronta della versione
 * concordata** non porta niente di nuovo — e' una riga riscaricata (un pull ripartito da piu'
 * indietro, un riallineamento) — e una modifica fatta qui sopra quella versione le e' successiva
 * per costruzione, qualunque cosa dicano gli orologi. Senza, ogni riga riscaricata dopo una modifica
 * locale diventava una nota di conflitto con se stessa.
 *
 * Il segno «in trascrizione su» delle sessioni non e' una modifica (vedi `TranscribingMarker`), in
 * nessuno dei due versi: una sessione diversa dalla concordata solo per il segno non e' «cambiata
 * qui», e non e' «cambiata altrove».
 */
object SyncMerge {

  enum class Decision {
    /** Il remoto si applica sopra al locale. */
    APPLY,

    /**
     * Il locale resta: e' cambiato davvero ed e' piu' recente, oppure e' stato cancellato qui e il
     * remoto non ha niente di nuovo. Sara' il push a portarlo.
     */
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
    /**
     * L'impronta del locale senza il segno «in trascrizione su» ([SyncCodec.bareHash]). Solo per
     * una sessione che il segno ce l'ha; null altrimenti.
     */
    val localBareHash: String? = null,
  ) {
    /**
     * Cambiata qui da quando server e dispositivo erano d'accordo: sporca *e* con un contenuto
     * diverso — e non solo per il segno, che da solo non e' una modifica.
     */
    val locallyChanged: Boolean
      get() = exists && dirty && localHash != null && localHash != metaHash && !(localBareHash != null && localBareHash == metaHash)

    /**
     * Cancellata qui, e il tombstone non e' ancora salito: la riga non c'e', la voce nell'outbox si',
     * e il server la conosceva (senza una versione concordata il push non avrebbe niente da dire).
     */
    val pendingDelete: Boolean get() = !exists && dirty && metaHash != null
  }

  fun decide(table: String, local: LocalView, remote: WireChange): Decision {
    if (!local.exists) {
      // Cancellare quello che non c'e': niente da fare (l'eventuale voce fantasma la toglie l'applier).
      if (remote.isDelete) return Decision.SKIP
      // Cancellata qui, e altrove nessuno l'ha cambiata davvero: la cancellazione resta, e salira'
      // col push. Prima vinceva qualunque remoto, anche il solo segno di una trascrizione, e la
      // riga rinasceva vuota mentre i tombstone dei figli, gia' nell'outbox, salivano lo stesso: la
      // nota tornava, le sue registrazioni sparivano ovunque.
      if (local.pendingDelete && !changedSinceAgreed(table, local, remote)) return Decision.SKIP
      // Altrimenti il remoto porta qualcosa di nuovo — testo, un titolo, una trascrizione — e vince
      // sulla cancellazione, come una modifica fatta qui vince su una cancellazione remota. I figli
      // tornano col riallineamento dei figli (vedi SyncApplier, `revived`).
      return Decision.APPLY
    }

    if (!local.locallyChanged) return Decision.APPLY

    // Stesso contenuto da tutte e due le parti: l'unica differenza e' chi l'ha scritto prima.
    if (!remote.isDelete && remote.hash.isNotEmpty() && remote.hash == local.localHash) return Decision.APPLY

    // Il remoto e' la versione su cui si e' scritto qui: riscaricata, non nuova. Vince il locale,
    // senza guardare l'orologio: e' successivo per costruzione.
    if (!remote.isDelete && remote.hash.isNotEmpty() && remote.hash == local.metaHash) return Decision.SKIP

    val localNewer = (local.localUpdatedAt ?: 0L) > remote.updatedAt

    // Per tutto quello che non e' una nota vale l'ultimo che ha scritto, senza copie: sono righe
    // che si rifanno (una sessione si rinomina, una parte si riordina), non testo.
    if (table != "notes") return if (localNewer) Decision.SKIP else Decision.APPLY

    // Una cancellazione remota non porta testo da salvare: se il locale e' piu' recente resta
    // (e il push lo fara' rinascere), altrimenti se ne va, ma in una copia.
    if (remote.isDelete) return if (localNewer) Decision.SKIP else Decision.APPLY_AND_FORK

    return if (localNewer) Decision.KEEP_AND_FORK_REMOTE else Decision.APPLY_AND_FORK
  }

  /**
   * Il remoto e' diverso dalla versione concordata per qualcosa che non sia il segno «in
   * trascrizione su». Senza una versione concordata tutto e' nuovo.
   */
  fun changedSinceAgreed(table: String, local: LocalView, remote: WireChange): Boolean {
    val base = local.metaHash ?: return true
    if (remote.hash.isNotEmpty() && remote.hash == base) return false
    val payload = remote.payload
    if (table == "sessions" && payload != null && SyncCodec.bareHash(payload) == base) return false
    return true
  }

  /**
   * L'ordine in cui si applicano le tabelle: un figlio non arriva mai prima del padre. Preset e
   * statistiche non hanno padri, e stanno in fondo.
   */
  val APPLY_ORDER: List<String> = listOf("folders", "notes", "sources", "sessions", "audio_parts", "transcripts", "export_presets", "transcription_runs")

  fun orderOf(table: String): Int = APPLY_ORDER.indexOf(table).let { if (it < 0) APPLY_ORDER.size else it }
}
