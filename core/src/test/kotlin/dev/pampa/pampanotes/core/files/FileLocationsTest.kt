package dev.pampa.pampanotes.core.files

import dev.pampa.pampanotes.core.db.SizeTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il riassunto di «dove stanno i tuoi file», e quando salira' quello che sta solo qui. */
class FileLocationsTest {

  @Test
  fun `le quattro combinazioni di qui e computer danno i quattro posti`() {
    assertEquals(FilePlace.BOTH, FilePlace.of(here = true, archived = true))
    assertEquals(FilePlace.ONLY_HERE, FilePlace.of(here = true, archived = false))
    assertEquals(FilePlace.ONLY_COMPUTER, FilePlace.of(here = false, archived = true))
    assertEquals(FilePlace.ELSEWHERE, FilePlace.of(here = false, archived = false))
  }

  @Test
  fun `ogni file finisce in un posto solo, col peso della sua riga`() {
    val summary = PlaceSummary.of(
      listOf(
        FileFact(100, here = true, archived = true),
        FileFact(200, here = true, archived = true),
        FileFact(50, here = true, archived = false),
        FileFact(1_000, here = false, archived = true),
        FileFact(7, here = false, archived = false),
      ),
    )
    assertEquals(SizeTotal(2, 300), summary.both)
    assertEquals(SizeTotal(1, 50), summary.onlyHere)
    assertEquals(SizeTotal(1, 1_000), summary.onlyComputer)
    assertEquals(SizeTotal(1, 7), summary.elsewhere)
    assertEquals(SizeTotal(5, 1_357), summary.total)
    assertEquals(SizeTotal(3, 350), summary.here)
  }

  @Test
  fun `un elenco vuoto non ha posti da mostrare`() {
    val summary = PlaceSummary.of(emptyList())
    assertEquals(SizeTotal(0, 0), summary.total)
    assertTrue(summary.nonEmpty.isEmpty())
  }

  @Test
  fun `i posti vuoti non si elencano, e l'ordine resta quello della pagina`() {
    val summary = PlaceSummary.of(
      listOf(
        FileFact(1, here = false, archived = false),
        FileFact(1, here = true, archived = true),
      ),
    )
    assertEquals(listOf(FilePlace.BOTH, FilePlace.ELSEWHERE), summary.nonEmpty)
  }

  @Test
  fun `a rischio sono i file che stanno solo qui, registrazioni e originali insieme`() {
    val locations = FileLocations.of(
      recordings = listOf(
        FileFact(10_000, here = true, archived = false),
        FileFact(20_000, here = true, archived = true),
      ),
      originals = listOf(
        FileFact(300, here = true, archived = false),
        FileFact(400, here = false, archived = true),
      ),
    )
    assertEquals(SizeTotal(2, 10_300), locations.atRisk)
    assertEquals(SizeTotal(1, 400), locations.onlyComputer)
    assertEquals(false, locations.isEmpty)
    assertTrue(FileLocations().isEmpty)
  }

  @Test
  fun `senza computer o con l'archivio spento non si promette nessun invio`() {
    assertEquals(ArchiveOutlook.NoComputer, outlook(hasComputer = false, periodicNextAt = NOW + 1_000))
    assertEquals(ArchiveOutlook.Off, outlook(enabled = false, periodicNextAt = NOW + 1_000))
  }

  @Test
  fun `un giro in corso vince su tutto il resto`() {
    assertEquals(ArchiveOutlook.Running, outlook(running = true, oneShotNextAt = NOW, periodicNextAt = NOW + 1_000))
  }

  @Test
  fun `un giro in coda che non e' mai partito aspetta la rete`() {
    assertEquals(ArchiveOutlook.WaitingNetwork, outlook(oneShotNextAt = NOW - 5_000, periodicNextAt = NOW + HOUR))
  }

  @Test
  fun `un giro gia' tentato con l'ora nel futuro e' un nuovo tentativo`() {
    assertEquals(ArchiveOutlook.Retry(NOW + 60_000), outlook(oneShotNextAt = NOW + 60_000, oneShotAttempts = 1))
  }

  @Test
  fun `un giro gia' tentato con l'ora passata aspetta solo la rete`() {
    assertEquals(ArchiveOutlook.WaitingNetwork, outlook(oneShotNextAt = NOW - 1, oneShotAttempts = 2))
  }

  @Test
  fun `senza giri in coda vale il periodico`() {
    assertEquals(ArchiveOutlook.Scheduled(NOW + HOUR), outlook(periodicNextAt = NOW + HOUR))
  }

  @Test
  fun `un periodico scaduto aspetta la rete, non si dice un'ora passata`() {
    assertEquals(ArchiveOutlook.WaitingNetwork, outlook(periodicNextAt = NOW - HOUR))
  }

  @Test
  fun `acceso ma senza lavori in coda non si inventa un orario`() {
    assertEquals(ArchiveOutlook.Unknown, outlook())
    assertEquals(ArchiveOutlook.Unknown, outlook(periodicNextAt = Long.MAX_VALUE))
  }

  private fun outlook(
    hasComputer: Boolean = true,
    enabled: Boolean = true,
    running: Boolean = false,
    oneShotNextAt: Long? = null,
    oneShotAttempts: Int = 0,
    periodicNextAt: Long? = null,
  ) = ArchiveOutlook.of(hasComputer, enabled, running, oneShotNextAt, oneShotAttempts, periodicNextAt, NOW)

  private companion object {
    const val NOW = 1_700_000_000_000L
    const val HOUR = 3_600_000L
  }
}
