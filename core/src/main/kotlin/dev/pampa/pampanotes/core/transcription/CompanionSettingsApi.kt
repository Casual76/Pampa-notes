package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** La scheda video del computer di casa, come la descrive `/health`. */
data class CompanionGpu(val name: String, val totalGb: Double?, val freeGb: Double?)

/** Quanta memoria video il companion pensa di usare, e con che cosa. */
data class VramEstimate(
  val estimateGb: Double,
  /** Il tetto: quello misurato (automatico) o quello indicato a mano. Null se il companion non lo dice. */
  val budgetGb: Double? = null,
  val batchSize: Int? = null,
  val model: String? = null,
  val computeType: String? = null,
  val mode: VramMode? = null,
) {
  /** La stima non ci sta: il companion dimezzera' i lotti, e alla peggio passera' al processore. */
  val exceedsBudget: Boolean get() = budgetGb != null && estimateGb > budgetGb
}

enum class VramMode(val wire: String) {
  /** La misura il computer, a ogni avvio del modello. */
  AUTO("auto"),

  /** La indica l'utente: per un PC dove la scheda serve anche ad altro. */
  MANUAL("manual");

  companion object {
    fun from(value: String?): VramMode? = entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
  }
}

/**
 * Le impostazioni del companion che l'app sa cambiare.
 *
 * Quelle che non sa cambiare ([computeType], [batchSizeMax], [idleMinutes]) si rimandano come sono
 * arrivate: un POST che le omettesse potrebbe riportarle al default su un companion che non fa merge.
 */
data class CompanionSettings(
  val model: String,
  val computeType: String? = null,
  val vramMode: VramMode = VramMode.AUTO,
  val vramGb: Double? = null,
  val batchSizeMax: Int? = null,
  val idleMinutes: Int? = null,
)

/** Cosa si sa del computer di casa, per la pagina Trascrizione. */
sealed interface CompanionStatus {
  /** Nessun indirizzo: non c'e' niente da chiedere. */
  data object Unconfigured : CompanionStatus

  /** Non risponde: spento, fuori rete, o Tailscale giu'. */
  data object Unreachable : CompanionStatus

  /** Risponde, ma e' un companion di prima: `/health` non parla di memoria video. */
  data object Unsupported : CompanionStatus

  /**
   * Risponde e dice tutto.
   *
   * @param gpu null quando trascrive col processore.
   * @param settings null quando le impostazioni non si possono leggere: chi parla non e' il
   *   proprietario (un ospite, un codice sbagliato), o l'amministrazione non c'e'. La scheda si
   *   mostra lo stesso, senza niente da cambiare ([canEdit] falso).
   */
  data class Ready(
    val gpu: CompanionGpu?,
    val vram: VramEstimate?,
    val settings: CompanionSettings?,
    val canEdit: Boolean,
    /** «Chi parla»: il computer sa separare le voci ([CompanionFeatures.DIARIZE], cioe' ha il token). */
    val diarize: Boolean = false,
  ) : CompanionStatus
}

/** Com'e' andato un salvataggio. */
sealed interface CompanionSaveResult {
  data class Saved(val estimate: VramEstimate?) : CompanionSaveResult

  /** Il companion ha detto di no: le credenziali non sono del proprietario. */
  data object Forbidden : CompanionSaveResult

  data class Failed(val code: String) : CompanionSaveResult
}

/**
 * Le impostazioni della memoria video del computer di casa, lette e scritte dall'app.
 *
 * Il companion le tiene nel suo `config.json`; il posto in cui si decide pero' e' il telefono,
 * perche' e' li' che si vede il risultato — una lezione trascritta in dieci minuti o in quaranta —
 * e sul PC non c'e' una finestra da aprire. Tre chiamate:
 *
 * - `GET /health` (niente credenziali): `gpu` e `vram`, per la scheda «RTX 4070 Ti · 12 GB» e la
 *   stima di adesso. Un companion vecchio non li ha: la sezione non si mostra.
 * - `GET/POST /v1/admin/settings`: leggere e salvare, solo il proprietario.
 * - `POST /v1/admin/estimate`: la stessa stima per delle impostazioni non ancora salvate, cosi' la
 *   pagina dice «circa 3,1 GB» mentre si sceglie, prima di scrivere niente.
 *
 * Le credenziali sono quelle di una trascrizione ([ComputerAuth]: il biglietto dell'account o il
 * codice scritto a mano), e l'indirizzo lo sceglie [EndpointResolver] come per ogni altra chiamata.
 * Tutto quello che arriva e' tollerante: un campo che manca e' un campo che non si mostra.
 */
@Singleton
class CompanionSettingsApi @Inject constructor(
  private val http: TranscriptionHttp,
  private val resolver: EndpointResolver,
  private val settingsStore: PampaSettingsStore,
  private val auth: ComputerAuth,
) {

  suspend fun status(): CompanionStatus {
    val base = base() ?: return CompanionStatus.Unconfigured
    val health = try {
      http.getJson("${root(base)}/health", emptyMap(), readTimeoutMillis = TIMEOUT_MS)
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      return CompanionStatus.Unreachable
    }
    val parsed = CompanionSettingsJson.parseHealth(health) ?: return CompanionStatus.Unsupported

    val settings = runCatching { authorized { headers -> http.getJson("$base/admin/settings", headers, readTimeoutMillis = TIMEOUT_MS) } }
      .onFailure { if (it is CancellationException) throw it }
      .getOrNull()
      ?.let(CompanionSettingsJson::parseSettings)
    return CompanionStatus.Ready(
      gpu = parsed.gpu, vram = parsed.vram, settings = settings, canEdit = settings != null,
      diarize = CompanionFeatures.DIARIZE in OpenAiCompatProvider.parseFeatures(health),
    )
  }

  /** La stima per delle impostazioni non salvate. Null se il companion non sa farla. */
  suspend fun estimate(settings: CompanionSettings): VramEstimate? {
    val base = base() ?: return null
    return runCatching {
      authorized { headers -> http.postJson("$base/admin/estimate", headers, CompanionSettingsJson.encode(settings), readTimeoutMillis = TIMEOUT_MS) }
    }.onFailure { if (it is CancellationException) throw it }
      .getOrNull()
      ?.let(CompanionSettingsJson::parseEstimate)
  }

  suspend fun save(settings: CompanionSettings): CompanionSaveResult {
    val base = base() ?: return CompanionSaveResult.Failed("empty")
    return try {
      val body = authorized { headers -> http.postJson("$base/admin/settings", headers, CompanionSettingsJson.encode(settings), readTimeoutMillis = TIMEOUT_MS) }
      CompanionSaveResult.Saved(CompanionSettingsJson.parseEstimate(body))
    } catch (error: Throwable) {
      if (error is CancellationException) throw error
      when (val mapped = TranscriptionError.from(error)) {
        is TranscriptionError.Unauthorized -> CompanionSaveResult.Forbidden
        else -> CompanionSaveResult.Failed(mapped.code)
      }
    }
  }

  private suspend fun base(): String? {
    val settings = settingsStore.current()
    val endpoint = resolver.resolve(settings.endpointUrl, settings.endpointRemoteUrl) ?: return null
    return OpenAiCompatProvider.normalize(endpoint.url)
  }

  private fun root(base: String) = base.removeSuffix("/v1")

  private suspend fun <T> authorized(block: suspend (Map<String, String>) -> T): T = auth.call(
    isUnauthorized = { TranscriptionError.from(it) is TranscriptionError.Unauthorized },
    rejected = { TranscriptionError.Unauthorized(CompanionAuth.ACCOUNT_REJECTED) },
  ) { bearer ->
    block(bearer?.takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())
  }

  private companion object {
    /** Domande piccole: se il PC non risponde in otto secondi, la pagina dice che non risponde. */
    const val TIMEOUT_MS = 8_000
  }
}

/**
 * Il JSON del companion, letto e scritto. Puro: si prova in JVM.
 *
 * Ogni lettura accetta la forma piatta e quella dentro un contenitore (`{"settings": {...}}`,
 * `{"vram": {...}}`, `{"estimate": {...}}`): il companion lo scrive un altro agente, e una forma
 * sola sarebbe una scommessa.
 */
object CompanionSettingsJson {

  data class Health(val gpu: CompanionGpu?, val vram: VramEstimate?)

  /** `gpu` e `vram` da `/health`. Null quando `vram` non c'e': un companion che non sa di memoria video. */
  fun parseHealth(element: JsonElement?): Health? {
    val root = element as? JsonObject ?: return null
    val vramElement = root["vram"]
    if (vramElement !is JsonObject) return null
    return Health(gpu = parseGpu(root["gpu"]), vram = parseEstimate(vramElement))
  }

  fun parseGpu(element: JsonElement?): CompanionGpu? {
    val gpu = element as? JsonObject ?: return null
    val name = gpu.string("name")?.takeIf { it.isNotBlank() } ?: return null
    return CompanionGpu(name = name, totalGb = gpu.double("total_gb"), freeGb = gpu.double("free_gb"))
  }

  fun parseEstimate(element: JsonElement?): VramEstimate? {
    val root = element as? JsonObject ?: return null
    val obj = listOf("vram", "estimate").firstNotNullOfOrNull { root[it] as? JsonObject } ?: root
    val estimate = obj.double("estimate_gb") ?: return null
    return VramEstimate(
      estimateGb = estimate,
      budgetGb = obj.double("budget_gb"),
      batchSize = obj.int("batch_size"),
      model = obj.string("model"),
      computeType = obj.string("compute_type"),
      mode = VramMode.from(obj.string("mode") ?: obj.string("vram_mode")),
    )
  }

  fun parseSettings(element: JsonElement?): CompanionSettings? {
    val root = element as? JsonObject ?: return null
    val obj = root["settings"] as? JsonObject ?: root
    val model = obj.string("model")?.takeIf { it.isNotBlank() } ?: return null
    return CompanionSettings(
      model = model,
      computeType = obj.string("compute_type"),
      vramMode = VramMode.from(obj.string("vram_mode")) ?: VramMode.AUTO,
      vramGb = obj.double("vram_gb"),
      batchSizeMax = obj.int("batch_size_max"),
      idleMinutes = obj.int("idle_minutes"),
    )
  }

  /** Il corpo di un POST: i campi che ci sono. In automatico `vram_gb` non si manda. */
  fun encode(settings: CompanionSettings): String = buildJsonObject {
    put("model", settings.model)
    settings.computeType?.let { put("compute_type", it) }
    put("vram_mode", settings.vramMode.wire)
    if (settings.vramMode == VramMode.MANUAL) settings.vramGb?.let { put("vram_gb", it) }
    settings.batchSizeMax?.let { put("batch_size_max", it) }
    settings.idleMinutes?.let { put("idle_minutes", it) }
  }.toString()

  private fun JsonObject.primitive(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
  private fun JsonObject.string(key: String): String? = primitive(key)?.content
  private fun JsonObject.double(key: String): Double? = primitive(key)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
  private fun JsonObject.int(key: String): Int? = primitive(key)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
}
