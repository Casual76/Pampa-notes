package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.alignment.AlignmentProtocol
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
      throw IOException("Unstamped or changed transcription plan; paid data retained. Start a new manual job.")
    }
    val options = File(directory, "options.json")
    val encoded = json.encodeToString(Options.serializer(), Options(signature, request.model, request.language, request.prompt, request.temperature))
    if (options.exists()) {
      if (options.readText() != encoded) throw IOException("Transcription options changed; paid data retained. Start a new manual job.")
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

  fun beginUpload(resultFile: File) {
    val marker = File(resultFile.parentFile, "${resultFile.name}.sending")
    if (marker.exists()) throw IOException("Uncertain previous paid request; results retained, automatic rerun refused")
    write(marker, "pending")
  }

  fun finishUpload(resultFile: File) {
    val marker = File(resultFile.parentFile, "${resultFile.name}.sending")
    if (marker.exists() && !marker.delete()) throw IOException("Cannot finish saved transcription request")
  }

  @Serializable internal data class Input(val partId: String, val sha256: String, val durationMs: Long, val version: Int = 1)
  @Serializable private data class Options(val signature: String, val model: String, val language: String?, val prompt: String?, val temperature: Double)
}
