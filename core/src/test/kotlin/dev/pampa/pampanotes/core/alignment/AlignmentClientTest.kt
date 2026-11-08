package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.transcription.CompanionAuth
import dev.pampa.pampanotes.core.transcription.ResolvedEndpoint
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AlignmentClientTest {
  private class Fixture : AlignmentTransport {
    val gets = mutableListOf<String>(); val posts = mutableListOf<String>(); val deletes = mutableListOf<String>()
    var features = listOf(AlignmentProtocol.CAPABILITY)
    var account = true
    var offline = false
    var denied = false
    var owner = "owner-1"
    var lastFields: Map<String, String> = emptyMap()
    var lastHeaders: Map<String, String> = emptyMap()
    var answer: JsonElement? = null
    override suspend fun get(url: String, headers: Map<String, String>, timeout: Int): JsonElement? {
      gets += url
      check(timeout in 1..15_000)
      if (offline) throw TranscriptionError.Network("offline fixture")
      if (url.endsWith("/health")) return buildJsonObject {
        put("instance", "fixture-process"); put("features", JsonArray(features.map(::JsonPrimitive)))
        put("auth", buildJsonObject { put("account", account) })
      }
      if (url.endsWith("/sync/status")) return buildJsonObject { put("ownerId", owner) }
      if (denied) throw TranscriptionError.OwnerOnly("owner_only")
      check(headers["Authorization"]?.startsWith("Bearer ") == true)
      return buildJsonObject { put("model", "unused-for-alignment") }
    }
    override suspend fun post(url: String, headers: Map<String, String>, fields: Map<String, String>, file: File?, mime: String, timeout: Int): JsonElement? {
      posts += url; lastFields = fields; lastHeaders = headers
      check(timeout in 1..AlignmentClient.POST_TIMEOUT_MS.toInt())
      check(url.endsWith("/audio/alignments")) // Fixture fails if any transcription/inference route is used.
      return answer
    }
    override suspend fun delete(url: String, headers: Map<String, String>, timeout: Int) { deletes += url; check(timeout <= 4000) }
  }
  private fun client(f: Fixture, token: String = "pt_fixture_owner", configured: Boolean = true) =
    AlignmentClient(f, { _, _ -> if (configured) ResolvedEndpoint("http://home-pc", true) else null }, CompanionAuth.fixed(token))
  private suspend fun deferred(reason: String, call: suspend () -> Unit) {
    try { call(); fail("must defer") } catch (e: AlignmentDeferred) { assertEquals(reason, e.reason) }
  }
  @Test fun `old capability and unconfigured PC defer only alignment without inference`() = runBlocking {
    val f = Fixture(); f.features = emptyList()
    deferred("capability_absent") { client(f).connect("pc", "", true) }
    deferred("unconfigured") { client(f, configured = false).connect("", "", true) }
    assertTrue(f.posts.isEmpty()); assertEquals(1, f.gets.size)
  }
  @Test fun `offline PC never falls back to transcription`() = runBlocking {
    val f = Fixture(); f.offline = true
    try { client(f).connect("pc", "", true); fail("offline") } catch (_: TranscriptionError.Network) { }
    assertTrue(f.posts.isEmpty())
  }
  @Test fun `guest anonymous and manual token cannot align account owner audio`() = runBlocking {
    for (token in listOf("pg_guest", "", "manual-owner-code")) {
      val f = Fixture()
      deferred(if (token == "manual-owner-code") "owner_account_required" else "owner_auth_required") { client(f, token).connect("pc", "", true) }
      assertTrue(f.posts.isEmpty()); assertFalse(f.gets.any { it.endsWith("/admin/settings") })
    }
  }
  @Test fun `owner authorization happens before any audio upload and wrong owner index is rejected`() = runBlocking {
    val f = Fixture(); f.denied = true
    deferred("owner_only") { client(f).connect("pc", "", true) }
    deferred("account_changed") { client(f).verifyAccount("https://worker", "fixture-session-token", "other-owner") }
    assertTrue(f.posts.isEmpty())
  }
  @Test fun `exact multipart alignment route fresh job identity owned by reference and bounded cancel`() = runBlocking {
    val f = Fixture(); val c = client(f); val connection = c.connect("pc", "", true)
    val text = "  Cloud,\tunchanged!\n"; val sha = AlignmentProtocol.sha256("audio fixture")
    val request = AlignmentRequest(source = AlignmentSource("raw-1", 42, AlignmentProtocol.sha256(text), listOf(AlignmentPart("a", sha))), partId = "a", text = text,
      textSha256 = AlignmentProtocol.sha256(text), language = "en", segments = emptyList())
    val response = AlignmentResponse(1, request.source, "a", text, request.textSha256, "en", "utf16", "ms", sha, "fresh-job-1", "fixture-process", "deferred",
      AlignmentProtocol.tokens(text).map { AlignmentTiming(it.first, it.last + 1, null, null, "missing", "no_bounded_window") })
    f.answer = AlignmentProtocol.json.encodeToJsonElement(AlignmentResponse.serializer(), response)
    val received = c.align(connection, request, null, "audio/wav", "fresh-job-1")
    AlignmentProtocol.validate(received, request, 60_000, "fresh-job-1")
    assertEquals(listOf("http://home-pc/v1/audio/alignments"), f.posts)
    assertEquals("fresh-job-1", f.lastHeaders["X-Pampa-Job"])
    assertEquals(request, AlignmentProtocol.json.decodeFromString(AlignmentRequest.serializer(), f.lastFields.getValue("alignment")))
    assertFalse(f.lastFields.toString().contains("fixture_owner"))
    c.cancel(connection, "fresh-job-1")
    assertEquals(listOf("http://home-pc/v1/jobs/fresh-job-1"), f.deletes)
  }
  @Test fun `unbound local owner cannot use archive by reference`() = runBlocking {
    val f = Fixture(); f.account = false; val c = client(f, "local-owner")
    val connection = c.connect("pc", "", false)
    val request = AlignmentRequest(source = AlignmentSource("raw", 1, AlignmentProtocol.sha256("x"), listOf(AlignmentPart("a", AlignmentProtocol.sha256("bytes")))), partId = "a",
      text = "x", textSha256 = AlignmentProtocol.sha256("x"), language = "en", segments = emptyList())
    deferred("owned_audio_required") { c.align(connection, request, null, "audio/wav", "fresh-job-1") }
    assertTrue(f.posts.isEmpty())
  }
}
