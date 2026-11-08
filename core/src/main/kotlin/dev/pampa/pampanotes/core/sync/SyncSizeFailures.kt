package dev.pampa.pampanotes.core.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Local durable retry suppression, separate from sync_meta (which is exclusively the server base).
 * Outbox entries remain dirty, protecting local-only changes from pull/rebaseline deletion.
 * The file lives beside the database, outside the database-only backup; it holds only row keys and
 * content hashes. A new account/server uses a separate file. No text or credentials are written.
 */
class SyncSizeFailures(private val file: File) {
  private val hashes = if (file.exists()) {
    // A damaged ledger is an explicit failure, never permission to silently discard the outbox.
    val value = SyncCodec.json.parseToJsonElement(file.readText()) as JsonObject
    value.mapValues { (_, hash) -> (hash as JsonPrimitive).content }.toMutableMap()
  } else mutableMapOf()

  fun blocked(table: String, id: String, hash: String): Boolean = hashes[key(table, id)] == hash
  val recovered: Boolean get() = hashes[RECOVERY_KEY] == "1"

  fun finishRecovery() {
    if (!recovered) { hashes[RECOVERY_KEY] = "1"; save() }
  }

  fun record(table: String, id: String, hash: String) {
    if (hashes.put(key(table, id), hash) != hash) save()
  }

  fun remove(table: String, id: String) {
    if (hashes.remove(key(table, id)) != null) save()
  }

  fun retain(keys: Set<String>) {
    if (hashes.keys.retainAll(keys + RECOVERY_KEY)) save()
  }

  private fun save() {
    val pending = File(file.path + ".tmp")
    pending.outputStream().use { stream ->
      stream.write(JsonObject(hashes.mapValues { JsonPrimitive(it.value) }).toString().toByteArray(Charsets.UTF_8))
      stream.fd.sync()
    }
    Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  companion object {
    private const val RECOVERY_KEY = "@cloud-source-wire-recovery/1"
    // Mirrored read-only from worker/src/sync.ts. A single segment may exceed the 1.5 MB
    // chunk target but must fit the 1.9 MB row guard, including brackets and the size margin.
    const val MAX_ROW_BYTES = 1_900_000
    const val ERROR_PREFIX = "sync_row_too_large:"
    fun key(table: String, id: String) = "$table/$id"
    fun error(count: Int): String = ERROR_PREFIX + count
    fun count(error: String?): Int = error?.takeIf { it.startsWith(ERROR_PREFIX) }
      ?.removePrefix(ERROR_PREFIX)?.toIntOrNull()?.takeIf { it > 0 } ?: 0

    /** Same independent payload / single segment guards as the production Worker. */
    fun exceedsLimits(change: WireChange): Boolean =
      (change.payload?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0) > MAX_ROW_BYTES ||
        change.segments.orEmpty().any {
          SyncCodec.json.encodeToString(dev.pampa.pampanotes.core.db.SegmentEntity.serializer(), it)
            .toByteArray(Charsets.UTF_8).size + 3 > MAX_ROW_BYTES
        }
  }
}
