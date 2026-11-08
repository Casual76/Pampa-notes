package dev.pampa.pampanotes.core.backup

/** Backup policy, shared by capture and legacy restore. No typed JSON/provenance rewriting.
 *
 * Keep the graph, histories, import merge baselines and completed statistics verbatim. Only
 * installation work/identity journals and the session's transient work marker are private here.
 * Unknown tables fail closed instead of silently losing future content or exporting future secrets.
 * The SQL literals are also executed by the offline production-schema regression.
 */
internal object BackupContentSql {
  val contentTables = setOf(
    "folders", "notes", "note_tags", "sessions", "audio_parts", "transcripts", "segments",
    "sources", "export_presets", "transcription_runs",
  )
  val privateTables = setOf("jobs", "sync_outbox", "sync_state", "sync_guard", "sync_meta", "sync_origin")
  val metadataTables = setOf("room_master_table", "android_metadata")
  val searchTables = setOf("notes_fts", "transcripts_fts")
  val searchShadowSuffixes = setOf("_content", "_segments", "_segdir", "_docsize", "_stat")

  const val CLEAR_SESSION_WORK = "UPDATE sessions SET transcribingOn = NULL, transcribingSince = NULL"
  const val REBUILD_NOTES_SEARCH = "INSERT INTO notes_fts (noteId, title, body) SELECT id, title, body FROM notes"
  const val REBUILD_TRANSCRIPTS_SEARCH = "INSERT INTO transcripts_fts (transcriptId, sessionId, noteId, text) SELECT t.id, t.sessionId, s.noteId, t.text FROM transcripts t JOIN sessions s ON s.id = t.sessionId"
  // Plain VACUUM works on API 26 SQLite too; VACUUM INTO does not.
  const val COMPACT = "VACUUM"

  fun isSearchShadow(name: String): Boolean =
    searchTables.any { table -> searchShadowSuffixes.any { suffix -> name == table + suffix } }

  fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
}
