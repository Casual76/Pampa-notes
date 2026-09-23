package dev.pampa.pampanotes.core.archive

import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.CompanionAuth
import dev.pampa.pampanotes.core.transcription.ComputerAuth
import dev.pampa.pampanotes.core.transcription.EndpointResolver
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.core.transcription.RemoteJobPoller
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import dev.pampa.pampanotes.core.transcription.TranscriptionHttp
import dev.pampa.pampanotes.core.transcription.call
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Le date di un file dell'archivio, come le legge il computer di casa: la nascita e l'ultima
 * modifica di un `.sdocx` (da `end_tag.bin`), il momento della registrazione di un audio (dai
 * metadati, con ffprobe). Microsecondi epoch, null quando il file non lo dice.
 */
data class FileMeta(
  val sha256: String,
  val kind: String,
  val createdUs: Long?,
  val modifiedUs: Long?,
  val recordedUs: Long?,
)

/** Com'e' andata una domanda al computer. */
sealed interface FileMetaResult {
  data class Found(val meta: FileMeta) : FileMetaResult

  /** Il computer ha risposto: quel file non ce l'ha. Non cambiera' riprovando. */
  data object Unknown : FileMetaResult

  /** Nessun computer configurato: non c'e' nessuno a cui chiedere, ne' adesso ne' dopo. */
  data object Unconfigured : FileMetaResult

  /** Spento, fuori rete, o un companion di prima che non sa rispondere: si riprova piu' avanti. */
  data object Unavailable : FileMetaResult
}

/**
 * `GET /v1/files/<sha256>/meta` sul computer di casa: le date di un file che qui non c'e'.
 *
 * Serve al giro che rida' le date vere alle note importate prima che l'app le sapesse leggere
 * ([dev.pampa.pampanotes.core.importing.RealDatesBackfill]): quando il `.sdocx` e' stato tolto dal
 * telefono con «Libera spazio», o e' arrivato dal sync, scaricarlo per leggere 148 byte sarebbe
 * assurdo. Il computer li legge da se' e risponde con un JSON.
 *
 * Solo il proprietario: le credenziali sono quelle dell'archivio ([ComputerAuth]). Un companion
 * di prima non ha l'endpoint e non lo dice in `/health` (`features` senza `file_meta`): vale
 * [FileMetaResult.Unavailable], e il giro riprova al prossimo avvio, quando il PC sara' aggiornato.
 */
@Singleton
class FileMetaApi @Inject constructor(
  private val http: TranscriptionHttp,
  private val resolver: EndpointResolver,
  private val settingsStore: PampaSettingsStore,
  private val auth: ComputerAuth,
) {

  /**
   * Una sessione di domande: `/health` una volta sola, poi una richiesta per file. Null se non c'e'
   * un computer configurato.
   */
  suspend fun open(): Session? {
    val settings = settingsStore.current()
    val endpoint = resolver.resolve(settings.endpointUrl, settings.endpointRemoteUrl) ?: return null
    return Session(OpenAiCompatProvider.normalize(endpoint.url))
  }

  inner class Session internal constructor(private val base: String) {
    private var supported: Boolean? = null

    /** Vero se il computer risponde e sa rispondere a `/meta`. Chiesto una volta per sessione. */
    suspend fun supported(): Boolean {
      supported?.let { return it }
      val health = try {
        http.getJson("${base.removeSuffix("/v1")}/health", emptyMap(), readTimeoutMillis = TIMEOUT_MS)
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        null
      }
      return FileMetaJson.hasFeature(health, FEATURE).also { supported = it }
    }

    suspend fun meta(sha256: String): FileMetaResult {
      if (!supported()) return FileMetaResult.Unavailable
      return try {
        val body = auth.call(
          isUnauthorized = { TranscriptionError.from(it) is TranscriptionError.Unauthorized },
          rejected = { TranscriptionError.Unauthorized(CompanionAuth.ACCOUNT_REJECTED) },
        ) { bearer ->
          val headers = bearer?.takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
          http.getJson("$base/files/$sha256/meta", headers, readTimeoutMillis = TIMEOUT_MS)
        }
        FileMetaJson.parse(body, sha256)?.let { FileMetaResult.Found(it) } ?: FileMetaResult.Unknown
      } catch (error: Throwable) {
        if (error is CancellationException) throw error
        if (RemoteJobPoller.httpCode(error) == 404) FileMetaResult.Unknown else FileMetaResult.Unavailable
      }
    }
  }

  private companion object {
    const val FEATURE = "file_meta"

    /** Domande piccole: un PC che non risponde in otto secondi e' un PC da riprovare dopo. */
    const val TIMEOUT_MS = 8_000
  }
}

/** Il JSON del companion. Puro: si prova in JVM. */
object FileMetaJson {

  /**
   * `features` di `/health`: una lista di nomi (`["file_meta", ...]`) o, per tolleranza, un oggetto
   * di interruttori (`{"file_meta": true}`).
   */
  fun hasFeature(health: JsonElement?, feature: String): Boolean {
    val features = (health as? JsonObject)?.get("features") ?: return false
    return when (features) {
      is JsonArray -> features.any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == feature }
      is JsonObject -> (features[feature] as? JsonPrimitive)?.booleanOrNull == true
      else -> false
    }
  }

  /** La risposta di `/meta`. Null se non e' quella, o se parla di un altro file. */
  fun parse(body: JsonElement?, expectedSha: String): FileMeta? {
    val obj = body as? JsonObject ?: return null
    val sha = (obj["sha256"] as? JsonPrimitive)?.content ?: expectedSha
    if (!sha.equals(expectedSha, ignoreCase = true)) return null
    return FileMeta(
      sha256 = sha,
      kind = (obj["kind"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: "other",
      createdUs = obj.long("created_us"),
      modifiedUs = obj.long("modified_us"),
      recordedUs = obj.long("recorded_us"),
    )
  }

  private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() }
}
