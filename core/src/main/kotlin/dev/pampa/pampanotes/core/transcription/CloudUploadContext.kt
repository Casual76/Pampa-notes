package dev.pampa.pampanotes.core.transcription

import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One private runner intent, bound only to its audio POST, never to metadata or shared quota. */
internal class CloudUploadContext(
  private val upload: TranscriptionWorkStorage.Upload,
  private val provider: String,
  private val checkDispatch: suspend () -> Unit = {},
  private val persistBody: (kotlinx.serialization.json.JsonElement) -> Unit = {},
  private val persist: (TranscriptResult) -> Unit = {},
) : AbstractCoroutineContextElement(Key) {
  private enum class State { READY, DISPATCHED, REFUSED }
  private val state = AtomicReference(State.READY)
  private var saved = false

  suspend fun authorize() = checkDispatch()

  /** HTTP has the complete response, even if prompt cancellation prevents provider parsing. */
  fun receivedBody(body: kotlinx.serialization.json.JsonElement?) { if (body != null) persistBody(body) }

  /** Synchronous: save before the guard's suspending accounting/finally and prompt cancellation. */
  fun received(result: TranscriptResult) {
    if (!saved) { persist(result); saved = true }
  }

  /** Called immediately before network IO. Only the matching POST gets an outcome callback. */
  fun dispatch(url: String): CloudUploadContext? {
    if (!state.compareAndSet(State.READY, State.DISPATCHED))
      throw java.io.IOException("Multiple paid requests for one transcription intent refused")
    val target = URL(url)
    val expected = when (provider) {
      "groq" -> "api.groq.com/openai/v1/audio/transcriptions"
      "openrouter" -> "openrouter.ai/api/v1/audio/transcriptions"
      "mistral" -> "api.mistral.ai/v1/audio/transcriptions"
      else -> return null
    }
    return takeIf { target.protocol == "https" && "${target.host.lowercase()}${target.path}" == expected }
  }

  fun rejected() = upload.rejected()

  fun observedRefusal(code: Int) = upload.observedRefusal(code)

  fun uncertain() = upload.uncertain()

  /** A typed guard/metadata refusal before any audio POST cannot have billed this intent.
   * Raw unknown IO and cancellation remain conservative, including uninstrumented providers.
   */
  suspend fun refusedBeforeDispatch(error: Throwable) {
    if ((error is TranscriptionError.RateLimited ||
        error is TranscriptionError.Unauthorized || error is TranscriptionError.CreditRequired ||
        error is TranscriptionError.CloudControl || error is TranscriptionError.CompanionOnly ||
        error is TranscriptionError.Network || error is TranscriptionError.Timeout ||
        error is TranscriptionError.UnknownModel || error is TranscriptionError.FileTooLarge) &&
        state.compareAndSet(State.READY, State.REFUSED)) {
      withContext(NonCancellable + Dispatchers.IO) { rejected() }
    }
  }

  companion object Key : CoroutineContext.Key<CloudUploadContext>
}
