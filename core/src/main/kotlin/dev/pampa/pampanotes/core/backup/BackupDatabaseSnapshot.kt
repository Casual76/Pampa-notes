package dev.pampa.pampanotes.core.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteStatement
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.repo.DatabaseTransactions
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal data class BackupDatabaseInfo(val version: Int, val counts: BackupCounts)

/** Builds a new SQLite file from logical rows, never from live database/WAL pages.
 *
 * Capture reads through the singleton Room connection inside its real writer transaction. Ordinary
 * DAO writers and explicit transactions therefore cannot split the graph across two revisions.
 * No checkpoint, live DELETE/UPDATE, raw file copy, ATTACH or best-effort fallback is involved.
 *
 * Private tables keep their schema but none of their rows are even read. Search is rebuilt from
 * content, so old FTS pages cannot carry deleted content. Compaction removes the cleared transient
 * session markers too. Schema SQL, Room identity, indexes, FKs and user_version come from the source;
 * an old restored database still goes through its usual Room migrations on the next app start.
 */
internal class BackupDatabaseSnapshot(
  private val database: PampaDatabase,
  private val transactions: DatabaseTransactions,
) {
  /** [afterTable] is an internal suspension seam for real-writer/cancellation regressions. */
  suspend fun capture(destination: File, afterTable: suspend (String) -> Unit = {}): BackupDatabaseInfo {
    try {
      return transactions.run {
        val job = coroutineContext
        job.ensureActive()
        val source = database.openHelper.writableDatabase
        check(source.inTransaction()) { "Backup capture requires Room writer exclusion" }
        rebuild(destination, source.version, { source.query(it) }, { job.ensureActive() }, afterTable)
      }
    } catch (failure: Throwable) {
      delete(destination)
      throw failure
    }
  }

  /** Sanitize even pre-fix backups, before closing or replacing the live database. */
  suspend fun prepareRestore(sourceFile: File, destination: File): BackupDatabaseInfo {
    val job = coroutineContext
    try {
      job.ensureActive()
      SQLiteDatabase.openDatabase(
        sourceFile.absolutePath, null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
      ).use { source ->
        source.beginTransactionNonExclusive()
        try {
          return rebuild(destination, source.version, { source.rawQuery(it, null) }, { job.ensureActive() })
        } finally {
          // Read-only work: no commit or migration of the extracted source, either.
          source.endTransaction()
        }
      }
    } catch (failure: Throwable) {
      delete(destination)
      throw failure
    }
  }

  private data class SchemaEntry(val type: String, val name: String, val table: String, val sql: String)

  private suspend fun rebuild(
    destination: File,
    version: Int,
    query: (String) -> Cursor,
    checkCancelled: () -> Unit,
    afterTable: suspend (String) -> Unit = {},
  ): BackupDatabaseInfo {
    checkCancelled()
    check(!destination.exists()) { "Backup snapshot must be a fresh file" }
    val schema = buildList {
      query("SELECT type, name, tbl_name, sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY rowid").use { cursor ->
        while (cursor.moveToNext()) add(SchemaEntry(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)))
      }
    }
    val tables = schema.filter { it.type == "table" && !it.name.startsWith("sqlite_") && !BackupContentSql.isSearchShadow(it.name) }
    val names = tables.map { it.name }.toSet()
    val supported = BackupContentSql.contentTables + BackupContentSql.privateTables + BackupContentSql.metadataTables + BackupContentSql.searchTables
    check(names.all { it in supported }) { "Unsupported backup tables: ${names - supported}" }
    check("room_master_table" in names) { "Missing Room identity" }

    destination.parentFile?.mkdirs()
    try {
      SQLiteDatabase.openDatabase(
        destination.absolutePath, null,
        SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
      ).use { copy ->
        // This file is standalone, without a WAL that an archive could accidentally omit.
        copy.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
          check(cursor.moveToFirst() && cursor.getString(0).equals("delete", ignoreCase = true))
        }
        copy.execSQL("PRAGMA foreign_keys=OFF")
        copy.beginTransaction()
        try {
          tables.forEach { table -> checkCancelled(); copy.execSQL(table.sql) }
          tables.filter { it.name in BackupContentSql.contentTables || it.name in BackupContentSql.metadataTables }.forEach { table ->
            copyRows(table.name, query, copy, checkCancelled)
            afterTable(table.name)
            checkCancelled()
          }
          // These columns did not exist before schema 8. Do not change an old backup's version.
          if ("sessions" in names) {
            val columns = columns("sessions", query)
            if ("transcribingOn" in columns && "transcribingSince" in columns) copy.execSQL(BackupContentSql.CLEAR_SESSION_WORK)
          }
          if ("notes_fts" in names) copy.execSQL(BackupContentSql.REBUILD_NOTES_SEARCH)
          if ("transcripts_fts" in names) copy.execSQL(BackupContentSql.REBUILD_TRANSCRIPTS_SEARCH)
          // Preserve public AUTOINCREMENT high-water marks, never the private outbox's journal.
          if (schema.any { it.name == "sqlite_sequence" }) {
            query("SELECT name, seq FROM sqlite_sequence").use { cursor ->
              while (cursor.moveToNext()) if (cursor.getString(0) in BackupContentSql.contentTables) {
                copy.execSQL("DELETE FROM sqlite_sequence WHERE name = ?", arrayOf(cursor.getString(0)))
                copy.execSQL("INSERT INTO sqlite_sequence (name, seq) VALUES (?, ?)", arrayOf(cursor.getString(0), cursor.getLong(1)))
              }
            }
          }
          // No sync/search triggers run while loading or clearing rows in the new file.
          schema.filter { it.type == "index" || it.type == "trigger" || it.type == "view" }.forEach { entry ->
            checkCancelled()
            copy.execSQL(entry.sql)
          }
          copy.version = version
          checkCancelled()
          copy.setTransactionSuccessful()
        } finally {
          copy.endTransaction()
        }
        checkCancelled()
        // A logical UPDATE alone can leave recoverable marker/device bytes in free page space.
        copy.execSQL(BackupContentSql.COMPACT)
        checkCancelled()
        copy.rawQuery("PRAGMA integrity_check", null).use { cursor ->
          check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) { "Invalid backup database" }
        }
        copy.rawQuery("PRAGMA foreign_key_check", null).use { cursor -> check(!cursor.moveToFirst()) { "Broken backup graph" } }
        fun count(table: String): Int = if (table !in names) 0 else
          copy.rawQuery("SELECT COUNT(*) FROM ${BackupContentSql.quote(table)}", null).use { it.moveToFirst(); it.getInt(0) }
        return BackupDatabaseInfo(version, BackupCounts(count("folders"), count("notes"), count("sessions"), count("audio_parts"), count("transcripts"), count("sources")))
      }
    } catch (failure: Throwable) {
      delete(destination)
      throw failure
    }
  }

  private fun columns(table: String, query: (String) -> Cursor): List<String> = buildList {
    query("PRAGMA table_info(${BackupContentSql.quote(table)})").use { cursor ->
      while (cursor.moveToNext()) add(cursor.getString(1))
    }
  }

  private fun copyRows(table: String, query: (String) -> Cursor, destination: SQLiteDatabase, checkCancelled: () -> Unit) {
    val fields = columns(table, query)
    val quotedTable = BackupContentSql.quote(table)
    val fieldSql = fields.joinToString(",") { BackupContentSql.quote(it) }
    val bindings = fields.joinToString(",") { "?" }
    destination.compileStatement("INSERT INTO $quotedTable ($fieldSql) VALUES ($bindings)").use { insert ->
      var lastRow: Long? = null
      // Older Android versions do not consistently throw SQLiteBlobTooBigException for a full
      // CursorWindow. Detect large rows in SQLite first, returning only a single small integer.
      val rowBytes = fields.joinToString(" + ") { "COALESCE(length(CAST(${BackupContentSql.quote(it)} AS BLOB)), 0)" }
      var windows = query("SELECT COALESCE(MAX($rowBytes), 0) FROM $quotedTable").use { it.moveToFirst(); it.getLong(0) > MAX_CURSOR_ROW_BYTES }
      if (!windows) {
        try {
          query("SELECT rowid, * FROM $quotedTable ORDER BY rowid").use { cursor ->
            while (cursor.moveToNext()) {
              checkCancelled()
              insert.clearBindings()
              for (index in fields.indices) bind(insert, index + 1, cursor, index + 1)
              insert.executeInsert()
              lastRow = cursor.getLong(0)
            }
          }
        } catch (_: SQLiteBlobTooBigException) {
          windows = true
        }
      }
      if (windows) {
        // A transcript's exact public source document can exceed Android's CursorWindow.
        // Resume after already copied rows, reading remaining values in bounded byte windows.
        // CAST to BLOB preserves embedded NULs, which length/substr on TEXT would truncate.
        val encoding = query("PRAGMA encoding").use { it.moveToFirst(); Charset.forName(it.getString(0)) }
        val remaining = lastRow?.let { " WHERE rowid > $it" }.orEmpty()
        query("SELECT rowid FROM $quotedTable$remaining ORDER BY rowid").use { cursor ->
          while (cursor.moveToNext()) {
            checkCancelled()
            val row = cursor.getLong(0)
            insert.clearBindings()
            fields.forEachIndexed { index, field ->
              val quotedField = BackupContentSql.quote(field)
              val where = "FROM $quotedTable WHERE rowid = $row"
              query("SELECT typeof($quotedField), length(CAST($quotedField AS BLOB)) $where").use { value ->
                check(value.moveToFirst())
                when (val type = value.getString(0)) {
                  "null" -> insert.bindNull(index + 1)
                  "integer", "real" -> query("SELECT $quotedField $where").use { number ->
                    check(number.moveToFirst()); bind(insert, index + 1, number, 0)
                  }
                  "text", "blob" -> {
                    val length = value.getInt(1)
                    val bytes = ByteArrayOutputStream(minOf(length, VALUE_CHUNK_BYTES))
                    var offset = 1L
                    while (offset <= length) {
                      checkCancelled()
                      query("SELECT substr(CAST($quotedField AS BLOB), $offset, $VALUE_CHUNK_BYTES) $where").use { chunk ->
                        check(chunk.moveToFirst())
                        val block = chunk.getBlob(0)
                        check(block.isNotEmpty())
                        bytes.write(block)
                        offset += block.size
                      }
                    }
                    if (type == "text") insert.bindString(index + 1, bytes.toString(encoding.name()))
                    else insert.bindBlob(index + 1, bytes.toByteArray())
                  }
                  else -> error("Unsupported SQLite value")
                }
              }
            }
            insert.executeInsert()
          }
        }
      }
    }
  }

  private fun bind(insert: SQLiteStatement, position: Int, cursor: Cursor, index: Int) {
    when (cursor.getType(index)) {
      Cursor.FIELD_TYPE_NULL -> insert.bindNull(position)
      Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(position, cursor.getLong(index))
      Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(position, cursor.getDouble(index))
      Cursor.FIELD_TYPE_STRING -> insert.bindString(position, cursor.getString(index))
      Cursor.FIELD_TYPE_BLOB -> insert.bindBlob(position, cursor.getBlob(index))
      else -> error("Unsupported SQLite value")
    }
  }

  companion object {
    private const val VALUE_CHUNK_BYTES = 64 * 1024
    private const val MAX_CURSOR_ROW_BYTES = 256 * 1024

    /** Only this owned snapshot and its SQLite sidecars; never the live database. */
    fun delete(file: File) {
      listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
    }
  }
}
