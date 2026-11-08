package dev.pampa.pampanotes.core.transcription

import java.net.URL
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Private installation quota boundary used by every direct cloud HTTP request, including setup.
 * Callbacks return fingerprints, never exported credentials. No sleeping, retries or provider choice.
 */
class CloudRequestGate(
  private val cooldowns: DurableCloudCooldowns,
  private val account: suspend () -> String,
  private val key: suspend (String) -> String?,
  private val now: () -> Long = System::currentTimeMillis,
) {
  suspend fun permit(url: String, headers: Map<String, String>): Permit? {
    val provider = when (URL(url).host.lowercase()) {
      "openrouter.ai" -> "openrouter"
      "api.groq.com" -> "groq"
      "api.mistral.ai" -> "mistral"
      else -> return null // Companion/relay traffic has its own authentication and cancellation.
    }
    val bearer = headers.entries.firstOrNull { it.key.equals("Authorization", true) }?.value
      ?.takeIf { it.startsWith("Bearer ", true) }?.substring(7)?.trim().orEmpty()
    if (bearer.isEmpty()) throw TranscriptionError.Unauthorized("Missing cloud key")
    val fingerprint = CloudRetry.fingerprint(bearer)
    val bound = currentCoroutineContext()[CloudRequestContext]?.permit?.takeIf { it.provider == provider }
    if (bound != null && bound.fingerprint != fingerprint) throw TranscriptionError.CloudControl("cloud_key_changed")
    return (bound ?: Permit(provider, fingerprint, account())).also { it.beforeRequest() }
  }

  inner class Permit internal constructor(
    internal val provider: String,
    internal val fingerprint: String,
    private val accountFingerprint: String,
  ) {
    suspend fun beforeRequest() {
      currentCoroutineContext().ensureActive()
      checkIdentity()
      val until = withContext(Dispatchers.IO) {
        try { cooldowns.deadline(provider, fingerprint, accountFingerprint) }
        catch (_: Exception) { throw TranscriptionError.CloudControl("cloud_cost_storage") }
      }
      if (until != null && until > now()) throw limited(until)
      currentCoroutineContext().ensureActive()
    }

    suspend fun checkIdentity() {
      if (account() != accountFingerprint) throw TranscriptionError.CloudControl("cloud_account_changed")
      if (key(provider) != fingerprint) throw TranscriptionError.CloudControl("cloud_key_changed")
    }

    /** Finish a known quota write even when the UI/worker is cancelled during disk IO.
     * The caller still observes cancellation; a reopened installation retains the server's wait.
     */
    suspend fun observed(error: TranscriptionError.RateLimited): TranscriptionError.RateLimited =
      withContext(NonCancellable + Dispatchers.IO) {
        try { limited(cooldowns.extend(provider, fingerprint, accountFingerprint, CloudRetry.deadline(error, now()),
          error.retryAtMillis != null || error.retryAfterSec != null)) }
        catch (_: Exception) { throw TranscriptionError.CloudControl("cloud_cost_storage") }
      }

    private fun limited(until: Long) = TranscriptionError.RateLimited(
      (until - now()).coerceAtLeast(0) / 1000.0, "Provider cooldown", until,
      CloudRetry.scope(provider, fingerprint, accountFingerprint),
    )
  }
}

internal class CloudRequestContext(val permit: CloudRequestGate.Permit) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<CloudRequestContext>
}
