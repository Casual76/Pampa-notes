package dev.pampa.pampanotes.ui.cloudcost

import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.core.spending.CloudSpending
import dev.pampa.pampanotes.core.transcription.TranscribeOverrides
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject

/** Same preflight for the primary action, retry and the per-job options sheet. */
class SessionCloudTranscription @Inject constructor(
  private val settings: PampaSettingsStore,
  private val spending: CloudSpending,
  private val transcription: TranscriptionRepository,
  private val scheduler: WorkScheduler,
) {
  suspend fun enqueue(sessionId: String, overrides: TranscribeOverrides?): String? {
    val provider = settings.current().transcriptionProvider
    if (provider != TranscriptionProviderId.CUSTOM) {
      var preflight = spending.preflight(sessionId, provider.id)
      if (preflight.blockedReason == "cloud_price_unknown" && provider == TranscriptionProviderId.OPENROUTER) {
        spending.refresh(preflight.model)
        preflight = spending.preflight(sessionId, provider.id)
      }
      preflight.blockedReason?.let { return it }
    }
    val job = transcription.enqueue(sessionId, provider, overrides)
    if (job?.state == JobState.QUEUED) scheduler.kick(job.provider)
    return job?.errorCode
  }
}
