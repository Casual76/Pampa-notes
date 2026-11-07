package dev.pampa.pampanotes.core.relay

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire limits, also used to bound disk journals, responses and working memory. */
object RelayLimits {
  const val CHUNK_BYTES = 8 * 1024 * 1024
  const val MAX_FILE_BYTES = 256L * 1024 * 1024
  const val TTL_MS = 600_000L
  const val BEGIN_FRESHNESS_MS = 180_000L
  const val MAX_JOBS = 64
  const val MAX_ATTEMPTS = 6
  internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  internal val id = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
  internal val hash = Regex("[0-9a-f]{64}")
}

@Serializable
enum class RelayKind(val wire: String) { AUDIO("audio"), SOURCE("source") }

/** This identity is read from an existing Room row; never constructed from remote filenames. */
@Serializable
data class RelayLocalFile(
  val kind: RelayKind,
  val entityId: String,
  val noteId: String,
  val fileName: String,
  val originalName: String,
  val mime: String,
  val sha256: String,
  val bytes: Long,
  /** Includes parent, creation/import identity and row contents; checked again before placement. */
  val revision: String,
)

/** Token only exists in memory. toString deliberately excludes credentials. */
class RelaySession(
  val baseUrl: String,
  val ownerId: String,
  internal val token: String,
  val deviceId: String,
  val deviceName: String,
) {
  val accountKey: String = relayHash("$baseUrl\n$ownerId".toByteArray())
  override fun toString(): String = "RelaySession(redacted)"
}

interface RelayAccounts {
  /** Must validate the session's server owner against the database's current sync owner. */
  suspend fun snapshot(): RelaySession
  suspend fun isCurrent(session: RelaySession): Boolean
}

interface RelayLocalFiles {
  suspend fun list(noteId: String? = null): List<RelayLocalFile>
  suspend fun get(kind: RelayKind, entityId: String): RelayLocalFile?
  fun path(file: RelayLocalFile): File
  /** Serialize the reread + placement with Room writes; do not create rows or archive markers. */
  suspend fun placeIfCurrent(expected: RelayLocalFile, place: suspend () -> Unit): Boolean
  fun hold(file: RelayLocalFile) {}
  fun release(file: RelayLocalFile) {}
}

interface RelayClock {
  fun elapsedMillis(): Long
  fun wallMillis(): Long
}

/** Machine states: UI owns translated labels; no server diagnostic/token is surfaced. */
enum class RelayState {
  QUEUED, UPLOADING, READY, DOWNLOADING, RECEIVED, ALREADY_LOCAL, RETRY,
  EXPIRED, QUOTA, UNSUPPORTED, UNMATCHED, ACCOUNT_CHANGED, ROW_CHANGED,
  SOURCE_CHANGED, INTEGRITY_ERROR, AUTH_REQUIRED, FAILED, QUEUE_FULL,
}

data class RelayProgress(
  val jobId: String,
  val kind: RelayKind,
  val entityId: String,
  val state: RelayState,
  val bytes: Long,
  val totalBytes: Long,
  val expiresAt: Long? = null,
)

data class RelayResult(
  val state: RelayState,
  val jobId: String? = null,
  val kind: RelayKind? = null,
  val entityId: String? = null,
  val transferId: String? = null,
  val bytes: Long = 0,
  val totalBytes: Long = 0,
  val expiresAt: Long? = null,
  val retryAfterMillis: Long? = null,
  val code: String? = null,
  val file: File? = null,
  /** Server-anchored monotonic remainder when read from the journal; UI expiry refresh only. */
  val remainingMillis: Long? = null,
)

data class RelayReport(val results: List<RelayResult> = emptyList()) {
  val needsRetry: Boolean get() = results.any { it.state == RelayState.RETRY }
  val retryAfterMillis: Long? get() = results.mapNotNull { it.retryAfterMillis }.minOrNull()
}

data class RelayAvailability(val ready: List<RelayTransfer>, val result: RelayResult? = null)

@Serializable
data class RelayChunk(val index: Int, val bytes: Int, val sha256: String)

@Serializable
data class RelayTransfer(
  val transferId: String,
  val requestId: String,
  val kind: String,
  val entityId: String,
  val sha256: String,
  val bytes: Long,
  val mime: String,
  val originalName: String,
  val deviceId: String,
  val deviceName: String,
  val state: String,
  val createdAt: Long,
  val expiresAt: Long,
  val serverTime: Long,
  val chunkBytes: Int,
  val chunkCount: Int,
  val chunks: List<RelayChunk> = emptyList(),
) {
  internal fun validate(manifest: Boolean = false): RelayTransfer {
    relayRequire(RelayLimits.id.matches(transferId) && RelayLimits.id.matches(requestId), "invalid_manifest")
    relayRequire(kind == "audio" || kind == "source", "invalid_manifest")
    relayRequire(RelayLimits.hash.matches(sha256) && bytes in 1..RelayLimits.MAX_FILE_BYTES, "invalid_manifest")
    relayRequire(chunkBytes == RelayLimits.CHUNK_BYTES &&
      chunkCount == ((bytes + chunkBytes - 1) / chunkBytes).toInt(), "invalid_manifest")
    relayRequire(expiresAt - createdAt == RelayLimits.TTL_MS && serverTime > 0, "invalid_manifest")
    relayRequire(state == "ready" || state == "uploading", "invalid_manifest")
    relayRequire(chunks.size <= chunkCount && chunks.map { it.index }.distinct().size == chunks.size, "invalid_manifest")
    chunks.forEach {
      relayRequire(it.index in 0 until chunkCount && it.bytes == chunkLength(it.index) &&
        RelayLimits.hash.matches(it.sha256), "invalid_manifest")
    }
    if (manifest && state == "ready") relayRequire(chunks.size == chunkCount, "incomplete_manifest")
    if (serverTime >= expiresAt) throw RelayFailure(RelayState.EXPIRED, "expired")
    return this
  }

  internal fun chunkLength(index: Int): Int = minOf(chunkBytes.toLong(), bytes - index.toLong() * chunkBytes).toInt()
}

@Serializable
internal data class RelayBegin(
  val requestId: String,
  val requestedAt: Long,
  val kind: String,
  val entityId: String,
  val sha256: String,
  val bytes: Long,
  val mime: String,
  val originalName: String,
  val deviceId: String,
  val deviceName: String,
)

@Serializable
internal data class RelayDiscovery(val serverTime: Long, val transfers: List<RelayTransfer> = emptyList())

@Serializable
internal data class RelayAck(
  val index: Int, val bytes: Int, val sha256: String, val expiresAt: Long, val serverTime: Long,
)

class RelayFailure(val state: RelayState, val code: String) : IOException(code)

internal fun relayRequire(valid: Boolean, code: String) {
  if (!valid) throw RelayFailure(RelayState.INTEGRITY_ERROR, code)
}

internal fun relayHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).relayHex()
internal fun ByteArray.relayHex(): String = joinToString("") { "%02x".format(it) }
