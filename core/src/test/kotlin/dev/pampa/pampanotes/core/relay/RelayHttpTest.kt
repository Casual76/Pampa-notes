package dev.pampa.pampanotes.core.relay

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RelayHttpTest {
  @Test fun cancellationDisconnectsWhileWaitingForResponse() = runBlocking {
    val entered = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    val closed = AtomicBoolean(false)
    val connection = object : HttpURLConnection(URL("https://example.invalid")) {
      override fun connect() {}
      override fun usingProxy(): Boolean = false
      override fun disconnect() { closed.set(true); disconnected.countDown() }
      override fun getResponseCode(): Int {
        entered.countDown()
        check(disconnected.await(5, TimeUnit.SECONDS))
        throw java.io.IOException("closed")
      }
    }
    val http = RelayHttp { connection }
    val session = RelaySession("https://example.invalid", "owner", "do-not-log", "device", "")
    val job = launch(Dispatchers.IO) { http.discover(session) }
    assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
    withTimeout(2_000) { job.cancelAndJoin() }
    assertTrue(closed.get())
  }

  @Test fun redirectsAreNotFollowedAndErrorMessagesExcludeSecrets() = runBlocking {
    val connection = object : HttpURLConnection(URL("https://example.invalid")) {
      override fun connect() {}
      override fun usingProxy(): Boolean = false
      override fun disconnect() {}
      override fun getResponseCode(): Int = 302
      override fun getErrorStream() = ByteArrayInputStream("""{"error":"sensitive server message"}""".toByteArray())
    }
    val http = RelayHttp { connection }
    try {
      http.discover(RelaySession("https://example.invalid", "owner", "secret", "device", ""))
      fail("redirect must fail")
    } catch (failure: RelayFailure) {
      assertEquals(RelayState.FAILED, failure.state)
      assertEquals("http_302", failure.message)
    }
    assertFalse(connection.instanceFollowRedirects)
    assertEquals("Bearer secret", connection.getRequestProperty("Authorization"))
  }

  @Test fun unsafeServerUrlsCannotReceiveBearerCredentials() {
    listOf("http://public.example", "https://user:password@example.com", "https://example.com?token=x",
      "https://example.com/#x", "file:///tmp/relay").forEach {
      try { RelayHttp.normalizedBase(it); fail("accepted $it") } catch (_: RelayFailure) {}
    }
    assertEquals("http://127.0.0.1:8787", RelayHttp.normalizedBase("http://127.0.0.1:8787/"))
    assertEquals("https://example.com", RelayHttp.normalizedBase("https://example.com/"))
  }
}
