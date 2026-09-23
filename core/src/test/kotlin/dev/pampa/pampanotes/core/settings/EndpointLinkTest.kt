package dev.pampa.pampanotes.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointLinkTest {

  @Test
  fun `indirizzo e token come li stampa il server`() {
    val link = EndpointLink.parse("pampanotes://endpoint?url=http://192.168.178.52:8765&token=abc123")
    assertEquals(EndpointLink("http://192.168.178.52:8765", "abc123"), link)
  }

  @Test
  fun `senza token il token e' nullo, non vuoto`() {
    val link = EndpointLink.parse("pampanotes://endpoint?url=http://192.168.1.10:8765")
    assertEquals(EndpointLink("http://192.168.1.10:8765", null), link)
    assertNull(EndpointLink.parse("pampanotes://endpoint?url=http://192.168.1.10:8765&token=")?.token)
  }

  @Test
  fun `l'indirizzo di fuori casa viaggia nello stesso link, e se non e' http si ignora`() {
    val both = EndpointLink.parse("pampanotes://endpoint?url=http://192.168.1.10:8765&remote=http://100.64.0.7:8765&token=t")
    assertEquals(EndpointLink("http://192.168.1.10:8765", "t", "http://100.64.0.7:8765"), both)
    assertNull(EndpointLink.parse("pampanotes://endpoint?url=http://pc:8765&remote=ftp://pc")?.remoteUrl)
    assertEquals("http://pc:8765", EndpointLink.parse("pampanotes://endpoint?url=http://pc:8765&remote=ftp://pc")?.url)
  }

  @Test
  fun `il codice di collegamento viaggia nel link, e uno che non ha la forma giusta si ignora`() {
    val link = EndpointLink.parse("pampanotes://endpoint?url=http://pc:8765&remote=http://100.64.0.7:8765&bind=Ab3_-xYz0123456789Qw")
    assertEquals("Ab3_-xYz0123456789Qw", link?.bindCode)
    assertEquals("http://100.64.0.7:8765", link?.remoteUrl)
    assertNull(EndpointLink.parse("pampanotes://endpoint?url=http://pc:8765")?.bindCode)
    assertNull(EndpointLink.parse("pampanotes://endpoint?url=http://pc:8765&bind=corto")?.bindCode)
    assertNull(EndpointLink.parse("pampanotes://endpoint?url=http://pc:8765&bind=con%20spazi%20dentro%20davvero")?.bindCode)
    assertEquals("http://pc:8765", EndpointLink.parse("pampanotes://endpoint?url=http://pc:8765&bind=corto")?.url)
  }

  @Test
  fun `la barra finale se ne va, come fa gia' il campo delle impostazioni`() {
    assertEquals("http://pc:8765", EndpointLink.parse("pampanotes://endpoint?url=http://pc:8765/")?.url)
  }

  @Test
  fun `i valori codificati si decodificano una volta sola`() {
    val link = EndpointLink.parse("pampanotes://endpoint?url=http%3A%2F%2Fpc%3A8765&token=a%25b")
    assertEquals("http://pc:8765", link?.url)
    // `a%25b` e' "a%b" codificato: decodificarlo due volte darebbe un errore o un altro valore.
    assertEquals("a%b", link?.token)
  }

  @Test
  fun `un indirizzo che non e' http non e' un server`() {
    assertNull(EndpointLink.parse("pampanotes://endpoint?url=ftp://pc:21"))
    assertNull(EndpointLink.parse("pampanotes://endpoint?url=javascript:alert(1)"))
    assertNull(EndpointLink.parse("pampanotes://endpoint?url=192.168.1.10:8765"))
  }

  @Test
  fun `schema o host sbagliati non sono un link nostro`() {
    assertNull(EndpointLink.parse("https://endpoint?url=http://pc:8765"))
    assertNull(EndpointLink.parse("pampanotes://altro?url=http://pc:8765"))
    assertNull(EndpointLink.parse("pampanotes://endpoint"))
    assertNull(EndpointLink.parse(""))
    assertNull(EndpointLink.parse(null))
    assertNull(EndpointLink.parse("non e' nemmeno un uri ://"))
  }
}
