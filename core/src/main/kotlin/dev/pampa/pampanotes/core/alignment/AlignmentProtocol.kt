package dev.pampa.pampanotes.core.alignment

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class AlignmentPart(val id: String, val sha256: String)
@Serializable data class AlignmentSource(
  @SerialName("raw_id") val rawId: String,
  @SerialName("raw_version") val rawVersion: Long,
  @SerialName("raw_sha256") val rawSha256: String,
  val parts: List<AlignmentPart>,
)
@Serializable data class AlignmentWindow(
  @SerialName("text_start") val textStart: Int,
  @SerialName("text_end") val textEnd: Int,
  @SerialName("start_ms") val startMs: Long,
  @SerialName("end_ms") val endMs: Long,
)
@Serializable data class AlignmentRequest(
  @SerialName("protocol_version") val protocolVersion: Int = 1,
  val source: AlignmentSource,
  @SerialName("part_id") val partId: String,
  val text: String,
  @SerialName("text_sha256") val textSha256: String,
  val language: String,
  val segments: List<AlignmentWindow>,
)
@Serializable data class AlignmentTiming(
  @SerialName("text_start") val textStart: Int,
  @SerialName("text_end") val textEnd: Int,
  @SerialName("start_ms") val startMs: Long?,
  @SerialName("end_ms") val endMs: Long?,
  val status: String,
  val reason: String? = null,
)
@Serializable data class AlignmentResponse(
  @SerialName("protocol_version") val protocolVersion: Int,
  val source: AlignmentSource,
  @SerialName("part_id") val partId: String,
  val text: String,
  @SerialName("text_sha256") val textSha256: String,
  val language: String,
  @SerialName("offset_unit") val offsetUnit: String,
  @SerialName("time_unit") val timeUnit: String,
  @SerialName("audio_sha256") val audioSha256: String,
  @SerialName("job_id") val jobId: String,
  val instance: String,
  val status: String,
  val timings: List<AlignmentTiming>,
)

/** Timing evidence is an additive overlay. Never pass a partial list to WordTimings.complete. */
object AlignmentProtocol {
  const val CAPABILITY = "forced_alignment_v1"
  const val MAX_TEXT = 200_000
  const val MAX_WINDOWS = 1000
  const val MAX_WINDOW_TEXT = 400
  const val MAX_WINDOW_MS = 30_000L
  val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
  private val hash = Regex("[a-f0-9]{64}")
  private val language = Regex("[a-z]{2,3}")
  private val job = Regex("[a-zA-Z0-9_-]{8,64}")

  fun sha256(text: String): String = digest(text.toByteArray(Charsets.UTF_8))
  fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return hex(digest.digest())
  }
  private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
  fun validHash(value: String) = hash.matches(value)
  fun validLanguage(value: String?) = value != null && language.matches(value)
  fun validJob(value: String) = job.matches(value)

  fun validUnicode(text: String): Boolean {
    var i = 0
    while (i < text.length) {
      val c = text[i++]
      if (c.isHighSurrogate()) { if (i == text.length || !text[i++].isLowSurrogate()) return false }
      else if (c.isLowSurrogate()) return false
    }
    return true
  }
  fun boundary(text: String, index: Int): Boolean = index in 0..text.length &&
    !(index > 0 && index < text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())

  // Python's Unicode \S semantics include control separators and NBSP; Java's default \S does not.
  private fun whitespace(c: Char) = c.isWhitespace() || Character.isSpaceChar(c) || c == '\u0085'
  fun tokens(text: String): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    var start = -1
    text.forEachIndexed { i, c ->
      if (whitespace(c)) { if (start >= 0) { spans += start until i; start = -1 } }
      else if (start < 0) start = i
    }
    if (start >= 0) spans += start until text.length
    return spans
  }

  fun validate(request: AlignmentRequest, durationMs: Long) {
    require(request.protocolVersion == 1)
    require(request.source.rawId.length in 1..128 && request.source.rawVersion > 0 && validHash(request.source.rawSha256))
    val parts = request.source.parts
    require(parts.size in 1..1000 && parts.map { it.id }.distinct().size == parts.size)
    require(parts.all { it.id.length in 1..128 && validHash(it.sha256) })
    require(parts.any { it.id == request.partId })
    require(request.text.length <= MAX_TEXT && validUnicode(request.text) && sha256(request.text) == request.textSha256)
    require(validLanguage(request.language) && request.segments.size <= MAX_WINDOWS)
    var textEnd = 0
    var timeEnd = 0L
    request.segments.forEach { w ->
      require(w.textStart >= textEnd && w.textEnd > w.textStart && boundary(request.text, w.textStart) && boundary(request.text, w.textEnd))
      require(w.startMs >= timeEnd && w.endMs > w.startMs && durationMs > 0 && w.endMs <= durationMs)
      require(w.endMs - w.startMs <= MAX_WINDOW_MS && w.textEnd - w.textStart <= MAX_WINDOW_TEXT)
      textEnd = w.textEnd; timeEnd = w.endMs
    }
  }

  /** Fail closed on wrong echo, missing token evidence, overlapping times or invented precision. */
  fun validate(response: AlignmentResponse, request: AlignmentRequest, durationMs: Long, jobId: String) {
    validate(request, durationMs)
    require(response.protocolVersion == 1 && response.source == request.source && response.partId == request.partId)
    require(response.text == request.text && response.textSha256 == request.textSha256 && response.language == request.language)
    require(response.offsetUnit == "utf16" && response.timeUnit == "ms" && response.jobId == jobId && validJob(jobId))
    require(response.instance.isNotBlank() && response.instance.length <= 128)
    require(response.audioSha256 == request.source.parts.single { it.id == request.partId }.sha256)
    val tokens = tokens(request.text)
    require(response.timings.size == tokens.size)
    var lastEnd = 0L
    response.timings.zip(tokens).forEach { (t, span) ->
      require(t.textStart == span.first && t.textEnd == span.last + 1)
      when (t.status) {
        "missing" -> require(t.startMs == null && t.endMs == null)
        "measured" -> {
          require(request.text.substring(span).any { it.isLetterOrDigit() })
          val start = requireNotNull(t.startMs); val end = requireNotNull(t.endMs)
          require(durationMs > 0 && start >= lastEnd && end > start && end <= durationMs && t.reason == null)
          val bounded = request.segments.any { w -> t.textStart >= w.textStart && t.textEnd <= w.textEnd && start >= w.startMs && end <= w.endMs }
          val whole = request.segments.isEmpty() && request.text.length <= MAX_WINDOW_TEXT && durationMs in 1..MAX_WINDOW_MS
          require(bounded || whole)
          lastEnd = end
        }
        else -> throw IllegalArgumentException("Unknown timing evidence")
      }
    }
    val measured = response.timings.count { it.status == "measured" }
    val status = when { measured == 0 -> "deferred"; measured == tokens.size -> "aligned"; else -> "partial" }
    require(response.status == status)
  }

  data class CoarseSegment(val text: String, val startMs: Long, val endMs: Long)

  /** Only exact substring mapping. A gap must be whitespace; ambiguity/normalization defers. */
  fun windows(text: String, segments: List<CoarseSegment>, durationMs: Long): List<AlignmentWindow> {
    if (!validUnicode(text)) return emptyList()
    val result = mutableListOf<AlignmentWindow>()
    var cursor = 0
    var timeEnd = 0L
    for (s in segments) {
      if (s.text.isEmpty()) continue
      val start = text.indexOf(s.text, cursor)
      if (start < cursor || text.substring(cursor, start).any { !whitespace(it) }) return emptyList()
      val end = start + s.text.length
      if (!boundary(text, start) || !boundary(text, end) || s.startMs < timeEnd || s.endMs <= s.startMs || s.endMs > durationMs) return emptyList()
      if (end - start <= MAX_WINDOW_TEXT && s.endMs - s.startMs <= MAX_WINDOW_MS) result += AlignmentWindow(start, end, s.startMs, s.endMs)
      cursor = end; timeEnd = s.endMs
      if (result.size > MAX_WINDOWS) return emptyList()
    }
    if (text.substring(cursor).any { !whitespace(it) }) return emptyList()
    return result
  }
}
