package dev.pampa.pampanotes.core.transcription

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il biglietto per il computer di casa: quando se ne chiede uno, quando si tiene, quando si ripiega. */
class ComputerAuthTest {

  private var now = 1_000_000_000L
  private var account: ComputerAuth.Account? = ComputerAuth.Account("https://pampa.example.dev", "sessione")
  private var code: String? = "codice"
  private var fetches = 0
  private var fetchFails = false
  private var lifetime = 12 * 60 * 60_000L

  private fun auth() = ComputerAuth(
    account = { account },
    manualCode = { code },
    fetch = { _, _ ->
      fetches++
      if (fetchFails) throw IOException("il Worker non risponde")
      ComputerTicket("pt_biglietto$fetches", now + lifetime)
    },
    clock = { now },
  )

  @Test
  fun `senza accesso al sync vale il codice scritto a mano`() = runBlocking {
    account = null
    assertEquals("codice", auth().bearer())
    assertEquals(0, fetches)
  }

  @Test
  fun `senza accesso e senza codice non si manda niente`() = runBlocking {
    account = null
    code = null
    assertNull(auth().bearer())
  }

  @Test
  fun `con l'accesso si chiede un biglietto, e lo si tiene`() = runBlocking {
    val auth = auth()
    assertEquals("pt_biglietto1", auth.bearer())
    now += 5 * 60 * 60_000L
    assertEquals("pt_biglietto1", auth.bearer())
    assertEquals(1, fetches)
  }

  @Test
  fun `a meno di un'ora dalla scadenza si rinnova`() = runBlocking {
    val auth = auth()
    auth.bearer()
    now += lifetime - ComputerAuth.REFRESH_BEFORE_MS + 1
    assertEquals("pt_biglietto2", auth.bearer())
    assertEquals(2, fetches)
  }

  @Test
  fun `col Worker muto si usa il biglietto che si ha, finche' vale`() = runBlocking {
    val auth = auth()
    auth.bearer()
    now += lifetime - ComputerAuth.REFRESH_BEFORE_MS + 1
    fetchFails = true
    assertEquals("pt_biglietto1", auth.bearer())
  }

  @Test
  fun `col Worker muto e niente in mano si ripiega sul codice, e per un minuto non lo si richiama`() = runBlocking {
    fetchFails = true
    val auth = auth()
    assertEquals("codice", auth.bearer())
    assertEquals("codice", auth.bearer())
    assertEquals(1, fetches)
    now += ComputerAuth.RETRY_AFTER_FAILURE_MS
    fetchFails = false
    assertEquals("pt_biglietto2", auth.bearer())
  }

  @Test
  fun `cambiare account butta il biglietto dell'altro`() = runBlocking {
    val auth = auth()
    auth.bearer()
    account = ComputerAuth.Account("https://pampa.example.dev", "un'altra sessione")
    assertEquals("pt_biglietto2", auth.bearer())
  }

  @Test
  fun `invalidate fa chiedere un biglietto nuovo`() = runBlocking {
    val auth = auth()
    auth.bearer()
    auth.invalidate()
    assertEquals("pt_biglietto2", auth.bearer())
  }

  @Test
  fun `un 401 col biglietto lo rinnova e riprova una volta`() = runBlocking {
    val auth = auth()
    val seen = mutableListOf<String?>()
    val result = auth.call(isUnauthorized = { it is Unauthorized }, rejected = { it }) { bearer ->
      seen += bearer
      if (bearer == "pt_biglietto1") throw Unauthorized()
      "fatto"
    }
    assertEquals("fatto", result)
    assertEquals(listOf("pt_biglietto1", "pt_biglietto2"), seen)
  }

  @Test
  fun `un companion che rifiuta ogni biglietto riceve il codice, e poi per ore solo quello`() = runBlocking {
    val auth = auth()
    val seen = mutableListOf<String?>()
    val result = auth.call(isUnauthorized = { it is Unauthorized }, rejected = { it }) { bearer ->
      seen += bearer
      if (CompanionAuth.isTicket(bearer)) throw Unauthorized()
      "fatto col codice"
    }
    assertEquals("fatto col codice", result)
    assertEquals(listOf("pt_biglietto1", "pt_biglietto2", "codice"), seen)
    assertEquals("codice", auth.bearer())
    now += ComputerAuth.PREFER_CODE_AFTER_REJECTION_MS
    assertTrue(CompanionAuth.isTicket(auth.bearer()))
  }

  @Test
  fun `senza codice il secondo rifiuto dice che il computer non riconosce l'account`() = runBlocking {
    code = null
    val auth = auth()
    val error = runCatching {
      auth.call(isUnauthorized = { it is Unauthorized }, rejected = { IllegalStateException(CompanionAuth.ACCOUNT_REJECTED) }) { _ ->
        throw Unauthorized()
      }
    }.exceptionOrNull()
    assertEquals(CompanionAuth.ACCOUNT_REJECTED, error?.message)
    assertEquals(2, fetches)
  }

  @Test
  fun `un 401 col codice non si riprova`() = runBlocking {
    account = null
    var calls = 0
    val error = runCatching {
      auth().call(isUnauthorized = { it is Unauthorized }, rejected = { it }) { _ ->
        calls++
        throw Unauthorized()
      }
    }.exceptionOrNull()
    assertTrue(error is Unauthorized)
    assertEquals(1, calls)
  }

  @Test
  fun `un orologio avanti non fa sembrare morto un biglietto appena nato`() {
    val now = 5_000_000L
    // Il Worker dice «scade fra dodici ore»: si tiene quella.
    assertEquals(now + 12 * 60 * 60_000L, ComputerAuth.localExpiry(now + 12 * 60 * 60_000L, now))
    // Il telefono e' avanti di undici ore: sembrerebbe scadere fra un'ora. Vale la durata nominale.
    assertEquals(now + ComputerAuth.NOMINAL_LIFETIME_MS, ComputerAuth.localExpiry(now + 60 * 60_000L, now))
    // Mai piu' della durata nominale, anche se il Worker dicesse di piu'.
    assertEquals(now + ComputerAuth.NOMINAL_LIFETIME_MS, ComputerAuth.localExpiry(now + 48 * 60 * 60_000L, now))
  }

  @Test
  fun `la risposta del Worker si legge, e un biglietto senza prefisso si rifiuta`() {
    val good = Json.parseToJsonElement("""{"ticket":"pt_abc.def","expiresAt":123}""").jsonObject
    assertEquals(ComputerTicket("pt_abc.def", 123), ComputerAuth.parseTicket(good))
    val bad = Json.parseToJsonElement("""{"ticket":"qualcosa","expiresAt":123}""").jsonObject
    assertTrue(runCatching { ComputerAuth.parseTicket(bad) }.exceptionOrNull() is TranscriptionError.Parse)
  }

  private class Unauthorized : Exception("401")
}
