package dev.pampa.pampanotes.core.sync

/**
 * Chi ha ragione sul computer di casa: questo dispositivo o l'account.
 *
 * Non e' il merge a tre vie delle note. Il computer e' uno, e di un indirizzo non si tengono due
 * versioni: vince l'ultimo che l'ha scritto, per `updatedAt`. Il server fa lo stesso controllo e
 * rifiuta un PUT piu' vecchio del suo, quindi un «manda» deciso qui su dati vecchi di un secondo
 * non fa danni: torna indietro con la versione corrente, e si applica quella.
 *
 * Puro: si prova in JVM.
 */
object ComputerMerge {

  enum class Decision {
    /** Uguali, o niente da nessuna delle due parti. */
    NOTHING,
    /** Questo dispositivo ha la versione buona: sale. */
    PUSH,
    /** L'account ha la versione buona: si scrive qui. */
    APPLY,
  }

  /** Quello che questo dispositivo sa del suo computer. */
  data class Local(
    val hasEndpoint: Boolean,
    /** 0: mai scritto da quando il computer segue l'account (un'installazione di prima). */
    val updatedAt: Long,
    /** Modificato qui e non ancora salito. */
    val dirty: Boolean,
  )

  fun decide(local: Local, remote: AccountComputer?): Decision = when {
    // L'account non ne ha uno. Se qui c'e', sale: e' cosi' che il computer configurato prima di
    // questa funzione arriva all'account senza che nessuno lo riscriva. Una cancellazione nata qui
    // sale anche lei, come una riga vuota: un DELETE non lascerebbe un `updatedAt` con cui un
    // dispositivo rimasto indietro capisca di esserlo.
    remote == null -> if (local.hasEndpoint || local.dirty) Decision.PUSH else Decision.NOTHING
    // Una modifica di qui contro una dell'account: la piu' recente. A pari orologio vince l'account,
    // che e' quello che gli altri dispositivi hanno gia' visto.
    local.dirty -> if (local.updatedAt > remote.updatedAt) Decision.PUSH else Decision.APPLY
    remote.updatedAt > local.updatedAt -> Decision.APPLY
    // Pulito ma piu' nuovo dell'account: l'account ha perso qualcosa (un server rifatto). Si rimanda.
    remote.updatedAt < local.updatedAt -> Decision.PUSH
    else -> Decision.NOTHING
  }
}
