package dev.pampa.pampanotes.core.transcription

import java.io.IOException
import java.net.URL
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Private, single-POST accounting ticket. Metadata and later attempts cannot settle it. */
internal class CloudAttemptAccounting(private val provider: String) : AbstractCoroutineContextElement(Key) {
  private var dispatched = false
  private var invoked = false
  private var refusal: (() -> Unit)? = null
  private var finished: (() -> Unit)? = null

  @Synchronized fun bind(onRefusal: () -> Unit, onFinished: () -> Unit) {
    check(refusal == null && !dispatched)
    refusal = onRefusal
    finished = onFinished
  }

  @Synchronized fun dispatch(url: String): CloudAttemptAccounting? {
    val target = URL(url)
    val expected = when (provider) {
      "openrouter" -> "openrouter.ai/api/v1/audio/transcriptions"
      "groq" -> "api.groq.com/openai/v1/audio/transcriptions"
      "mistral" -> "api.mistral.ai/v1/audio/transcriptions"
      else -> return null
    }
    if (target.protocol != "https" || "${target.host.lowercase()}${target.path}" != expected) return null
    if (dispatched) throw IOException("Multiple paid requests for one spending attempt refused")
    dispatched = true
    return this
  }

  @Synchronized fun rejected() { check(dispatched); refusal?.invoke() }

  @Synchronized fun refusedBeforeDispatch() { if (!dispatched) refusal?.invoke() }

  @Synchronized fun invokingProvider() { invoked = true }

  @Synchronized fun finish() {
    try { if (!invoked && !dispatched) refusal?.invoke() }
    finally { finished?.invoke() }
  }

  companion object Key : CoroutineContext.Key<CloudAttemptAccounting>
}
