package dev.pampa.pampanotes.core.archive

import dev.pampa.pampanotes.core.settings.ArchiveFailure
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quando il computer «non c'e'» e quando invece c'e' e rifiuta un file. */
class ArchiveRulesTest {

  @Test
  fun `rete e tempo scaduto vogliono dire computer irraggiungibile`() {
    assertTrue(ArchiveRepository.isUnreachable(ConnectException("rifiutata")))
    assertTrue(ArchiveRepository.isUnreachable(SocketTimeoutException("scaduto")))
    assertTrue(ArchiveRepository.isUnreachable(ArchiveException(0, "server personale non configurato")))
  }

  @Test
  fun `una risposta del server, anche brutta, vuol dire che il computer c'e'`() {
    assertFalse(ArchiveRepository.isUnreachable(ArchiveException(500, "disco pieno")))
    assertFalse(ArchiveRepository.isUnreachable(ArchiveException(404, "non c'e'")))
    assertFalse(ArchiveRepository.isUnreachable(IllegalStateException("database")))
  }

  @Test
  fun `contano contro il file i rifiuti che parlano del file`() {
    assertTrue(ArchiveRepository.countsAgainstFile(413))
    assertTrue(ArchiveRepository.countsAgainstFile(500))
    assertFalse(ArchiveRepository.countsAgainstFile(401))
    assertFalse(ArchiveRepository.countsAgainstFile(429))
  }

  @Test
  fun `i rifiuti si salvano e si rileggono`() {
    val failure = ArchiveFailure("abc123", 2, 1_700_000_000_000)
    assertEquals(failure, ArchiveFailure.decode(failure.encode()))
    assertNull(ArchiveFailure.decode("rotto"))
  }

  @Test
  fun `un file mancante o diverso non e' un computer irraggiungibile`() {
    val missing: IOException = ArchiveMissing("Voce 001")
    assertTrue(missing !is ArchiveException)
    assertTrue(ArchiveMismatch() is IOException)
  }
}
