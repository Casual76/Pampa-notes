package dev.pampa.pampanotes.core.importing

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PampaBundleFileIntegrityRoundTwoTest {
  private val bytes = byteArrayOf(1, 2, 3)
  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  @Test fun audioAndSourceReferencesRequireExactBytesSizeAndExclusiveOwnership() {
    val root = Files.createTempDirectory("pn-integrity-r2").toFile()
    try {
      for (kind in listOf("audio", "sources")) {
        val dir = File(root, kind).also { it.mkdir() }
        val old = File(dir, "old.bin")
        val staged = File(dir, "staged.bin").also { it.writeBytes(bytes) }
        for (corrupt in listOf(byteArrayOf(9, 9, 9), byteArrayOf(1, 2))) {
          old.writeBytes(corrupt)
          assertFalse(PampaBundleFileIntegrity.verified(dir, old.name, 3, sha(bytes), true))
          assertEquals(staged.name, PampaBundleFileIntegrity.select(dir, old.name, 3, sha(bytes), true, staged.name))
          assertArrayEquals(corrupt, old.readBytes())
          assertArrayEquals(bytes, staged.readBytes())
        }
        old.writeBytes(bytes)
        assertEquals(old.name, PampaBundleFileIntegrity.select(dir, old.name, 3, sha(bytes).uppercase(), true, staged.name))
        assertEquals(staged.name, PampaBundleFileIntegrity.select(dir, old.name, 3, sha(bytes), false, staged.name))
      }
    } finally { root.deleteRecursively() }
  }

  @Test fun unsafeAndUnknownContentNeverQualifyForLocalReuse() {
    val root = Files.createTempDirectory("pn-path-r2").toFile()
    try {
      val dir = File(root, "audio").also { it.mkdir() }
      val outside = File(root, "outside.bin").also { it.writeBytes(bytes) }
      val valid = File(dir, "valid.bin").also { it.writeBytes(bytes) }
      for (name in listOf("../outside.bin", "..\\outside.bin", outside.absolutePath, "nested/../valid.bin")) {
        assertFalse(PampaBundleFileIntegrity.verified(dir, name, 3, sha(bytes), true))
      }
      assertFalse(PampaBundleFileIntegrity.verified(dir, valid.name, 3, "", true))
      assertFalse(PampaBundleFileIntegrity.verified(dir, valid.name, -1, sha(bytes), true))
      assertArrayEquals(bytes, outside.readBytes())
    } finally { root.deleteRecursively() }
  }

  @Test fun omittedUpdateRetainsMissingReferenceButRejectsExistingCorruptionWithoutChangingFiles() {
    val dir = Files.createTempDirectory("pn-omitted-r2").toFile()
    try {
      assertEquals("evicted.bin", PampaBundleFileIntegrity.select(dir, "evicted.bin", 3, sha(bytes), true, null))
      assertNull(PampaBundleFileIntegrity.select(dir, null, 0, "", true, null))
      val old = File(dir, "corrupt.bin").also { it.writeBytes(byteArrayOf(9, 9, 9)) }
      try {
        PampaBundleFileIntegrity.select(dir, old.name, 3, sha(bytes), true, null)
        fail("An omitted attachment cannot certify corrupt local content")
      } catch (_: IllegalArgumentException) { }
      assertArrayEquals(byteArrayOf(9, 9, 9), old.readBytes())
    } finally { dir.deleteRecursively() }
  }

  @Test fun cancellationDuringHashingPropagatesAndLeavesBothFilesUntouched() {
    val dir = Files.createTempDirectory("pn-cancel-r2").toFile()
    try {
      val large = ByteArray(3 * 64 * 1024) { (it % 251).toByte() }
      val old = File(dir, "old.bin").also { it.writeBytes(large) }
      val staged = File(dir, "staged.bin").also { it.writeBytes(large) }
      var checks = 0
      try {
        PampaBundleFileIntegrity.select(dir, old.name, large.size.toLong(), sha(large), true, staged.name) {
          if (++checks == 3) throw CancellationException("cancel mid-hash")
        }
        fail("Expected cancellation")
      } catch (_: CancellationException) { }
      assertEquals(3, checks)
      assertArrayEquals(large, old.readBytes())
      assertArrayEquals(large, staged.readBytes())
    } finally { dir.deleteRecursively() }
  }
}
