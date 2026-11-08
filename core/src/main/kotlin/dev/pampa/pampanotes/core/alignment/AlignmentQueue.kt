package dev.pampa.pampanotes.core.alignment

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable

@Serializable data class AlignmentPlacement(val id: String, val sha256: String, val position: Int, val durationMs: Long, val fileName: String)
@Serializable data class AlignmentSnapshot(
  val sessionId: String,
  val noteId: String,
  val folderId: String,
  val activeTranscriptId: String?,
  val selectedTranscriptId: String,
  val selectedTextSha256: String,
  val rawLanguage: String?,
  val accountKey: String,
  val settingsKey: String,
  val segmentSha256: String,
  val placement: List<AlignmentPlacement>,
  val request: AlignmentRequest,
) {
  val key: String get() = AlignmentProtocol.sha256(AlignmentProtocol.json.encodeToString(serializer(), this))
}
@Serializable data class AlignmentQueueEntry(
  val snapshot: AlignmentSnapshot,
  val attempts: Int = 0,
  val nextAt: Long = 0,
  val state: String = "queued",
  val reason: String? = null,
  /** Persisted before POST; restart cancels it before allocating a fresh id. No bearer on disk. */
  val remoteJobId: String? = null,
  val remoteBase: String? = null,
  val remoteInstance: String? = null,
)
@Serializable private data class QueueFile(val version: Int = 1, val entries: List<AlignmentQueueEntry> = emptyList())

/** Installation-private, atomic, bounded snapshot ledger. Construct ONLY under noBackupFilesDir. */
class DurableAlignmentQueue(private val directory: File) {
  private val file = File(directory, "queue-v1.json")
  private val lock = locks.getOrPut(directory.absolutePath) { Any() }

  fun entries(): List<AlignmentQueueEntry> = synchronized(lock) { read().entries }
  fun get(key: String) = entries().find { it.snapshot.key == key }
  fun enqueue(snapshot: AlignmentSnapshot): Boolean = synchronized(lock) {
    val saved = read().entries
    val existing = saved.find { it.snapshot.key == snapshot.key }
    if (existing?.state == "stale") {
      write(saved.map { if (it.snapshot.key == snapshot.key) AlignmentQueueEntry(snapshot) else it })
      return@synchronized true
    }
    val rows = if (saved.size >= MAX_ENTRIES) saved.filterNot { it.state in setOf("done", "stale") } else saved
    if (rows.any { it.snapshot.key == snapshot.key } || rows.size >= MAX_ENTRIES) return@synchronized false
    write(rows + AlignmentQueueEntry(snapshot)); true
  }
  fun update(key: String, transform: (AlignmentQueueEntry) -> AlignmentQueueEntry) = synchronized(lock) {
    // Completion and error handlers can arrive after cancellation. Decide under the same lock
    // as cancelSession/cancelAll, so a late result cannot resurrect the durable tombstone.
    write(read().entries.map { if (it.snapshot.key == key && it.state != "cancelled") transform(it) else it })
  }
  fun cancelSession(sessionId: String) = synchronized(lock) {
    // Tombstones prevent auto-discovery from undoing a user's cancellation.
    write(read().entries.map { if (it.snapshot.sessionId == sessionId) it.copy(state = "cancelled", nextAt = Long.MAX_VALUE) else it })
  }
  fun cancelAll() = synchronized(lock) { write(read().entries.map { it.copy(state = "cancelled", nextAt = Long.MAX_VALUE) }) }
  fun wake() = synchronized(lock) {
    write(read().entries.map { if (it.state == "deferred") it.copy(state = "queued", attempts = 0, nextAt = 0) else it })
  }
  fun wakeEndpoint(instance: String) = synchronized(lock) {
    write(read().entries.map { entry ->
      if (entry.state == "deferred" && (entry.reason in setOf("offline", "network", "timeout", "server", "unconfigured", "capability_absent", "audio_missing") ||
          entry.reason == "missing_evidence" && entry.remoteInstance != instance)) entry.copy(state = "queued", attempts = 0, nextAt = 0)
      else entry
    })
  }
  fun due(now: Long) = entries().firstOrNull { it.state in setOf("queued", "deferred") && it.nextAt <= now }
  fun nextAt(): Long? = entries().filter { it.state in setOf("queued", "deferred") && it.nextAt < Long.MAX_VALUE }.minOfOrNull { it.nextAt }
  fun defer(key: String, reason: String, now: Long, retryable: Boolean = true, retryAfterMs: Long = 0) = update(key) { entry ->
    val tries = entry.attempts + 1
    val delay = maxOf(retryAfterMs, (60_000L shl (tries - 1).coerceIn(0, 8))).coerceAtMost(24 * 60 * 60_000L)
    entry.copy(state = "deferred", attempts = tries, reason = reason,
      nextAt = if (retryable && tries < MAX_ATTEMPTS) Math.addExact(now, delay) else Long.MAX_VALUE)
  }
  private fun read(): QueueFile {
    if (!file.exists()) return QueueFile()
    require(file.length() <= MAX_BYTES) { "Alignment queue exceeds limit" }
    return AlignmentProtocol.json.decodeFromString(QueueFile.serializer(), file.readText(Charsets.UTF_8)).also {
      require(it.version == 1 && it.entries.size <= MAX_ENTRIES)
      require(it.entries.map { row -> row.snapshot.key }.distinct().size == it.entries.size)
      require(it.entries.all { row -> row.attempts in 0..100_000 && row.state in setOf("queued", "deferred", "done", "stale", "cancelled") })
    }
  }
  private fun write(entries: List<AlignmentQueueEntry>) {
    require(entries.size <= MAX_ENTRIES)
    check(directory.isDirectory || directory.mkdirs())
    val bytes = AlignmentProtocol.json.encodeToString(QueueFile.serializer(), QueueFile(entries = entries)).toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_BYTES)
    val temp = File(directory, "queue-v1.tmp")
    FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }
  companion object {
    const val MAX_ENTRIES = 256
    const val MAX_ATTEMPTS = 6
    const val MAX_BYTES = 64L * 1024 * 1024
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
  }
}
