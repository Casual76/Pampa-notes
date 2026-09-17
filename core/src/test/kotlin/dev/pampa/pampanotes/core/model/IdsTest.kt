package dev.pampa.pampanotes.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlugifyTest {

  @Test
  fun `toglie accenti e spazi`() {
    assertEquals("rivoluzione-francese", "Rivoluzione Francese".slugify())
    assertEquals("citta-e-poteri", "Città e poteri".slugify())
    assertEquals("perche-kant", "Perché Kant?".slugify())
  }

  @Test
  fun `non lascia trattini agli estremi`() {
    assertEquals("kant", "  — Kant — ".slugify())
    assertEquals("nota-1", "Nota #1".slugify())
  }

  @Test
  fun `un titolo senza lettere latine non produce un nome vuoto`() {
    assertEquals("senza-nome", "???".slugify())
    assertEquals("senza-nome", "".slugify())
    // Un file chiamato "-" romperebbe l'export tanto quanto uno chiamato "".
    assertEquals("senza-nome", "-".slugify())
  }

  @Test
  fun `taglia alla lunghezza chiesta senza lasciare un trattino in coda`() {
    val long = "appunti di storia contemporanea sul secondo dopoguerra in europa occidentale"
    val slug = long.slugify(maxLength = 20)
    assertTrue(slug.length <= 20)
    assertTrue(!slug.endsWith("-"))
  }

  @Test
  fun `conta le parole ignorando gli spazi multipli`() {
    assertEquals(3, "una  frase\ncorta".wordCount())
    assertEquals(0, "   ".wordCount())
  }

  @Test
  fun `due id non coincidono`() {
    assertNotEquals(Ids.newId(), Ids.newId())
  }
}
