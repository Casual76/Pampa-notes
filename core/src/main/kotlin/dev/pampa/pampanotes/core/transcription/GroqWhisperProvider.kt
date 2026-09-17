package dev.pampa.pampanotes.core.transcription

import java.io.File

/**
 * Whisper su Groq, con la chiave dell'utente.
 *
 * Il tetto per richiesta e' quello che decide tutto il resto dell'architettura: venticinque megabyte
 * sul piano gratuito sono circa venti minuti di m4a a qualita' telefono, quindi una lezione va
 * decodificata, tagliata nei silenzi, ricodificata piccola e mandata a pezzi. Il numero e'
 * configurabile perche' il piano a pagamento lo alza a cento.
 */
class GroqWhisperProvider(
  private val http: TranscriptionHttp,
  private val apiKey: String,
  maxUploadBytes: Long = FREE_TIER_LIMIT_BYTES,
) : TranscriptionProvider {

  override val id: String = ID

  override val capabilities: TranscriptionCapabilities = TranscriptionCapabilities(
    maxUploadBytes = maxUploadBytes,
    supportsSegments = true,
    needsChunking = true,
  )

  override suspend fun listModels(): List<String> {
    val body = http.getJson("$BASE_URL/models", headers())
    // Fra tutti i modelli del catalogo interessano quelli che trascrivono, e si riconoscono dal nome:
    // Groq non marca la modalita' in nessun campo.
    return VerboseJson.parseModels(body).filter { it.contains("whisper", ignoreCase = true) }
  }

  override suspend fun health(): EndpointHealth {
    val started = System.currentTimeMillis()
    return runCatching { listModels() }
      .map { models ->
        EndpointHealth(
          reachable = true,
          latencyMs = System.currentTimeMillis() - started,
          models = models,
          serverName = "Groq",
        )
      }
      .getOrElse { error ->
        EndpointHealth(
          reachable = false,
          latencyMs = System.currentTimeMillis() - started,
          detail = TranscriptionError.from(error).message,
        )
      }
  }

  override suspend fun transcribe(
    file: File,
    mime: String,
    request: TranscribeRequest,
    onProgress: (UploadProgress) -> Unit,
  ): TranscriptResult {
    val limit = capabilities.maxUploadBytes
    if (limit != null && file.length() > limit) {
      throw TranscriptionError.FileTooLarge(limit, "il pezzo supera il limite di Groq")
    }

    val fields = linkedMapOf(
      "model" to request.model,
      "response_format" to "verbose_json",
      "temperature" to request.temperature.toString(),
    )
    request.language?.takeIf { it.isNotBlank() }?.let { fields["language"] = it }
    request.prompt?.takeIf { it.isNotBlank() }?.let { fields["prompt"] = it.take(PROMPT_MAX_CHARS) }

    val body = http.postAudio(
      url = "$BASE_URL/audio/transcriptions",
      headers = headers(),
      fields = fields,
      file = file,
      fileMime = mime,
      readTimeoutMillis = READ_TIMEOUT_MS,
      onProgress = onProgress,
    )
    return VerboseJson.parse(body)
  }

  private fun headers(): Map<String, String> = mapOf("Authorization" to "Bearer $apiKey")

  companion object {
    const val ID = "groq"
    const val BASE_URL = "https://api.groq.com/openai/v1"

    /** Il tetto del piano gratuito. Quello a pagamento arriva a cento megabyte. */
    const val FREE_TIER_LIMIT_BYTES = 25L * 1024 * 1024
    const val DEV_TIER_LIMIT_BYTES = 100L * 1024 * 1024

    /** Il prompt di Whisper vale al massimo 224 token: ottocento caratteri di nomi propri ci stanno. */
    const val PROMPT_MAX_CHARS = 800

    /**
     * Groq risponde in pochi secondi anche per venti minuti di audio, ma il caricamento sulla rete
     * mobile puo' essere lento e il tempo di lettura parte da quando l'ultimo byte e' uscito.
     */
    const val READ_TIMEOUT_MS = 5 * 60 * 1000

    /** Il modello che vogliamo, quando c'e'; altrimenti il primo whisper del catalogo. */
    val PREFERRED_MODELS = listOf("whisper-large-v3-turbo", "whisper-large-v3")

    fun pickModel(available: List<String>): String? =
      PREFERRED_MODELS.firstOrNull { it in available } ?: available.firstOrNull { it.contains("whisper", true) }
  }
}

/**
 * Un endpoint compatibile con l'API OpenAI: di regola il computer di casa con WhisperX.
 *
 * Nessun tetto e nessun taglio: la macchina che sta dall'altra parte macina un'ora di audio senza
 * battere ciglio, e mandargliela intera evita sia le cuciture sia gli errori che le cuciture possono
 * introdurre. Il prezzo e' che bisogna aspettarla, e l'attesa puo' durare mezz'ora.
 */
class OpenAiCompatProvider(
  private val http: TranscriptionHttp,
  baseUrl: String,
  private val token: String? = null,
  private val readTimeoutMillis: Int = 0,
) : TranscriptionProvider {

  /** Normalizzato una volta: chi digita l'indirizzo mette o non mette la barra e il `/v1`. */
  private val base: String = normalize(baseUrl)

  override val id: String = ID

  override val capabilities: TranscriptionCapabilities = TranscriptionCapabilities(
    maxUploadBytes = null,
    supportsSegments = true,
    needsChunking = false,
  )

  override suspend fun listModels(): List<String> =
    VerboseJson.parseModels(http.getJson("$base/models", headers()))

  override suspend fun health(): EndpointHealth {
    val started = System.currentTimeMillis()
    // Prima `/health`, che i server pensati per questo espongono e risponde subito; se non c'e' si
    // ripiega su `/models`, che qualsiasi endpoint compatibile ha.
    val health = runCatching { http.getJson("${base.removeSuffix("/v1")}/health", headers()) }.getOrNull()
    val models = runCatching { listModels() }.getOrElse { error ->
      return EndpointHealth(
        reachable = false,
        latencyMs = System.currentTimeMillis() - started,
        detail = TranscriptionError.from(error).message,
      )
    }
    val serverName = (health as? kotlinx.serialization.json.JsonObject)
      ?.get("model")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    return EndpointHealth(
      reachable = true,
      latencyMs = System.currentTimeMillis() - started,
      models = models,
      serverName = serverName,
    )
  }

  override suspend fun transcribe(
    file: File,
    mime: String,
    request: TranscribeRequest,
    onProgress: (UploadProgress) -> Unit,
  ): TranscriptResult {
    val fields = linkedMapOf(
      "model" to request.model,
      "response_format" to "verbose_json",
      "temperature" to request.temperature.toString(),
      // Certi server danno i tempi solo se glieli si chiede; quelli che non capiscono il campo lo ignorano.
      "timestamp_granularities[]" to "segment",
    )
    request.language?.takeIf { it.isNotBlank() }?.let { fields["language"] = it }
    request.prompt?.takeIf { it.isNotBlank() }?.let { fields["prompt"] = it }

    val body = http.postAudio(
      url = "$base/audio/transcriptions",
      headers = headers(),
      fields = fields,
      file = file,
      fileMime = mime,
      readTimeoutMillis = readTimeoutMillis,
      onProgress = onProgress,
    )
    return VerboseJson.parse(body)
  }

  private fun headers(): Map<String, String> =
    token?.takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

  companion object {
    const val ID = "custom"

    /**
     * Da quello che si scrive nel campo a un indirizzo che funziona.
     *
     * Chi incolla un indirizzo mette `http://192.168.1.10:8765`, oppure lo stesso con la barra, o
     * gia' con `/v1`. Tutti e tre devono funzionare: chiedere la forma esatta e' chiedere all'utente
     * di indovinare la nostra convenzione.
     */
    fun normalize(raw: String): String {
      val trimmed = raw.trim().trimEnd('/')
      val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
      return if (withScheme.endsWith("/v1")) withScheme else "$withScheme/v1"
    }
  }
}
