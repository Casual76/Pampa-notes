package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SyncDao
import dev.pampa.pampanotes.core.db.SyncMetaEntity
import dev.pampa.pampanotes.core.files.AppFileWriteProtection
import dev.pampa.pampanotes.core.importing.PampaBundleSourceFiles
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** Local attachment names are references to this device's bytes, never portable identity. */
internal object SyncLocalFiles {
  /**
   * One page's file lifetime. Create before its Room transaction; close in NonCancellable finally
   * AFTER the committed-reference cleanup attempt, including rollback/cancellation/query failures.
   * Uses the same release generations as PN staging, so precommit sweep snapshots stay safe.
   */
  class WriteOwner internal constructor() : AutoCloseable {
    private val token = Any()
    private var closed = false

    @Synchronized
    internal fun requireOpen() = check(!closed) { "Sync file write owner is closed" }

    @Synchronized
    internal fun hold(file: File) {
      requireOpen()
      AppFileWriteProtection.hold(file, token)
    }

    @Synchronized
    override fun close() {
      if (closed) return
      AppFileWriteProtection.release(token)
      closed = true
    }
  }

  fun beginWrites(): WriteOwner = WriteOwner()

  /**
   * Null references alone never waive a missing original. PN omitted attachments have an expected
   * missing-* reference; binary, archived and derived sources must remain eligible for recovery.
   * Check both versions so a formerly file-backed local source cannot be mistaken for pasted text.
   */
  fun preserveFilelessSource(local: SourceEntity?, incoming: SourceEntity): Boolean =
    incoming.storedFileName == null && local?.storedFileName == null &&
      PampaBundleSourceFiles.isFileless(incoming) && (local == null || PampaBundleSourceFiles.isFileless(local))

  private fun nameKey(table: String): String? = when (table) {
    "audio_parts" -> "fileName"
    "sources" -> "storedFileName"
    else -> null
  }

  /**
   * Compatibility helper for callers without a page transaction. Its temporary owner always
   * closes, including failed/cancelled copies; transactional callers must pass their page owner.
   */
  fun selectName(
    root: File, remoteName: String?, size: Long, sha: String, ownName: String?,
    otherNames: Collection<String>, candidates: Sequence<String?>, created: MutableList<File>,
    checkpoint: () -> Unit = {},
  ): String? = beginWrites().use { owner ->
    selectName(root, remoteName, size, sha, ownName, otherNames, candidates, created, owner, checkpoint = checkpoint)
  }

  /**
   * Retain a verified, exclusively owned reference. Bytes borrowed from another entity always
   * get a fresh file: ordinary part/source deletion is allowed to unlink its file unconditionally.
   * Even a missing remote name must not collide with another row's future recovery destination.
   * [preserveFileless] is only for sources positively classified by [preserveFilelessSource].
   */
  fun selectName(
    root: File, remoteName: String?, size: Long, sha: String, ownName: String?,
    otherNames: Collection<String>, candidates: Sequence<String?>, created: MutableList<File>,
    owner: WriteOwner,
    preserveFileless: Boolean = false,
    checkpoint: () -> Unit = {},
  ): String? {
    owner.requireOpen()
    if (preserveFileless && ownName == null && remoteName == null) return null
    val canonicalRoot = root.canonicalFile
    val occupied = otherNames.map { File(root, it).canonicalFile }.filter { it.parentFile == canonicalRoot }.toSet()
    fun reserved(name: String): Boolean {
      val file = safeFile(root, name)?.canonicalFile ?: return true
      return file in occupied
    }
    val direct = sequenceOf(ownName, remoteName).filterNotNull().filterNot(::reserved)
    verifiedName(root, size, sha, direct, checkpoint)?.let { return it }
    val donor = verifiedName(root, size, sha, sequenceOf(ownName, remoteName) + candidates, checkpoint)
    if (donor != null) {
      val target = freshFile(root, ::reserved, create = true, owner = owner)
      created += target // Record ownership before a cancellation checkpoint or any write.
      safeFile(root, donor)!!.inputStream().use { input ->
        target.outputStream().use { output ->
          requireDigest(input, output, size, sha, checkpoint)
        }
      }
      return target.name
    }
    if (remoteName == null) return null
    safeFile(root, remoteName)?.let { if (!reserved(remoteName) && !it.exists()) return remoteName }
    // Do not overwrite or expose an unrelated existing file, including through a traversal or
    // symlink. This is deliberately a missing reference; sync never downloads or moves bytes.
    return freshFile(root, ::reserved, create = false).name
  }

  private fun freshFile(root: File, reserved: (String) -> Boolean, create: Boolean, owner: WriteOwner? = null): File {
    while (true) {
      val name = "sync-${UUID.randomUUID()}.bin"
      val file = safeFile(root, name) ?: throw IOException("Cannot resolve local sync attachment directory")
      if (reserved(name) || file.exists()) continue
      if (!create) return file
      // Registration must precede creation, not just the first copy checkpoint. Keep the hold
      // until the page's committed-reference cleanup, however long copying/Room takes.
      requireNotNull(owner).hold(file)
      if (file.createNewFile()) return file
    }
  }

  private fun verifiedName(root: File, size: Long, sha: String, names: Sequence<String?>, checkpoint: () -> Unit = {}): String? =
    names.filterNotNull().distinct().firstOrNull { verified(root, it, size, sha, checkpoint) }

  private fun safeFile(root: File, name: String): File? {
    if (name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == ':' || it.code < 32 }) return null
    return runCatching {
      File(root, name).takeIf { !Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == root.canonicalFile }
    }.getOrNull()
  }

  private fun verified(root: File, name: String, size: Long, sha: String, checkpoint: () -> Unit): Boolean {
    if (size < 0 || sha.length != 64 || sha.any { it !in "0123456789abcdefABCDEF" }) return false
    val file = safeFile(root, name) ?: return false
    return try {
      if (!file.isFile || file.length() != size) false else {
        file.inputStream().use { requireDigest(it, null, size, sha, checkpoint) }
        file.length() == size
      }
    } catch (_: IOException) { false } catch (_: SecurityException) { false }
  }

  private fun requireDigest(input: java.io.InputStream, output: OutputStream?, size: Long, sha: String, checkpoint: () -> Unit) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    while (true) {
      checkpoint()
      val read = input.read(buffer)
      if (read < 0) break
      count += read
      if (count > size) throw IOException("Sync attachment length changed")
      digest.update(buffer, 0, read)
      output?.write(buffer, 0, read)
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it) }
    if (count != size || !hash.equals(sha, true)) throw IOException("Sync attachment content changed")
    checkpoint()
  }

  /** Call under a Room transaction with canonical current references, even after cancellation. */
  fun cleanupCreated(created: List<File>, referenced: Set<File>) {
    created.filterNot { it.canonicalFile in referenced }.forEach { runCatching { it.delete() } }
  }

  /*
   * Keep the server's filename for encoding, independently of the local file reference. Private
   * entries in the existing sync journal need no schema/wire change, participate in the same
   * transaction as the row and its agreed hash, survive restart, and clear with account re-seeding.
   * They have no origin or outbox entry and are never part of a portable graph or a sync push.
   *
   * Merely suppressing the immediate echo is insufficient: a later touch would compare the local
   * filename's hash with the remote hash and push it back. Projecting only this one field keeps
   * payload and hash consistent, even on a later real metadata edit, with unmodified protocol 1.
   */
  private fun journalTable(table: String) = "local_attachment_name:$table"

  suspend fun remember(sync: SyncDao, change: WireChange, localPayload: JsonElement?) {
    val key = nameKey(change.tbl) ?: return
    val remote = change.payload as? JsonObject
    val local = localPayload as? JsonObject
    if (change.isDelete || remote == null || local == null || local[key] == remote[key] || !sameContent(local, remote)) {
      sync.deleteMeta(journalTable(change.tbl), change.id)
      return
    }
    val state = buildJsonObject {
      put("name", remote[key] ?: JsonNull)
      put("sha256", remote.getValue("sha256"))
      put("sizeBytes", remote.getValue("sizeBytes"))
    }
    sync.upsertMeta(SyncMetaEntity(journalTable(change.tbl), change.id, change.seq, state.toString(), change.updatedAt))
  }

  suspend fun forget(sync: SyncDao, table: String, id: String) {
    if (nameKey(table) != null) sync.deleteMeta(journalTable(table), id)
  }

  /** Real edits still hash normally; a changed content hash/size invalidates the old projection. */
  suspend fun project(sync: SyncDao?, table: String, id: String, encoded: Encoded): Encoded {
    val key = nameKey(table) ?: return encoded
    if (sync == null) return encoded
    if (sync.meta(table, id) == null) return encoded
    val saved = sync.meta(journalTable(table), id) ?: return encoded
    val state = runCatching { SyncCodec.json.parseToJsonElement(saved.hash) as? JsonObject }.getOrNull() ?: return encoded
    val local = encoded.payload as? JsonObject ?: return encoded
    if (!sameContent(local, state)) return encoded
    val name = state["name"] ?: return encoded
    // A successful push advances sync_meta but keeps the projected wire name. Do not bind this
    // mapping to a sequence/hash: otherwise the first touch after that push would publish the
    // local path again. Every remote apply/rebase updates both entries in one Room transaction.
    val payload = JsonObject(local + (key to name))
    return encoded.copy(payload = payload, hash = SyncCodec.hash(payload))
  }

  private fun sameContent(a: JsonObject, b: JsonObject): Boolean =
    a["sha256"] != null && a["sha256"] == b["sha256"] && a["sizeBytes"] != null && a["sizeBytes"] == b["sizeBytes"]
}
