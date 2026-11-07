package dev.pampa.pampanotes.core.spending

import dev.pampa.pampanotes.core.transcription.OpenRouterTranscriptionProvider
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import dev.pampa.pampanotes.core.transcription.TranscriptionHttp
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class OpenRouterCostMetadata(
  private val http: TranscriptionHttp,
  private val baseUrl: String = OpenRouterTranscriptionProvider.BASE_URL,
) {
  suspend fun prices(key: String, model: String, now: Long): ModelPrice {
    val catalogue = http.getJson("$baseUrl/models?output_modalities=transcription", headers(key))
    val root = catalogue as? JsonObject
    val item = (root?.get("data") as? JsonArray)?.filterIsInstance<JsonObject>()
      ?.firstOrNull { it.text("id") == model }
      ?: throw TranscriptionError.UnknownModel(model, "Transcription model unavailable")
    val outputs = (item["architecture"] as? JsonObject)?.get("output_modalities") as? JsonArray
    if (outputs?.any { (it as? JsonPrimitive)?.contentOrNull == "transcription" } != true) {
      throw TranscriptionError.UnknownModel(model, "Not a transcription model")
    }
    // STT cannot force a provider. Include every endpoint; one bad price makes the bound unknown.
    val endpoints = http.getJson("$baseUrl/models/$model/endpoints", headers(key))
    return parsePrice(model, item, endpoints, now)
  }

  suspend fun quota(key: String, now: Long): KeyQuota = parseQuota(http.getJson("$baseUrl/key", headers(key)), fingerprint(key), now)

  companion object {
    private fun headers(key: String) = mapOf("Authorization" to "Bearer $key")
    fun fingerprint(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }

    // Official STT documentation explicitly identifies Whisper as duration billed. Never infer
    // a universal unit from pricing.prompt, tokenizer, context_length, or a zero completion price.
    private val documentedDurationModels = setOf("openai/whisper-1", "openai/whisper-large-v3", "openai/whisper-large-v3-turbo")
    private val documentedTokenModels = setOf("openai/gpt-4o-transcribe", "openai/gpt-4o-mini-transcribe", "google/gemini-3.5-transcribe")

    fun parsePrice(model: String, catalogue: JsonObject, endpointsBody: JsonElement?, now: Long): ModelPrice {
      val declared = (catalogue["pricing"] as? JsonObject)?.text("unit")
      val unit = when {
        declared in setOf("per_second", "audio_second", "persecaudio") -> PriceUnit.AUDIO_SECOND
        declared in setOf("token", "per_token") -> PriceUnit.TOKEN
        model in documentedDurationModels -> PriceUnit.AUDIO_SECOND
        model in documentedTokenModels -> PriceUnit.TOKEN
        else -> PriceUnit.UNKNOWN
      }
      val endpoints = ((endpointsBody as? JsonObject)?.get("data") as? JsonObject)?.get("endpoints") as? JsonArray
      val prices = endpoints?.map { (it as? JsonObject)?.get("pricing") as? JsonObject }.orEmpty()
      val rates = prices.map { Usd.amount(it?.text("prompt")) }
      val outputs = prices.map { Usd.amount(it?.text("completion")) }
      val additionalFees = prices.any { price ->
        price?.any { (name, value) -> name !in setOf("prompt", "completion", "discount", "unit") &&
          (Usd.amount((value as? JsonPrimitive)?.contentOrNull)?.signum() != 0) } == true
      }
      val complete = prices.isNotEmpty() && rates.all { it != null } && outputs.all { it != null } && !additionalFees &&
        (unit != PriceUnit.AUDIO_SECOND || outputs.all { it?.signum() == 0 })
      return ModelPrice(model, unit, rates.filterNotNull().minOrNull()?.toPlainString(),
        rates.filterNotNull().maxOrNull()?.toPlainString(), outputs.filterNotNull().maxOrNull()?.toPlainString(), now, complete)
    }

    fun parseQuota(body: JsonElement?, fingerprint: String, now: Long): KeyQuota {
      val data = (body as? JsonObject)?.get("data") as? JsonObject ?: throw TranscriptionError.Parse("Invalid key status")
      fun number(name: String): String? = data.text(name)?.takeIf { runCatching { java.math.BigDecimal(it) }.isSuccess }
      val effective = data["effective_budget"] as? JsonObject
      return KeyQuota(fingerprint, now, number("limit"), number("limit_remaining"), data.text("limit_reset"),
        number("usage"), number("usage_daily"), number("usage_weekly"), number("usage_monthly"),
        effective?.text("limit_usd"), effective?.text("remaining_usd"), effective?.text("resets_at"))
    }

    fun actualCost(body: JsonElement?): String? = (((body as? JsonObject)?.get("usage") as? JsonObject)
      ?.get("cost") as? JsonPrimitive)?.contentOrNull?.takeIf { Usd.amount(it) != null }
  }
}

private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
