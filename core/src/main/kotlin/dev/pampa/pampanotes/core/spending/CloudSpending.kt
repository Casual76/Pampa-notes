package dev.pampa.pampanotes.core.spending

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.TranscribeRequest
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import dev.pampa.pampanotes.core.transcription.TranscriptionHttp
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

@Singleton
class CloudCostStore @Inject constructor(@ApplicationContext context: Context) {
  // Installation accounting must not roll back when restoring an old note database/backup.
  val durable = DurableCloudCostStore(File(context.noBackupFilesDir, "cloud-transcription/costs.json"))
}

data class CloudPreflight(
  val provider: String,
  val model: String,
  val durationMs: Long,
  val policy: EffectiveCloudPolicy,
  val estimate: CostEstimate,
  val totals: SpendingTotals,
  val blockedReason: String? = null,
)

/** Shared by queue/import/bulk and UI. It never changes the selected paid provider. */
@Singleton
class CloudSpending @Inject constructor(
  private val store: CloudCostStore,
  private val db: PampaDatabase,
  private val settings: PampaSettingsStore,
  private val keys: AiKeyStore,
  http: TranscriptionHttp,
) {
  private val metadata = OpenRouterCostMetadata(http)
  private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  val changes get() = store.durable.changes
  val keyChanges get() = keys.states
  suspend fun data(): CloudCostData = withContext(Dispatchers.IO) {
    try { store.durable.read() } catch (error: Exception) { throw TranscriptionError.CloudControl("cloud_cost_storage") }
  }
  suspend fun configure(value: CloudCostSettings) = withContext(Dispatchers.IO) { store.durable.update { it.copy(settings = value.validated()) } }
  suspend fun setFolder(folderId: String, value: FolderCloudSetting) = withContext(Dispatchers.IO) {
    store.durable.update { it.copy(folders = it.folders + (folderId to value.copy(openRouterModel = value.openRouterModel?.trim()?.takeIf(String::isNotEmpty)))) }
  }
  suspend fun folderPolicy(folderId: String): EffectiveCloudPolicy {
    val current = settings.current()
    return FolderCloudPolicy.resolve(folderId, db.folders().all().map { CloudFolder(it.id, it.parentId, it.name) }, data().folders, current.customOnly)
  }
  fun observeFolder(folderId: String): Flow<FolderCloudSnapshot> =
    combine(changes, db.folders().observeAll(), settings.settings) { _, folders, prefs ->
      val data = data()
      FolderCloudSnapshot(data.folders[folderId] ?: FolderCloudSetting(), FolderCloudPolicy.resolve(folderId,
        folders.map { CloudFolder(it.id, it.parentId, it.name) }, data.folders, prefs.customOnly))
    }
  suspend fun policyForSession(sessionId: String?, partId: String? = null): EffectiveCloudPolicy {
    // A queued audio part may have moved to another session/note since it was enqueued.
    val currentSession = if (partId != null) db.audioParts().get(partId)?.sessionId else sessionId
    val session = currentSession?.let { db.sessions().get(it) }
    val note = session?.let { db.notes().get(it.noteId) }
    return FolderCloudPolicy.resolve(note?.folderId, db.folders().all().map { CloudFolder(it.id, it.parentId, it.name) }, data().folders, settings.current().customOnly)
  }

  suspend fun preflight(sessionId: String, provider: String? = null): CloudPreflight {
    val prefs = settings.current()
    val selected = provider ?: prefs.transcriptionProvider.id
    val policy = policyForSession(sessionId)
    val model = if (selected == "openrouter") policy.model ?: prefs.openRouterTranscriptionModel else if (selected == "groq") "whisper-large-v3-turbo" else prefs.endpointModel
    val audio = db.audioParts().bySession(sessionId)
    val duration = if (audio.isEmpty() || audio.any { it.durationMs <= 0 }) 0 else audio.sumOf { it.durationMs }
    val data = data()
    val estimate = CloudEstimates.estimate(selected, model, duration, data.prices[model], data.settings, System.currentTimeMillis())
    return CloudPreflight(selected, model, duration, policy, estimate, SpendingLedger.totals(data, System.currentTimeMillis()),
      if (selected == "custom") null else policy.reason ?: SpendingLedger.check(data, "preflight:$sessionId", estimate, System.currentTimeMillis()))
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  fun observePreflight(sessionId: String, provider: String? = null): Flow<CloudPreflight> =
    db.sessions().observe(sessionId).flatMapLatest { session ->
      val note = session?.session?.noteId?.let { db.notes().observe(it) } ?: flowOf(null)
      combine(changes, settings.settings, db.folders().observeAll(), note, db.audioParts().observeBySession(sessionId)) { _, _, _, _, _ ->
        preflight(sessionId, provider)
      }
    }

  suspend fun refresh(model: String? = null) {
    val key = keys.key(ProviderId.OPENROUTER)?.takeIf { it.isNotBlank() } ?: throw TranscriptionError.Unauthorized("Missing OpenRouter key")
    val now = System.currentTimeMillis()
    var quotaError: Exception? = null
    try {
      val quota = metadata.quota(key, now)
      withContext(Dispatchers.IO) { store.durable.update { it.copy(quotas = it.quotas + (quota.fingerprint to quota)) } }
    } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { quotaError = error }
    val selected = model?.takeIf { it.isNotBlank() } ?: settings.current().openRouterTranscriptionModel.takeIf { it.isNotBlank() }
    if (selected != null) {
      val price = metadata.prices(key, selected, now)
      withContext(Dispatchers.IO) { store.durable.update { it.copy(prices = it.prices + (selected to price)) } }
    }
    quotaError?.let { throw it }
  }

  suspend fun currentQuota(): KeyQuota? {
    val key = keys.key(ProviderId.OPENROUTER)?.takeIf { it.isNotBlank() } ?: return null
    return data().quotas[OpenRouterCostMetadata.fingerprint(key)]
  }

  /** Called for each actual network attempt, after cached chunks have been skipped. */
  suspend fun beforeUpload(provider: String, keyFingerprint: String, request: TranscribeRequest): TranscribeRequest {
    var policy = policyForSession(request.cloudSessionId, request.cloudPartId)
    if (!policy.allowed) throw TranscriptionError.CloudControl(policy.reason ?: "cloud_folder_blocked")
    checkBoundKey(provider, keyFingerprint)
    var model = if (provider == "openrouter") policy.model ?: request.cloudDefaultModel ?: request.model else request.model
    if (provider == "openrouter" && data().prices[model]?.fresh(System.currentTimeMillis()) != true) {
      try { refresh(model) } catch (cancel: CancellationException) { throw cancel }
      catch (unknown: TranscriptionError.UnknownModel) { throw unknown }
      catch (_: Exception) { /* stale stays unknown */ }
    }
    // Metadata requests may take time: re-resolve a moved note and privacy immediately before reserving.
    policy = policyForSession(request.cloudSessionId, request.cloudPartId)
    if (!policy.allowed) throw TranscriptionError.CloudControl(policy.reason ?: "cloud_folder_blocked")
    model = if (provider == "openrouter") policy.model ?: request.cloudDefaultModel ?: request.model else request.model
    // Do not combine a cached earlier chunk with a different model after a folder rule change.
    // An explicit retry resolves the new model before the runner constructs its cache signature.
    if (model != request.model) throw TranscriptionError.CloudControl("cloud_model_changed")
    checkBoundKey(provider, keyFingerprint)
    val id = UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    withContext(Dispatchers.IO) {
      try {
        store.durable.update { data ->
          val estimate = CloudEstimates.estimate(provider, model, request.cloudDurationMs, data.prices[model], data.settings, now)
          SpendingLedger.check(data, request.cloudJobId ?: request.cloudSessionId.orEmpty(), estimate, now)?.let { throw TranscriptionError.CloudControl(it) }
          data.copy(charges = data.charges + CloudCharge(id, request.cloudJobId ?: request.cloudSessionId.orEmpty(), provider,
            keyFingerprint, model, request.cloudDurationMs, now, estimate.maxUsd, priceVerified = estimate.reservable))
        }
      } catch (error: TranscriptionError) { throw error }
      catch (_: Exception) { throw TranscriptionError.CloudControl("cloud_cost_storage") }
    }
    inFlight.add(id)
    return request.copy(model = model, cloudAttemptFinished = { inFlight.remove(id) }, cloudResponse = { body ->
      OpenRouterCostMetadata.actualCost(body)?.let { cost -> settle(id, cost) }
    })
  }

  private fun settle(id: String, cost: String) {
    require(Usd.amount(cost) != null)
    try { store.durable.settle(id, cost, System.currentTimeMillis()) }
    catch (_: Exception) { throw TranscriptionError.CloudControl("cloud_cost_storage") }
  }

  private suspend fun checkBoundKey(provider: String, fingerprint: String) {
    val key = keys.key(if (provider == "openrouter") ProviderId.OPENROUTER else ProviderId.GROQ)
    if (key.isNullOrBlank() || OpenRouterCostMetadata.fingerprint(key) != fingerprint) {
      throw TranscriptionError.CloudControl("cloud_key_changed")
    }
  }

  /** User-entered provider statement amount. Zero requires the same explicit confirmation as any amount. */
  suspend fun reconcile(id: String, actualUsd: String) = withContext(Dispatchers.IO) {
    if (inFlight.contains(id)) throw TranscriptionError.CloudControl("cloud_charge_inflight")
    val amount = Usd.parseInput(actualUsd) ?: throw IllegalArgumentException("Invalid USD")
    store.durable.settle(id, amount, System.currentTimeMillis(), reconciled = true)
  }
  suspend fun resetConfirmedTotals() = withContext(Dispatchers.IO) {
    store.durable.update { it.copy(settings = it.settings.copy(resetAt = System.currentTimeMillis())) }
  }
}
