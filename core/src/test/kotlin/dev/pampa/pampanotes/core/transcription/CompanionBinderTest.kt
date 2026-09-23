package dev.pampa.pampanotes.core.transcription

import dev.antigravity.fluidengine.ai.net.AiErrorMapper
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il computer diventa dell'account: chi ci prova, con cosa, e cosa vuol dire ogni risposta. */
class CompanionBinderTest {

  private var account: CompanionBinder.Account? = CompanionBinder.Account("tu@gmail.com", "pampa.example.workers.dev/")
  private var bearer: String? = "pt_biglietto"
  private val calls = mutableListOf<Triple<String, String, String>>()
  private var answer: (String) -> kotlinx.serialization.json.JsonElement? = { Json.parseToJsonElement("""{"ok":true,"owner":"tu@gmail.com"}""") }

  private fun binder() = CompanionBinder(
    account = { account },
    bearer = { bearer },
    post = { url, bearer, body ->
      calls += Triple(url, bearer, body)
      answer(url)
    },
  )

  private fun httpError(code: Int, body: String = """{"detail":"no"}"""): Throwable =
    TranscriptionError.from(AiErrorMapper.map(code, emptyMap(), body))

  @Test
  fun `manda codice, account e indice col biglietto come bearer`() = runBlocking {
    val result = binder().bind(listOf("http://192.168.1.10:8765/"), "codice-di-prova-123")
    assertEquals(CompanionBindResult.Bound("tu@gmail.com"), result)
    val (url, sent, body) = calls.single()
    assertEquals("http://192.168.1.10:8765/v1/pair/bind", url)
    assertEquals("pt_biglietto", sent)
    val json = Json.parseToJsonElement(body).jsonObject
    assertEquals("codice-di-prova-123", json["code"]!!.jsonPrimitive.content)
    assertEquals("tu@gmail.com", json["owner"]!!.jsonPrimitive.content)
    // Lo schema lo aggiunge il telefono: il companion lo vuole per chiamare l'indice.
    assertEquals("https://pampa.example.workers.dev", json["index_url"]!!.jsonPrimitive.content)
  }

  @Test
  fun `senza account o senza biglietto non si prova nemmeno`() = runBlocking {
    account = null
    assertEquals(CompanionBindResult.NotSignedIn, binder().bind(listOf("http://pc:8765"), "c"))
    account = CompanionBinder.Account("tu@gmail.com", "https://x")
    bearer = "codice-scritto-a-mano"
    assertEquals(CompanionBindResult.NotSignedIn, binder().bind(listOf("http://pc:8765"), "c"))
    bearer = null
    assertEquals(CompanionBindResult.NotSignedIn, binder().bind(listOf("http://pc:8765"), "c"))
    assertTrue(calls.isEmpty())
  }

  @Test
  fun `se casa non risponde prova l'indirizzo di fuori`() = runBlocking {
    answer = { url -> if (url.startsWith("http://192.168")) throw IOException("rete") else Json.parseToJsonElement("""{"ok":true}""") }
    val result = binder().bind(listOf("http://192.168.1.10:8765", "http://100.64.1.2:8765"), "c")
    assertEquals(CompanionBindResult.Bound("tu@gmail.com"), result)
    assertEquals(2, calls.size)
  }

  @Test
  fun `un rifiuto non si riprova sull'altro indirizzo`() = runBlocking {
    answer = { throw httpError(403) }
    val result = binder().bind(listOf("http://192.168.1.10:8765", "http://100.64.1.2:8765"), "c")
    assertTrue(result is CompanionBindResult.Rejected)
    assertEquals(1, calls.size)
  }

  @Test
  fun `le risposte del companion`() {
    assertEquals(CompanionBindResult.Taken, CompanionBinder.classify(httpError(409)))
    assertTrue(CompanionBinder.classify(httpError(401)) is CompanionBindResult.Rejected)
    assertTrue(CompanionBinder.classify(httpError(400)) is CompanionBindResult.Rejected)
    assertTrue(CompanionBinder.classify(httpError(502)) is CompanionBindResult.Unreachable)
    assertTrue(CompanionBinder.classify(IOException("rete")) is CompanionBindResult.Unreachable)
  }
}
