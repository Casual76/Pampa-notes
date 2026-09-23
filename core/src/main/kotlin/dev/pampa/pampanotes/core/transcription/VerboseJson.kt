package dev.pampa.pampanotes.core.transcription

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToLong

/**
 * La risposta `verbose_json` di un servizio compatibile OpenAI.
 *
 * Scritto a mano invece che con `@Serializable` perche' i due servizi che ci interessano non
 * rispondono identici: WhisperX aggiunge le parole e talvolta chi parla, Groq no; i tempi sono
 * secondi in virgola mobile da una parte e a volte stringhe dall'altra. Un parser indulgente qui
 * costa trenta righe e toglie una classe intera di guasti in produzione.
 */
object VerboseJson {

  /**
   * @param fallbackText usato quando la risposta non porta segmenti: certi server rispondono con il
   *   solo `text`, e mezzo risultato vale piu' di un errore.
   */
  fun parse(body: JsonElement?): TranscriptResult {
    val root = body as? JsonObject ?: throw TranscriptionError.Parse("risposta non riconosciuta")

    val text = root["text"].asString()?.trim().orEmpty()
    val language = root["language"].asString()?.takeIf { it.isNotBlank() }
    val durationMs = root["duration"].asDouble()?.let { (it * 1000).roundToLong() }

    val segments = (root["segments"] as? JsonArray)
      ?.mapNotNull { element -> parseSegment(element) }
      .orEmpty()

    if (text.isEmpty() && segments.isEmpty()) {
      // Il JSON e' a posto, e' l'audio che non aveva niente da dire: lo si dice cosi'.
      throw TranscriptionError.NoSpeech("il servizio non ha riconosciuto parole")
    }

    // Su cosa ha girato e quanto ci ha messo, se il computer di casa lo dice: finisce nelle
    // statistiche della trascrizione (vedi [ServerReports]). Groq non lo dice, e non succede niente.
    serverReport(root)?.let(ServerReports::publish)

    return TranscriptResult(
      // Quando i segmenti ci sono, il testo si ricompone da loro: e' l'unico modo di essere sicuri
      // che testo e tempi raccontino la stessa cosa dopo che i segmenti sono stati filtrati.
      text = if (segments.isNotEmpty()) segments.joinToString(" ") { it.text.trim() }.trim() else text,
      segments = segments,
      language = language,
      durationMs = durationMs ?: segments.maxOfOrNull { it.endMs },
      // I due campi del companion che lavora da se': Groq e un companion vecchio non li mandano.
      archived = root["archived"].asString()?.lowercase() == "true",
      serverChunks = root["chunks"].asDouble()?.toInt()?.takeIf { it > 0 },
    )
  }

  /**
   * I tre campi che il companion aggiunge alla risposta: `processing_s`, `audio_s`, `device_used`.
   * Null quando non ce n'e' nessuno; tollerante a numeri scritti come stringhe e a valori
   * impossibili (negativi, non finiti), che si lasciano fuori invece di falsare una media.
   */
  fun serverReport(root: JsonObject): ServerReport? {
    fun seconds(key: String): Long? = root[key].asDouble()
      ?.takeIf { it.isFinite() && it >= 0.0 }
      ?.let { (it * 1000).roundToLong() }
    val report = ServerReport(
      processingMs = seconds("processing_s"),
      audioMs = seconds("audio_s"),
      device = root["device_used"].asString()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
    )
    return report.takeIf { it.processingMs != null || it.audioMs != null || it.device != null }
  }

  private fun parseSegment(element: JsonElement): RawSegment? {
    val obj = element as? JsonObject ?: return null
    val text = obj["text"].asString()?.trim().orEmpty()
    if (text.isEmpty()) return null
    val start = obj["start"].asDouble() ?: return null
    val end = obj["end"].asDouble() ?: start
    return RawSegment(
      startMs = (start * 1000).roundToLong(),
      endMs = (end * 1000).roundToLong(),
      text = text,
      noSpeechProb = obj["no_speech_prob"].asDouble()?.toFloat(),
      avgLogProb = obj["avg_logprob"].asDouble()?.toFloat(),
      words = parseWords(obj["words"]),
    )
  }

  /**
   * Le parole di un segmento, quando ci sono.
   *
   * Si scartano quelle senza tempi: l'allineamento di WhisperX non trova il token di certi numeri
   * e sigle e le restituisce senza `start`, e una parola senza tempi in mezzo alla frase farebbe
   * saltare il cursore. Meglio una parola che non si accende di una che si accende a caso.
   */
  private fun parseWords(element: JsonElement?): List<RawWord> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull { item ->
      val obj = item as? JsonObject ?: return@mapNotNull null
      val text = (obj["word"] ?: obj["text"]).asString()?.trim().orEmpty()
      if (text.isEmpty()) return@mapNotNull null
      val start = obj["start"].asDouble() ?: return@mapNotNull null
      val end = obj["end"].asDouble() ?: return@mapNotNull null
      RawWord(startMs = (start * 1000).roundToLong(), endMs = (end * 1000).roundToLong(), text = text)
    }
  }

  /** L'elenco dei modelli di `GET /models`, in entrambe le forme che i server usano. */
  fun parseModels(body: JsonElement?): List<String> {
    val root = body ?: return emptyList()
    val array = when {
      root is JsonArray -> root
      root is JsonObject && root["data"] is JsonArray -> root["data"]!!.jsonArray
      root is JsonObject && root["models"] is JsonArray -> root["models"]!!.jsonArray
      else -> return emptyList()
    }
    return array.mapNotNull { element ->
      when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> element["id"].asString() ?: element["name"].asString()
        else -> null
      }
    }.filter { it.isNotBlank() }.distinct()
  }

  private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

  /** Tollera sia `1.5` sia `"1.5"`: WhisperX serializza i tempi in entrambi i modi a seconda della versione. */
  private fun JsonElement?.asDouble(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
  }
}
