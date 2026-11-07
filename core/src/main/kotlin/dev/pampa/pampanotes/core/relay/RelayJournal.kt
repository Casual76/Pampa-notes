package dev.pampa.pampanotes.core.relay

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable

@Serializable
internal data class RelayJob(
  val id: String,
  val accountKey: String,
  val upload: Boolean,
  val local: RelayLocalFile,
  /** Exact UTF-8 JSON persisted BEFORE the first POST. No credential is persisted. */
  val beginJson: String = "",
  val chunks: List<RelayChunk> = emptyList(),
  val transfer: RelayTransfer? = null,
  val deadline: Long,
  val serverTime: Long,
  val elapsedAt: Long,
  val touchedAt: Long,
  val wallAt: Long = touchedAt,
  val state: RelayState = RelayState.QUEUED,
  val attempts: Int = 0,
  val doneBytes: Long = 0,
  val code: String? = null,
) {
  val active: Boolean get() = state in setOf(RelayState.QUEUED, RelayState.RETRY, RelayState.UPLOADING, RelayState.DOWNLOADING)

  /** Monotonic across process death. A reboot/clock discontinuity fails closed until explicit resend. */
  fun remaining(clock: RelayClock): Long {
    val delta = clock.elapsedMillis() - elapsedAt
    val wallDelta = clock.wallMillis() - wallAt
    return if (delta < 0 || kotlin.math.abs(wallDelta - delta) > 60_000L) 0
      else (deadline - serverTime - delta).coerceAtLeast(0)
  }

  fun observed(transfer: RelayTransfer, clock: RelayClock): RelayJob = copy(
    transfer = transfer, deadline = transfer.expiresAt, serverTime = transfer.serverTime,
    elapsedAt = clock.elapsedMillis(), touchedAt = clock.wallMillis(), wallAt = clock.wallMillis(),
  )

  fun result(retryAfterMillis: Long? = null, file: File? = null) = RelayResult(
    state, id, local.kind, local.entityId, transfer?.transferId, doneBytes, local.bytes,
    transfer?.expiresAt, retryAfterMillis, code, file,
  )
}

/** Bounded private records; only this directory's generated UUID names are ever resolved/deleted. */
class RelayJournal(private val root: File) {
  init { check(root.mkdirs() || root.isDirectory) }

  internal fun all(): List<RelayJob> = root.listFiles().orEmpty()
    .filter { it.isFile && it.extension == "json" && UUID_NAME.matches(it.nameWithoutExtension) }
    .sortedByDescending { it.lastModified() }
    .take(RelayLimits.MAX_JOBS)
    .mapNotNull { file ->
      try {
        relayRequire(file.length() <= MAX_RECORD, "journal_too_large")
        val job = RelayLimits.json.decodeFromString<RelayJob>(file.readText())
        relayRequire(job.id == file.nameWithoutExtension && job.chunks.size <= 32 &&
          job.beginJson.toByteArray().size <= 4096 && job.accountKey.matches(RelayLimits.hash), "invalid_journal")
        job
      } catch (_: Exception) {
        // A damaged record must never be reconstructed into a fresh begin on a background pass.
        file.delete()
        stage(file.nameWithoutExtension).deleteRecursively()
        null
      }
    }

  internal fun save(job: RelayJob) {
    require(UUID_NAME.matches(job.id))
    val target = File(root, job.id + ".json")
    if (!target.exists()) {
      val records = all()
      if (records.size >= RelayLimits.MAX_JOBS) {
        val oldest = records.filterNot { it.active }.minByOrNull { it.touchedAt }
          ?: throw RelayFailure(RelayState.QUEUE_FULL, "queue_full")
        remove(oldest)
      }
    }
    val bytes = RelayLimits.json.encodeToString(RelayJob.serializer(), job).toByteArray()
    relayRequire(bytes.size <= MAX_RECORD, "journal_too_large")
    atomicWrite(target, bytes)
  }

  internal fun stage(id: String): File {
    require(UUID_NAME.matches(id))
    return File(root, id + ".stage")
  }

  internal fun clearStage(job: RelayJob) { stage(job.id).deleteRecursively() }
  internal fun stagingBytes(): Long = root.listFiles().orEmpty().filter { it.name.endsWith(".stage") }
    .sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
  internal fun remove(job: RelayJob) {
    clearStage(job)
    File(root, job.id + ".json").delete()
  }

  internal fun cleanup(clock: RelayClock, accountKey: String) {
    val jobs = all()
    jobs.forEach { job ->
      if (job.accountKey != accountKey) {
        // Old-account staging cannot be installed by the new account.
        clearStage(job)
      }
      if (job.remaining(clock) <= 0 && (job.active || job.state == RelayState.READY)) {
        save(job.copy(state = RelayState.EXPIRED, code = "expired", touchedAt = clock.wallMillis()))
        clearStage(job)
      } else if (!job.active) clearStage(job)
    }
    val stages = jobs.map { it.id + ".stage" }.toSet()
    root.listFiles().orEmpty().forEach {
      if ((it.name.endsWith(".stage") && it.name !in stages) || it.name.endsWith(".tmp")) {
        it.deleteRecursively()
      }
    }
  }

  companion object {
    private val UUID_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private const val MAX_RECORD = 32 * 1024L

    internal fun atomicMove(source: File, target: File) {
      // Failure stays private. Never copy a partial file to the visible destination.
      Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    internal fun atomicWrite(target: File, bytes: ByteArray) {
      val temporary = File(target.parentFile, target.name + ".tmp")
      FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
      atomicMove(temporary, target)
    }
  }
}
