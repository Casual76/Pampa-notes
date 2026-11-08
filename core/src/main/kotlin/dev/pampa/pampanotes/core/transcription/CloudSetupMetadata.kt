package dev.pampa.pampanotes.core.transcription

/** Metadata-only setup shared by Settings and its onboarding surface. Never sends audio. */
class CloudSetupMetadata(private val http: TranscriptionHttp) {
  suspend fun openRouter(key: String): List<String> = http.cloudSession(OpenRouterTranscriptionProvider.BASE_URL, headers(key)) {
    // The public catalogue cannot verify credentials. /key is authenticated and free of inference.
    http.getJson("${OpenRouterTranscriptionProvider.BASE_URL}/key", headers(key))
    OpenRouterTranscriptionProvider(http, key).listModels()
  }
  suspend fun groq(key: String): List<String> = GroqWhisperProvider(http, key).listModels()
  suspend fun mistral(key: String): EndpointHealth = MistralTranscriptionProvider(http, key).health()
  private fun headers(key: String) = mapOf("Authorization" to "Bearer $key")
}
