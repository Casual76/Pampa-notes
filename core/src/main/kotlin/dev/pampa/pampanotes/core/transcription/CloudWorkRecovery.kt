package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.alignment.AlignmentProtocol
import dev.pampa.pampanotes.core.audio.ChunkSpec
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.JobEntity
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Read-only recovery, deliberately separate from RAW history, active selection and alignment. */
data class RecoveredCloudWork(val windows: List<RecoveredCloudWindow>, val uncertain: Boolean)
data class RecoveredCloudWindow(
  val part: Int, val startMs: Long, val endMs: Long?, val state: RecoveryWindowState, val text: String? = null,
)
enum class RecoveryWindowState { RECEIVED, MISSING, UNCERTAIN, UNAVAILABLE }

internal object CloudWorkRecovery {
  private const val PLAN = "recovery.json"
  private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

  @Serializable private data class Part(val id: String, val fileName: String, val durationMs: Long, val sha256: String, val position: Int, val createdAt: Long)
  @Serializable private data class Plan(val sessionId: String, val parts: List<Part>, val version: Int = 1)
  private fun plan(parts: List<AudioPartEntity>) = Plan(parts.first().sessionId, parts.sortedBy { it.position }.map {
    Part(it.id, it.fileName, it.durationMs, it.sha256, it.position, it.createdAt)
  })

  /** Capture the actual before-image before any POST; never manufacture one from a resumed cache. */
  fun prepare(directory: File, parts: List<AudioPartEntity>, storage: TranscriptionWorkStorage) {
    val file = File(directory, PLAN)
    val expected = plan(parts)
    if (file.exists()) {
      if (json.decodeFromString(Plan.serializer(), file.readText()) != expected)
        throw RetainedCloudWorkChanged("Original transcription placement changed; paid results retained")
    } else if (!storage.hasRetainedWork(directory)) {
      storage.write(file, json.encodeToString(Plan.serializer(), expected))
    }
  }

  /** Never fetch audio, create a job, save a transcript, settle usage, or contact a provider. */
  fun read(directory: File, job: JobEntity, parts: List<AudioPartEntity>, audioRoot: File): RecoveredCloudWork? {
    if (parts.isEmpty() || parts.any { it.sessionId != job.sessionId }) return null
    val storage = TranscriptionWorkStorage()
    if (!storage.hasRetainedWork(directory)) return null
    val signature = File(directory, StoredChunks.WORK_FILE).readText()
    if (!signature.startsWith("${job.provider}|")) return null
    if (storage.savedModel(directory) != job.model) return null
    val manifest = File(directory, PLAN)
    if (manifest.exists() && json.decodeFromString(Plan.serializer(), manifest.readText()) != plan(parts)) return null
    val windows = parts.sortedBy { it.position }.flatMapIndexed { index, part ->
      val dir = File(directory, "part-${part.id}")
      val input = runCatching { storage.identity(dir) }.getOrNull()
      val audio = File(audioRoot, part.fileName)
      val matches = input != null && input.partId == part.id && input.durationMs == part.durationMs &&
        (!AlignmentProtocol.validHash(part.sha256) || input.sha256 == part.sha256) &&
        runCatching {
          if (audio.isFile) AlignmentProtocol.sha256(audio) == input.sha256
          else part.sha256 == input.sha256 || (manifest.exists() && !AlignmentProtocol.validHash(part.sha256))
        }.getOrDefault(false)
      val planFile = File(dir, "plan.json")
      val specs = if (planFile.exists()) runCatching { StoredChunks.decodePlan(planFile.readText()) }.getOrNull() else null
      // A missing/corrupt split plan must not make chunk zero look like an entire part.
      val unknownPlan = (planFile.exists() && specs == null) ||
        (specs == null && dir.listFiles().orEmpty().any { it.name.startsWith("chunk-") })
      if (unknownPlan) return@flatMapIndexed listOf(RecoveredCloudWindow(index + 1, 0, part.durationMs.takeIf { it > 0 }, RecoveryWindowState.UNAVAILABLE))
      (specs ?: listOf(ChunkSpec(0, 0, part.durationMs))).map { spec ->
        val result = File(dir, if (specs == null) "whole.json" else "chunk-${spec.index}.json")
        // A complete fsynced pending response is readable even if atomic replacement failed.
        // This never commits it or unlocks its sending marker.
        val chunk = if (matches) listOf(result, File(result.path + ".pending")).firstNotNullOfOrNull { candidate ->
          if (candidate.isFile) runCatching { StoredChunks.decode(candidate.readText(), spec, signature) }.getOrNull() else null
        } else null
        val compatible = chunk != null && (chunk.audioSha256 == null || chunk.audioSha256 == input?.sha256) &&
          (chunk.sourceProvider == null || chunk.sourceProvider == job.provider) && (chunk.sourceModel == null || chunk.sourceModel == job.model)
        val bodyText = if (matches) listOf(File(result.path + ".received"), File(result.path + ".received.pending"))
          .firstNotNullOfOrNull { file -> runCatching {
            if (!file.isFile) null else {
              val body = json.parseToJsonElement(file.readText()) as? JsonObject
              if (body == null || "error" in body) null else (body["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
          }.getOrNull() } else null
        val state = when {
          compatible || bodyText != null -> RecoveryWindowState.RECEIVED
          (result.exists() || File(result.path + ".pending").exists()) -> RecoveryWindowState.UNAVAILABLE
          storage.hasUncertainUploadFor(result) -> RecoveryWindowState.UNCERTAIN
          else -> RecoveryWindowState.MISSING
        }
        RecoveredCloudWindow(index + 1, spec.startMs, spec.endMs.takeIf { it > spec.startMs }, state,
          if (compatible) chunk!!.sourceText ?: chunk.fallbackText.ifEmpty { chunk.segments.joinToString(" ") { it.text } } else bodyText)
      }
    }
    return RecoveredCloudWork(windows, storage.hasUncertainUpload(directory))
  }
}
