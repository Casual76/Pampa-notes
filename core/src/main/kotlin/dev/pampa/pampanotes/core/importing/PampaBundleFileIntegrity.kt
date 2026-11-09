package dev.pampa.pampanotes.core.importing

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Read-only verification. Never repairs an existing file in place or removes another owner. */
internal object PampaBundleFileIntegrity {
  private val sha256 = Regex("[a-fA-F0-9]{64}")

  private fun safeFile(directory: File, name: String): File? {
    if (name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == ':' || it.isISOControl() } || File(name).isAbsolute) return null
    val file = File(directory, name).absoluteFile
    return try {
      // Android may expose the private app directory through /data/user/0 -> /data/data.
      // Normalize the trusted parent before comparing paths. Keep the storage directory itself
      // and the individual attachment bound to their expected names: neither may redirect out.
      val absoluteDirectory = directory.absoluteFile
      val parent = absoluteDirectory.parentFile ?: return null
      val expectedDirectory = File(parent.canonicalFile, absoluteDirectory.name)
      file.takeIf {
        absoluteDirectory.canonicalFile == expectedDirectory &&
          it.canonicalFile == File(expectedDirectory, name)
      }
    } catch (_: IOException) { null }
  }

  fun verified(directory: File, name: String, bytes: Long, sha: String, exclusivelyOwned: Boolean, checkpoint: () -> Unit = {}): Boolean {
    checkpoint()
    if (!exclusivelyOwned || bytes < 0 || !sha.matches(sha256)) return false
    val file = safeFile(directory, name) ?: return false
    return try {
      if (!file.isFile || file.length() != bytes) return false
      val digest = MessageDigest.getInstance("SHA-256")
      var total = 0L
      file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          checkpoint()
          val count = input.read(buffer)
          if (count < 0) break
          total += count
          if (total > bytes) return false
          digest.update(buffer, 0, count)
        }
      }
      checkpoint()
      total == bytes && file.length() == bytes && digest.digest().joinToString("") { "%02x".format(it) }.equals(sha, true)
    } catch (_: IOException) { false }
  }

  /** Staged names refer to already verified immutable files, with one filename per entity.
   * Without staging, a missing safe reference may remain recoverable remotely. Existing corrupt,
   * shared or unsafe files abort the transaction instead of being blessed by an omitted update. */
  fun select(directory: File, oldName: String?, bytes: Long, sha: String, exclusivelyOwned: Boolean,
    stagedName: String?, checkpoint: () -> Unit = {}): String? {
    if (oldName != null && verified(directory, oldName, bytes, sha, exclusivelyOwned, checkpoint)) return oldName
    checkpoint()
    if (stagedName != null) {
      require(safeFile(directory, stagedName)?.isFile == true && stagedName != oldName) { "Invalid staged attachment reference" }
      return stagedName
    }
    if (oldName == null) return null
    val absent = safeFile(directory, oldName)?.let { !it.exists() } == true
    require(absent && exclusivelyOwned) { "Retained attachment integrity unavailable" }
    return oldName
  }
}
