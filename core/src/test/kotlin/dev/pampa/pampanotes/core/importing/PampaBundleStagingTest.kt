package dev.pampa.pampanotes.core.importing

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PampaBundleStagingTest {
  @get:Rule val temp = TemporaryFolder()
  private val bytes = ByteArray(1024 * 1024) { (it * 19).toByte() }
  private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
  private fun archive(path: String = "blobs/audio.m4a") = temp.newFile().also { file ->
    ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
  }
  private fun attachment(id: String = "A", path: String = "blobs/audio.m4a") = PampaBundleAttachment(id, true, path, bytes.size.toLong(), sha(bytes))

  @Test fun `deduplicated blob bytes retain separate entity keys and incremental progress`() {
    val audio = temp.newFolder(); val sources = temp.newFolder()
    val staging = PampaBundleStaging(audio, sources)
    val progress = mutableListOf<Float>()
    val stored = staging.stage(archive(), "", listOf(attachment("A"), attachment("B")), {}, progress::add)
    assertEquals(setOf(true to "A", true to "B"), stored.keys)
    assertNotEquals(stored[true to "A"], stored[true to "B"])
    assertEquals(2, audio.listFiles()!!.size)
    assertArrayEquals(bytes, File(audio, stored.getValue(true to "A")).readBytes())
    val retained = File(audio, stored.getValue(true to "B"))
    File(audio, stored.getValue(true to "A")).delete()
    assertArrayEquals(bytes, retained.readBytes())
    assertTrue(progress.size > 4)
    assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
    assertTrue(progress.any { it > 0f && it < 1f })
    assertEquals(1f, progress.last())
    staging.cleanup()
    assertTrue(audio.listFiles()!!.isEmpty())
  }

  @Test fun `tampered shared blob metadata and missing entries fail without touching retained files`() {
    for (bad in listOf(attachment("B").copy(sha256 = "0".repeat(64)), attachment("B", "absent.m4a"))) {
      val audio = temp.newFolder(); val sources = temp.newFolder()
      val retained = File(audio, "retained.m4a").also { it.writeText("local audio") }
      val staging = PampaBundleStaging(audio, sources)
      try { staging.stage(archive(), "", listOf(attachment(), bad), {}, {}); fail("Expected invalid attachment") }
      catch (_: IllegalArgumentException) { } finally { staging.cleanup() }
      assertEquals("local audio", retained.readText())
      assertEquals(listOf(retained), audio.listFiles()!!.toList())
    }
  }

  @Test fun `cancellation inside one large attachment cleans all staging`() {
    val audio = temp.newFolder(); val sources = temp.newFolder()
    val staging = PampaBundleStaging(audio, sources)
    var transferred = false
    try {
      staging.stage(archive(), "", listOf(attachment()), { if (transferred) throw CancellationException() }, { transferred = it > 0f })
      fail("Expected cancellation")
    } catch (_: CancellationException) { } finally { staging.cleanup() }
    assertTrue(transferred)
    assertTrue(audio.listFiles()!!.isEmpty())
  }

  @Test fun `text only package has a completed empty staging phase`() {
    val audio = temp.newFolder(); val sources = temp.newFolder()
    val staging = PampaBundleStaging(audio, sources)
    var last = -1f
    assertTrue(staging.stage(archive(), "", emptyList(), {}, { last = it }).isEmpty())
    assertEquals(1f, last)
    assertTrue(staging.files.isEmpty())
  }
}
