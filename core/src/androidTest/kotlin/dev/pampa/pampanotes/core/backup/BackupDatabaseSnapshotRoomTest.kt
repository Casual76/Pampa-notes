package dev.pampa.pampanotes.core.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.repo.DatabaseTransactions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** PREPARED, NOT EXECUTED: real Room/writer/WAL and production snapshot -> BackupArchive -> Room.
 * Only generated fixture databases and synthetic bytes; no WorkManager, accounts, providers/audio.
 */
@RunWith(AndroidJUnit4::class)
class BackupDatabaseSnapshotRoomTest {
  @get:Rule val migrations = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(), PampaDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
  )

  private class Fixture : AutoCloseable {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.cacheDir, "backup-content-${UUID.randomUUID()}").also { it.mkdirs() }
    val db = PampaDatabase.build(context, File(root, "source.sqlite").absolutePath).also {
      it.openHelper.setWriteAheadLoggingEnabled(true)
    }
    val transactions = DatabaseTransactions(db)
    val snapshot = BackupDatabaseSnapshot(db, transactions)
    val target = File(root, "snapshot.sqlite")
    val note = NoteEntity("n", "f", "Before", body = " Exact body\r\nΩ\t", createdAt = 1, updatedAt = 2,
      bundleBodyBase = " baseline\r\n", bundleNativeAdopted = true)
    val session = SessionEntity("s", "n", title = "Before session", date = "2026-11-08", position = 0,
      activeTranscriptId = "refined", createdAt = 3, updatedAt = 4, transcribingOn = PRIVATE_WORK,
      transcribingSince = 12345, voiceNames = " {\"p:SPEAKER_01\":\"Speaker Ω\"} ")

    suspend fun seed() {
      db.folders().upsert(FolderEntity("f", "Personal", createdAt = 1, updatedAt = 2, kind = "personal"))
      db.notes().upsert(note)
      db.tags().insertAll(listOf(NoteTagEntity("n", " Exact tag Ω ")))
      db.sessions().upsert(session)
      db.audioParts().upsert(AudioPartEntity("p", "s", 0, "p.wav", "original.wav", "audio/wav", 32, 1234,
        "public-hash", 99, archivedAt = 77, recordedAtEpochMs = 1780000000123,
        recordedAtLocal = "2026-11-08T11:22:33.123", recordedAtZoneId = "Europe/Rome",
        recordedAtSource = "samsung_recording", recordedAtPrecision = "millisecond"))
      for ((id, source) in listOf("raw" to EXACT_SOURCE, "empty" to "", "null" to null)) {
        db.transcripts().upsert(TranscriptEntity(id, "s", TranscriptKind.RAW, "mistral", "public-model",
          text = " $id\r\nΩ\t", wordCount = 3, createdAt = 5, cloudSourceJson = source))
      }
      db.transcripts().upsert(TranscriptEntity("refined", "s", TranscriptKind.REFINED, "groq", "public-refiner",
        text = " Refined\r\nΩ ", parentId = "raw", preset = "CUSTOM", promptHash = "public-prompt-hash", wordCount = 2, createdAt = 6))
      db.segments().insertAll(listOf(SegmentEntity(id = 17, transcriptId = "raw", partId = "p", indexInPart = 0,
        partStartMs = 0, partEndMs = 1234, sessionStartMs = 0, sessionEndMs = 1234, text = " Segment Ω ",
        wordsJson = " [{\"word\":\" Ω \",\"start\":0.25,\"end\":0.75}] ", wordsEstimated = false, speaker = "SPEAKER_01")))
      db.sources().upsert(SourceEntity("source", "n", SourceKind.SDOCX, "original.sdocx", "application/zip", 44,
        "source-hash", "source.sdocx", extractedChars = 23, detail = " Exact material ", importedAt = 7,
        archivedAt = 88, sdocxCreatedAtMillis = 1770000000000))
      db.exportPresets().upsert(ExportPresetEntity("preset", "Preset", " {\"format\":\"pn\",\"future\":true} ", 8, true))
      db.stats().insert(TranscriptionRunEntity("run", "completed-history-job", "s", "n", "mistral", "public-model",
        "cpu", 1234, 80, 50, 3, 1, false, 9, "Public historical device label"))
      val sql = db.openHelper.writableDatabase
      sql.execSQL("INSERT INTO jobs (id,sessionId,type,provider,state,chunkTotal,chunkDone,progress,attempts,optionsJson,createdAt,updatedAt) VALUES ('queued','s','TRANSCRIBE','mistral','QUEUED',0,0,0,0,?,10,11)", arrayOf(PRIVATE_JOB))
      sql.execSQL("INSERT INTO sync_state (id,lastPullSeq,deviceId) VALUES (1,9876,?)", arrayOf(PRIVATE_DEVICE))
      sql.execSQL("INSERT INTO sync_origin (tbl,rowId,ownerId) VALUES ('notes','n',?)", arrayOf(PRIVATE_OWNER))
      sql.execSQL("INSERT INTO sync_meta (tbl,rowId,serverSeq,hash,updatedAt) VALUES ('pending.relay','journal',1,?,12)", arrayOf(PRIVATE_PENDING))
      sql.execSQL("INSERT INTO notes_fts (noteId,title,body) VALUES ('orphan','stale',?)", arrayOf(PRIVATE_STALE_SEARCH))
    }

    override fun close() { db.close(); root.deleteRecursively() }
  }

  private fun dump(query: (String) -> Cursor, table: String, clearWork: Boolean = false): List<List<Any?>> =
    query("SELECT * FROM ${BackupContentSql.quote(table)}").use { cursor ->
      buildList {
        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
          if (clearWork && cursor.columnNames[index] in setOf("transcribingOn", "transcribingSince")) null else when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
            else -> cursor.getString(index)
          }
        })
      }.sortedBy { it.toString() }
    }

  private fun schema(query: (String) -> Cursor): List<List<String?>> =
    query("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name").use { cursor ->
      buildList { while (cursor.moveToNext()) add((0..3).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
    }

  @Test fun fullGraphAndExactPublicBytesTravelThroughProductionArchiveAndReopenWithRoom() = runBlocking(Dispatchers.IO) {
    Fixture().use { f ->
      f.seed()
      assertTrue(f.db.openHelper.writableDatabase.isWriteAheadLoggingEnabled)
      val query: (String) -> Cursor = { f.db.openHelper.readableDatabase.query(it) }
      val original = (BackupContentSql.contentTables + BackupContentSql.privateTables).associateWith { dump(query, it) }
      val originalSchema = schema(query)
      val info = f.snapshot.capture(f.target)
      assertEquals(BackupCounts(1, 1, 1, 1, 4, 1), info.counts)
      val out = ByteArrayOutputStream()
      BackupArchive.write(out, BackupManifest(createdAt = 1, app = "Synthetic", databaseVersion = info.version,
        databaseBytes = f.target.length(), counts = info.counts), f.target)
      val staged = BackupArchive.extract(ByteArrayInputStream(out.toByteArray()), File(f.root, "extracted"), maxDatabaseVersion = 15)
      assertArrayEquals(f.target.readBytes(), staged.database.readBytes())
      listOf(PRIVATE_JOB, PRIVATE_DEVICE, PRIVATE_OWNER, PRIVATE_PENDING, PRIVATE_WORK, PRIVATE_STALE_SEARCH).forEach { sentinel ->
        assertFalse(sentinel, staged.database.readBytes().toString(Charsets.ISO_8859_1).contains(sentinel))
        assertFalse(sentinel, out.toByteArray().toString(Charsets.ISO_8859_1).contains(sentinel))
      }
      SQLiteDatabase.openDatabase(staged.database.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
        val copied: (String) -> Cursor = { copy.rawQuery(it, null) }
        assertEquals(originalSchema, schema(copied))
        for (table in BackupContentSql.contentTables) assertEquals(table, dump(query, table, clearWork = table == "sessions"), dump(copied, table))
        for (table in BackupContentSql.privateTables) assertTrue(table, dump(copied, table).isEmpty())
        assertEquals(dump(query, "room_master_table"), dump(copied, "room_master_table"))
        copy.rawQuery("PRAGMA integrity_check", null).use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
        copy.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
      }
      // Room's production opener validates the unchanged identity/schema and installs its callbacks.
      val restored = PampaDatabase.build(f.context, staged.database.absolutePath)
      try {
        assertEquals(1, restored.notes().count())
        assertEquals(EXACT_SOURCE, restored.transcripts().get("raw")!!.cloudSourceJson)
        assertEquals("", restored.transcripts().get("empty")!!.cloudSourceJson)
        assertNull(restored.transcripts().get("null")!!.cloudSourceJson)
        assertEquals("refined", restored.sessions().get("s")!!.activeTranscriptId)
        assertNull(restored.sessions().get("s")!!.transcribingOn)
        assertNull(restored.sync().state())
        assertEquals(0, restored.sync().metaCount())
        assertTrue(restored.sync().outbox().isEmpty())
        assertTrue(dump({ restored.openHelper.readableDatabase.query(it) }, "jobs").isEmpty())
      } finally { restored.close() }
      for ((table, expected) in original) assertEquals("Live $table unchanged", expected, dump(query, table))
    }
  }

  @Test fun ordinaryDaoAndExplicitRoomWritersCannotEnterHalfwayThroughCapture() = runBlocking(Dispatchers.IO) {
    withTimeout(30_000) {
      for (explicit in listOf(false, true)) Fixture().use { f ->
        f.seed()
        val atNotes = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val attempted = CompletableDeferred<Unit>()
        val backup = async {
          f.snapshot.capture(f.target) { table -> if (table == "notes") { atNotes.complete(Unit); resume.await() } }
        }
        atNotes.await()
        val writer = async(start = CoroutineStart.UNDISPATCHED) {
          attempted.complete(Unit)
          val update: suspend () -> Unit = {
            f.db.notes().upsert(f.note.copy(title = "After"))
            f.db.sessions().upsert(f.session.copy(title = "After session"))
          }
          if (explicit) f.transactions.run(update) else update()
        }
        attempted.await()
        assertFalse("Writer must wait for real Room exclusion", writer.isCompleted)
        resume.complete(Unit)
        backup.await()
        writer.await()
        SQLiteDatabase.openDatabase(f.target.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
          copy.rawQuery("SELECT n.title,s.title FROM notes n JOIN sessions s ON s.noteId=n.id", null).use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals("Before", cursor.getString(0)); assertEquals("Before session", cursor.getString(1))
          }
        }
        assertEquals("After", f.db.notes().get("n")!!.title)
      }
    }
  }

  @Test fun cancellationRemovesTheWholeSnapshotAndReleasesRoomWriterExclusion() = runBlocking(Dispatchers.IO) {
    withTimeout(30_000) {
      Fixture().use { f ->
        f.seed()
        val reached = CompletableDeferred<Unit>()
        val backup = launch { f.snapshot.capture(f.target) { if (it == "sessions") { reached.complete(Unit); awaitCancellation() } } }
        reached.await()
        backup.cancelAndJoin()
        listOf("", "-wal", "-shm", "-journal").forEach { assertFalse(File(f.target.path + it).exists()) }
        assertEquals(PRIVATE_WORK, f.db.sessions().get("s")!!.transcribingOn)
        assertTrue(dump({ f.db.openHelper.readableDatabase.query(it) }, "jobs").isNotEmpty())
        f.transactions.run { f.db.notes().upsert(f.note.copy(title = "Writable after cancellation")) }
        assertEquals("Writable after cancellation", f.db.notes().get("n")!!.title)
      }
    }
  }

  @Test fun largePublicSourceBeyondCursorWindowKeepsExactNulUnicodeAndFinalTail() = runBlocking(Dispatchers.IO) {
    Fixture().use { f ->
      f.seed()
      val exact = " Ω🙂\u0000\r\n\t".repeat(300_000) + " Exact final tail "
      f.db.openHelper.writableDatabase.execSQL("UPDATE transcripts SET cloudSourceJson = ? WHERE id = 'raw'", arrayOf(exact))
      f.snapshot.capture(f.target)
      SQLiteDatabase.openDatabase(f.target.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
        val bytes = ByteArrayOutputStream()
        val length = copy.rawQuery("SELECT length(CAST(cloudSourceJson AS BLOB)) FROM transcripts WHERE id='raw'", null).use { it.moveToFirst(); it.getInt(0) }
        assertTrue(length > 2 * 1024 * 1024)
        var offset = 1
        while (offset <= length) {
          copy.rawQuery("SELECT substr(CAST(cloudSourceJson AS BLOB), ?, 65536) FROM transcripts WHERE id='raw'", arrayOf(offset.toString())).use { chunk ->
            assertTrue(chunk.moveToFirst())
            val block = chunk.getBlob(0)
            assertTrue(block.isNotEmpty())
            bytes.write(block)
            offset += block.size
          }
        }
        assertArrayEquals(exact.toByteArray(Charsets.UTF_8), bytes.toByteArray())
      }
    }
  }

  @Test fun schema14RestoreDropsOldJobsAndIdentityThenUsesNormalProductionMigration() = runBlocking(Dispatchers.IO) {
    Fixture().use { f ->
      val legacy = File(f.root, "legacy.sqlite")
      migrations.createDatabase(legacy.absolutePath, 14).use { db ->
        db.execSQL("INSERT INTO folders (id,name,sortOrder,createdAt,updatedAt) VALUES ('f','Legacy',0,1,1)")
        db.execSQL("INSERT INTO notes (id,folderId,title,body,pinned,createdAt,updatedAt,bundleBodyBase,bundleNativeAdopted) VALUES ('n','f','Legacy',' Exact body ',0,1,1,' baseline ',1)")
        db.execSQL("INSERT INTO sessions (id,noteId,title,date,position,createdAt,updatedAt,transcribingOn) VALUES ('s','n','','2026-11-08',0,1,1,?)", arrayOf(PRIVATE_WORK))
        db.execSQL("INSERT INTO jobs (id,sessionId,type,provider,state,chunkTotal,chunkDone,progress,attempts,optionsJson,createdAt,updatedAt) VALUES ('queued','s','TRANSCRIBE','mistral','QUEUED',0,0,0,0,?,1,1)", arrayOf(PRIVATE_JOB))
        db.execSQL("INSERT INTO sync_state (id,lastPullSeq,deviceId) VALUES (1,99,?)", arrayOf(PRIVATE_DEVICE))
      }
      val info = f.snapshot.prepareRestore(legacy, f.target)
      assertEquals(14, info.version)
      SQLiteDatabase.openDatabase(legacy.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { original ->
        assertEquals(14, original.version)
        original.rawQuery("SELECT optionsJson FROM jobs", null).use { assertTrue(it.moveToFirst()); assertEquals(PRIVATE_JOB, it.getString(0)) }
      }
      val restored = PampaDatabase.build(f.context, f.target.absolutePath)
      try {
        assertEquals(" baseline ", restored.notes().get("n")!!.bundleBodyBase)
        assertEquals(true, restored.notes().get("n")!!.bundleNativeAdopted)
        assertNull(restored.sessions().get("s")!!.transcribingOn)
        assertNull(restored.sync().state())
        assertTrue(dump({ restored.openHelper.readableDatabase.query(it) }, "jobs").isEmpty())
        assertEquals(15, restored.openHelper.readableDatabase.version)
      } finally { restored.close() }
    }
  }

  companion object {
    private const val PRIVATE_JOB = "SYNTHETIC_PRIVATE_JOB_417"
    private const val PRIVATE_DEVICE = "SYNTHETIC_PRIVATE_DEVICE_418"
    private const val PRIVATE_OWNER = "SYNTHETIC_PRIVATE_OWNER_419"
    private const val PRIVATE_PENDING = "SYNTHETIC_PRIVATE_PENDING_RELAY_420"
    private const val PRIVATE_WORK = "SYNTHETIC_PRIVATE_WORK_DEVICE_422"
    private const val PRIVATE_STALE_SEARCH = "SYNTHETIC_PRIVATE_STALE_SEARCH_424"
    private const val EXACT_SOURCE = " \r\n{ \"future\":{\"opaque\":[null,\"\",1]}, \"text\":\"Keep  spaces\\nΩ\" }\t"
  }
}
