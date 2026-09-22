package dev.pampa.pampanotes.core.transcription

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointResolverTest {

  private var now = 1_000_000L
  private var lanAnswers = true
  private var probes = 0

  private fun resolver() = EndpointResolver(
    probe = { probes++; lanAnswers },
    clock = { now },
  )

  private val lan = "http://192.168.1.10:8765"
  private val remote = "http://100.64.0.7:8765"

  @Test
  fun `a casa si va per la rete locale`() = runBlocking {
    assertEquals(ResolvedEndpoint(lan, viaLan = true), resolver().resolve(lan, remote))
  }

  @Test
  fun `fuori casa si va da Tailscale`() = runBlocking {
    lanAnswers = false
    assertEquals(ResolvedEndpoint(remote, viaLan = false), resolver().resolve(lan, remote))
  }

  @Test
  fun `la risposta si tiene mezzo minuto, poi si richiede`() = runBlocking {
    val r = resolver()
    r.resolve(lan, remote)
    r.resolve(lan, remote)
    assertEquals(1, probes)
    now += EndpointResolver.CACHE_MS
    r.resolve(lan, remote)
    assertEquals(2, probes)
  }

  @Test
  fun `cambiare un indirizzo butta via la cache`() = runBlocking {
    val r = resolver()
    r.resolve(lan, remote)
    r.resolve(lan, "http://altro:8765")
    assertEquals(2, probes)
  }

  @Test
  fun `invalidate fa richiedere subito`() = runBlocking {
    val r = resolver()
    r.resolve(lan, remote)
    r.invalidate()
    r.resolve(lan, remote)
    assertEquals(2, probes)
  }

  @Test
  fun `con un indirizzo solo non si sonda niente`() = runBlocking {
    lanAnswers = false
    val r = resolver()
    assertEquals(ResolvedEndpoint(lan, viaLan = true), r.resolve(lan, ""))
    assertEquals(ResolvedEndpoint(remote, viaLan = false), r.resolve("", remote))
    assertEquals(0, probes)
  }

  @Test
  fun `senza indirizzi non c'e' niente da risolvere`() = runBlocking {
    assertNull(resolver().resolve("", "  "))
  }
}
