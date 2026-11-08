package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.alignment.AlignmentProtocol
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Installation-local work evidence, never exported. Failures preserve all previous paid results. */
internal open class TranscriptionWorkStorage {
  private val json = Json { encodeDefaults = true }

  open fun write(file: File, text: String) {
    val pending = File(file.parentFile, "${file.name}.pending")
    if (pending.exists()) throw IOException("Interrupted transcription storage write: ${file.name}")
    FileOutputStream(pending).use { stream ->
      stream.write(text.toByteArray(Charsets.UTF_8))
      stream.fd.sync()
    }
    Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  }

  fun prepare(directory: File, signature: String, request: TranscribeRequest, cloud: Boolean): File {
    if (!directory.isDirectory) throw IOException("Transcription work directory unavailable")
    val stamp = File(directory, StoredChunks.WORK_FILE)
    val entries = directory.listFiles() ?: throw IOException("Cannot inspect transcription work directory")
    val previous = if (stamp.exists()) stamp.readText() else null
    if (previous != signature && entries.isNotEmpty()) {
      // A deliberate switch to the companion must not destroy an earlier cloud result.
      if (!cloud) {
        val alternate = File(directory, "companion-${AlignmentProtocol.sha256(signature)}")
        if (!alternate.isDirectory && !alternate.mkdirs()) throw IOException("Cannot create companion work directory")
        return prepare(alternate, signature, request, cloud)
      }
      throw RetainedCloudWorkChanged("Unstamped or changed transcription plan; paid data retained. Start a new manual job.")
    }
    val options = File(directory, "options.json")
    val encoded = json.encodeToString(Options.serializer(), Options(signature, request.model, request.language, request.prompt, request.temperature))
    if (options.exists()) {
      if (options.readText() != encoded) throw RetainedCloudWorkChanged("Transcription options changed; paid data retained. Start a new manual job.")
    } else write(options, encoded)
    // This commit marker is written last, atomically, before any upload.
    if (previous == null) write(stamp, signature)
    return directory
  }

  fun identity(directory: File): Input? {
    val file = File(directory, "input.json")
    if (!file.exists()) return null
    return json.decodeFromString(Input.serializer(), file.readText()).also {
      if (it.version != 1 || !AlignmentProtocol.validHash(it.sha256)) throw IOException("Invalid saved transcription input")
    }
  }

  fun recordInput(directory: File, input: Input) = write(File(directory, "input.json"), json.encodeToString(Input.serializer(), input))

  fun beginUpload(resultFile: File): Upload = synchronized(uploadLock) {
    val marker = File(resultFile.parentFile, "${resultFile.name}.sending")
    // A rejection replaces this exact attempt atomically. Legacy/unknown/interrupted writes
    // stay conservative; neither a provider cooldown nor an exception can unlock them.
    if (marker.exists() && !rejectedMarker(marker))
      throw UncertainCloudRequest()
    if (File(marker.path + ".pending").exists()) throw UncertainCloudRequest()
    val token = UUID.randomUUID().toString()
    write(marker, "pending:$token")
    Upload(marker, token)
  }

  internal inner class Upload(private val marker: File, private val token: String) {
    /** Status evidence is separate from permission to retry: quota/accounting must still commit. */
    fun observedRefusal(code: Int) = synchronized(uploadLock) {
      require(code == 401 || code == 402 || code == 429)
      val previous = marker.readText()
      if (previous != "pending:$token" && previous != "rejected:$token")
        throw IOException("Transcription request identity changed; refusal evidence not saved")
      val evidence = File(marker.path + ".refusal")
      val value = "refusal:$token:$code"
      if (evidence.exists() && evidence.readText() == value) return@synchronized
      write(evidence, value)
    }

    fun rejected() = synchronized(uploadLock) {
      val previous = marker.readText()
      if (previous == "rejected:$token") return@synchronized
      if (previous != "pending:$token") throw IOException("Transcription request identity changed; automatic rerun refused")
      // Keep evidence on disk, even if cancellation/process death prevents the runner's catch.
      // write() refuses leftover .pending files and syncs before its atomic replacement.
      write(marker, "rejected:$token")
    }

    /** A later usage-storage failure revokes retry permission for this exact response. */
    fun uncertain() = synchronized(uploadLock) {
      val previous = marker.readText()
      if (previous == "pending:$token") return@synchronized
      if (previous != "rejected:$token") throw IOException("Transcription request identity changed; cannot retain uncertain usage")
      write(marker, "pending:$token")
    }
  }

  fun finishUpload(resultFile: File) = synchronized(uploadLock) {
    val marker = File(resultFile.parentFile, "${resultFile.name}.sending")
    if (marker.exists() && !marker.delete()) throw IOException("Cannot finish saved transcription request")
  }

  /** Unknown/interrupted markers never become permission to resend, including after restart. */
  fun hasUncertainUpload(directory: File): Boolean = synchronized(uploadLock) {
    directory.walkTopDown().filter { it.isFile }.any { file ->
      file.name.endsWith(".sending.pending") || (file.name.endsWith(".sending") && !rejectedMarker(file))
    }
  }

  fun hasUncertainUploadFor(result: File): Boolean {
    val marker = File(result.path + ".sending")
    return File(marker.path + ".pending").exists() || (marker.exists() && !rejectedMarker(marker))
  }

  private fun rejectedMarker(file: File): Boolean = runCatching {
    file.readText().matches(Regex("rejected:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
  }.getOrDefault(false)

  /** Explicit user recovery and sweeping share this predicate; scratch PCM alone is disposable. */
  fun hasRetainedWork(directory: File): Boolean = hasUncertainUpload(directory) || directory.walkTopDown().any {
    it.isFile && retainedResultName.matches(it.name)
  }

  /** Only derived audio is disposable after cancellation. Paid text and request evidence survive. */
  fun cleanUp(directory: File) {
    if (!hasRetainedWork(directory)) { directory.deleteRecursively(); return }
    directory.walkTopDown().filter { it.isFile && (it.name == "audio.pcm" || it.extension in setOf("m4a", "wav")) }
      .forEach { it.delete() }
  }

  fun savedModel(directory: File): String = json.decodeFromString(Options.serializer(), File(directory, "options.json").readText()).model

  @Serializable internal data class Input(val partId: String, val sha256: String, val durationMs: Long, val version: Int = 1)
  @Serializable private data class Options(val signature: String, val model: String, val language: String?, val prompt: String?, val temperature: Double)
  private companion object {
    val uploadLock = Any()
    val retainedResultName = Regex("(?:whole|chunk-[0-9]+)\\.json(?:\\.(?:pending|received(?:\\.pending)?))?")
  }
}
