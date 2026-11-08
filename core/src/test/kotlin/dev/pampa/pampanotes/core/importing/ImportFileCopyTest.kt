package dev.pampa.pampanotes.core.importing

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportFileCopyTest {
  @get:Rule val temp = TemporaryFolder()
  private val bytes = ByteArray(256 * 1024 + 7) { (it * 31).toByte() }
  private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

  @Test fun `provider copy preserves all bytes and hashes in bounded reads`() {
    var largest = 0
    val input = object : java.io.ByteArrayInputStream(bytes) {
      override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        largest = maxOf(largest, length)
        return super.read(buffer, offset, length)
      }
    }
    val target = temp.newFile()
    var copied = 0L
    val result = input.use { ImportFileCopy.copy(it, target, onBytes = { count -> copied += count }) }
    assertEquals(sha(bytes) to bytes.size.toLong(), result)
    assertEquals(bytes.size.toLong(), copied)
    assertTrue(largest <= 65536)
    assertArrayEquals(bytes, target.readBytes())
  }

  @Test fun `cancelled provider copy deletes partial cache without reporting success`() {
    val target = File(temp.root, "cancelled.part")
    var checks = 0
    try {
      bytes.inputStream().use { ImportFileCopy.copy(it, target, checkpoint = { if (++checks == 3) throw CancellationException() }) }
      fail("Expected cancellation")
    } catch (_: CancellationException) { }
    assertFalse(target.exists())
  }

  @Test fun `truncated excess and corrupt bytes never survive staging`() {
    for ((size, hash) in listOf(bytes.size - 1L to sha(bytes), bytes.size + 1L to sha(bytes), bytes.size.toLong() to "0".repeat(64))) {
      val target = temp.newFile()
      try { bytes.inputStream().use { ImportFileCopy.copy(it, target, size, hash) }; fail("Expected integrity failure") }
      catch (_: IllegalArgumentException) { }
      assertFalse(target.exists())
    }
  }

  @Test fun `provider read failure cleans partial cache`() {
    val target = temp.newFile()
    val input = object : java.io.InputStream() { override fun read(): Int = throw IOException("provider offline") }
    try { input.use { ImportFileCopy.copy(it, target) }; fail("Expected unavailable provider") } catch (_: IOException) { }
    assertFalse(target.exists())
  }

  @Test fun `failure categories keep cancellation and actionable distinctions`() {
    assertEquals(ImportFailure.ACCESS, ImportFailure.of(SecurityException("secret URI")))
    assertEquals(ImportFailure.NO_SPACE, ImportFailure.of(IOException("open failed: ENOSPC")))
    assertEquals(ImportFailure.VERSION, ImportFailure.of(IllegalArgumentException("Unsupported Pampa schema 100")))
    assertEquals(ImportFailure.CORRUPT, ImportFailure.of(IllegalArgumentException("Attachment integrity mismatch: private/path")))
    assertEquals(ImportFailure.DESTINATION, ImportFailure.of(IllegalArgumentException("Destination cannot be inside the imported hierarchy")))
    assertEquals(ImportFailure.ACTIVE_JOB, ImportFailure.of(IllegalArgumentException("Finish the active legacy session job before upgrading")))
    assertEquals(ImportFailure.EMPTY, ImportFailure.of(ImportFailureException(ImportFailure.EMPTY)))
    assertEquals(ImportFailure.FAILED, ImportFailure.fromCode("private error details"))
    try { ImportFailure.of(CancellationException()); fail("Expected cancellation") } catch (_: CancellationException) { }
  }
}
