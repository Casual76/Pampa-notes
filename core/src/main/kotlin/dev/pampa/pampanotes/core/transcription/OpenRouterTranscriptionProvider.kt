package dev.pampa.pampanotes.core.transcription

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToLong

/** OpenRouter's dedicated STT API. Never routes audio through chat completions. */
class OpenRouterTranscriptionProvider(
  private val http: TranscriptionHttp,
  private val apiKey: String,
  private val baseUrl: String = BASE_URL,
) : TranscriptionProvider {
  override val id = ID
  override val capabilities = TranscriptionCapabilities(
    maxUploadBytes = MAX_UPLOAD_BYTES,
    supportsSegments = true, // Depending on the selected model; JSON-only models remain untimed.
    needsChunking = true,
    acceptedExtensions = GroqWhisperProvider.ACCEPTED_EXTENSIONS,
  )

  override suspend fun listModels(): List<String> = parseModels(
    http.getJson("$baseUrl/models?output_modalities=transcription", headers()),
  )

  override suspend fun health(): EndpointHealth {
    val started = System.currentTimeMillis()
    val models = listModels()
    return EndpointHealth(true, System.currentTimeMillis() - started, models, "OpenRouter")
  }

  override suspend fun transcribe(
    file: File,
    mime: String,
    request: TranscribeRequest,
    onProgress: (UploadProgress) -> Unit,
    onRemote: (RemoteProgress) -> Unit,
  ): TranscriptResult {
    if (file.length() > MAX_UPLOAD_BYTES) {
      throw TranscriptionError.FileTooLarge(MAX_UPLOAD_BYTES, "OpenRouter multipart limit exceeded")
    }
    // One request, one format. Never re-upload a successful transcription to obtain timestamps.
    // prompt is accepted but ignored by OpenRouter, so deliberately omit vocabulary hints.
    return parseResponse(http.postAudio(
      url = "$baseUrl/audio/transcriptions",
      headers = headers(),
      fields = fields(request),
      file = file,
      fileMime = mime,
      readTimeoutMillis = GroqWhisperProvider.READ_TIMEOUT_MS,
      onProgress = onProgress,
      onResponse = request.cloudResponse,
    ))
  }

  private fun headers() = mapOf("Authorization" to "Bearer $apiKey")

  companion object {
    const val ID = "openrouter"
    const val BASE_URL = "https://openrouter.ai/api/v1"
    const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024

    /** Only documented structured models opt in. New/unknown models safely use JSON. */
    private val STRUCTURED_MODELS = setOf(
      "openai/whisper-1", "openai/whisper-large-v3", "openai/whisper-large-v3-turbo",
      "microsoft/mai-transcribe-2",
    )

    fun responseFormat(model: String): String = if (model in STRUCTURED_MODELS) "verbose_json" else "json"

    fun fields(request: TranscribeRequest): Map<String, String> = linkedMapOf(
      "model" to request.model,
      "response_format" to responseFormat(request.model),
      "temperature" to request.temperature.coerceIn(0.0, 1.0).toString(),
    ).apply {
      request.language?.takeIf { it.isNotBlank() }?.let { put("language", it) }
      // Segment times are the verbose default; this requests the optional top-level words too.
      if (responseFormat(request.model) == "verbose_json") put("timestamp_granularities[]", "word")
    }

    fun parseResponse(body: JsonElement?): TranscriptResult {
      val root = body as? JsonObject ?: throw TranscriptionError.Parse("Invalid transcription response")
      val original = root["text"] as? JsonPrimitive
      // Segment/word text is a presentation, not an attested top-level original. Reject an
      // unsupported shape before the runner can cache it as exact source evidence.
      if ("error" in root || original?.isString != true) {
        throw TranscriptionError.Parse("Invalid transcription response")
      }
      val text = original.content
      val reportedLanguage = (root["language"] as? JsonPrimitive)?.takeIf { it.isString }?.content
      val parsed = (try { VerboseJson.parse(root) } catch (silent: TranscriptionError.NoSpeech) {
        if (text.isNotBlank()) throw silent
        // A present whitespace-only original is still known exact text. It may be one silent
        // part of a non-silent recording; keep it in the runner/cache rather than inventing "".
        TranscriptResult(text, emptyList(), reportedLanguage,
          (root["duration"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }?.let { (it * 1000).roundToLong() })
      }).copy(language = reportedLanguage)
      // Some providers supply only partially timed text. Never throw away the untimed remainder.
      fun normalize(value: String) = value.replace(Regex("\\s+"), " ").trim()
      return if (normalize(text) != normalize(parsed.text)) {
        parsed.copy(text = text, segments = emptyList())
      } else parsed.copy(text = text, segments = parsed.segments.map { segment ->
        // Partial word lists would make the UI interpolate the missing words while claiming
        // exact alignment. Keep the real segment interval and label interpolation as estimated.
        if (segment.words.isNotEmpty() &&
          normalize(segment.words.joinToString(" ") { it.text }) != normalize(segment.text)
        ) segment.copy(words = emptyList()) else segment
      })
    }

    /** Defend against an unfiltered/mixed catalogue; audio input alone does not imply STT. */
    fun parseModels(body: JsonElement?): List<String> {
      val data = (body as? JsonObject)?.get("data") as? JsonArray
        ?: throw TranscriptionError.Parse("Invalid OpenRouter transcription catalogue")
      return data.mapNotNull { item ->
        val model = item as? JsonObject ?: return@mapNotNull null
        val outputs = (model["architecture"] as? JsonObject)?.get("output_modalities") as? JsonArray
        if (outputs?.any { (it as? JsonPrimitive)?.contentOrNull == "transcription" } != true) return@mapNotNull null
        (model["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
      }.distinct().sorted()
    }
  }
}
