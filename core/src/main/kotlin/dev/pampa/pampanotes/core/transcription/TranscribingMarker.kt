package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.SessionMarkerRow

/** Una sessione che un altro dispositivo sta trascrivendo adesso. */
data class RemoteTranscribing(
  val sessionId: String,
  val noteId: String,
  /** Il nome del dispositivo nel sync: «Tab S9», «Pixel 8». */
  val device: String,
  val since: Long,
  /** La sessione ha gia' una trascrizione: la si sta rifacendo, non facendo. */
  val transcribed: Boolean,
)

/** Una nota con delle lezioni in trascrizione altrove: quante sono ancora senza testo, e dove. */
data class NoteTranscribingElsewhere(
  /** Quelle senza trascrizione: le stesse che `NoteRow.untranscribedSessions` conta «da fare». */
  val untranscribed: Int,
  val device: String,
)

/**
 * Il segno «in trascrizione su» delle sessioni (`SessionEntity.transcribingOn`/`transcribingSince`).
 * Puro: si prova in JVM.
 *
 * `jobs` e' la coda di un dispositivo e non viaggia. Senza un riflesso sincronizzato, mentre il
 * telefono trascrive una lezione il tablet la mostrava «Da trascrivere» e offriva «Trascrivi»: un
 * tocco, e la stessa ora di audio passava due volte dal computer (o da Groq). Il segno e' quel
 * riflesso, con tre regole:
 *
 *  - **lo scrive solo chi trascrive.** Il dispositivo che lavora mette il suo nome quando un lavoro
 *    parte e lo toglie quando smette di lavorare — finito, fallito, annullato, o tornato in fila ad
 *    aspettare il computer o il limite di Groq. Quello che un altro dispositivo dice di *me* non
 *    conta, e quello che io dico di me vince sempre ([merge]);
 *  - **non e' una modifica.** Non alza `updatedAt`: per le sessioni vale l'ultimo che ha scritto, e un
 *    lavoro partito un secondo dopo che il tablet ha rinominato la sessione non deve riportare il
 *    titolo di prima. Entra invece nell'impronta — e' l'unico modo perche' salga — ma solo quando c'e':
 *    vuoto, la sessione ha l'impronta di sempre;
 *  - **scade.** Un processo ucciso a meta' non fa in tempo a toglierlo: dopo [STALE_AFTER_MS] il segno
 *    non vale piu', e il dispositivo che l'ha lasciato lo toglie da se' al primo avvio. Chi lavora a
 *    lungo lo rinnova ogni [REFRESH_AFTER_MS], cosi' una lezione lenta sul processore non scade mentre
 *    e' ancora in corso.
 */
object TranscribingMarker {
  /** Oltre, il segno e' di un processo morto: la sessione torna da fare. */
  const val STALE_AFTER_MS: Long = 3L * 60 * 60 * 1000

  /** Ogni quanto chi lavora rinnova il suo segno: ben dentro [STALE_AFTER_MS]. */
  const val REFRESH_AFTER_MS: Long = 60L * 60 * 1000

  /**
   * Il segno dice che un **altro** dispositivo ci sta lavorando adesso. Il proprio non conta mai:
   * qui si mostra il lavoro vero, con le sue barre, e un segno di questo dispositivo senza un lavoro
   * e' un avanzo di un processo morto.
   */
  fun isElsewhere(on: String?, since: Long?, me: String, now: Long): Boolean {
    if (on.isNullOrBlank() || since == null) return false
    if (on == me) return false
    // L'ora e' quella di chi l'ha scritto: un orologio avanti di un giorno terrebbe la sessione
    // «in trascrizione» per un giorno piu' tre ore. Oltre la durata di un segno, nel futuro, non vale.
    return now - since < STALE_AFTER_MS && since - now < STALE_AFTER_MS
  }

  /** Le sessioni che un altro dispositivo sta trascrivendo, per id. */
  fun elsewhere(rows: List<SessionMarkerRow>, me: String, now: Long): Map<String, RemoteTranscribing> =
    rows.filter { isElsewhere(it.transcribingOn, it.transcribingSince, me, now) }.associate {
      it.id to RemoteTranscribing(
        sessionId = it.id,
        noteId = it.noteId,
        device = it.transcribingOn.orEmpty(),
        since = it.transcribingSince ?: 0L,
        transcribed = it.activeTranscriptId != null,
      )
    }

  /** Le stesse, per nota: quello che i badge della home e della cartella contano. */
  fun byNote(elsewhere: Collection<RemoteTranscribing>): Map<String, NoteTranscribingElsewhere> =
    elsewhere.groupBy { it.noteId }.mapValues { (_, list) ->
      NoteTranscribingElsewhere(
        untranscribed = list.count { !it.transcribed },
        // Il piu' recente: di solito e' uno solo, ma se sono due il badge dice quello che ha appena iniziato.
        device = list.maxBy { it.since }.device,
      )
    }

  /** Cosa scrivere perche' il segno di questo dispositivo dica il vero. */
  data class Plan(val mark: Set<String>, val clear: Set<String>) {
    val isEmpty: Boolean get() = mark.isEmpty() && clear.isEmpty()
  }

  /**
   * @param running le sessioni che hanno qui una trascrizione al lavoro adesso (non in fila).
   * @param mine le sessioni col segno di questo dispositivo, e da quando.
   */
  fun plan(running: Set<String>, mine: Map<String, Long?>, now: Long): Plan = Plan(
    mark = running.filterTo(mutableSetOf()) { id ->
      val since = mine[id]
      id !in mine || since == null || now - since >= REFRESH_AFTER_MS
    },
    clear = mine.keys - running,
  )

  /**
   * Il segno da tenere quando una sessione arriva dall'indice e qui ce n'e' gia' una.
   *
   * Se uno dei due parla di questo dispositivo vale il locale: solo qui si sa se il lavoro c'e'. Un
   * remoto che dice «su di me» quando qui e' finito e' una copia vecchia che un altro ha rimandato
   * modificando la sessione; un remoto senza segno quando qui si sta lavorando e' un altro che ha
   * rinominato la sessione senza sapere del lavoro. Altrimenti vale il remoto: e' la notizia piu'
   * fresca sugli altri.
   */
  fun merge(localOn: String?, localSince: Long?, remoteOn: String?, remoteSince: Long?, me: String): Pair<String?, Long?> =
    if (localOn == me || remoteOn == me) localOn to localSince else remoteOn to remoteSince

  /** [merge] su due sessioni intere: la remota, col segno giusto. */
  fun mergeInto(local: SessionEntity, remote: SessionEntity, me: String): SessionEntity {
    val (on, since) = merge(local.transcribingOn, local.transcribingSince, remote.transcribingOn, remote.transcribingSince, me)
    return if (on == remote.transcribingOn && since == remote.transcribingSince) remote else remote.copy(transcribingOn = on, transcribingSince = since)
  }
}
