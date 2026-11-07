package dev.pampa.pampanotes.core.transcription

import java.io.File

/** Recheck privacy immediately before every upload, including later chunks and retries. */
class CloudTranscriptionGuard(
  private val provider: TranscriptionProvider,
  private val beforeUpload: suspend (TranscribeRequest) -> TranscribeRequest = { it },
  private val prepare: suspend (TranscribeRequest) -> TranscribeRequest = { it },
  private val customOnly: suspend () -> Boolean,
) : TranscriptionProvider by provider {
  suspend fun prepareRequest(request: TranscribeRequest): TranscribeRequest = prepare(request)
  override suspend fun transcribe(
    file: File,
    mime: String,
    request: TranscribeRequest,
    onProgress: (UploadProgress) -> Unit,
    onRemote: (RemoteProgress) -> Unit,
  ): TranscriptResult {
    if (customOnly()) throw TranscriptionError.CompanionOnly()
    val authorized = beforeUpload(request)
    // A settings change during metadata lookup must stop this attempt too.
    try {
      if (customOnly()) throw TranscriptionError.CompanionOnly()
      return provider.transcribe(file, mime, authorized, onProgress, onRemote)
    } finally {
      authorized.cloudAttemptFinished?.invoke()
    }
  }
}
