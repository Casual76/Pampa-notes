package dev.pampa.pampanotes.core.sync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer

/**
 * Le tre chiamate al Worker. JSON dentro, JSON fuori, un bearer token.
 *
 * Il token e' quello di sviluppo o l'ID token di Google: il server sa distinguere, il client no e
 * non deve. Un 401 e' «non sei chi dici», un 409 e' «parliamo protocolli diversi» — tutti e due
 * arrivano a chi chiama come [SyncException] con il codice, perche' la pagina deve dirli in modo
 * diverso.
 */
class SyncApi(
  private val userAgent: String,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) {

  suspend fun push(baseUrl: String, token: String, request: PushRequest): PushResponse =
    call(baseUrl, "/v1/sync/push", token, "POST", SyncCodec.json.encodeToString(PushRequest.serializer(), request), PushResponse.serializer())

  suspend fun pull(baseUrl: String, token: String, deviceId: String, since: Long, limit: Int = 200): PullResponse =
    call(baseUrl, "/v1/sync/pull?since=$since&limit=$limit&deviceId=${URLEncoder.encode(deviceId, "UTF-8")}", token, "GET", null, PullResponse.serializer())

  suspend fun status(baseUrl: String, token: String): SyncStatus =
    call(baseUrl, "/v1/sync/status", token, "GET", null, SyncStatus.serializer())

  /** L'ID token di Google entra, il token di sessione esce. Il bearer qui e' l'ID token stesso. */
  suspend fun loginWithGoogle(baseUrl: String, idToken: String, deviceId: String, deviceName: String): LoginResponse =
    call(baseUrl, "/v1/auth/google", idToken, "POST", SyncCodec.json.encodeToString(LoginRequest.serializer(), LoginRequest(idToken, deviceId, deviceName)), LoginResponse.serializer())

  suspend fun logout(baseUrl: String, token: String) {
    call(baseUrl, "/v1/auth/logout", token, "POST", "{}", LoggedOut.serializer())
  }

  // --- gli ospiti del computer di casa ---

  suspend fun createGuest(baseUrl: String, token: String, name: String): GuestInfo =
    call(baseUrl, "/v1/guests", token, "POST", SyncCodec.json.encodeToString(GuestName.serializer(), GuestName(name)), GuestInfo.serializer())

  suspend fun listGuests(baseUrl: String, token: String): List<GuestInfo> =
    call(baseUrl, "/v1/guests", token, "GET", null, GuestsBody.serializer()).guests

  suspend fun revokeGuest(baseUrl: String, token: String, guestId: String) {
    call(baseUrl, "/v1/guests/${URLEncoder.encode(guestId, "UTF-8")}", token, "DELETE", null, Revoked.serializer())
  }

  // --- il computer di casa dell'account ---

  /** `null` se l'account non ne ha uno: un 404 qui e' una risposta, non un errore. */
  suspend fun getComputer(baseUrl: String, token: String): AccountComputer? = try {
    call(baseUrl, COMPUTER_PATH, token, "GET", null, AccountComputer.serializer())
  } catch (missing: SyncException) {
    if (missing.code == 404) null else throw missing
  }

  suspend fun putComputer(baseUrl: String, token: String, request: PutComputerRequest): PutComputerResponse =
    call(baseUrl, COMPUTER_PATH, token, "PUT", SyncCodec.json.encodeToString(PutComputerRequest.serializer(), request), PutComputerResponse.serializer())

  /** @return se c'era qualcosa da togliere. */
  suspend fun deleteComputer(baseUrl: String, token: String): Boolean = try {
    call(baseUrl, COMPUTER_PATH, token, "DELETE", null, Removed.serializer()).removed
  } catch (missing: SyncException) {
    if (missing.code == 404) false else throw missing
  }

  @kotlinx.serialization.Serializable
  private data class Removed(val removed: Boolean = false)

  @kotlinx.serialization.Serializable
  private data class GuestName(val name: String)

  @kotlinx.serialization.Serializable
  private data class GuestsBody(val guests: List<GuestInfo> = emptyList())

  @kotlinx.serialization.Serializable
  private data class Revoked(val revoked: Boolean = false)

  @kotlinx.serialization.Serializable
  private data class LoggedOut(val loggedOut: Boolean = false)

  private suspend fun <T> call(baseUrl: String, path: String, token: String, method: String, body: String?, serializer: KSerializer<T>): T = withContext(io) {
    val connection = URL(normalize(baseUrl) + path).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = method
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      connection.setRequestProperty("User-Agent", userAgent)
      connection.setRequestProperty("Accept", "application/json")
      connection.setRequestProperty("Authorization", "Bearer $token")
      if (body != null) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val bytes = body.toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
      }
      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (code !in 200..299) throw SyncException(code, errorMessage(text, code))
      SyncCodec.json.decodeFromString(serializer, text)
    } finally {
      connection.disconnect()
    }
  }

  private fun errorMessage(text: String, code: Int): String =
    runCatching { SyncCodec.json.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()?.takeIf { it.isNotBlank() } ?: "risposta $code"

  @kotlinx.serialization.Serializable
  private data class ErrorBody(val error: String = "")

  companion object {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 60_000
    private const val COMPUTER_PATH = "/v1/account/computer"

    /** `https://pampa.qualcuno.workers.dev/` o `192.168.1.10:8787`: tutte e due devono andare. */
    fun normalize(raw: String): String {
      val trimmed = raw.trim().trimEnd('/')
      return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }
  }
}

/** Il server ha risposto, ma non bene. 401: token; 409: protocollo; 5xx: suo. */
class SyncException(val code: Int, message: String) : IOException(message)
