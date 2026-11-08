package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.files.AppFileWriteProtection
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Fresh immutable files only. Cleanup on rollback; release ONLY after the Room commit/cleanup. */
internal class PampaBundleStaging(private val audioDir: File, private val sourcesDir: File) {
  val files = mutableListOf<File>()
  private val owner = Any()
  private var started = false
  private var released = false

  fun stage(
    archive: File,
    root: String,
    attachments: List<PampaBundleAttachment>,
    checkpoint: () -> Unit,
    onProgress: (Float) -> Unit,
  ): Map<Pair<Boolean, String>, String> {
    check(!started && !released) { "Bundle staging is already used" }
    started = true
    try {
      return stageFiles(archive, root, attachments, checkpoint, onProgress)
    } catch (error: Throwable) {
      cleanup()
      throw error
    }
  }

  private fun stageFiles(
    archive: File,
    root: String,
    attachments: List<PampaBundleAttachment>,
    checkpoint: () -> Unit,
    onProgress: (Float) -> Unit,
  ): Map<Pair<Boolean, String>, String> {
    val included = attachments.filter { it.path != null }
    val groups = included.groupBy { it.path!! }.values
    val total = included.fold(0L) { sum, attachment -> Math.addExact(sum, attachment.bytes.coerceAtLeast(1)) }
    var completed = 0L
    val stored = mutableMapOf<Pair<Boolean, String>, String>()
    onProgress(0f)
    ZipFile(archive).use { zip ->
      groups.forEach { group ->
        checkpoint()
        val attachment = group.first()
        require(attachment.bytes >= 0 && attachment.sha256?.matches(Regex("[a-fA-F0-9]{64}")) == true) { "Invalid attachment metadata" }
        require(group.all { it.bytes == attachment.bytes && it.sha256.equals(attachment.sha256, true) }) { "Shared attachment metadata conflict" }
        val entry = zip.getEntry(root + attachment.path)
        require(entry != null && !entry.isDirectory && entry.size == attachment.bytes) { "Attachment size mismatch" }
        val extension = attachment.path!!.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "bin"
        var verified: File? = null
        group.forEach { reference ->
          checkpoint()
          // Local deletion/eviction owns files per entity. Sharing a filename would let deleting
          // one recording remove another's bytes. Decompress a blob once, then copy locally;
          // verify each fresh file, keeping both identities and their file lifetimes independent.
          val target = File(if (reference.audio) audioDir else sourcesDir, "${UUID.randomUUID()}.$extension")
          // Register before creation. AppFiles uses this same registry, including for the
          // historical two-directory staging constructor and separate AppFiles instances.
          AppFileWriteProtection.hold(target, owner)
          files += target
          // Only the first reference reads ZIP bytes; later independent copies use this verified
          // file. ZipFile does not compare media CRCs itself, so validate it in that same pass.
          val archivedInput = if (verified == null) java.util.zip.CheckedInputStream(zip.getInputStream(entry), java.util.zip.CRC32()) else null
          (archivedInput ?: verified!!.inputStream()).use { input ->
            ImportFileCopy.copy(input, target, reference.bytes, reference.sha256, checkpoint) { count ->
              completed += count
              onProgress((completed.toDouble() / total.coerceAtLeast(1)).toFloat())
            }
            require(archivedInput == null || archivedInput.checksum.value == entry.crc) { "Attachment ZIP CRC mismatch" }
          }
          if (verified == null) verified = target
          if (reference.bytes == 0L) completed++
          stored[reference.audio to reference.id] = target.name
          onProgress((completed.toDouble() / total.coerceAtLeast(1)).toFloat())
        }
      }
    }
    checkpoint()
    onProgress(1f)
    return stored
  }

  fun requireProtected() {
    check(!released && files.all { AppFileWriteProtection.heldBy(it, owner) }) { "Bundle staging is not protected" }
  }

  /** Keep committed bytes; old overlapping sweep snapshots retain their release generation. */
  fun release() {
    AppFileWriteProtection.release(owner)
    released = true
  }

  fun cleanup() {
    try { files.forEach { it.delete() } } finally { release() }
  }
}
