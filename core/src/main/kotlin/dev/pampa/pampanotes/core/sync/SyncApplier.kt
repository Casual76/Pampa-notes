package dev.pampa.pampanotes.core.sync

import androidx.room.withTransaction
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.ExportPresetEntity
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SyncMetaEntity
import dev.pampa.pampanotes.core.db.SyncOriginEntity
import dev.pampa.pampanotes.core.db.SyncOutboxEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.model.Ids
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.sync.SyncMerge.Decision
import dev.pampa.pampanotes.core.sync.SyncMerge.LocalView
import dev.pampa.pampanotes.core.transcription.TranscribingMarker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement

data class ApplyOutcome(
  val applied: Int = 0,
  val deleted: Int = 0,
  val skipped: Int = 0,
  val forked: Int = 0,
  /**
   * Le righe il cui padre qui non c'e' ancora: non scritte, da riprovare con le pagine che
   * seguono. Vedi [SyncApplier.apply].
   */
  val orphans: List<WireChange> = emptyList(),
  /** Le cancellazioni remote fermate da una modifica fatta qui a un figlio: la riga risale col push. */
  val resurrected: Int = 0,
  /**
   * Cartelle, note e sessioni cancellate qui e rinate perche' altrove sono cambiate davvero
   * (`tbl/id`). I loro figli qui se ne sono andati in cascata, e i tombstone dei figli aspettano
   * nell'outbox: prima del push vanno riportati indietro ([SyncRepository], `revive`), o salirebbero
   * e cancellerebbero registrazioni e trascrizioni ovunque mentre la nota resta.
   */
  val revived: List<String> = emptyList(),
)

/**
 * Scrive nel database quello che e' arrivato dal server.
 *
 * Tutto dentro **una transazione** per pagina, con la guardia alzata: i trigger dell'outbox stanno
 * fermi, e o entra tutta la pagina o niente — `lastPullSeq` avanza solo dopo. Dentro, **prima gli
 * upsert da padre a figlio, poi le cancellazioni da figlio a padre** ([SyncPlan]): una nota spostata
 * e la cartella da cui e' uscita cancellata, nella stessa pagina, devono lasciare la nota nella
 * cartella nuova, non portarsela via con la cascata. E `transcripts_fts_ai` legge `sessions` per
 * denormalizzare il `noteId`: una trascrizione arrivata prima della sua sessione nascerebbe
 * invisibile alla ricerca.
 *
 * Le cose che non sono ovvie e stanno qui apposta:
 *  - un **padre che manca** si controlla *prima* di scrivere, con una lettura. Prima si lasciava
 *    scattare la chiave esterna e si tornava indietro a un savepoint; ma ogni scrittura di Room e'
 *    una transazione annidata, e una annidata fallita segna fallita quella esterna: la pagina intera
 *    tornava indietro **in silenzio**, mentre `lastPullSeq` avanzava. Qui niente eccezioni da
 *    prendere: o il padre c'e', o la riga aspetta;
 *  - una **cancellazione remota di una riga sporca** toglie anche la sua voce di outbox, o il push
 *    proverebbe a mandare una riga che non c'e' piu';
 *  - una **cancellazione remota di una cartella, una nota o una sessione con un figlio cambiato
 *    qui** non si applica: la riga resta, torna sporca, e il push la fa rinascere. E' la stessa
 *    regola del testo scritto a mano: una trascrizione appena arrivata o una fonte importata qui non
 *    spariscono per una cascata che l'altro dispositivo non poteva sapere di causare;
 *  - la **nota di conflitto** nasce sotto la guardia, quindi la sua voce di outbox si scrive a
 *    mano, o resterebbe prigioniera del dispositivo. Nasce in tutti e due i versi: dal locale
 *    quando vince il remoto, dal remoto quando vince il locale;
 *  - quando il locale resta ([Decision.SKIP], [Decision.KEEP_AND_FORK_REMOTE]) la versione
 *    remota diventa comunque **la base concordata** (`sync_meta`): e' quella che il push
 *    dichiara, e senza il server rifiuterebbe il locale come non aggiornato, a ogni giro;
 *  - una **sessione con un lavoro in corso** che viene cancellata altrove — da sola o con la sua
 *    nota o cartella — ha il lavoro annullato prima, o il worker scriverebbe in una riga sparita;
 *  - il **segno «in trascrizione su»** di una sessione non segue chi vince: quello che parla di
 *    questo dispositivo resta quello di qui, quello degli altri viene dal remoto anche quando il
 *    resto della riga resta locale ([TranscribingMarker.merge]). Tenuto il nostro sopra una riga
 *    remota, la sessione torna sporca e il push lo rimanda;
 *  - una riga **cancellata qui** il cui tombstone non e' ancora salito resta cancellata se il
 *    remoto non porta niente di nuovo (anche solo il segno di una trascrizione): la versione
 *    remota diventa la base, e il tombstone sale senza essere rifiutato. Se invece il remoto e'
 *    cambiato davvero vince lui e la riga rinasce; se e' un contenitore i suoi figli, portati via
 *    dalla cascata di qui, tornano con [ApplyOutcome.revived]. Se anche il padre e' stato
 *    cancellato qui, la cancellazione resta: non c'e' dove rimetterla;
 *  - i **file** delle parti e delle fonti cancellate altrove, anche in cascata, non si buttano:
 *    vanno in `filesDir/trash/<giorno>/`, e solo dopo che la transazione e' andata a buon fine.
 *    Questo dispositivo potrebbe averne l'unica copia, e una pagina tornata indietro non deve
 *    lasciare righe che puntano a un file spostato.
 */
@Singleton
class SyncApplier @Inject constructor(
  private val db: PampaDatabase,
  private val files: AppFiles,
  private val payloads: SyncPayloads,
) {
  private val json = SyncCodec.json

  /**
   * @param deviceNames come si chiamano gli altri dispositivi, per il titolo di una copia del loro testo.
   *
   * Una riga il cui padre qui non c'e' non fa fallire la pagina: si riprova nella stessa pagina
   * finche' un giro non ne applica nessuna (una sottocartella puo' arrivare prima della cartella che
   * la contiene: stessa tabella, `seq` piu' basso), e quelle che restano tornano in
   * [ApplyOutcome.orphans]. Il server tiene una riga sola per elemento, col numero della sua
   * **ultima** modifica: una nota ritoccata dopo che le si e' aggiunta una fonte arriva *dopo* la
   * fonte, magari in una pagina successiva. Chi chiama ripresenta gli orfani insieme alla pagina
   * dopo, dove il padre di solito c'e'.
   */
  /**
   * @param revive le righe (`tbl/id`) da riportare indietro anche se qui sono state cancellate: i
   *   figli di una riga rinata ([ApplyOutcome.revived]). Il loro tombstone nell'outbox si scorda
   *   dentro la stessa transazione che le riscrive.
   */
  suspend fun apply(
    changes: List<WireChange>,
    ownerId: String,
    deviceName: String,
    deviceNames: Map<String, String> = emptyMap(),
    revive: Set<String> = emptySet(),
  ): ApplyOutcome {
    if (changes.isEmpty()) return ApplyOutcome()
    val latest = SyncPlan.latestPerRow(changes)
    val upserts = SyncPlan.upserts(latest)
    val deletes = SyncPlan.deletes(latest)
    val big = latest.size > BIG_PAGE
    val trash = mutableListOf<File>()

    val outcome = db.withTransaction {
      val sync = db.sync()
      sync.setApplying(1)
      // Su una pagina grande i trigger dell'indice di ricerca costano una scansione lineare per
      // riga: si tolgono, si applica, si rimettono e si ricostruisce. Il DDL in SQLite e'
      // transazionale, quindi anche questo torna indietro se qualcosa va storto.
      if (big) SEARCH_TRIGGER_NAMES.forEach { db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $it") }
      try {
        val tally = Tally(ownerId, deviceName, deviceNames, trash, revive)
        var pending: List<WireChange> = upserts
        while (pending.isNotEmpty()) {
          val parked = pending.filterNot { applyOne(it, tally) }
          if (parked.size == pending.size) break
          pending = parked
        }
        for (change in deletes) applyOne(change, tally)
        if (big) {
          PampaDatabase.SEARCH_TRIGGERS.forEach { db.openHelper.writableDatabase.execSQL(it) }
          db.search().rebuild()
        }
        tally.outcome(orphans = pending)
      } finally {
        sync.setApplying(0)
      }
    }
    trash.forEach(::quarantine)
    return outcome
  }

  /** I conti di una pagina, e dove si raccolgono i file da spostare a transazione chiusa. */
  private class Tally(
    val ownerId: String,
    val deviceName: String,
    val deviceNames: Map<String, String>,
    val trash: MutableList<File>,
    val revive: Set<String>,
  ) {
    var applied = 0
    var deleted = 0
    var skipped = 0
    var forked = 0
    var resurrected = 0
    val revived = mutableListOf<String>()

    fun outcome(orphans: List<WireChange>) = ApplyOutcome(applied, deleted, skipped, forked, orphans, resurrected, revived)
  }

  /** @return false se la riga aspetta il suo padre: niente e' stato scritto. */
  private suspend fun applyOne(change: WireChange, tally: Tally): Boolean {
    val sync = db.sync()
    val local = payloads.encode(change.tbl, change.id)
    var entry = sync.outboxEntry(change.tbl, change.id)
    val meta = sync.meta(change.tbl, change.id)
    // Un figlio di una riga rinata: il suo tombstone e' quello della cascata di qui, e non deve
    // salire. Via, e la riga si decide come se qui non ci fosse mai stata.
    if (local == null && entry?.op == WireChange.OP_DELETE && !change.isDelete && SyncPlan.key(change.tbl, change.id) in tally.revive) {
      sync.forgetOutbox(change.tbl, change.id)
      entry = null
    }
    val view = LocalView(
      exists = local != null,
      dirty = entry != null,
      localHash = local?.hash,
      localUpdatedAt = local?.updatedAt,
      metaHash = meta?.hash,
      localBareHash = if (local != null) localBareHash(change.tbl, change.id) else null,
    )
    val decision = SyncMerge.decide(change.tbl, view, change)

    // Il remoto sta per essere scritto: il suo padre deve esserci. Si guarda prima, e se manca non
    // si tocca niente — nemmeno la copia di conflitto, che al secondo tentativo nascerebbe due volte.
    val writesRemote = !change.isDelete && (decision == Decision.APPLY || decision == Decision.APPLY_AND_FORK)
    if (writesRemote && parentMissing(change)) {
      // Cancellata qui insieme al padre, che non torna: la cancellazione resta. La versione remota
      // diventa la base, cosi' il tombstone sale invece di tornare `stale` a ogni giro. Se il padre
      // rinasce piu' avanti, questa torna con lui (`revive`): il suo tombstone e' ancora qui.
      if (view.pendingDelete && parentDeletedHere(change)) {
        rebase(change, tally.ownerId)
        tally.skipped++
        return true
      }
      return false
    }

    when (decision) {
      Decision.SKIP -> {
        // Un tombstone per una riga che non c'e': se era rimasta una voce fantasma, via anche quella.
        if (change.isDelete && local == null && entry != null) sync.forgetOutbox(change.tbl, change.id)
        if (local != null) {
          if (change.tbl == "sessions" && !change.isDelete) adoptRemoteMarker(change, tally.deviceName)
          rebase(change, tally.ownerId)
        } else if (!change.isDelete && view.pendingDelete) {
          // Cancellata qui, e il remoto non ha niente di nuovo: la cancellazione resta, e sale
          // dichiarando come base la versione che il server ha adesso.
          rebase(change, tally.ownerId)
        }
        tally.skipped++
      }
      Decision.KEEP_AND_FORK_REMOTE -> {
        forkRemote(change, tally.deviceNames)
        rebase(change, tally.ownerId)
        tally.skipped++
        tally.forked++
      }
      Decision.APPLY, Decision.APPLY_AND_FORK -> {
        if (change.isDelete && hasChangedDescendants(change)) {
          resurrect(change)
          tally.skipped++
          tally.resurrected++
          return true
        }
        if (decision == Decision.APPLY_AND_FORK) {
          fork(change.id, tally.deviceName)
          tally.forked++
        }
        // Rinata sopra una cancellazione di qui: i figli se ne sono andati con la cascata, e tornano
        // col riallineamento che [SyncRepository] fa prima del push.
        val reborn = !change.isDelete && view.pendingDelete && change.tbl in CONTAINERS
        val keptLocalMarker = if (change.isDelete) { delete(change, tally.trash); false } else upsert(change, tally.deviceName)
        bookkeep(change, tally.ownerId)
        if (reborn) tally.revived += SyncPlan.key(change.tbl, change.id)
        // La sessione e' quella remota, ma il segno «in trascrizione su» e' rimasto quello di qui:
        // e' diversa da quella concordata, e deve salire.
        if (keptLocalMarker) sync.markDirty(SyncOutboxEntity(tbl = change.tbl, rowId = change.id, op = WireChange.OP_UPSERT))
        if (change.isDelete) tally.deleted++ else tally.applied++
      }
    }
    return true
  }

  private suspend fun parentMissing(change: WireChange): Boolean {
    val parent = SyncPlan.parentOf(change.tbl, change.payload) ?: return false
    return !exists(parent)
  }

  /** Il padre di questa riga e' stato cancellato qui, e il suo tombstone non e' ancora salito. */
  private suspend fun parentDeletedHere(change: WireChange): Boolean {
    val parent = SyncPlan.parentOf(change.tbl, change.payload) ?: return false
    return db.sync().outboxEntry(parent.tbl, parent.id)?.op == WireChange.OP_DELETE
  }

  private suspend fun exists(ref: SyncPlan.RowRef): Boolean = when (ref.tbl) {
    "folders" -> db.folders().get(ref.id) != null
    "notes" -> db.notes().get(ref.id) != null
    "sessions" -> db.sessions().get(ref.id) != null
    else -> true
  }

  private suspend fun bookkeep(change: WireChange, ownerId: String) {
    val sync = db.sync()
    if (change.isDelete) {
      sync.deleteMeta(change.tbl, change.id)
      sync.deleteOrigin(change.tbl, change.id)
    } else {
      sync.upsertMeta(SyncMetaEntity(change.tbl, change.id, serverSeq = change.seq, hash = change.hash, updatedAt = change.updatedAt))
      sync.upsertOrigin(SyncOriginEntity(change.tbl, change.id, ownerId))
    }
    // Applicato il remoto, la voce locale — se c'era — non ha piu' niente da dire.
    sync.forgetOutbox(change.tbl, change.id)
  }

  /**
   * Il locale resta, ma la versione concordata da adesso e' quella remota. La voce di outbox
   * resta anche lei: il locale deve ancora salire, e salira' dichiarando questa base.
   */
  private suspend fun rebase(change: WireChange, ownerId: String) {
    val sync = db.sync()
    if (change.isDelete) {
      sync.deleteMeta(change.tbl, change.id)
    } else {
      sync.upsertMeta(SyncMetaEntity(change.tbl, change.id, serverSeq = change.seq, hash = change.hash, updatedAt = change.updatedAt))
      sync.upsertOrigin(SyncOriginEntity(change.tbl, change.id, ownerId))
    }
  }

  /**
   * La cancellazione remota non passa: la riga resta, senza base (sul server adesso e' un tombstone,
   * la cui impronta e' vuota come la base che il push dichiarera') e sporca, cosi' il push la
   * rimanda e gli altri dispositivi la riavranno. Il padre, se anche lui e' stato cancellato nella
   * stessa pagina, arriva dopo (figlio prima di padre) e trova questa voce: resta anche lui.
   */
  private suspend fun resurrect(change: WireChange) {
    val sync = db.sync()
    sync.deleteMeta(change.tbl, change.id)
    sync.markDirty(SyncOutboxEntity(tbl = change.tbl, rowId = change.id, op = WireChange.OP_UPSERT))
  }

  /**
   * Un figlio di questa riga e' cambiato qui e non e' ancora salito. «Cambiato» come lo intende il
   * merge: una voce nell'outbox *e* un contenuto diverso dall'ultima versione concordata — una riga
   * solo toccata non ferma niente.
   */
  private suspend fun hasChangedDescendants(change: WireChange): Boolean {
    if (change.tbl !in CONTAINERS) return false
    val tree = descendants(change.tbl, change.id)
    val sync = db.sync()
    val candidates = listOf(
      "folders" to tree.folders.filter { it != change.id },
      "notes" to tree.notes.filter { change.tbl != "notes" || it != change.id },
      "sessions" to tree.sessions.filter { change.tbl != "sessions" || it != change.id },
      "sources" to tree.sources.map { it.id },
      "audio_parts" to tree.parts.map { it.id },
      "transcripts" to tree.transcripts,
    )
    for ((table, ids) in candidates) {
      for (entry in inChunks(ids) { sync.dirtyAmong(table, it) }) {
        val encoded = payloads.encode(table, entry.rowId) ?: continue
        val meta = sync.meta(table, entry.rowId)
        if (meta == null || (meta.hash != encoded.hash && !onlyMarkerChanged(table, entry.rowId, meta.hash))) return true
      }
    }
    return false
  }

  /**
   * Una sessione diversa da quella concordata solo per il segno «in trascrizione su» messo qui: non
   * e' un figlio cambiato. Una nota cancellata altrove mentre qui la si trascrive se ne va — e il
   * lavoro si annulla con lei — invece di rinascere per uno stato che nessuno ha scritto.
   */
  private suspend fun onlyMarkerChanged(table: String, id: String, metaHash: String): Boolean =
    localBareHash(table, id)?.let { it == metaHash } ?: false

  /**
   * L'impronta di una sessione di qui senza il segno «in trascrizione su»; null se non e' una
   * sessione o se il segno non c'e' (allora l'impronta e' gia' quella). Vedi [LocalView.localBareHash].
   */
  private suspend fun localBareHash(table: String, id: String): String? {
    if (table != "sessions") return null
    val session = db.sessions().get(id) ?: return null
    if (session.transcribingOn == null && session.transcribingSince == null) return null
    val bare = session.copy(transcribingOn = null, transcribingSince = null)
    return SyncPayloads.encode(SessionEntity.serializer(), bare, bare.updatedAt).hash
  }

  /** Quello che una cancellazione si porta via in cascata, radice compresa. */
  private class Subtree(
    val folders: List<String>,
    val notes: List<String>,
    val sessions: List<String>,
    val transcripts: List<String>,
    val parts: List<AudioPartEntity>,
    val sources: List<SourceEntity>,
  )

  private suspend fun descendants(tbl: String, id: String): Subtree {
    val sync = db.sync()
    val folders = if (tbl == "folders") folderTree(id) else emptyList()
    val notes = when (tbl) {
      "folders" -> inChunks(folders) { sync.noteIdsInFolders(it) }
      "notes" -> listOf(id)
      else -> emptyList()
    }
    val sessions = if (tbl == "sessions") listOf(id) else inChunks(notes) { sync.sessionIdsOfNotes(it) }
    return Subtree(
      folders = folders,
      notes = notes,
      sessions = sessions,
      transcripts = inChunks(sessions) { sync.transcriptIdsOfSessions(it) },
      parts = inChunks(sessions) { sync.partsOfSessions(it) },
      sources = inChunks(notes) { sync.sourcesOfNotes(it) },
    )
  }

  /** La cartella e tutte quelle dentro, a qualunque profondita'. Le cartelle sono poche: si leggono tutte. */
  private suspend fun folderTree(rootId: String): List<String> = FolderRepository.descendants(rootId, db.folders().all()).toList()

  /** Una `IN (...)` a pezzi: SQLite su Android vecchi non accetta piu' di 999 parametri. */
  private suspend fun <T> inChunks(ids: List<String>, query: suspend (List<String>) -> List<T>): List<T> =
    if (ids.isEmpty()) emptyList() else ids.chunked(IN_CHUNK).flatMap { query(it) }

  /**
   * Il segno «in trascrizione su» di una sessione che qui resta com'e' ([Decision.SKIP]): gli altri
   * campi sono quelli di qui, ma quello che il remoto sa degli *altri* dispositivi e' piu' fresco.
   * Sotto la guardia, quindi niente outbox: la riga e' gia' sporca, e il push la porta col segno nuovo.
   */
  private suspend fun adoptRemoteMarker(change: WireChange, deviceName: String) {
    val payload = change.payload ?: return
    val local = db.sessions().get(change.id) ?: return
    val remote = decode(SessionEntity.serializer(), payload)
    val (on, since) = TranscribingMarker.merge(local.transcribingOn, local.transcribingSince, remote.transcribingOn, remote.transcribingSince, deviceName)
    if (on != local.transcribingOn || since != local.transcribingSince) db.sessions().setMarker(change.id, on, since)
  }

  /**
   * @return vero se la riga scritta non e' quella remota: una sessione a cui si e' tenuto il segno
   *   «in trascrizione su» di qui (vedi [TranscribingMarker.merge]).
   */
  private suspend fun upsert(change: WireChange, deviceName: String): Boolean {
    val payload = change.payload ?: return false
    when (change.tbl) {
      "folders" -> db.folders().upsert(decode(FolderEntity.serializer(), payload))
      "notes" -> {
        val note = decode(NotePayload.serializer(), payload)
        db.notes().upsert(note.note)
        db.tags().replace(note.note.id, note.tags)
      }
      "sessions" -> {
        // Solo chi trascrive sa se il suo lavoro c'e' ancora: il segno di questo dispositivo non lo
        // decide una copia arrivata da fuori, in nessuno dei due versi.
        val remote = decode(SessionEntity.serializer(), payload)
        val local = db.sessions().get(remote.id)
        val merged = if (local == null) remote else TranscribingMarker.mergeInto(local, remote, deviceName)
        db.sessions().upsert(merged)
        return merged != remote
      }
      "audio_parts" -> db.audioParts().upsert(decode(AudioPartEntity.serializer(), payload))
      "transcripts" -> {
        val transcript = decode(TranscriptEntity.serializer(), payload)
        db.transcripts().upsert(transcript)
        // I segmenti si rifanno in blocco, come fa `rebuildRaw`: i loro id locali non contano.
        db.segments().deleteByTranscript(transcript.id)
        change.segments?.takeIf { it.isNotEmpty() }?.let { list ->
          db.segments().insertAll(list.map { it.copy(id = 0, transcriptId = transcript.id) })
        }
      }
      "sources" -> db.sources().upsert(decode(SourceEntity.serializer(), payload))
      "export_presets" -> db.exportPresets().upsert(decode(ExportPresetEntity.serializer(), payload))
      // Senza padre da aspettare: il `sessionId` di una corsa non e' una chiave esterna.
      "transcription_runs" -> db.stats().upsert(RunPayload.decode(payload))
    }
    return false
  }

  private suspend fun delete(change: WireChange, trash: MutableList<File>) {
    when (change.tbl) {
      "folders", "notes", "sessions" -> {
        // La cascata non passa di qui: quello che si porta via si guarda adesso.
        val tree = descendants(change.tbl, change.id)
        tree.sessions.forEach { sessionId ->
          db.jobs().activeForSession(sessionId)?.let { db.jobs().setState(it.id, JobState.CANCELLED, System.currentTimeMillis()) }
        }
        tree.parts.forEach { trash += files.audioFile(it.fileName) }
        tree.sources.forEach { source -> source.storedFileName?.let { trash += files.sourceFile(it) } }
        when (change.tbl) {
          "folders" -> db.folders().delete(change.id)
          "notes" -> db.notes().delete(change.id)
          else -> db.sessions().delete(change.id)
        }
      }
      "audio_parts" -> {
        db.audioParts().get(change.id)?.let { trash += files.audioFile(it.fileName) }
        db.audioParts().delete(change.id)
      }
      "transcripts" -> db.transcripts().delete(change.id)
      "sources" -> {
        db.sources().get(change.id)?.storedFileName?.let { trash += files.sourceFile(it) }
        db.sources().delete(change.id)
      }
      "export_presets" -> db.exportPresets().delete(change.id)
      "transcription_runs" -> db.stats().delete(change.id)
    }
  }

  /**
   * La nota com'e' qui, salvata come nota nuova prima che il remoto la sovrascriva.
   *
   * Il titolo dice cos'e' e da dove viene; la data e' quella del giorno; il resto e' identico,
   * tag compresi. Non e' appuntata, anche se l'originale lo era: e' un duplicato da rileggere.
   */
  private suspend fun fork(noteId: String, deviceName: String) {
    val note = db.notes().get(noteId) ?: return
    val now = System.currentTimeMillis()
    val stamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(now))
    val copy = note.copy(
      id = Ids.newId(),
      title = "${note.title} (conflitto — $deviceName, $stamp)",
      pinned = false,
      createdAt = now,
      updatedAt = now,
    )
    db.notes().upsert(copy)
    db.tags().replace(copy.id, db.tags().tags(noteId))
    db.sync().markDirty(SyncOutboxEntity(tbl = "notes", rowId = copy.id, op = WireChange.OP_UPSERT))
  }

  /**
   * La nota com'e' arrivata, salvata come nota nuova: qui e' cambiata dopo e resta quella di qui,
   * ma quello che ha scritto l'altro dispositivo non si butta. Il titolo dice da dove viene.
   *
   * Se l'altro dispositivo l'ha spostata in una cartella che qui non c'e' ancora, la copia nasce
   * accanto alla nota di qui: meglio nella cartella sbagliata che una chiave esterna che fallisce.
   */
  private suspend fun forkRemote(change: WireChange, deviceNames: Map<String, String>) {
    val payload = change.payload ?: return
    val remote = decode(NotePayload.serializer(), payload)
    val folderId = remote.note.folderId.takeIf { db.folders().get(it) != null }
      ?: db.notes().get(change.id)?.folderId
      ?: return
    val now = System.currentTimeMillis()
    val stamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(now))
    val author = deviceNames[change.deviceId]?.takeIf { it.isNotBlank() } ?: "altrove"
    val copy = remote.note.copy(
      id = Ids.newId(),
      folderId = folderId,
      title = "${remote.note.title} (conflitto — $author, $stamp)",
      pinned = false,
      createdAt = now,
      updatedAt = now,
    )
    db.notes().upsert(copy)
    db.tags().replace(copy.id, remote.tags)
    db.sync().markDirty(SyncOutboxEntity(tbl = "notes", rowId = copy.id, op = WireChange.OP_UPSERT))
  }

  private fun quarantine(file: File) {
    if (!file.exists()) return
    val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    val dir = File(files.root, "trash/$day").apply { mkdirs() }
    val target = File(dir, file.name)
    if (!file.renameTo(target)) {
      runCatching { file.copyTo(target, overwrite = true); file.delete() }
    }
  }

  private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, element: JsonElement): T =
    json.decodeFromJsonElement(serializer, element)

  private companion object {
    const val BIG_PAGE = 100
    const val IN_CHUNK = 500
    val CONTAINERS = setOf("folders", "notes", "sessions")
    val SEARCH_TRIGGER_NAMES = listOf("notes_fts_ai", "notes_fts_au", "notes_fts_ad", "transcripts_fts_ai", "transcripts_fts_au", "transcripts_fts_ad")
  }
}
