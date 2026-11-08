package dev.pampa.pampanotes.core.alignment

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.repo.RawTranscripts
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.spending.CloudSpending
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/** Separate from paid jobs. This repository can only submit existing text for forced alignment. */
@Singleton
class AlignmentRepository @Inject constructor(
  @ApplicationContext context: Context,
  private val db: PampaDatabase,
  private val files: AppFiles,
  private val inUse: FilesInUse,
  private val settings: PampaSettingsStore,
  private val client: AlignmentClient,
  private val spending: CloudSpending,
) {
  private val queue = DurableAlignmentQueue(File(context.noBackupFilesDir, "alignment"))
  private val serial = Mutex()
  private val cancellationGate = Mutex()
  private val cancellationChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  private data class Account(val key: String, val owner: String?, val token: String?, val index: String) {
    override fun toString() = "Alignment account"
  }
  private suspend fun account(): Account {
    val first = settings.current()
    val owner = settings.syncOwnerId()
    val token = settings.syncToken()
    if (settings.syncForeignOwner.first().isNotEmpty()) throw AlignmentDeferred("account_changed", false)
    val after = settings.current()
    if (first != after) throw AlignmentDeferred("settings_changed", false)
    if (after.syncAccount.isNotBlank()) {
      if (owner == null || token.isNullOrBlank() || after.syncServerUrl.isBlank()) throw AlignmentDeferred("account_auth_required", false)
      return Account(AlignmentProtocol.sha256(listOf(owner, after.syncAccount, after.syncServerUrl, token).joinToString("\u0000")), owner, token, after.syncServerUrl)
    }
    // A signed-out database retains its owner and must never send their audio to a local token.
    if (owner != null) throw AlignmentDeferred("account_auth_required", false)
    val manual = settings.endpointToken()?.takeIf { it.isNotBlank() && !it.startsWith("pg_") }
      ?: throw AlignmentDeferred("owner_auth_required", false)
    return Account(AlignmentProtocol.sha256("local\u0000$manual"), null, null, "")
  }
  private fun settingsKey(p: PampaSettings, policy: String) = AlignmentProtocol.sha256(listOf(
    p.endpointUrl, p.endpointRemoteUrl, p.endpointUpdatedAt.toString(), p.customOnly.toString(), p.language,
    p.archiveEnabled.toString(), p.archiveOnlyUnmetered.toString(), policy,
  ).joinToString("\u0000"))

  /** Hook after successful cloud RAW save; no inference, network or paid queue access. */
  suspend fun onCloudSaved(sessionId: String): Int = discover(sessionId)

  /** Hook on application/endpoint wake. Revives bounded deferred attempts, preserves cancellations. */
  suspend fun onWake(): Int = withContext(Dispatchers.IO) { queue.wake(); discover() }
  /** Low-frequency periodic capability probe, never inference and never a paid queue wake. */
  suspend fun onEndpointProbe() = withContext(Dispatchers.IO) {
    if (queue.entries().none { it.state == "deferred" }) return@withContext
    try {
      account()
      val p = settings.current()
      client.availableInstance(p.endpointUrl, p.endpointRemoteUrl)?.let { queue.wakeEndpoint(it) }
    } catch (e: CancellationException) { currentCoroutineContext().ensureActive() }
    catch (_: Exception) { }
  }

  suspend fun discover(sessionId: String? = null): Int = withContext(Dispatchers.IO) {
    val who = try { account() } catch (e: AlignmentDeferred) { return@withContext 0 }
    val candidates = if (sessionId != null) listOfNotNull(db.sessions().get(sessionId)) else db.sessions().all()
    var count = 0
    for (session in candidates) {
      currentCoroutineContext().ensureActive()
      db.withTransaction {
        val currentSession = db.sessions().get(session.id) ?: return@withTransaction
        val rows = db.transcripts().bySession(session.id)
        val raw = RawTranscripts.forSelection(rows, currentSession.activeTranscriptId) ?: return@withTransaction
        val ordered = db.audioParts().bySession(session.id)
        var doc = CloudSources.decode(raw.cloudSourceJson)
        if (doc == null && raw.cloudSourceJson == null) {
          doc = CloudSources.legacy(raw, ordered) ?: return@withTransaction
          db.transcripts().upsert(raw.copy(cloudSourceJson = CloudSources.encode(doc)))
        }
        if (doc == null || !CloudSources.matches(doc, raw)) return@withTransaction
        for (part in doc.parts) {
          if (!CloudSources.cloud(part.provider) || part.text == null || part.text.isBlank() || AlignmentLanguage.code(part.language) == null) continue
          if (doc.overlays.any { it.partId == part.id && it.timings.isNotEmpty() && it.timings.all { t -> t.status == "measured" } }) continue
          val snapshot = snapshot(session.id, part.id, who) ?: continue
          if (queue.enqueue(snapshot)) count++
        }
      }
    }
    count
  }

  /** Transactional DB view; no inference. Comparison includes the refined selection and placement. */
  private suspend fun snapshot(sessionId: String, partId: String, who: Account): AlignmentSnapshot? {
    val session = db.sessions().get(sessionId) ?: return null
    val note = db.notes().get(session.noteId) ?: return null
    val rows = db.transcripts().bySession(sessionId)
    val raw = RawTranscripts.forSelection(rows, session.activeTranscriptId) ?: return null
    val selected = rows.find { it.id == session.activeTranscriptId } ?: raw
    val doc = CloudSources.decode(raw.cloudSourceJson)?.takeIf { CloudSources.matches(it, raw) } ?: return null
    val ordered = db.audioParts().bySession(sessionId)
    val byId = ordered.associateBy { it.id }
    val boundSource = CloudSources.bindSource(doc, ordered) ?: return null
    val part = doc.parts.find { it.id == partId } ?: return null
    val text = part.text ?: return null
    val alignmentLanguage = AlignmentLanguage.code(part.language) ?: return null
    if (!CloudSources.cloud(part.provider) || text.length > AlignmentProtocol.MAX_TEXT || !AlignmentProtocol.validUnicode(text)) return null
    val segments = db.segments().byTranscript(raw.id)
    val duration = byId.getValue(partId).durationMs
    val windows = AlignmentProtocol.windows(text, segments.filter { it.partId == partId }.sortedWith(compareBy({ it.partStartMs }, { it.indexInPart }))
      .map { AlignmentProtocol.CoarseSegment(it.text, it.partStartMs, it.partEndMs) }, duration)
    val request = AlignmentRequest(source = AlignmentSource(raw.id, CloudSources.version(raw), AlignmentProtocol.sha256(raw.text), boundSource),
      partId = partId, text = text, textSha256 = AlignmentProtocol.sha256(text), language = alignmentLanguage, segments = windows)
    AlignmentProtocol.validate(request, duration)
    val p = settings.current()
    val policy = spending.policyForSession(sessionId).toString()
    val identity = AlignmentProtocol.json.encodeToString(ListSerializer(SegmentEntity.serializer()), segments) + CloudSources.encode(doc.copy(overlays = emptyList()))
    return AlignmentSnapshot(sessionId, session.noteId, note.folderId, session.activeTranscriptId, selected.id, AlignmentProtocol.sha256(selected.text), raw.language,
      who.key, settingsKey(p, policy), AlignmentProtocol.sha256(identity), ordered.map { AlignmentPlacement(it.id, it.sha256, it.position, it.durationMs, it.fileName) }, request)
  }

  private suspend fun isCurrent(expected: AlignmentSnapshot): Boolean {
    val who = try { account() } catch (_: AlignmentDeferred) { return false }
    if (who.key != expected.accountKey) return false
    if (queue.get(expected.key)?.state == "cancelled") return false
    return db.withTransaction { snapshot(expected.sessionId, expected.request.partId, who) == expected }
  }

  /** One bounded work slice; worker schedules the persisted next deadline and returns success. */
  suspend fun runNext(): Long? = serial.withLock { withContext(Dispatchers.IO) {
    val entry = queue.due(System.currentTimeMillis()) ?: return@withContext queue.nextAt()
    val expected = entry.snapshot
    var connection: AlignmentClient.Connection? = null
    var jobId: String? = null
    val held = expected.placement.map { FilesInUse.audio(it.fileName) }
    val owner = Any()
    try {
      if (!isCurrent(expected)) { queue.update(expected.key) { it.copy(state = "stale", nextAt = Long.MAX_VALUE) }; discover(expected.sessionId); return@withContext queue.nextAt() }
      val who = account()
      if (who.owner != null) client.verifyAccount(who.index, who.token!!, who.owner)
      val p = settings.current()
      val connected = client.connect(p.endpointUrl, p.endpointRemoteUrl, who.owner != null)
      connection = connected
      if (!isCurrent(expected)) throw AlignmentDeferred("source_changed", false)
      // A previous POST may have survived process death. Never reuse its id or abandon live work.
      if (entry.remoteJobId != null && entry.remoteBase == connected.base) {
        try { client.cancel(connected, entry.remoteJobId) }
        catch (e: TranscriptionError.BlobMissing) { /* Already forgotten after restart. */ }
        catch (e: TranscriptionError.Parse) {
          if (entry.remoteInstance == connected.instance) throw e
        }
      }
      inUse.hold(held, AlignmentClient.POST_TIMEOUT_MS + 120_000L, owner = owner)
      validateFiles(expected)
      val part = db.audioParts().get(expected.request.partId) ?: throw AlignmentDeferred("audio_missing", false)
      val local = safeFile(part.fileName).takeIf { it.isFile }
      if (local == null && (part.archivedAt <= 0 || who.owner == null)) throw AlignmentDeferred("audio_missing")
      if (!isCurrent(expected)) throw AlignmentDeferred("source_changed", false)
      val freshJobId = UUID.randomUUID().toString()
      jobId = freshJobId
      queue.update(expected.key) { it.copy(remoteJobId = freshJobId, remoteBase = connected.base, remoteInstance = connected.instance) }
      // Reactive cancellation, no network/status polling and no busy retry loop.
      val response = coroutineScope {
        val post = async { client.align(connected, expected.request, local, part.mime, freshJobId) }
        val watcher = launch {
          merge(db.sessions().observe(expected.sessionId).map { Unit }, db.transcripts().observeBySession(expected.sessionId).map { Unit },
            db.audioParts().observeBySession(expected.sessionId).map { Unit }, db.segments().observe(expected.request.source.rawId).map { Unit },
            settings.settings.map { Unit }, settings.syncForeignOwner.map { Unit }, spending.changes.map { Unit }, cancellationChanges).collect {
            if (!isCurrent(expected)) { post.cancel(CancellationException("alignment_snapshot_changed")); cancel() }
          }
        }
        try { post.await() }
        catch (e: CancellationException) { currentCoroutineContext().ensureActive(); throw AlignmentDeferred("source_changed", false) }
        finally { watcher.cancel() }
      }
      if (!isCurrent(expected)) throw AlignmentDeferred("source_changed", false)
      client.authorize(connected)
      AlignmentProtocol.validate(response, expected.request, part.durationMs, freshJobId)
      validateFiles(expected)
      if (!apply(expected, response, freshJobId)) throw AlignmentDeferred("source_changed", false)
      queue.update(expected.key) { it.copy(state = if (response.status == "aligned") "done" else "deferred",
        reason = if (response.status == "aligned") null else "missing_evidence", nextAt = Long.MAX_VALUE,
        remoteJobId = null, remoteBase = null, remoteInstance = connected.instance) }
    } catch (cancel: CancellationException) {
      // System stop leaves the durable request pending; cancellation tombstones remain cancelled.
      currentCoroutineContext().ensureActive()
      if (queue.get(expected.key)?.state != "cancelled") queue.defer(expected.key, "timeout", System.currentTimeMillis())
    } catch (e: Exception) {
      if (queue.get(expected.key)?.state != "cancelled") {
        val reason = (e as? AlignmentDeferred)?.reason ?: (e as? TranscriptionError)?.code ?: "invalid_alignment"
        val retry = (e as? AlignmentDeferred)?.retryable ?: ((e as? TranscriptionError)?.retryable ?: false)
        val retryMs = ((e as? TranscriptionError.Server)?.retryAfterSec ?: (e as? TranscriptionError.RateLimited)?.retryAfterSec ?: 0.0)
          .takeIf { it.isFinite() && it > 0 }?.times(1000)?.toLong() ?: 0
        queue.defer(expected.key, reason, System.currentTimeMillis(), retry, retryMs)
      }
    } finally {
      // Same-account/current-endpoint credentials only; no cancellation sent across account changes.
      val c = connection
      val id = jobId
      if (c != null && id != null) withContext(NonCancellable) {
        try { if (account().key == expected.accountKey) client.cancel(c, id) } catch (_: Exception) { }
      }
      inUse.release(held, owner)
    }
    queue.nextAt()
  } }

  private fun safeFile(name: String): File {
    return AlignmentAudio.file(files.audio, name)
  }
  private fun validateFiles(snapshot: AlignmentSnapshot) {
    if (!AlignmentAudio.verify(files.audio, snapshot.placement)) throw AlignmentDeferred("audio_hash_changed", false)
  }

  /** Only updates the separate overlay field. RAW text/count/selection/segments remain untouched. */
  private suspend fun apply(expected: AlignmentSnapshot, response: AlignmentResponse, jobId: String): Boolean = cancellationGate.withLock {
    db.withTransaction {
      val who = account()
      val raw = db.transcripts().get(expected.request.source.rawId) ?: return@withTransaction false
      val current = snapshot(expected.sessionId, expected.request.partId, who)
      validateFiles(expected)
      val updated = AlignmentApplication.plan(expected, current, raw, response, who.key, queue.get(expected.key)?.state == "cancelled", jobId)
        ?: return@withTransaction false
      // Recheck account/settings immediately before the single atomic write.
      if (account().key != expected.accountKey || snapshot(expected.sessionId, expected.request.partId, who) != expected) return@withTransaction false
      db.transcripts().upsert(updated)
      true
    }
  }

  suspend fun cancelSession(sessionId: String) = withContext(Dispatchers.IO) { cancellationGate.withLock { queue.cancelSession(sessionId) }; cancellationChanges.tryEmit(Unit); Unit }
  suspend fun cancelAll() = withContext(Dispatchers.IO) { cancellationGate.withLock { queue.cancelAll() }; cancellationChanges.tryEmit(Unit); Unit }
  suspend fun nextDeadline(): Long? = withContext(Dispatchers.IO) { queue.nextAt() }
}
