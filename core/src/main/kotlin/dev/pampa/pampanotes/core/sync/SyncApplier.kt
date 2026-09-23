package dev.pampa.pampanotes.core.sync

import android.database.sqlite.SQLiteConstraintException
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
   * Le righe il cui padre qui non c'e' ancora: non applicate, da riprovare con le pagine che
   * seguono. Vedi [SyncApplier.apply].
   */
  val orphans: List<WireChange> = emptyList(),
)

/**
 * Scrive nel database quello che e' arrivato dal server.
 *
 * Tutto dentro **una transazione** per pagina, con la guardia alzata: i trigger dell'outbox stanno
 * fermi, e o entra tutta la pagina o niente — `lastPullSeq` avanza solo dopo. Le righe si applicano
 * nell'ordine padre→figlio ([SyncMerge.APPLY_ORDER]): `transcripts_fts_ai` legge `sessions` per
 * denormalizzare il `noteId`, e una trascrizione arrivata prima della sua sessione nascerebbe
 * invisibile alla ricerca.
 *
 * Cinque cose che non sono ovvie e stanno qui apposta:
 *  - una **cancellazione remota di una riga sporca** toglie anche la sua voce di outbox, o il push
 *    proverebbe a mandare una riga che non c'e' piu';
 *  - la **nota di conflitto** nasce sotto la guardia, quindi la sua voce di outbox si scrive a
 *    mano, o resterebbe prigioniera del dispositivo. Nasce in tutti e due i versi: dal locale
 *    quando vince il remoto, dal remoto quando vince il locale;
 *  - quando il locale resta ([Decision.SKIP], [Decision.KEEP_AND_FORK_REMOTE]) la versione
 *    remota diventa comunque **la base concordata** (`sync_meta`): e' quella che il push
 *    dichiara, e senza il server rifiuterebbe il locale come non aggiornato, a ogni giro;
 *  - una **sessione con un lavoro in corso** che viene cancellata altrove: il lavoro si annulla
 *    prima, o il worker scriverebbe il risultato in una riga sparita;
 *  - i **file** di una parte o di una fonte cancellate altrove non si buttano: vanno in
 *    `filesDir/trash/<giorno>/`. Questo dispositivo potrebbe averne l'unica copia.
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
   * Una riga il cui padre qui non c'e' non fa fallire la pagina: torna in [ApplyOutcome.orphans]. Il
   * server tiene una riga sola per elemento, col numero della sua **ultima** modifica, quindi una
   * nota ritoccata dopo che le si e' aggiunta una fonte arriva *dopo* la fonte, magari in una pagina
   * successiva. Prima, la fonte faceva scattare la chiave esterna, la pagina tornava indietro, e il
   * pull falliva sempre nello stesso punto: il dispositivo non si sincronizzava piu', e siccome il
   * push viene dopo il pull non mandava piu' niente nemmeno lui. Chi chiama ripresenta gli orfani
   * insieme alla pagina dopo, dove il padre di solito c'e'.
   */
  suspend fun apply(changes: List<WireChange>, ownerId: String, deviceName: String, deviceNames: Map<String, String> = emptyMap()): ApplyOutcome {
    if (changes.isEmpty()) return ApplyOutcome()
    val ordered = changes.sortedWith(compareBy({ SyncMerge.orderOf(it.tbl) }, { it.seq }))
    val big = ordered.size > BIG_PAGE

    return db.withTransaction {
      val sync = db.sync()
      sync.setApplying(1)
      // Su una pagina grande i trigger dell'indice di ricerca costano una scansione lineare per
      // riga: si tolgono, si applica, si rimettono e si ricostruisce. Il DDL in SQLite e'
      // transazionale, quindi anche questo torna indietro se qualcosa va storto.
      if (big) SEARCH_TRIGGER_NAMES.forEach { db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $it") }
      try {
        var outcome = ApplyOutcome()
        for (change in ordered) outcome = applyOrPark(change, ownerId, deviceName, deviceNames, outcome)
        if (big) {
          PampaDatabase.SEARCH_TRIGGERS.forEach { db.openHelper.writableDatabase.execSQL(it) }
          db.search().rebuild()
        }
        outcome
      } finally {
        sync.setApplying(0)
      }
    }
  }

  /**
   * [applyOne] dentro un savepoint: se il padre manca, torna indietro tutto quello che la riga aveva
   * fatto — anche la copia di conflitto, che altrimenti nascerebbe due volte quando la riga si
   * riprova — e la riga si mette da parte.
   */
  private suspend fun applyOrPark(change: WireChange, ownerId: String, deviceName: String, deviceNames: Map<String, String>, outcome: ApplyOutcome): ApplyOutcome {
    val sql = db.openHelper.writableDatabase
    sql.execSQL("SAVEPOINT sync_row")
    return try {
      applyOne(change, ownerId, deviceName, deviceNames, outcome).also { sql.execSQL("RELEASE sync_row") }
    } catch (orphan: SQLiteConstraintException) {
      if (orphan.message?.contains("FOREIGN KEY", ignoreCase = true) != true) {
        sql.execSQL("ROLLBACK TO sync_row"); sql.execSQL("RELEASE sync_row")
        throw orphan
      }
      sql.execSQL("ROLLBACK TO sync_row")
      sql.execSQL("RELEASE sync_row")
      outcome.copy(orphans = outcome.orphans + change)
    }
  }

  private suspend fun applyOne(change: WireChange, ownerId: String, deviceName: String, deviceNames: Map<String, String>, outcome: ApplyOutcome): ApplyOutcome {
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

    return when (SyncMerge.decide(change.tbl, view, change)) {
      Decision.SKIP -> {
        // Un tombstone per una riga che non c'e': se era rimasta una voce fantasma, via anche quella.
        if (change.isDelete && local == null && entry != null) sync.forgetOutbox(change.tbl, change.id)
        if (local != null) rebase(change, ownerId)
        outcome.copy(skipped = outcome.skipped + 1)
      }
      Decision.KEEP_AND_FORK_REMOTE -> {
        forkRemote(change, deviceNames)
        rebase(change, ownerId)
        outcome.copy(skipped = outcome.skipped + 1, forked = outcome.forked + 1)
      }
      Decision.APPLY -> {
        if (change.isDelete) delete(change) else upsert(change)
        bookkeep(change, ownerId)
        if (change.isDelete) outcome.copy(deleted = outcome.deleted + 1) else outcome.copy(applied = outcome.applied + 1)
      }
      Decision.APPLY_AND_FORK -> {
        fork(change.id, deviceName)
        if (change.isDelete) delete(change) else upsert(change)
        bookkeep(change, ownerId)
        outcome.copy(applied = outcome.applied + 1, forked = outcome.forked + 1)
      }
    }
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

  private suspend fun delete(change: WireChange) {
    when (change.tbl) {
      "folders" -> db.folders().delete(change.id)
      "notes" -> db.notes().delete(change.id)
      "sessions" -> {
        db.jobs().activeForSession(change.id)?.let { db.jobs().setState(it.id, JobState.CANCELLED, System.currentTimeMillis()) }
        db.sessions().delete(change.id)
      }
      "audio_parts" -> {
        db.audioParts().get(change.id)?.let { quarantine(files.audioFile(it.fileName)) }
        db.audioParts().delete(change.id)
      }
      "transcripts" -> db.transcripts().delete(change.id)
      "sources" -> {
        db.sources().get(change.id)?.storedFileName?.let { quarantine(files.sourceFile(it)) }
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
   */
  private suspend fun forkRemote(change: WireChange, deviceNames: Map<String, String>) {
    val payload = change.payload ?: return
    val remote = decode(NotePayload.serializer(), payload)
    val now = System.currentTimeMillis()
    val stamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(now))
    val author = deviceNames[change.deviceId]?.takeIf { it.isNotBlank() } ?: "altrove"
    val copy = remote.note.copy(
      id = Ids.newId(),
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
    val SEARCH_TRIGGER_NAMES = listOf("notes_fts_ai", "notes_fts_au", "notes_fts_ad", "transcripts_fts_ai", "transcripts_fts_au", "transcripts_fts_ad")
  }
}
