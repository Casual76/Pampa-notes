package dev.pampa.pampanotes.core.testing

import dev.pampa.pampanotes.core.transcription.CloudRequestGate
import dev.pampa.pampanotes.core.transcription.CloudRetry
import dev.pampa.pampanotes.core.transcription.DurableCloudCooldowns
import dev.pampa.pampanotes.core.transcription.TranscriptionHttp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** An in-memory HTTP transport. Never opens sockets, servers or provider connections. */
class CloudHttpFixture(private val file: File) {
  var account = "account-A"
  var key = "fixture-key"
  var code = 200
  var retryAfter: String? = null
  var body = """{"data":[]}"""
  var beforeCode: () -> Unit = {}
  var onRequest: (URL) -> Unit = {}
  val requests = mutableListOf<URL>()
  fun http(): TranscriptionHttp = TranscriptionHttp("offline", cloudGate = CloudRequestGate(
    DurableCloudCooldowns(file), { CloudRetry.fingerprint(account) }, { CloudRetry.fingerprint(key) }),
    connectionFactory = { url ->
      requests.add(url)
      onRequest(url)
      object : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode(): Int { beforeCode(); return code }
        override fun getHeaderFields(): Map<String?, List<String>> = retryAfter?.let { mapOf("Retry-After" to listOf(it)) } ?: emptyMap()
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream() = ByteArrayOutputStream()
      }
    })
  fun headers() = mapOf("Authorization" to "Bearer $key")
  fun deadline(provider: String, key: String = this.key, account: String = this.account) =
    DurableCloudCooldowns(file).deadline(provider, CloudRetry.fingerprint(key), CloudRetry.fingerprint(account))
}
