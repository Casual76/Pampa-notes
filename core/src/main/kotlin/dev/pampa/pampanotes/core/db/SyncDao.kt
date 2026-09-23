package dev.pampa.pampanotes.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Il diario di bordo della sincronizzazione: outbox, stato, guardia, impronte.
 *
 * Qui `OnConflictStrategy.REPLACE` e' ammesso — su `sync_outbox` e `sync_guard` non ci sono figli
 * ne' trigger — ed e' anzi quello che serve all'outbox: sostituire la voce di una riga le da' un
 * `id` nuovo, cioe' una revisione nuova. Sulle tabelle sincronizzate invece REPLACE e' vietato:
 * cancella i figli in cascata.
 */
@Dao
interface SyncDao {
  // --- outbox ---

  @Query("SELECT * FROM sync_outbox ORDER BY id")
  suspend fun outbox(): List<SyncOutboxEntity>

  @Query("SELECT COUNT(*) FROM sync_outbox")
  fun observeOutboxCount(): Flow<Int>

  @Query("SELECT * FROM sync_outbox WHERE tbl = :tbl AND rowId = :rowId")
  suspend fun outboxEntry(tbl: String, rowId: String): SyncOutboxEntity?

  /** Una voce scritta a mano, fuori dai trigger: la nota di conflitto nasce sotto la guardia. */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun markDirty(entry: SyncOutboxEntity)

  /** Cancella la voce solo se e' ancora quella letta: una modifica nel frattempo ha un id nuovo. */
  @Query("DELETE FROM sync_outbox WHERE tbl = :tbl AND rowId = :rowId AND id = :id")
  suspend fun clearOutbox(tbl: String, rowId: String, id: Long)

  @Query("DELETE FROM sync_outbox WHERE tbl = :tbl AND rowId = :rowId")
  suspend fun forgetOutbox(tbl: String, rowId: String)

  @Query("DELETE FROM sync_outbox")
  suspend fun clearAllOutbox()

  // --- la seminatura: tutto quello che c'e' entra nell'outbox come «da mandare» ---

  @Query("INSERT OR IGNORE INTO sync_outbox (tbl, rowId, op) SELECT 'folders', id, 'U' FROM folders")
  suspend fun seedFolders()

  @Query("INSERT OR IGNORE INTO sync_outbox (tbl, rowId, op) SELECT 'notes', id, 'U' FROM notes")
  suspend fun seedNotes()

  @Query("INSERT OR IGNORE INTO sync_outbox (tbl, rowId, op) SELECT 'sources', id, 'U' FROM sources")
  suspend fun seedSources()

  @Query("INSERT OR IGNORE INTO sync_outbox (tbl, rowId, op) SELECT 'sessions', id, 'U' FROM sessions")
  suspend fun seedSessions()

  @Query("INSERT OR IGNORE INTO sync_outbox (tbl, rowId, op) SELECT 'audio_parts', id, 'U' FROM audio_parts")
  suspend fun seedAudioParts()

  @Query("INSERT OR IGNORE INTO sync_outbox (tbl, rowId, op) SELECT 'transcripts', id, 'U' FROM transcripts")
  suspend fun seedTranscripts()

  @Query("INSERT OR IGNORE INTO sync_outbox (tbl, rowId, op) SELECT 'export_presets', id, 'U' FROM export_presets")
  suspend fun seedExportPresets()

  // --- stato ---

  @Query("SELECT * FROM sync_state WHERE id = 1")
  suspend fun state(): SyncStateEntity?

  @Query("SELECT * FROM sync_state WHERE id = 1")
  fun observeState(): Flow<SyncStateEntity?>

  @Upsert
  suspend fun upsertState(state: SyncStateEntity)

  // --- guardia ---

  @Query("INSERT OR REPLACE INTO sync_guard (id, applying) VALUES (1, :applying)")
  suspend fun setApplying(applying: Int)

  // --- impronte ---

  @Query("SELECT * FROM sync_meta WHERE tbl = :tbl AND rowId = :rowId")
  suspend fun meta(tbl: String, rowId: String): SyncMetaEntity?

  @Query("SELECT * FROM sync_meta")
  suspend fun allMeta(): List<SyncMetaEntity>

  @Upsert
  suspend fun upsertMeta(meta: SyncMetaEntity)

  @Query("DELETE FROM sync_meta WHERE tbl = :tbl AND rowId = :rowId")
  suspend fun deleteMeta(tbl: String, rowId: String)

  @Query("DELETE FROM sync_meta")
  suspend fun clearAllMeta()

  // --- origine ---

  @Upsert
  suspend fun upsertOrigin(origin: SyncOriginEntity)

  @Query("DELETE FROM sync_origin WHERE tbl = :tbl AND rowId = :rowId")
  suspend fun deleteOrigin(tbl: String, rowId: String)

  @Query("DELETE FROM sync_origin")
  suspend fun clearAllOrigin()

  /** Quante righe hanno una versione concordata con un account: zero vuol dire «mai sincronizzato». */
  @Query("SELECT COUNT(*) FROM sync_meta")
  suspend fun metaCount(): Int

  // --- i discendenti, per le cancellazioni remote ---
  //
  // Una cartella, una nota o una sessione cancellate altrove si portano via i figli in cascata, e
  // la cascata non passa dall'applier: i file delle parti e delle fonti si spostano nel cestino
  // prima, e una modifica fatta qui a un figlio ferma la cancellazione (vedi SyncApplier).

  @Query("SELECT id FROM notes WHERE folderId IN (:folderIds)")
  suspend fun noteIdsInFolders(folderIds: List<String>): List<String>

  @Query("SELECT id FROM sessions WHERE noteId IN (:noteIds)")
  suspend fun sessionIdsOfNotes(noteIds: List<String>): List<String>

  @Query("SELECT id FROM transcripts WHERE sessionId IN (:sessionIds)")
  suspend fun transcriptIdsOfSessions(sessionIds: List<String>): List<String>

  @Query("SELECT * FROM audio_parts WHERE sessionId IN (:sessionIds)")
  suspend fun partsOfSessions(sessionIds: List<String>): List<AudioPartEntity>

  @Query("SELECT * FROM sources WHERE noteId IN (:noteIds)")
  suspend fun sourcesOfNotes(noteIds: List<String>): List<SourceEntity>

  /** Le voci «cambiata» di queste righe: i tombstone no, una riga cancellata qui non ha niente da difendere. */
  @Query("SELECT * FROM sync_outbox WHERE op = 'U' AND tbl = :tbl AND rowId IN (:ids)")
  suspend fun dirtyAmong(tbl: String, ids: List<String>): List<SyncOutboxEntity>
}
