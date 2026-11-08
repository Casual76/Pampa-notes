package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.transcription.CompanionAuth
import dev.pampa.pampanotes.core.transcription.ComputerAuth
import dev.pampa.pampanotes.core.transcription.EndpointResolver
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.core.transcription.ResolvedEndpoint
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import dev.pampa.pampanotes.core.transcription.TranscriptionHttp
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

class AlignmentDeferred(val reason: String, val retryable: Boolean = true) : Exception(reason)

/** HTTP seam for in-memory contract fixtures; the Android adapter reuses existing transport. */
internal interface AlignmentTransport {
  suspend fun get(url: String, headers: Map<String, String>, timeout: Int): JsonElement?
  suspend fun post(url: String, headers: Map<String, String>, fields: Map<String, String>, file: File?, mime: String, timeout: Int): JsonElement?
  suspend fun delete(url: String, headers: Map<String, String>, timeout: Int)
}

/** Only the additive alignment route. No TranscriptionProvider, runner, keys or inference fallback. */
@Singleton
class AlignmentClient internal constructor(
  private val http: AlignmentTransport,
  private val resolve: suspend (String, String) -> ResolvedEndpoint?,
  private val auth: CompanionAuth,
) {
  @Inject constructor(http: TranscriptionHttp, resolver: EndpointResolver, auth: ComputerAuth) : this(
    object : AlignmentTransport {
      override suspend fun get(url: String, headers: Map<String, String>, timeout: Int) = http.getJson(url, headers, timeout)
      override suspend fun post(url: String, headers: Map<String, String>, fields: Map<String, String>, file: File?, mime: String, timeout: Int) =
        if (file == null) http.postForm(url, headers, fields, timeout)
        else http.postAudio(url, headers, fields, file, mime, fileName = "original-audio", readTimeoutMillis = timeout)
      override suspend fun delete(url: String, headers: Map<String, String>, timeout: Int) { http.delete(url, headers, timeout) }
    }, resolver::resolve, auth,
  )
  data class Connection(val base: String, val bearer: String, val instance: String, val accountBound: Boolean) {
    fun headers() = mapOf("Authorization" to "Bearer $bearer")
    override fun toString() = "Alignment connection"
  }
  suspend fun availableInstance(lan: String, remote: String): String? = withTimeout(10_000L) {
    val endpoint = resolve(lan, remote) ?: return@withTimeout null
    val base = OpenAiCompatProvider.normalize(endpoint.url).removeSuffix("/v1")
    val health = http.get("$base/health", emptyMap(), 4000) as? JsonObject ?: return@withTimeout null
    if ((health["features"] as? JsonArray)?.any { (it as? JsonPrimitive)?.content == AlignmentProtocol.CAPABILITY } != true) return@withTimeout null
    health["instance"]?.jsonPrimitive?.content
  }

  suspend fun connect(lan: String, remote: String, accountBound: Boolean): Connection = withTimeout(30_000L) {
    val endpoint = resolve(lan, remote) ?: throw AlignmentDeferred("unconfigured", false)
    val base = OpenAiCompatProvider.normalize(endpoint.url)
    val health = http.get("${base.removeSuffix("/v1")}/health", emptyMap(), 4000) as? JsonObject
      ?: throw AlignmentDeferred("offline")
    if ((health["features"] as? JsonArray)?.any { (it as? JsonPrimitive)?.content == AlignmentProtocol.CAPABILITY } != true)
      throw AlignmentDeferred("capability_absent", false)
    val bearer = auth.bearer()?.takeIf { it.isNotBlank() && !it.startsWith("pg_") }
      ?: throw AlignmentDeferred("owner_auth_required", false)
    // Account-bound data never falls back to a manually entered token belonging to another PC.
    if (accountBound && (!CompanionAuth.isTicket(bearer) || (health["auth"] as? JsonObject)?.get("account")?.jsonPrimitive?.booleanOrNull != true))
      throw AlignmentDeferred("owner_account_required", false)
    val instance = health["instance"]?.jsonPrimitive?.content?.takeIf { it.length in 1..128 }
      ?: throw AlignmentDeferred("instance_missing", false)
    Connection(base, bearer, instance, accountBound).also { authorize(it) }
  }

  suspend fun verifyAccount(index: String, token: String, owner: String) {
    val trimmed = index.trim().trimEnd('/')
    val base = if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) trimmed else "https://$trimmed"
    val response = http.get("$base/v1/sync/status", mapOf("Authorization" to "Bearer $token"), 10_000) as? JsonObject
    if (response?.get("ownerId")?.jsonPrimitive?.content != owner) throw AlignmentDeferred("account_changed", false)
  }

  /** Owner-only read before any audio and after a result. Never binds/reconfigures a companion. */
  suspend fun authorize(connection: Connection) {
    try { withTimeout(15_000L) { http.get("${connection.base}/admin/settings", connection.headers(), 10_000) } }
    catch (e: TranscriptionError.Unauthorized) { auth.invalidate(); throw AlignmentDeferred("owner_auth_required", false) }
    catch (e: TranscriptionError.OwnerOnly) { throw AlignmentDeferred("owner_only", false) }
  }

  suspend fun align(connection: Connection, request: AlignmentRequest, file: File?, mime: String, jobId: String): AlignmentResponse {
    require(AlignmentProtocol.validJob(jobId))
    if (file == null && !connection.accountBound) throw AlignmentDeferred("owned_audio_required", false)
    val fields = mapOf("alignment" to AlignmentProtocol.json.encodeToString(AlignmentRequest.serializer(), request))
    val headers = connection.headers() + ("X-Pampa-Job" to jobId)
    val result = withTimeout(POST_TIMEOUT_MS) {
      http.post("${connection.base}/audio/alignments", headers, fields, file, mime, POST_TIMEOUT_MS.toInt())
    } ?: throw AlignmentDeferred("empty_response", false)
    require(result.toString().length <= MAX_RESPONSE) { "Alignment response exceeds limit" }
    return AlignmentProtocol.json.decodeFromJsonElement(AlignmentResponse.serializer(), result)
  }

  /** Bounded cancellation with current credentials, only on the same validated endpoint/account. */
  suspend fun cancel(connection: Connection, jobId: String) {
    if (!AlignmentProtocol.validJob(jobId)) return
    withTimeout(4000L) { http.delete("${connection.base}/jobs/$jobId", connection.headers(), 3000) }
  }

  companion object {
    const val POST_TIMEOUT_MS = 8 * 60_000L
    const val MAX_RESPONSE = 24 * 1024 * 1024
  }
}
