package dev.pampa.pampanotes.core.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtensionForTest {

  @Test
  fun `l'estensione del nome vince su tutto`() {
    assertEquals("m4a", AppFiles.extensionFor("lezione.m4a", "application/octet-stream", "bin"))
    assertEquals("mp3", AppFiles.extensionFor("Registrazione 3.MP3", "audio/mpeg", "bin"))
  }

  @Test
  fun `senza estensione si guarda il mime`() {
    assertEquals("m4a", AppFiles.extensionFor("registrazione", "audio/mp4", "bin"))
    assertEquals("pdf", AppFiles.extensionFor("documento", "application/pdf", "bin"))
    assertEquals("docx", AppFiles.extensionFor("appunti", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "bin"))
  }

  @Test
  fun `un mime sconosciuto cade sul ripiego`() {
    assertEquals("bin", AppFiles.extensionFor("qualcosa", "application/x-misterioso", "bin"))
  }

  @Test
  fun `una coda che non e' un'estensione non diventa un'estensione`() {
    // "Lezione del 12.09.2026" finisce con "2026": quattro cifre non sono un formato di file.
    assertEquals("m4a", AppFiles.extensionFor("Lezione del 12.09.2026", "audio/mp4", "bin"))
    // Una parola lunga dopo il punto non e' un'estensione.
    assertEquals("pdf", AppFiles.extensionFor("relazione.definitiva", "application/pdf", "bin"))
  }
}

class HashingTest {

  @get:Rule val temp = TemporaryFolder()

  @Test
  fun `l'hash di un testo vuoto e' quello noto`() {
    assertEquals(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      Hashing.sha256(""),
    )
  }

  @Test
  fun `copiando si ottengono lo stesso hash e la stessa lunghezza`() {
    val payload = "una lezione su Kant, con qualche accento: perché, così, città.".repeat(500)
    val target = File(temp.root, "copia.txt")
    val (sha, bytes) = Hashing.copyHashing(payload.byteInputStream(), target)

    assertEquals(Hashing.sha256(payload), sha)
    assertEquals(payload.toByteArray().size.toLong(), bytes)
    assertEquals(payload, target.readText())
  }

  @Test
  fun `due contenuti diversi danno hash diversi`() {
    assertTrue(Hashing.sha256("a") != Hashing.sha256("b"))
  }
}
