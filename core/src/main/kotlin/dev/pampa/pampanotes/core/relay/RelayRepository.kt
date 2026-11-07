package dev.pampa.pampanotes.core.relay

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Temporary same-account transport. Background methods only resume saved requests; new begin
 * identities can only be created by an explicit queue/upload call. All file writes serialize here.
 * Every operation snapshots credentials once and refuses to continue if that snapshot stops being current.
 */
class RelayRepository(
  private val accounts: RelayAccounts,
  private val local: RelayLocalFiles,
  private val http: RelayHttp,
  private val journal: RelayJournal,
  private val clock: RelayClock,
) {
  private val mutex = Mutex()

  suspend fun queueNote(noteId: String, resend: Boolean = false): RelayReport = queue(noteId, resend)
  suspend fun queueAllLocalFiles(resend: Boolean = false): RelayReport = queue(null, resend)

  suspend fun uploadNote(
    noteId: String, resend: Boolean = false, onProgress: (RelayProgress) -> Unit = {},
  ): RelayReport {
    val queued = queueNote(noteId, resend)
    val ids = queued.results.mapNotNull { it.jobId }.toSet()
    val resumed = resumeUploads(ids, onProgress)
    return RelayReport(queued.results.filter { it.jobId !in resumed.results.map { r -> r.jobId } } + resumed.results)
  }

  suspend fun uploadAllLocalFiles(
    resend: Boolean = false, onProgress: (RelayProgress) -> Unit = {},
  ): RelayReport {
    val queued = queueAllLocalFiles(resend)
    val resumed = resumeUploads(queued.results.mapNotNull { it.jobId }.toSet(), onProgress)
    return RelayReport(queued.results.filter { it.jobId !in resumed.results.map { r -> r.jobId } } + resumed.results)
  }

  /** Saved metadata suitable for a status UI; private account identifiers and begin JSON are omitted. */
  suspend fun jobs(): List<RelayResult> = locked {
    val session = accounts.snapshot()
    journal.cleanup(clock, session.accountKey)
    journal.all().filter { it.accountKey == session.accountKey }.map { it.result().copy(remainingMillis = it.remaining(clock)) }
  }

  suspend fun resumeUploads(
    jobIds: Set<String>? = null, onProgress: (RelayProgress) -> Unit = {},
  ): RelayReport = locked {
    withSession { session ->
      journal.cleanup(clock, session.accountKey)
      RelayReport(journal.all().filter {
        it.upload && it.accountKey == session.accountKey && it.active && (jobIds == null || it.id in jobIds)
      }.map { job -> attempt(job, session) { upload(it, session, onProgress) } })
    }
  }

  /** Discover without manufacturing Room rows. Re-run after a successful text sync to resolve unmatched entries. */
  suspend fun receiveReady(onProgress: (RelayProgress) -> Unit = {}): RelayReport = locked {
    withSession { session -> receive(session, null, onProgress) }
  }

  /** On-demand lookup for ArchiveFetcher, session playback and source opening. */
  suspend fun availability(kind: RelayKind, entityId: String): RelayAvailability = locked {
    try {
      val session = accounts.snapshot()
      val row = local.get(kind, entityId) ?: return@locked RelayAvailability(emptyList(),
        RelayResult(RelayState.ROW_CHANGED, kind = kind, entityId = entityId))
      val discovery = checked(session) { http.discover(session) }
      RelayAvailability(discovery.transfers.filter { matches(row, it) && it.state == "ready" })
    } catch (failure: RelayFailure) {
      RelayAvailability(emptyList(), RelayResult(failure.state, code = failure.code))
    }
  }

  suspend fun fetch(
    kind: RelayKind, entityId: String, onProgress: (RelayProgress) -> Unit = {},
  ): RelayResult = locked {
    try {
      val session = accounts.snapshot()
      val row = local.get(kind, entityId) ?: return@locked RelayResult(RelayState.ROW_CHANGED, kind = kind, entityId = entityId)
      val target = local.path(row)
      if (validFile(target, row.sha256, row.bytes)) {
        requireAccount(session)
        return@locked RelayResult(RelayState.ALREADY_LOCAL, kind = kind, entityId = entityId, file = target)
      }
      receive(session, row, onProgress).results.firstOrNull {
        it.entityId == entityId || it.state !in setOf(RelayState.UNMATCHED, RelayState.ALREADY_LOCAL)
      } ?: RelayResult(RelayState.UNMATCHED, kind = kind, entityId = entityId, code = "no_ready_transfer")
    } catch (failure: RelayFailure) { RelayResult(failure.state, kind = kind, entityId = entityId, code = failure.code) }
  }

  /** Explicitly forget a local job. This does not revoke a ready transfer needed by other receivers. */
  suspend fun forget(jobId: String) = locked {
    val session = accounts.snapshot()
    journal.all().firstOrNull { it.id == jobId && it.accountKey == session.accountKey }?.let(journal::remove)
  }

  private suspend fun queue(noteId: String?, resend: Boolean): RelayReport = locked {
    withSession { session ->
      journal.cleanup(clock, session.accountKey)
      val results = mutableListOf<RelayResult>()
      for (file in local.list(noteId)) {
        currentCoroutineContext().ensureActive()
        try {
          requireAccount(session)
          val target = local.path(file)
          if (!target.isFile) continue
          if (file.bytes !in 1..RelayLimits.MAX_FILE_BYTES || !RelayLimits.hash.matches(file.sha256) ||
            !RelayLimits.id.matches(file.entityId) || !RelayLimits.id.matches(session.deviceId)) {
            results += RelayResult(RelayState.UNSUPPORTED, kind = file.kind, entityId = file.entityId, code = "unsupported_file")
            continue
          }
          val old = journal.all().firstOrNull {
            it.upload && it.accountKey == session.accountKey && it.local == file
          }
          if (old != null && (!resend || old.active)) {
            results += old.result()
            continue
          }
          if (journal.all().count { it.active } >= RelayLimits.MAX_JOBS) {
            results += RelayResult(RelayState.QUEUE_FULL, kind = file.kind, entityId = file.entityId, code = "queue_full")
            break
          }
          local.hold(file)
          try {
            val chunks = fingerprint(file)
            val discovery = checked(session) { http.discover(session) }
            val begin = RelayBegin(UUID.randomUUID().toString(), discovery.serverTime, file.kind.wire,
              file.entityId, file.sha256, file.bytes, safeMime(file.mime), safeDisplayName(file.originalName),
              session.deviceId, session.deviceName.filterNot { it.isISOControl() }.take(128))
            val body = RelayLimits.json.encodeToString(RelayBegin.serializer(), begin)
            relayRequire(body.toByteArray().size <= 4096, "begin_too_large")
            val job = RelayJob(
              id = begin.requestId, accountKey = session.accountKey, upload = true, local = file,
              beginJson = body, chunks = chunks,
              deadline = begin.requestedAt + RelayLimits.BEGIN_FRESHNESS_MS + RelayLimits.TTL_MS,
              serverTime = discovery.serverTime, elapsedAt = clock.elapsedMillis(), touchedAt = clock.wallMillis(),
            )
            // A durable atomic write is mandatory before ANY begin POST.
            journal.save(job)
            results += job.result()
          } finally { local.release(file) }
        } catch (failure: RelayFailure) {
          results += RelayResult(failure.state, kind = file.kind, entityId = file.entityId, code = failure.code)
          if (failure.state in setOf(RelayState.ACCOUNT_CHANGED, RelayState.AUTH_REQUIRED)) break
        } catch (_: IOException) {
          results += RelayResult(RelayState.FAILED, kind = file.kind, entityId = file.entityId, code = "local_io")
        }
      }
      RelayReport(results)
    }
  }

  private suspend fun upload(initial: RelayJob, session: RelaySession, progress: (RelayProgress) -> Unit): RelayJob {
    var job = initial
    requireRow(job.local)
    relayRequire(fingerprint(job.local) == job.chunks, "source_changed")
    var transfer = checked(session) {
      if (job.transfer == null) http.begin(session, job.beginJson) else http.status(session, job.transfer!!.transferId)
    }
    verifyTransfer(job, transfer)
    job = job.observed(transfer, clock).copy(state = RelayState.UPLOADING)
    journal.save(job)
    if (transfer.state != "ready") {
      for (chunk in job.chunks) {
        requireLive(job)
        requireRow(job.local)
        val remote = transfer.chunks.firstOrNull { it.index == chunk.index }
        if (remote != null) relayRequire(remote == chunk, "chunk_conflict")
        else {
          val bytes = readChunk(job.local, chunk)
          val ack = checked(session) { http.put(session, transfer, chunk, bytes) }
          job = job.copy(serverTime = ack.serverTime, elapsedAt = clock.elapsedMillis(), wallAt = clock.wallMillis())
        }
        job = job.copy(doneBytes = (chunk.index.toLong() * RelayLimits.CHUNK_BYTES + chunk.bytes),
          touchedAt = clock.wallMillis())
        journal.save(job)
        emit(job, progress)
      }
      // Detect mutation after a chunk was read/acknowledged as well as before each retry.
      requireRow(job.local)
      relayRequire(fingerprint(job.local) == job.chunks, "source_changed")
      requireLive(job)
      transfer = checked(session) { http.commit(session, transfer.transferId) }
      verifyTransfer(job, transfer)
      relayRequire(transfer.state == "ready", "commit_not_ready")
    }
    return job.observed(transfer, clock).copy(state = RelayState.READY, doneBytes = job.local.bytes, code = null)
      .also { journal.save(it); emit(it, progress) }
  }

  private suspend fun receive(
    session: RelaySession, requested: RelayLocalFile?, progress: (RelayProgress) -> Unit,
  ): RelayReport {
    journal.cleanup(clock, session.accountKey)
    val discovery = checked(session) { http.discover(session) }
    val rows = if (requested == null) local.list() else listOf(requested)
    val results = mutableListOf<RelayResult>()
    for (entry in discovery.transfers.filter { it.state == "ready" }) {
      val candidates = rows.filter { matches(it, entry) }
        .sortedByDescending { it.entityId == entry.entityId }
      if (candidates.isEmpty()) {
        if (requested == null) results += RelayResult(RelayState.UNMATCHED, transferId = entry.transferId,
          totalBytes = entry.bytes, expiresAt = entry.expiresAt, code = "awaiting_text_sync")
        continue
      }
      for (row in candidates) {
        val target = local.path(row)
        if (validFile(target, row.sha256, row.bytes)) {
          results += RelayResult(RelayState.ALREADY_LOCAL, kind = row.kind, entityId = row.entityId,
            transferId = entry.transferId, file = target, expiresAt = entry.expiresAt)
          continue
        }
        var job = journal.all().firstOrNull {
          !it.upload && it.accountKey == session.accountKey && it.transfer?.transferId == entry.transferId && it.local == row
        }
        // A receipt records a past placement, not permanent possession of the file. Eviction or
        // corruption can require the same live transfer again. Keep its original expiry and
        // attempt count; attempt() still enforces both before any further download.
        if (job != null && !job.active && job.state != RelayState.RECEIVED) { results += job.result(); continue }
        if (job == null) {
          job = RelayJob(UUID.randomUUID().toString(), session.accountKey, false, row, transfer = entry,
            deadline = entry.expiresAt, serverTime = discovery.serverTime, elapsedAt = clock.elapsedMillis(),
            touchedAt = clock.wallMillis())
          try { journal.save(job) } catch (failure: RelayFailure) {
            results += RelayResult(failure.state, code = failure.code); break
          }
        }
        results += attempt(job, session) { download(it, session, progress) }
      }
    }
    return RelayReport(results)
  }

  private suspend fun download(initial: RelayJob, session: RelaySession, progress: (RelayProgress) -> Unit): RelayJob {
    var job = initial
    requireRow(job.local)
    val transfer = checked(session) { http.status(session, job.transfer!!.transferId) }
    verifyTransfer(job, transfer)
    relayRequire(transfer.state == "ready", "not_ready")
    job = job.observed(transfer, clock).copy(state = RelayState.DOWNLOADING, doneBytes = 0)
    journal.save(job)
    val stage = journal.stage(job.id).also { check(it.mkdirs() || it.isDirectory) }
    for (chunk in transfer.chunks.sortedBy { it.index }) {
      requireLive(job)
      requireRow(job.local)
      val complete = File(stage, chunk.index.toString() + ".chunk")
      if (!validFile(complete, chunk.sha256, chunk.bytes.toLong())) {
        complete.delete()
        val temporary = File(stage, chunk.index.toString() + ".part")
        temporary.delete()
        if (journal.stagingBytes() + chunk.bytes > MAX_STAGING_BYTES) {
          throw RelayFailure(RelayState.QUOTA, "staging_quota")
        }
        checked(session) {
          http.download(session, transfer, chunk, temporary) { within ->
            emit(job.copy(doneBytes = job.doneBytes + within), progress)
          }
        }
        RelayJournal.atomicMove(temporary, complete)
      }
      job = job.copy(doneBytes = job.doneBytes + chunk.bytes, touchedAt = clock.wallMillis())
      journal.save(job)
      emit(job, progress)
    }
    val assembled = File(stage, "verified.bin")
    assembled.delete()
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    FileOutputStream(assembled).use { output ->
      val buffer = ByteArray(64 * 1024)
      for (chunk in transfer.chunks.sortedBy { it.index }) {
        File(stage, chunk.index.toString() + ".chunk").inputStream().use { input ->
          while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n); digest.update(buffer, 0, n); count += n
          }
        }
      }
      output.fd.sync()
    }
    relayRequire(count == job.local.bytes && digest.digest().relayHex() == job.local.sha256, "file_hash_mismatch")
    val placed = local.placeIfCurrent(job.local) {
      requireAccount(session)
      val target = local.path(job.local)
      if (!validFile(target, job.local.sha256, job.local.bytes)) {
        RelayJournal.atomicMove(assembled, target)
        // Account changes racing the move must not leave a newly installed file behind.
        if (!accounts.isCurrent(session)) {
          RelayJournal.atomicMove(target, assembled)
          throw RelayFailure(RelayState.ACCOUNT_CHANGED, "account_changed")
        }
      }
    }
    if (!placed) throw RelayFailure(RelayState.ROW_CHANGED, "row_changed")
    job = job.copy(state = RelayState.RECEIVED, code = null)
    journal.save(job)
    journal.clearStage(job)
    emit(job, progress)
    return job
  }

  private suspend fun attempt(
    initial: RelayJob, session: RelaySession, run: suspend (RelayJob) -> RelayJob,
  ): RelayResult {
    var job = initial
    try {
      requireAccount(session)
      requireLive(job)
      if (job.attempts >= RelayLimits.MAX_ATTEMPTS) throw RelayFailure(RelayState.FAILED, "retry_limit")
      job = job.copy(attempts = job.attempts + 1)
      journal.save(job)
      local.hold(job.local)
      val done = run(job)
      return done.result(file = if (done.state == RelayState.RECEIVED) local.path(done.local) else null)
    } catch (cancelled: CancellationException) {
      // Most recent durable chunk journal stays resumable. Never persist cancellation as a failure.
      throw cancelled
    } catch (error: IOException) {
      job = journal.all().firstOrNull { it.id == job.id } ?: job
      val failure = error as? RelayFailure ?: RelayFailure(RelayState.RETRY, "local_io")
      var state = failure.state
      if (failure.code == "source_changed") state = RelayState.SOURCE_CHANGED
      val delay = minOf(60_000L, 10_000L * (1L shl (job.attempts - 1).coerceIn(0, 3)))
      if (state == RelayState.RETRY && (job.remaining(clock) <= delay || job.attempts >= RelayLimits.MAX_ATTEMPTS)) {
        state = if (job.remaining(clock) <= delay) RelayState.EXPIRED else RelayState.FAILED
      }
      job = job.copy(state = state, code = failure.code, touchedAt = clock.wallMillis())
      journal.save(job)
      if (!job.active) journal.clearStage(job)
      return job.result(retryAfterMillis = if (state == RelayState.RETRY) delay else null)
    } finally { local.release(job.local) }
  }

  private suspend fun requireAccount(session: RelaySession) {
    currentCoroutineContext().ensureActive()
    if (!accounts.isCurrent(session)) throw RelayFailure(RelayState.ACCOUNT_CHANGED, "account_changed")
  }

  private suspend fun <T> checked(session: RelaySession, call: suspend () -> T): T {
    requireAccount(session)
    val result = call()
    requireAccount(session)
    return result
  }

  private suspend fun requireRow(expected: RelayLocalFile) {
    if (local.get(expected.kind, expected.entityId) != expected) throw RelayFailure(RelayState.ROW_CHANGED, "row_changed")
  }

  private fun requireLive(job: RelayJob) {
    if (job.remaining(clock) <= 0) throw RelayFailure(RelayState.EXPIRED, "expired")
  }

  private fun verifyTransfer(job: RelayJob, transfer: RelayTransfer) {
    relayRequire(matches(job.local, transfer), "identity_mismatch")
    if (job.upload) {
      val begin = RelayLimits.json.decodeFromString<RelayBegin>(job.beginJson)
      relayRequire(transfer.requestId == begin.requestId && transfer.entityId == begin.entityId, "begin_mismatch")
    }
    job.transfer?.let {
      relayRequire(it.transferId == transfer.transferId && it.expiresAt == transfer.expiresAt, "expiry_changed")
    }
  }

  private fun matches(row: RelayLocalFile, transfer: RelayTransfer): Boolean =
    row.kind.wire == transfer.kind && row.sha256 == transfer.sha256 && row.bytes == transfer.bytes

  private suspend fun fingerprint(file: RelayLocalFile): List<RelayChunk> {
    val path = local.path(file)
    if (!path.isFile || path.length() != file.bytes) throw RelayFailure(RelayState.SOURCE_CHANGED, "source_changed")
    val chunks = mutableListOf<RelayChunk>()
    val whole = MessageDigest.getInstance("SHA-256")
    var total = 0L
    path.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (total < file.bytes) {
        val digest = MessageDigest.getInstance("SHA-256")
        val expected = minOf(RelayLimits.CHUNK_BYTES.toLong(), file.bytes - total).toInt()
        var received = 0
        while (received < expected) {
          currentCoroutineContext().ensureActive()
          val n = input.read(buffer, 0, minOf(buffer.size, expected - received))
          if (n <= 0) throw RelayFailure(RelayState.SOURCE_CHANGED, "source_changed")
          digest.update(buffer, 0, n); whole.update(buffer, 0, n); received += n; total += n
        }
        chunks += RelayChunk(chunks.size, expected, digest.digest().relayHex())
      }
      if (input.read() != -1) throw RelayFailure(RelayState.SOURCE_CHANGED, "source_changed")
    }
    if (whole.digest().relayHex() != file.sha256) throw RelayFailure(RelayState.SOURCE_CHANGED, "source_changed")
    return chunks
  }

  private fun readChunk(file: RelayLocalFile, chunk: RelayChunk): ByteArray {
    val bytes = ByteArray(chunk.bytes)
    RandomAccessFile(local.path(file), "r").use {
      it.seek(chunk.index.toLong() * RelayLimits.CHUNK_BYTES)
      it.readFully(bytes)
    }
    if (relayHash(bytes) != chunk.sha256) throw RelayFailure(RelayState.SOURCE_CHANGED, "source_changed")
    return bytes
  }

  private suspend fun validFile(file: File, hash: String, bytes: Long): Boolean {
    if (!file.isFile || file.length() != bytes) return false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        currentCoroutineContext().ensureActive()
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
      }
    }
    return digest.digest().relayHex() == hash
  }

  private fun safeMime(raw: String): String = raw.lowercase().takeIf {
    it.length <= 128 && it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+"))
  } ?: "application/octet-stream"

  private fun safeDisplayName(raw: String): String = raw
    .filterNot { it.isISOControl() || it in "/\\:" }.take(255)
    .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "file"

  private fun emit(job: RelayJob, progress: (RelayProgress) -> Unit) {
    progress(RelayProgress(job.id, job.local.kind, job.local.entityId, job.state,
      job.doneBytes, job.local.bytes, job.transfer?.expiresAt))
  }

  private suspend fun withSession(block: suspend (RelaySession) -> RelayReport): RelayReport = try {
    block(accounts.snapshot())
  } catch (failure: RelayFailure) { RelayReport(listOf(RelayResult(failure.state, code = failure.code))) }

  private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
    mutex.withLock { block() }
  }

  private companion object { const val MAX_STAGING_BYTES = 512L * 1024 * 1024 }
}
