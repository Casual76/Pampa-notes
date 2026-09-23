package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Chi da' le credenziali per il computer di casa.
 *
 * Un'interfaccia perche' le chiamate al companion non devono sapere da dove arrivano: dall'account
 * ([ComputerAuth]) o da un codice scritto a mano e non ancora salvato ([fixed], la prova della
 * connessione nelle impostazioni).
 */
interface CompanionAuth {
  /** Il bearer da mandare, o null per non mandarne nessuno. */
  suspend fun bearer(): String?

  /** Il companion ha rifiutato quello che si e' mandato: al prossimo giro se ne chiede uno nuovo. */
  fun invalidate()

  /**
   * Il companion ha rifiutato anche un biglietto appena fatto. Chi tiene un codice scritto a mano lo
   * usera' per un po' al posto dei biglietti: e' il caso di un companion vecchio, che conosce solo il
   * token del suo `config.json`.
   */
  fun ticketRejected() = Unit

  companion object {
    fun fixed(token: String?): CompanionAuth = object : CompanionAuth {
      override suspend fun bearer(): String? = token?.takeIf { it.isNotBlank() }
      override fun invalidate() = Unit
    }

    /** I biglietti del Worker cominciano cosi'; i codici del `config.json` e gli ospiti (`pg_`) no. */
    fun isTicket(bearer: String?): Boolean = bearer?.startsWith(TICKET_PREFIX) == true

    const val TICKET_PREFIX = "pt_"

    /** La frase dopo il secondo rifiuto: il biglietto e' buono, e' il PC che aspetta un altro account. */
    const val ACCOUNT_REJECTED = "il computer non riconosce l'account: controlla «owner» nel config.json"
  }
}

/**
 * Una chiamata al companion, con un secondo tentativo se il biglietto viene rifiutato.
 *
 * Un 401 con un biglietto in mano vuol dire quasi sempre che e' scaduto mentre il telefono dormiva,
 * o che il Worker ha cambiato chiave: se ne chiede uno nuovo e si riprova **una volta**. Se il
 * companion rifiuta anche quello il problema non e' il biglietto ma chi il PC crede di servire, e
 * [rejected] lo dice con le parole giuste invece di un «token sbagliato» che fa cercare un token
 * che non c'e'.
 *
 * Con un'eccezione: un companion di prima dei biglietti conosce solo il token del suo `config.json`,
 * e rifiuta qualunque `pt_`. Se c'e' un codice scritto a mano si prova quello, un'ultima volta, e
 * [CompanionAuth.ticketRejected] lo fa preferire per le ore dopo — senza, ogni lezione verso un PC
 * non ancora aggiornato salirebbe tre volte.
 */
suspend fun <T> CompanionAuth.call(
  isUnauthorized: (Throwable) -> Boolean,
  rejected: (Throwable) -> Throwable,
  block: suspend (bearer: String?) -> T,
): T {
  val first = bearer()
  try {
    return block(first)
  } catch (error: Throwable) {
    if (error is CancellationException || !isUnauthorized(error) || !CompanionAuth.isTicket(first)) throw error
    invalidate()
  }
  val second = bearer()
  try {
    return block(second)
  } catch (error: Throwable) {
    if (error is CancellationException || !isUnauthorized(error) || !CompanionAuth.isTicket(second)) throw error
    ticketRejected()
  }
  val third = bearer()
  if (third == null || CompanionAuth.isTicket(third)) throw rejected(CompanionAuthRejected())
  try {
    return block(third)
  } catch (error: Throwable) {
    if (error is CancellationException || !isUnauthorized(error)) throw error
    throw rejected(error)
  }
}

/** Il companion ha detto no a tutto quello che si aveva. */
class CompanionAuthRejected : Exception(CompanionAuth.ACCOUNT_REJECTED)

/** Il biglietto come lo tiene il telefono: il valore, e quando smettera' di valere. */
data class ComputerTicket(val value: String, val expiresAt: Long)

/**
 * Il biglietto per il computer di casa.
 *
 * Il companion vuole sapere chi gli parla, e la risposta giusta e' «l'account Google del
 * proprietario». Mandargli il token di sessione del sync sarebbe la strada corta e sbagliata: in
 * casa viaggia in chiaro su http, e apre tutte le note. Al suo posto un biglietto firmato dal Worker
 * (`POST /v1/computer/ticket`), che vale solo per il companion e dura dodici ore: il PC lo fa
 * verificare al Worker e lo tiene per il tempo in cui vale.
 *
 * Si tiene in memoria e basta — perso col processo, se ne chiede un altro — e si rinnova quando
 * manca meno di un'ora alla scadenza, cosi' una lezione lunga non parte con un biglietto che muore a
 * meta' dell'upload. Senza accesso al sync, o col Worker muto e niente di valido in mano, si usa il
 * codice scritto a mano nelle impostazioni, che puo' anche non esserci.
 */
@Singleton
class ComputerAuth internal constructor(
  private val account: suspend () -> Account?,
  private val manualCode: suspend () -> String?,
  private val fetch: suspend (baseUrl: String, sessionToken: String) -> ComputerTicket,
  private val clock: () -> Long,
) : CompanionAuth {

  @Inject constructor(settingsStore: PampaSettingsStore, http: TranscriptionHttp) : this(
    account = {
      val url = settingsStore.current().syncServerUrl.trim()
      val token = if (url.isEmpty()) null else readSecret { settingsStore.syncToken() }
      if (url.isEmpty() || token.isNullOrBlank()) null else Account(url, token)
    },
    manualCode = { readSecret { settingsStore.endpointToken() } },
    fetch = { baseUrl, token -> requestTicket(http, baseUrl, token) },
    clock = System::currentTimeMillis,
  )

  /** Dove sta il Worker e con che sessione ci si presenta. */
  data class Account(val baseUrl: String, val sessionToken: String) {
    /** Cambiare account o server butta il biglietto: era di qualcun altro. */
    internal val key: String get() = "$baseUrl|${sessionToken.hashCode()}"
  }

  private class Held(val key: String, val ticket: ComputerTicket)

  private val lock = Mutex()

  @Volatile private var held: Held? = null

  /** Quando il Worker ha detto di no l'ultima volta: per un minuto non lo si richiama a ogni sonda. */
  @Volatile private var failedAt: Pair<String, Long>? = null

  /** Quando il companion ha rifiutato un biglietto nuovo: per qualche ora vale il codice a mano. */
  @Volatile private var rejectedAt: Pair<String, Long>? = null

  override suspend fun bearer(): String? {
    val account = account() ?: return manualCode()
    val key = account.key
    val rejected = rejectedAt?.takeIf { it.first == key }?.second
    if (rejected != null && clock() - rejected < PREFER_CODE_AFTER_REJECTION_MS) manualCode()?.let { return it }
    return lock.withLock {
      val now = clock()
      val current = held?.takeIf { it.key == key }?.ticket
      if (current != null && current.expiresAt - now > REFRESH_BEFORE_MS) return@withLock current.value

      // Quello che si ha, finche' vale ancora un poco: meglio di un codice a mano se il Worker non
      // risponde, perche' il PC lo accetta per tutte le ore che gli restano.
      val stillValid = current?.takeIf { it.expiresAt - now > STILL_USABLE_MS }?.value
      val lastFailure = failedAt?.takeIf { it.first == key }?.second
      if (lastFailure != null && now - lastFailure < RETRY_AFTER_FAILURE_MS) return@withLock stillValid ?: manualCode()

      val fresh = try {
        fetch(account.baseUrl, account.sessionToken)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        // Worker muto, vecchio (senza la rotta), o senza COMPUTER_KEY: non e' un motivo per non
        // trascrivere. Si va avanti con quello che c'e', e fra un minuto si riprova.
        failedAt = key to now
        return@withLock stillValid ?: manualCode()
      }
      failedAt = null
      val ticket = ComputerTicket(fresh.value, localExpiry(fresh.expiresAt, now))
      held = Held(key, ticket)
      ticket.value
    }
  }

  override fun invalidate() {
    held = null
    failedAt = null
  }

  override fun ticketRejected() {
    val key = held?.key ?: return
    rejectedAt = key to clock()
  }

  companion object {
    /** Si rinnova quando manca meno di un'ora: una lezione caricata e trascritta sta ben dentro. */
    const val REFRESH_BEFORE_MS = 60 * 60_000L

    /** Sotto i cinque minuti un biglietto non si usa piu' nemmeno come ripiego. */
    const val STILL_USABLE_MS = 5 * 60_000L
    const val RETRY_AFTER_FAILURE_MS = 60_000L

    /** Sei ore: il tempo di accorgersi che il companion va aggiornato, e di aggiornarlo. */
    const val PREFER_CODE_AFTER_REJECTION_MS = 6 * 60 * 60_000L

    /** Quanto dura un biglietto per contratto. */
    const val NOMINAL_LIFETIME_MS = 12 * 60 * 60_000L

    /**
     * La scadenza secondo l'orologio del telefono.
     *
     * La firma la controlla il Worker col suo orologio, non questo. Un telefono avanti di qualche
     * ora vedrebbe un biglietto nuovo gia' in scadenza e ne chiederebbe uno a ogni chiamata: se il
     * conto dice che e' quasi morto appena nato, a sbagliare e' l'orologio di qui, e vale la durata
     * nominale. Un 401 vero lo butta comunque ([invalidate]).
     */
    internal fun localExpiry(serverExpiresAt: Long, now: Long): Long {
      val left = serverExpiresAt - now
      return if (left > REFRESH_BEFORE_MS * 2) now + left.coerceAtMost(NOMINAL_LIFETIME_MS) else now + NOMINAL_LIFETIME_MS
    }

    /** Un segreto illeggibile (il Keystore di un altro telefono, dopo un ripristino) vale come assente. */
    private suspend fun readSecret(read: suspend () -> String?): String? = try {
      read()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      null
    }

    private suspend fun requestTicket(http: TranscriptionHttp, baseUrl: String, sessionToken: String): ComputerTicket {
      val body = http.postJson(
        url = normalizeWorker(baseUrl) + "/v1/computer/ticket",
        headers = mapOf("Authorization" to "Bearer $sessionToken"),
        body = "{}",
      ) as? JsonObject ?: throw TranscriptionError.Parse("il Worker non ha dato un biglietto")
      return parseTicket(body)
    }

    internal fun parseTicket(body: JsonObject): ComputerTicket {
      val value = (body["ticket"] as? JsonPrimitive)?.content?.takeIf { CompanionAuth.isTicket(it) }
        ?: throw TranscriptionError.Parse("il Worker non ha dato un biglietto")
      val expiresAt = (body["expiresAt"] as? JsonPrimitive)?.longOrNull ?: 0L
      return ComputerTicket(value, expiresAt)
    }

    /** Come `SyncApi.normalize`: chi scrive l'indirizzo del Worker lo scrive senza schema. */
    private fun normalizeWorker(raw: String): String {
      val trimmed = raw.trim().trimEnd('/')
      return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }
  }
}
