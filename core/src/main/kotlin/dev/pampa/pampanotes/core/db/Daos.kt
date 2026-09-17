package dev.pampa.pampanotes.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
  @Query(
    """
    SELECT f.*,
      (SELECT COUNT(*) FROM notes n WHERE n.folderId = f.id) AS noteCount,
      (SELECT COUNT(*) FROM folders c WHERE c.parentId = f.id) AS childCount
    FROM folders f
    WHERE f.parentId IS :parentId
    ORDER BY f.sortOrder, f.name COLLATE NOCASE
    """,
  )
  fun observeChildren(parentId: String?): Flow<List<FolderRow>>

  @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
  fun observeAll(): Flow<List<FolderEntity>>

  @Query("SELECT * FROM folders")
  suspend fun all(): List<FolderEntity>

  @Query("SELECT * FROM folders WHERE id = :id")
  suspend fun get(id: String): FolderEntity?

  @Query("SELECT * FROM folders WHERE id = :id")
  fun observe(id: String): Flow<FolderEntity?>

  @Query("SELECT * FROM folders WHERE parentId IS :parentId ORDER BY sortOrder, name COLLATE NOCASE")
  suspend fun children(parentId: String?): List<FolderEntity>

  @Upsert
  suspend fun upsert(folder: FolderEntity)

  @Upsert
  suspend fun upsertAll(folders: List<FolderEntity>)

  @Query("DELETE FROM folders WHERE id = :id")
  suspend fun delete(id: String)

  @Query("SELECT COUNT(*) FROM folders")
  suspend fun count(): Int

  @Query("SELECT COUNT(*) FROM folders")
  fun observeCount(): Flow<Int>

  @Query("SELECT COUNT(*) FROM folders WHERE parentId IS :parentId AND name = :name COLLATE NOCASE AND id != :excludeId")
  suspend fun countSiblingsNamed(parentId: String?, name: String, excludeId: String): Int
}

@Dao
interface NoteDao {
  @Query(
    """
    SELECT n.*,
      (SELECT COUNT(*) FROM sessions s WHERE s.noteId = n.id) AS sessionCount,
      (SELECT COUNT(*) FROM audio_parts p JOIN sessions s ON p.sessionId = s.id WHERE s.noteId = n.id) AS audioCount,
      (SELECT COALESCE(SUM(p.durationMs), 0) FROM audio_parts p JOIN sessions s ON p.sessionId = s.id WHERE s.noteId = n.id) AS audioDurationMs,
      (SELECT COUNT(*) FROM sources src WHERE src.noteId = n.id) AS sourceCount,
      (SELECT COUNT(*) FROM sessions s WHERE s.noteId = n.id AND s.activeTranscriptId IS NULL
         AND EXISTS (SELECT 1 FROM audio_parts p WHERE p.sessionId = s.id)) AS untranscribedSessions
    FROM notes n
    WHERE n.folderId = :folderId
    ORDER BY n.pinned DESC, n.updatedAt DESC
    """,
  )
  fun observeRows(folderId: String): Flow<List<NoteRow>>

  @Query(
    """
    SELECT n.*,
      (SELECT COUNT(*) FROM sessions s WHERE s.noteId = n.id) AS sessionCount,
      (SELECT COUNT(*) FROM audio_parts p JOIN sessions s ON p.sessionId = s.id WHERE s.noteId = n.id) AS audioCount,
      (SELECT COALESCE(SUM(p.durationMs), 0) FROM audio_parts p JOIN sessions s ON p.sessionId = s.id WHERE s.noteId = n.id) AS audioDurationMs,
      (SELECT COUNT(*) FROM sources src WHERE src.noteId = n.id) AS sourceCount,
      (SELECT COUNT(*) FROM sessions s WHERE s.noteId = n.id AND s.activeTranscriptId IS NULL
         AND EXISTS (SELECT 1 FROM audio_parts p WHERE p.sessionId = s.id)) AS untranscribedSessions
    FROM notes n
    ORDER BY n.updatedAt DESC
    LIMIT :limit
    """,
  )
  fun observeRecent(limit: Int): Flow<List<NoteRow>>

  @Query("SELECT * FROM notes WHERE id = :id")
  suspend fun get(id: String): NoteEntity?

  @Query("SELECT * FROM notes WHERE id = :id")
  fun observe(id: String): Flow<NoteEntity?>

  @Query("SELECT * FROM notes WHERE id IN (:ids)")
  suspend fun getAll(ids: List<String>): List<NoteEntity>

  @Query("SELECT * FROM notes WHERE folderId = :folderId ORDER BY pinned DESC, updatedAt DESC")
  suspend fun byFolder(folderId: String): List<NoteEntity>

  @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
  suspend fun all(): List<NoteEntity>

  @Query("SELECT * FROM notes WHERE title LIKE '%' || :query || '%' ORDER BY updatedAt DESC LIMIT :limit")
  suspend fun byTitle(query: String, limit: Int): List<NoteEntity>

  @Upsert
  suspend fun upsert(note: NoteEntity)

  @Upsert
  suspend fun upsertAll(notes: List<NoteEntity>)

  @Query("UPDATE notes SET body = :body, updatedAt = :updatedAt WHERE id = :id")
  suspend fun updateBody(id: String, body: String, updatedAt: Long)

  @Query("UPDATE notes SET title = :title, updatedAt = :updatedAt WHERE id = :id")
  suspend fun updateTitle(id: String, title: String, updatedAt: Long)

  @Query("UPDATE notes SET folderId = :folderId, updatedAt = :updatedAt WHERE id = :id")
  suspend fun move(id: String, folderId: String, updatedAt: Long)

  @Query("UPDATE notes SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
  suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long)

  @Query("UPDATE notes SET updatedAt = :updatedAt WHERE id = :id")
  suspend fun touch(id: String, updatedAt: Long)

  @Query("DELETE FROM notes WHERE id = :id")
  suspend fun delete(id: String)

  @Query("SELECT COUNT(*) FROM notes")
  fun observeCount(): Flow<Int>

  @Query("SELECT COUNT(*) FROM notes")
  suspend fun count(): Int
}

@Dao
interface NoteTagDao {
  @Query("SELECT tag FROM note_tags WHERE noteId = :noteId ORDER BY tag")
  fun observe(noteId: String): Flow<List<String>>

  @Query("SELECT tag FROM note_tags WHERE noteId = :noteId ORDER BY tag")
  suspend fun tags(noteId: String): List<String>

  @Query("SELECT * FROM note_tags")
  suspend fun all(): List<NoteTagEntity>

  @Query("SELECT DISTINCT tag FROM note_tags ORDER BY tag")
  fun observeAllTags(): Flow<List<String>>

  @Query("DELETE FROM note_tags WHERE noteId = :noteId")
  suspend fun clear(noteId: String)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAll(tags: List<NoteTagEntity>)

  @Transaction
  suspend fun replace(noteId: String, tags: List<String>) {
    clear(noteId)
    insertAll(tags.map { NoteTagEntity(noteId, it) })
  }
}

@Dao
interface SessionDao {
  @Transaction
  @Query("SELECT * FROM sessions WHERE noteId = :noteId ORDER BY position")
  fun observeByNote(noteId: String): Flow<List<SessionWithParts>>

  @Transaction
  @Query("SELECT * FROM sessions WHERE noteId = :noteId ORDER BY position")
  suspend fun byNote(noteId: String): List<SessionWithParts>

  @Transaction
  @Query("SELECT * FROM sessions WHERE id = :id")
  fun observe(id: String): Flow<SessionWithParts?>

  @Transaction
  @Query("SELECT * FROM sessions WHERE id = :id")
  suspend fun getWithParts(id: String): SessionWithParts?

  @Query("SELECT * FROM sessions WHERE id = :id")
  suspend fun get(id: String): SessionEntity?

  @Query("SELECT * FROM sessions ORDER BY noteId, position")
  suspend fun all(): List<SessionEntity>

  @Query("SELECT * FROM sessions WHERE noteId = :noteId ORDER BY position DESC LIMIT 1")
  suspend fun last(noteId: String): SessionEntity?

  @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM sessions WHERE noteId = :noteId")
  suspend fun nextPosition(noteId: String): Int

  @Upsert
  suspend fun upsert(session: SessionEntity)

  @Upsert
  suspend fun upsertAll(sessions: List<SessionEntity>)

  @Query("UPDATE sessions SET activeTranscriptId = :transcriptId, updatedAt = :updatedAt WHERE id = :id")
  suspend fun setActiveTranscript(id: String, transcriptId: String?, updatedAt: Long)

  @Query("UPDATE sessions SET title = :title, date = :date, updatedAt = :updatedAt WHERE id = :id")
  suspend fun rename(id: String, title: String, date: String, updatedAt: Long)

  @Query("UPDATE sessions SET position = :position, updatedAt = :updatedAt WHERE id = :id")
  suspend fun setPosition(id: String, position: Int, updatedAt: Long)

  @Query("DELETE FROM sessions WHERE id = :id")
  suspend fun delete(id: String)

  @Query("SELECT COUNT(*) FROM sessions")
  suspend fun count(): Int
}

@Dao
interface AudioPartDao {
  @Query("SELECT * FROM audio_parts WHERE sessionId = :sessionId ORDER BY position")
  fun observeBySession(sessionId: String): Flow<List<AudioPartEntity>>

  @Query("SELECT * FROM audio_parts WHERE sessionId = :sessionId ORDER BY position")
  suspend fun bySession(sessionId: String): List<AudioPartEntity>

  @Query("SELECT * FROM audio_parts WHERE id = :id")
  suspend fun get(id: String): AudioPartEntity?

  @Query("SELECT * FROM audio_parts")
  suspend fun all(): List<AudioPartEntity>

  @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM audio_parts WHERE sessionId = :sessionId")
  suspend fun nextPosition(sessionId: String): Int

  @Upsert
  suspend fun upsert(part: AudioPartEntity)

  @Upsert
  suspend fun upsertAll(parts: List<AudioPartEntity>)

  @Query("UPDATE audio_parts SET sessionId = :sessionId, position = :position WHERE id = :id")
  suspend fun move(id: String, sessionId: String, position: Int)

  @Query("DELETE FROM audio_parts WHERE id = :id")
  suspend fun delete(id: String)

  @Query("SELECT * FROM audio_parts WHERE sha256 = :sha LIMIT 1")
  suspend fun findBySha(sha: String): AudioPartEntity?

  @Query("SELECT COUNT(*) AS count, COALESCE(SUM(sizeBytes), 0) AS bytes FROM audio_parts")
  fun observeTotal(): Flow<SizeTotal>

  @Query("SELECT fileName FROM audio_parts")
  suspend fun fileNames(): List<String>
}

@Dao
interface TranscriptDao {
  @Query("SELECT * FROM transcripts WHERE sessionId = :sessionId ORDER BY createdAt")
  fun observeBySession(sessionId: String): Flow<List<TranscriptEntity>>

  @Query("SELECT * FROM transcripts WHERE sessionId = :sessionId ORDER BY createdAt")
  suspend fun bySession(sessionId: String): List<TranscriptEntity>

  @Query("SELECT * FROM transcripts WHERE id = :id")
  suspend fun get(id: String): TranscriptEntity?

  @Query("SELECT * FROM transcripts WHERE id = :id")
  fun observe(id: String): Flow<TranscriptEntity?>

  @Query("SELECT * FROM transcripts")
  suspend fun all(): List<TranscriptEntity>

  @Query("SELECT * FROM transcripts WHERE sessionId = :sessionId AND kind = 'RAW' ORDER BY createdAt DESC LIMIT 1")
  suspend fun rawForSession(sessionId: String): TranscriptEntity?

  @Query("SELECT id FROM transcripts WHERE parentId = :parentId")
  suspend fun childIds(parentId: String): List<String>

  @Upsert
  suspend fun upsert(transcript: TranscriptEntity)

  @Upsert
  suspend fun upsertAll(transcripts: List<TranscriptEntity>)

  @Query("DELETE FROM transcripts WHERE id = :id")
  suspend fun delete(id: String)

  @Query("DELETE FROM transcripts WHERE parentId = :parentId")
  suspend fun deleteChildren(parentId: String)

  @Query("SELECT COUNT(*) FROM transcripts")
  suspend fun count(): Int
}

@Dao
interface SegmentDao {
  @Query("SELECT * FROM segments WHERE transcriptId = :transcriptId ORDER BY sessionStartMs, id")
  fun observe(transcriptId: String): Flow<List<SegmentEntity>>

  @Query("SELECT * FROM segments WHERE transcriptId = :transcriptId ORDER BY sessionStartMs, id")
  suspend fun byTranscript(transcriptId: String): List<SegmentEntity>

  @Query("SELECT * FROM segments")
  suspend fun all(): List<SegmentEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(segments: List<SegmentEntity>)

  @Query("DELETE FROM segments WHERE transcriptId = :transcriptId")
  suspend fun deleteByTranscript(transcriptId: String)

  @Query("SELECT COUNT(*) FROM segments WHERE transcriptId = :transcriptId")
  suspend fun count(transcriptId: String): Int
}

@Dao
interface SourceDao {
  @Query("SELECT * FROM sources WHERE noteId = :noteId ORDER BY importedAt DESC")
  fun observeByNote(noteId: String): Flow<List<SourceEntity>>

  @Query("SELECT * FROM sources WHERE noteId = :noteId ORDER BY importedAt DESC")
  suspend fun byNote(noteId: String): List<SourceEntity>

  @Query("SELECT * FROM sources WHERE id = :id")
  suspend fun get(id: String): SourceEntity?

  @Query("SELECT * FROM sources")
  suspend fun all(): List<SourceEntity>

  @Query("SELECT * FROM sources WHERE sha256 = :sha ORDER BY importedAt DESC LIMIT 1")
  suspend fun findBySha(sha: String): SourceEntity?

  @Upsert
  suspend fun upsert(source: SourceEntity)

  @Upsert
  suspend fun upsertAll(sources: List<SourceEntity>)

  @Query("DELETE FROM sources WHERE id = :id")
  suspend fun delete(id: String)

  @Query("SELECT COUNT(*) AS count, COALESCE(SUM(sizeBytes), 0) AS bytes FROM sources WHERE storedFileName IS NOT NULL")
  fun observeTotal(): Flow<SizeTotal>

  @Query("SELECT storedFileName FROM sources WHERE storedFileName IS NOT NULL")
  suspend fun storedFileNames(): List<String>
}

@Dao
interface JobDao {
  @Query("SELECT * FROM jobs ORDER BY createdAt DESC")
  fun observeAll(): Flow<List<JobEntity>>

  @Query("SELECT * FROM jobs WHERE state IN ('QUEUED','PREPARING','UPLOADING','TRANSCRIBING','STITCHING','CANCEL_REQUESTED') ORDER BY createdAt")
  fun observeActive(): Flow<List<JobEntity>>

  @Query("SELECT COUNT(*) FROM jobs WHERE state IN ('QUEUED','PREPARING','UPLOADING','TRANSCRIBING','STITCHING','CANCEL_REQUESTED')")
  fun observeActiveCount(): Flow<Int>

  @Query("SELECT * FROM jobs WHERE sessionId = :sessionId ORDER BY createdAt DESC")
  fun observeBySession(sessionId: String): Flow<List<JobEntity>>

  @Query("SELECT * FROM jobs WHERE sessionId = :sessionId AND state IN ('QUEUED','PREPARING','UPLOADING','TRANSCRIBING','STITCHING','CANCEL_REQUESTED') ORDER BY createdAt DESC LIMIT 1")
  suspend fun activeForSession(sessionId: String): JobEntity?

  @Query("SELECT * FROM jobs WHERE id = :id")
  suspend fun get(id: String): JobEntity?

  @Query("SELECT * FROM jobs WHERE id = :id")
  fun observe(id: String): Flow<JobEntity?>

  @Query("SELECT * FROM jobs WHERE provider = :provider AND state = 'QUEUED' ORDER BY createdAt LIMIT 1")
  suspend fun nextQueued(provider: String): JobEntity?

  @Query("SELECT COUNT(*) FROM jobs WHERE provider = :provider AND state = 'QUEUED'")
  suspend fun queuedCount(provider: String): Int

  @Upsert
  suspend fun upsert(job: JobEntity)

  @Update
  suspend fun update(job: JobEntity)

  @Query("UPDATE jobs SET state = :state, updatedAt = :updatedAt WHERE id = :id")
  suspend fun setState(id: String, state: JobState, updatedAt: Long)

  @Query("UPDATE jobs SET state = 'QUEUED', phase = NULL, updatedAt = :updatedAt WHERE state IN ('PREPARING','UPLOADING','TRANSCRIBING','STITCHING')")
  suspend fun requeueInterrupted(updatedAt: Long)

  @Query("DELETE FROM jobs WHERE id = :id")
  suspend fun delete(id: String)

  @Query("DELETE FROM jobs WHERE state IN ('DONE','FAILED','CANCELLED')")
  suspend fun deleteTerminal()

  @Query("SELECT * FROM jobs")
  suspend fun all(): List<JobEntity>
}

@Dao
interface ExportPresetDao {
  @Query("SELECT * FROM export_presets ORDER BY isDefault DESC, lastUsedAt DESC")
  fun observeAll(): Flow<List<ExportPresetEntity>>

  @Query("SELECT * FROM export_presets ORDER BY isDefault DESC, lastUsedAt DESC")
  suspend fun all(): List<ExportPresetEntity>

  @Query("SELECT * FROM export_presets WHERE id = :id")
  suspend fun get(id: String): ExportPresetEntity?

  @Upsert
  suspend fun upsert(preset: ExportPresetEntity)

  @Query("UPDATE export_presets SET isDefault = 0")
  suspend fun clearDefault()

  @Query("DELETE FROM export_presets WHERE id = :id")
  suspend fun delete(id: String)
}

@Dao
interface SearchDao {
  @Query("SELECT noteId, snippet(notes_fts, '[', ']', '…', -1, 14) AS snippet FROM notes_fts WHERE notes_fts MATCH :query LIMIT :limit")
  suspend fun searchNotes(query: String, limit: Int): List<NoteHit>

  @Query("SELECT transcriptId, sessionId, noteId, snippet(transcripts_fts, '[', ']', '…', -1, 14) AS snippet FROM transcripts_fts WHERE transcripts_fts MATCH :query LIMIT :limit")
  suspend fun searchTranscripts(query: String, limit: Int): List<TranscriptHit>

  @Query("DELETE FROM notes_fts")
  suspend fun clearNotesIndex()

  @Query("INSERT INTO notes_fts(noteId, title, body) SELECT id, title, body FROM notes")
  suspend fun fillNotesIndex()

  @Query("DELETE FROM transcripts_fts")
  suspend fun clearTranscriptsIndex()

  @Query("INSERT INTO transcripts_fts(transcriptId, sessionId, noteId, text) SELECT t.id, t.sessionId, s.noteId, t.text FROM transcripts t JOIN sessions s ON s.id = t.sessionId")
  suspend fun fillTranscriptsIndex()

  /** Ricostruisce tutto: dopo un ripristino, o dal tasto in Archiviazione. */
  @Transaction
  suspend fun rebuild() {
    clearNotesIndex()
    fillNotesIndex()
    clearTranscriptsIndex()
    fillTranscriptsIndex()
  }
}
