package dev.pampa.pampanotes.core.transcription

import java.io.File
import kotlinx.coroutines.ensureActive

/** Recheck privacy immediately before every upload, including later chunks and retries. */
class CloudTranscriptionGuard(
  private val provider: TranscriptionProvider,
  private val beforeUpload: suspend (TranscribeRequest) -> TranscribeRequest = { it },
  private val prepare: suspend (TranscribeRequest) -> TranscribeRequest = { it },
  private val beforeRequest: suspend () -> Unit = {},
  private val limited: suspend (TranscriptionError.RateLimited) -> TranscriptionError.RateLimited = { it },
  private val customOnly: suspend () -> Boolean,
) : TranscriptionProvider by provider {
  private suspend fun <T> checked(send: suspend () -> T): T {
    if (customOnly()) throw TranscriptionError.CompanionOnly()
    beforeRequest()
    try { return send() }
    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (error: Exception) {
      val mapped = TranscriptionError.from(error)
      if (mapped is TranscriptionError.RateLimited) throw limited(mapped)
      throw mapped
    }
  }
  override suspend fun listModels(): List<String> = checked { provider.listModels() }
  override suspend fun health(): EndpointHealth = checked { provider.health() }
  suspend fun prepareRequest(request: TranscribeRequest): TranscribeRequest = checked { prepare(request) }
  override suspend fun transcribe(
    file: File,
    mime: String,
    request: TranscribeRequest,
    onProgress: (UploadProgress) -> Unit,
    onRemote: (RemoteProgress) -> Unit,
  ): TranscriptResult = checked {
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    // Metadata/preparation requests share the selected provider's durable quota boundary.
    val accounting = CloudAttemptAccounting(provider.id)
    kotlinx.coroutines.withContext(accounting) {
      try {
        val authorized = beforeUpload(request)
        // A settings change during metadata lookup must stop this attempt too.
        try {
          if (customOnly()) throw TranscriptionError.CompanionOnly()
          beforeRequest()
          kotlinx.coroutines.currentCoroutineContext().ensureActive()
          accounting.invokingProvider()
          provider.transcribe(file, mime, authorized, onProgress, onRemote)
        } finally {
          authorized.cloudAttemptFinished?.invoke()
        }
      } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
          accounting.finish()
        }
      }
    }
  }
}
