package dev.pampa.pampanotes.core.transcription

import dev.antigravity.fluidengine.ai.net.AiError
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Com'e' andato il collegamento del computer all'account. */
sealed interface CompanionBindResult {
  /** Il computer adesso e' di [owner]: da qui in poi entra chi ha quell'account. */
  data class Bound(val owner: String) : CompanionBindResult

  /** Qui non si e' entrati con Google, o il Worker non da' biglietti: il computer resta com'era. */
  data object NotSignedIn : CompanionBindResult

  /** Il computer e' gia' di un altro account (409). Si cambia solo a mano, nel suo config.json. */
  data object Taken : CompanionBindResult

  /** Il codice e' scaduto o gia' usato, o l'indice non conferma l'account. [detail] per il registro. */
  data class Rejected(val detail: String) : CompanionBindResult

  /** Il computer non risponde, o non risponde il suo indice. */
  data class Unreachable(val detail: String) : CompanionBindResult
}

/**
 * Fa diventare il computer di casa dell'account con cui si e' entrati qui.
 *
 * Il companion appena installato non sa di chi e': gli mancano `owner` e `index_url` nel suo
 * `config.json`, e scriverli a mano voleva dire sapere cos'e' un Worker. Il QR della sua pagina porta
 * un codice di collegamento (`EndpointLink.bindCode`, dieci minuti, una volta sola) finche' non e' di
 * nessuno; dopo «Collega» questo lo rimanda al companion (`POST /v1/pair/bind`) con l'account e
 * l'indirizzo dell'indice, e con il biglietto per il PC come bearer. Il companion chiede all'indice se
 * il biglietto e' davvero di quell'account, e solo allora scrive le due righe.
 *
 * Verso il PC non va il token del sync, come sempre (vedi [ComputerAuth]): va il biglietto, e senza un
 * biglietto non si prova nemmeno — un codice scritto a mano non dice di chi e' il telefono.
 */
@Singleton
class CompanionBinder internal constructor(
  private val account: suspend () -> Account?,
  private val bearer: suspend () -> String?,
  private val post: suspend (url: String, bearer: String, body: String) -> JsonElement?,
) {

  @Inject constructor(settingsStore: PampaSettingsStore, http: TranscriptionHttp, auth: ComputerAuth) : this(
    account = {
      val settings = settingsStore.current()
      val index = settings.syncServerUrl.trim()
      val owner = settings.syncAccount.trim()
      if (index.isEmpty() || owner.isEmpty()) null else Account(owner, index)
    },
    bearer = { auth.bearer() },
    post = { url, bearer, body ->
      http.postJson(url, headers = mapOf("Authorization" to "Bearer $bearer"), body = body, readTimeoutMillis = 20_000)
    },
  )

  /** Chi e' entrato qui, e dove sta il suo indice. */
  data class Account(val owner: String, val indexUrl: String)

  /**
   * Prova l'indirizzo di casa e poi quello di fuori, se il primo non risponde: il QR si inquadra di
   * solito in casa, ma il telefono puo' essere su un'altra rete.
   */
  suspend fun bind(urls: List<String>, code: String): CompanionBindResult {
    val who = account() ?: return CompanionBindResult.NotSignedIn
    val ticket = bearer()?.takeIf { CompanionAuth.isTicket(it) } ?: return CompanionBindResult.NotSignedIn
    val body = requestBody(code, who.owner, normalizeWorker(who.indexUrl))
    var last: CompanionBindResult = CompanionBindResult.Unreachable("nessun indirizzo")
    for (url in urls.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()) {
      last = try {
        val answer = post("$url$BIND_PATH", ticket, body)
        val owner = ((answer as? JsonObject)?.get("owner") as? JsonPrimitive)?.content ?: who.owner
        return CompanionBindResult.Bound(owner)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        classify(error)
      }
      if (last !is CompanionBindResult.Unreachable) return last
    }
    return last
  }

  companion object {
    const val BIND_PATH = "/v1/pair/bind"

    internal fun requestBody(code: String, owner: String, indexUrl: String): String = buildJsonObject {
      put("code", code)
      put("owner", owner)
      put("index_url", indexUrl)
    }.toString()

    /** Dall'errore alla risposta: 409 e' «di un altro», i rifiuti sono rifiuti, il resto e' la rete. */
    internal fun classify(error: Throwable): CompanionBindResult {
      val mapped = TranscriptionError.from(error)
      val badRequest = generateSequence(error as Throwable?) { it.cause }.filterIsInstance<AiError.BadRequest>().firstOrNull()
      return when {
        badRequest?.code == 409 -> CompanionBindResult.Taken
        mapped is TranscriptionError.Unauthorized || badRequest != null -> CompanionBindResult.Rejected(mapped.message.orEmpty())
        mapped is TranscriptionError.Server && mapped.httpCode == 502 -> CompanionBindResult.Unreachable(mapped.message.orEmpty())
        mapped.retryable -> CompanionBindResult.Unreachable(mapped.message.orEmpty())
        else -> CompanionBindResult.Rejected(mapped.message.orEmpty())
      }
    }

    /** Come `SyncApi.normalize`: il companion vuole l'indirizzo con lo schema. */
    internal fun normalizeWorker(raw: String): String {
      val trimmed = raw.trim().trimEnd('/')
      return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }
  }
}
