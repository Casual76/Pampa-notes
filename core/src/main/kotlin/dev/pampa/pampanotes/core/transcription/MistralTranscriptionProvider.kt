package dev.pampa.pampanotes.core.transcription

import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Direct file STT, verified against Mistral's transcribe-files and limits docs 2026-11-08. */
class MistralTranscriptionProvider(
  private val http: TranscriptionHttp,
  private val apiKey: String,
  private val baseUrl: String = BASE_URL,
) : TranscriptionProvider {
  override val id = ID
  // Conservative local transport/chunk bound, not a claim about Mistral's upload quota.
  override val capabilities = TranscriptionCapabilities(25L * 1024 * 1024, true, true,
    acceptedExtensions = setOf("mp3", "wav", "m4a", "flac", "ogg", "webm"), maxChunkMinutes = 10, chunkToleranceMs = 0)
  override suspend fun listModels(): List<String> = MODELS
  override suspend fun health(): EndpointHealth {
    val started = System.currentTimeMillis()
    val available = VerboseJson.parseModels(http.getJson("$baseUrl/models", headers())).filter { it in MODELS }
    if (available.isEmpty()) throw TranscriptionError.UnknownModel("", "No documented Mistral transcription model available")
    return EndpointHealth(true, System.currentTimeMillis() - started, available, "Mistral")
  }
  override suspend fun transcribe(file: File, mime: String, request: TranscribeRequest,
    onProgress: (UploadProgress) -> Unit, onRemote: (RemoteProgress) -> Unit): TranscriptResult {
    if (request.model !in MODELS) throw TranscriptionError.UnknownModel(request.model, "Unsupported Mistral transcription model")
    if (file.length() > capabilities.maxUploadBytes!!) throw TranscriptionError.FileTooLarge(capabilities.maxUploadBytes, "Local Mistral upload bound exceeded")
    return parseResponse(http.postAudio("$baseUrl/audio/transcriptions", headers(), fields(request), file, mime,
      readTimeoutMillis = GroqWhisperProvider.READ_TIMEOUT_MS, onProgress = onProgress, onResponse = request.cloudResponse))
  }
  private fun headers() = mapOf("Authorization" to "Bearer $apiKey")
  companion object {
    const val ID = "mistral"
    const val BASE_URL = "https://api.mistral.ai/v1"
    const val DEFAULT_MODEL = "voxtral-mini-transcribe-3"
    val MODELS = listOf(DEFAULT_MODEL, "voxtral-small-transcribe-3")
    fun fields(request: TranscribeRequest): Map<String, String> = linkedMapOf(
      "model" to request.model, "timestamp_granularities" to "segment",
    ).apply {
      if (request.language.isNullOrBlank()) put("return_language", "true") else put("language", request.language)
      request.prompt?.takeIf(String::isNotBlank)?.let { put("context_bias", it) }
    }
    fun parseResponse(body: JsonElement?): TranscriptResult {
      val root = body as? JsonObject ?: throw TranscriptionError.Parse("Invalid Mistral transcription response")
      val text = (root["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: throw TranscriptionError.Parse("Missing Mistral transcript text")
      if ("error" in root) throw TranscriptionError.Parse("Invalid Mistral transcription response")
      // Validate the original before silence classification. A present empty/whitespace string
      // is exact paid evidence; a missing/null/nonstring field is an uncertain malformed 2xx.
      val parsed = VerboseJson.parseExactText(root, text)
      val language = (root["language"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
      // Segment intervals are not precise words, even if an unexpected response includes words.
      return parsed.copy(language = language, segments = parsed.segments.map { it.copy(words = emptyList()) })
    }
  }
}
