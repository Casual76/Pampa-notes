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
import dev.pampa.pampanotes.core.sync.SyncMerge.Decision
import dev.pampa.pampanotes.core.sync.SyncMerge.LocalView
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
  suspend fun apply(changes: List<WireChange>, ownerId: String, deviceName: String, deviceNames: Map<String, String> = emptyMap()): ApplyOutcome {
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
        val tally = Tally(ownerId, deviceName, deviceNames, trash)
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
  ) {
    var applied = 0
    var deleted = 0
    var skipped = 0
    var forked = 0
    var resurrected = 0

    fun outcome(orphans: List<WireChange>) = ApplyOutcome(applied, deleted, skipped, forked, orphans, resurrected)
  }

  /** @return false se la riga aspetta il suo padre: niente e' stato scritto. */
  private suspend fun applyOne(change: WireChange, tally: Tally): Boolean {
    val sync = db.sync()
    val local = payloads.encode(change.tbl, change.id)
    val entry = sync.outboxEntry(change.tbl, change.id)
    val meta = sync.meta(change.tbl, change.id)
    val view = LocalView(
      exists = local != null,
      dirty = entry != null,
      localHash = local?.hash,
      localUpdatedAt = local?.updatedAt,
      metaHash = meta?.hash,
    )
    val decision = SyncMerge.decide(change.tbl, view, change)

    // Il remoto sta per essere scritto: il suo padre deve esserci. Si guarda prima, e se manca non
    // si tocca niente — nemmeno la copia di conflitto, che al secondo tentativo nascerebbe due volte.
    val writesRemote = !change.isDelete && (decision == Decision.APPLY || decision == Decision.APPLY_AND_FORK)
    if (writesRemote && parentMissing(change)) return false

    when (decision) {
      Decision.SKIP -> {
        // Un tombstone per una riga che non c'e': se era rimasta una voce fantasma, via anche quella.
        if (change.isDelete && local == null && entry != null) sync.forgetOutbox(change.tbl, change.id)
        if (local != null) rebase(change, tally.ownerId)
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
        if (change.isDelete) delete(change, tally.trash) else upsert(change)
        bookkeep(change, tally.ownerId)
        if (change.isDelete) tally.deleted++ else tally.applied++
      }
    }
    return true
  }

  private suspend fun parentMissing(change: WireChange): Boolean {
    val parent = SyncPlan.parentOf(change.tbl, change.payload) ?: return false
    return !exists(parent)
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
        if (meta == null || meta.hash != encoded.hash) return true
      }
    }
    return false
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
  private suspend fun folderTree(rootId: String): List<String> {
    val byParent = db.folders().all().groupBy { it.parentId }
    val result = mutableListOf(rootId)
    val seen = mutableSetOf(rootId)
    var frontier = listOf(rootId)
    while (frontier.isNotEmpty()) {
      frontier = frontier.flatMap { parent -> byParent[parent].orEmpty().map { it.id } }.filter { seen.add(it) }
      result += frontier
    }
    return result
  }

  /** Una `IN (...)` a pezzi: SQLite su Android vecchi non accetta piu' di 999 parametri. */
  private suspend fun <T> inChunks(ids: List<String>, query: suspend (List<String>) -> List<T>): List<T> =
    if (ids.isEmpty()) emptyList() else ids.chunked(IN_CHUNK).flatMap { query(it) }

  private suspend fun upsert(change: WireChange) {
    val payload = change.payload ?: return
    when (change.tbl) {
      "folders" -> db.folders().upsert(decode(FolderEntity.serializer(), payload))
      "notes" -> {
        val note = decode(NotePayload.serializer(), payload)
        db.notes().upsert(note.note)
        db.tags().replace(note.note.id, note.tags)
      }
      "sessions" -> db.sessions().upsert(decode(SessionEntity.serializer(), payload))
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
    }
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
