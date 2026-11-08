package dev.pampa.pampanotes.core.importing

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** One bounded-memory pass for provider copies and verified attachment staging. */
internal object ImportFileCopy {
  fun copy(
    input: InputStream,
    target: File,
    expectedBytes: Long? = null,
    expectedSha: String? = null,
    checkpoint: () -> Unit = {},
    onBytes: (Long) -> Unit = {},
  ): Pair<String, Long> {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    try {
      target.outputStream().use { output ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          checkpoint()
          val count = input.read(buffer)
          if (count < 0) break
          if (count == 0) continue
          total = Math.addExact(total, count.toLong())
          require(expectedBytes == null || total <= expectedBytes) { "Attachment size mismatch" }
          digest.update(buffer, 0, count)
          output.write(buffer, 0, count)
          onBytes(count.toLong())
        }
      }
      checkpoint()
      val sha = digest.digest().joinToString("") { "%02x".format(it) }
      require(expectedBytes == null || total == expectedBytes) { "Attachment size mismatch" }
      require(expectedSha == null || sha.equals(expectedSha, true)) { "Attachment integrity mismatch" }
      return sha to total
    } catch (error: Exception) {
      target.delete()
      throw error
    }
  }
}
