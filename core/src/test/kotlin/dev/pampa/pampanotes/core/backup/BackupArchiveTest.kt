package dev.pampa.pampanotes.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupArchiveTest {

  @get:Rule val temp = TemporaryFolder()

  private fun card(schema: Int = BackupManifest.SCHEMA, audio: Boolean = true) = BackupManifest(
    schema = schema,
    createdAt = 1_789_740_000_000,
    app = "Pampa Notes 0.1.0",
    databaseVersion = 2,
    includesAudio = audio,
    counts = BackupCounts(folders = 2, notes = 3),
  )

  private fun file(name: String, content: String): File =
    temp.newFile(name).apply { writeText(content) }

  @Test
  fun `quello che si scrive e' quello che si rilegge`() {
    val db = file("pampa.db", "SQLite format 3 (per finta)")
    val voice = file("p-1.m4a", "audio")
    val out = ByteArrayOutputStream()
    BackupArchive.write(out, card(), db, audio = listOf(voice))

    val staging = temp.newFolder("staging")
    val staged = BackupArchive.extract(ByteArrayInputStream(out.toByteArray()), staging)

    assertEquals(3, staged.manifest.counts.notes)
    assertEquals("Pampa Notes 0.1.0", staged.manifest.app)
    assertEquals(db.readText(), staged.database.readText())
    assertEquals("audio", File(staged.audio, "p-1.m4a").readText())
  }

  @Test
  fun `il manifesto si legge senza estrarre i gigabyte che vengono dopo`() {
    val db = file("pampa.db", "x".repeat(4096))
    val out = ByteArrayOutputStream()
    BackupArchive.write(out, card(), db)

    val read = BackupArchive.readManifest(ByteArrayInputStream(out.toByteArray()))
    assertEquals(2, read.databaseVersion)
  }

  @Test
  fun `un nome che esce dalla cartella non e' accettabile`() {
    assertTrue(BackupArchive.accepts("manifest.json"))
    assertTrue(BackupArchive.accepts("database/pampa_notes.db"))
    assertTrue(BackupArchive.accepts("files/audio/p-1.m4a"))

    assertFalse(BackupArchive.accepts("../databases/altro.db"))
    assertFalse(BackupArchive.accepts("files/audio/../../../evil.so"))
    assertFalse(BackupArchive.accepts("/etc/passwd"))
    assertFalse(BackupArchive.accepts("C:/Windows/system32"))
    // Uno zip scritto su Windows separa con la barra rovesciata, e Java non la riconosce: il nome
    // diventerebbe un file solo, con le barre dentro.
    assertFalse(BackupArchive.accepts("files" + 92.toChar() + "audio" + 92.toChar() + "p-1.m4a"))
    // Fuori dai posti previsti: un domani ce ne sara' uno in piu', non uno qualsiasi.
    assertFalse(BackupArchive.accepts("lib/libpwn.so"))
    assertFalse(BackupArchive.accepts("files/audio/"))
  }

  @Test
  fun `una voce ostile si salta e il resto si ripristina lo stesso`() {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      zip.putNextEntry(ZipEntry(BackupEntries.MANIFEST))
      zip.write(
        """{"schema":1,"createdAt":1,"app":"t","databaseVersion":2,"includesAudio":false}"""
          .toByteArray(Charsets.UTF_8),
      )
      zip.closeEntry()
      zip.putNextEntry(ZipEntry("../../evil.so"))
      zip.write("pwn".toByteArray())
      zip.closeEntry()
      zip.putNextEntry(ZipEntry(BackupEntries.DATABASE))
      zip.write("vero".toByteArray())
      zip.closeEntry()
    }

    val staging = temp.newFolder("hostile")
    val staged = BackupArchive.extract(ByteArrayInputStream(out.toByteArray()), staging)
    assertEquals("vero", staged.database.readText())
    assertFalse(File(staging.parentFile, "evil.so").exists())
    assertFalse(File(staging, "evil.so").exists())
  }

  @Test
  fun `un file che non e' un backup lo dice invece di lasciare l'app a meta'`() {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      zip.putNextEntry(ZipEntry("foto.jpg"))
      zip.write(ByteArray(16))
      zip.closeEntry()
    }
    val staging = temp.newFolder("nonbackup")

    val failure = runCatching { BackupArchive.extract(ByteArrayInputStream(out.toByteArray()), staging) }
    assertTrue(failure.exceptionOrNull() is BackupFailure)
  }

  @Test
  fun `un backup di una versione piu' recente si rifiuta`() {
    val db = file("pampa.db", "dati")
    val out = ByteArrayOutputStream()
    BackupArchive.write(out, card(schema = BackupManifest.SCHEMA + 1), db)

    val staging = temp.newFolder("futuro")
    val failure = runCatching { BackupArchive.extract(ByteArrayInputStream(out.toByteArray()), staging) }
    assertTrue(failure.exceptionOrNull() is BackupFailure)
  }

  @Test
  fun `un backup senza database non e' un backup`() {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      zip.putNextEntry(ZipEntry(BackupEntries.MANIFEST))
      zip.write(
        """{"schema":1,"createdAt":1,"app":"t","databaseVersion":2}""".toByteArray(Charsets.UTF_8),
      )
      zip.closeEntry()
    }
    val staging = temp.newFolder("vuoto")
    val failure = runCatching { BackupArchive.extract(ByteArrayInputStream(out.toByteArray()), staging) }
    assertTrue(failure.exceptionOrNull() is BackupFailure)
  }

  @Test
  fun `il progresso arriva a uno`() {
    val db = file("pampa.db", "x".repeat(200_000))
    var last = -1f
    BackupArchive.write(ByteArrayOutputStream(), card(), db) { last = it }
    assertEquals(1f, last, 0.0001f)
  }

  @Test
  fun `un file sparito a meta' backup si salta, e la chiusura dice cosa e' entrato`() {
    val db = file("pampa.db", "database")
    val voice = file("p-1.m4a", "audio")
    val gone = File(temp.root, "p-2.m4a")
    val out = ByteArrayOutputStream()
    val trailer = BackupArchive.write(out, card(), db, audio = listOf(voice, gone))
    assertEquals(1, trailer.audioFiles)
    assertEquals(5L, trailer.audioBytes)

    val staged = BackupArchive.extract(ByteArrayInputStream(out.toByteArray()), temp.newFolder("vanished"))
    assertTrue(staged.manifest.sealed)
    assertTrue(File(staged.audio, "p-1.m4a").exists())
    assertFalse(File(staged.audio, "p-2.m4a").exists())
  }

  @Test
  fun `uno zip troncato fra due voci si rifiuta prima di toccare niente`() {
    val db = file("pampa.db", "database")
    val voices = (1..3).map { file("p-$it.m4a", "audio $it") }
    val out = ByteArrayOutputStream()
    BackupArchive.write(out, card(), db, audio = voices)
    val whole = out.toByteArray()

    // Si riscrive lo stesso archivio fermandosi dopo la seconda registrazione: per ZipInputStream e'
    // un archivio che finisce li', senza errori.
    val truncated = ByteArrayOutputStream()
    java.util.zip.ZipInputStream(ByteArrayInputStream(whole)).use { input ->
      ZipOutputStream(truncated).use { zip ->
        var entry = input.nextEntry
        while (entry != null && entry.name != BackupEntries.AUDIO_DIR + "p-3.m4a") {
          zip.putNextEntry(ZipEntry(entry.name))
          zip.write(input.readBytes())
          zip.closeEntry()
          entry = input.nextEntry
        }
      }
    }
    val failure = runCatching { BackupArchive.extract(ByteArrayInputStream(truncated.toByteArray()), temp.newFolder("cut")) }
    assertEquals(BackupFailure.Reason.INCOMPLETE, (failure.exceptionOrNull() as? BackupFailure)?.reason)
  }

  @Test
  fun `un backup di prima, senza chiusura, si controlla sul database`() {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      zip.putNextEntry(ZipEntry(BackupEntries.MANIFEST))
      zip.write(
        """{"schema":1,"createdAt":1,"app":"t","databaseVersion":2,"databaseBytes":10}""".toByteArray(Charsets.UTF_8),
      )
      zip.closeEntry()
      zip.putNextEntry(ZipEntry(BackupEntries.DATABASE))
      zip.write("corto".toByteArray())
      zip.closeEntry()
    }
    val failure = runCatching { BackupArchive.extract(ByteArrayInputStream(out.toByteArray()), temp.newFolder("old")) }
    assertEquals(BackupFailure.Reason.INCOMPLETE, (failure.exceptionOrNull() as? BackupFailure)?.reason)
  }

  @Test
  fun `il nome del file porta la data, cosi' i backup si ordinano da soli`() {
    val name = backupFileName(1_789_740_000_000)
    assertTrue(name.startsWith("pampa-notes-backup-"))
    assertTrue(name.endsWith(".zip"))
    assertTrue(name.length > "pampa-notes-backup-.zip".length)
  }
}
