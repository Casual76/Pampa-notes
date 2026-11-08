package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import kotlinx.serialization.Serializable

/** Public content only. No account, endpoint, job, relay, path, credential or retry state. */
@Serializable data class CloudTextPart(
  val id: String,
  val sha256: String,
  /** Null is unknown, never an empty original. */
  val text: String?,
  val language: String?,
  val provider: String,
  val model: String,
)
@Serializable data class CloudOriginal(
  val rawId: String,
  val rawVersion: Long,
  val text: String,
  val textSha256: String,
  val provider: String,
  val model: String,
  val language: String?,
  val parts: List<CloudTextPart>,
  /** Aggregate supplied by the runner, when distinct from the lossless per-part composition. */
  val reportedText: String? = null,
  /** A portable projection is explicitly not the complete original provider response. */
  val contentScope: String = "complete_original",
)
@Serializable data class CloudOverlay(val partId: String, val textSha256: String, val language: String, val timings: List<AlignmentTiming>)
@Serializable data class CloudSourceDocument(
  val version: Int = 1,
  val rawId: String,
  val rawVersion: Long,
  val textSha256: String,
  /** Current projection. Original snapshots below never change when parts move or disappear. */
  val parts: List<CloudTextPart>,
  val originals: List<CloudOriginal>,
  val overlays: List<CloudOverlay> = emptyList(),
  /** Portable/copy metadata never attests that imported IDs identify verified original audio. */
  val alignmentBinding: String = "source_snapshot",
)

object CloudSources {
  const val MAX_JSON = 32 * 1024 * 1024
  const val MAX_CONTENT = 4_000_000
  fun cloud(provider: String) = provider in setOf("groq", "openrouter", "mistral")
  fun version(raw: TranscriptEntity) = raw.createdAt.coerceAtLeast(1L)
  fun decode(value: String?): CloudSourceDocument? {
    if (value == null || value.length > MAX_JSON) return null
    return runCatching { AlignmentProtocol.json.decodeFromString(CloudSourceDocument.serializer(), value).also(::validate) }.getOrNull()
  }
  fun encode(value: CloudSourceDocument): String {
    validate(value)
    return AlignmentProtocol.json.encodeToString(CloudSourceDocument.serializer(), value).also { require(it.length <= MAX_JSON) }
  }
  private fun validate(value: CloudSourceDocument) {
    require(value.version == 1 && value.rawId.length in 1..128 && value.rawVersion > 0 && AlignmentProtocol.validHash(value.textSha256))
    require(value.alignmentBinding in setOf("source_snapshot", "portable_unbound"))
    require(value.parts.size <= 1000 && value.parts.map { it.id }.distinct().size == value.parts.size)
    require(value.originals.size in 1..1000 && value.originals.map { it.rawId to it.rawVersion }.distinct().size == value.originals.size)
    fun checkParts(parts: List<CloudTextPart>) {
      require(parts.size <= 1000 && parts.map { it.id }.distinct().size == parts.size)
      parts.forEach { p ->
        require(p.id.length in 1..128 && (p.sha256.isEmpty() || AlignmentProtocol.validHash(p.sha256)))
        require(p.text == null || p.text.length <= MAX_CONTENT)
        require(p.provider.length <= 128 && p.model.length <= 512 && (p.language?.length ?: 0) <= 64)
      }
    }
    checkParts(value.parts)
    var total = 0L
    value.originals.forEach { o ->
      require(o.rawId.length in 1..128 && o.rawVersion > 0 && o.text.length <= MAX_CONTENT && AlignmentProtocol.sha256(o.text) == o.textSha256)
      require((o.reportedText?.length ?: 0) <= MAX_CONTENT)
      require(o.contentScope in setOf("complete_original", "portable_projection"))
      checkParts(o.parts)
      total += o.text.length + (o.reportedText?.length ?: 0) + o.parts.sumOf { it.text?.length ?: 0 }
    }
    require(total <= MAX_CONTENT * 4L)
    require(value.overlays.size <= value.parts.size && value.overlays.map { it.partId }.distinct().size == value.overlays.size)
    value.overlays.forEach { overlay ->
      val part = value.parts.single { it.id == overlay.partId }
      require(part.text != null && AlignmentProtocol.sha256(part.text) == overlay.textSha256 && part.language == overlay.language)
      require(validOverlay(part.text, overlay.timings))
    }
  }
  private fun validOverlay(text: String, timings: List<AlignmentTiming>): Boolean {
    val tokens = AlignmentProtocol.tokens(text)
    if (text.length > AlignmentProtocol.MAX_TEXT || tokens.size != timings.size) return false
    var lastEnd = 0L
    return tokens.zip(timings).all { (span, timing) ->
      if (span.first != timing.textStart || span.last + 1 != timing.textEnd) false
      else when (timing.status) {
        "missing" -> timing.startMs == null && timing.endMs == null
        "measured" -> {
          val from = timing.startMs; val to = timing.endMs
          val valid = from != null && to != null && from >= lastEnd && to > from && text.substring(span).any { it.isLetterOrDigit() } && timing.reason == null
          if (valid) lastEnd = to!!
          valid
        }
        else -> false
      }
    }
  }
  fun matches(doc: CloudSourceDocument, raw: TranscriptEntity): Boolean =
    doc.rawId == raw.id && doc.rawVersion == version(raw) && doc.textSha256 == AlignmentProtocol.sha256(raw.text)

  /** Full ordered source identity, never a hash borrowed from replacement rows. */
  fun bindSource(doc: CloudSourceDocument, current: List<AudioPartEntity>): List<AlignmentPart>? {
    if (doc.alignmentBinding != "source_snapshot" || doc.originals.any { it.contentScope != "complete_original" }) return null
    val byId = current.associateBy { it.id }
    if (doc.parts.isEmpty() || doc.parts.any { byId[it.id]?.sha256 != it.sha256 || !AlignmentProtocol.validHash(it.sha256) }) return null
    if (current.map { it.id }.filter { id -> doc.parts.any { it.id == id } } != doc.parts.map { it.id }) return null
    return doc.parts.map { AlignmentPart(it.id, it.sha256) }
  }

  data class PortableProjection(val text: String, val cloudSourceJson: String?, val alignmentApplicable: Boolean = false)

  /**
   * Export/copy boundary. Call with the SOURCE RAW and SOURCE graph before alias/ID remapping.
   * Returns the exact selected text plus public metadata containing only selected part identities.
   * Complete app-local originals/reportedText are deliberately never copied into portable content.
   * Remapping never grants alignment applicability, even if row hashes happen to match.
   */
  fun portableProjection(raw: TranscriptEntity, selectedParts: List<AudioPartEntity>,
    remapRawId: String = raw.id, partIdRemap: Map<String, String> = emptyMap(), remapRawVersion: Long = version(raw)): PortableProjection {
    require(remapRawId.length in 1..128 && remapRawVersion > 0)
    // Preserve nullable/empty legacy state; an unknown opaque codec cannot be safely scope-filtered.
    if (raw.cloudSourceJson == null || raw.cloudSourceJson.isEmpty()) return PortableProjection(raw.text, raw.cloudSourceJson)
    val ordered = selectedParts.sortedWith(compareBy({ it.position }, { it.id }))
    require(ordered.map { it.id }.distinct().size == ordered.size)
    // Unsupported non-null metadata is replaced with an explicit unbound projection, never
    // downgraded to a nullable legacy row that auto-discovery might adopt as an original.
    val doc = decode(raw.cloudSourceJson)?.takeIf { matches(it, raw) } ?: create(raw, raw.text,
      ordered.map { CloudTextPart(it.id, "", null, raw.language, raw.provider, raw.model) })
    val byId = doc.parts.associateBy { it.id }
    val selected = ordered.mapNotNull { byId[it.id] }
    // A partial legacy multipart cannot be projected by guessing text boundaries.
    val whole = selected == doc.parts
    require(whole || selected.all { it.text != null }) { "Cannot losslessly scope unknown multipart cloud text" }
    val body = if (whole) raw.text else selected.joinToString("\n\n") { it.text!! }
    val projected = selected.map { p ->
      val id = partIdRemap[p.id] ?: p.id
      require(id.length in 1..128)
      // Never export an old audio identity outside the selected graph or borrow a replacement hash.
      val selectedHash = ordered.single { it.id == p.id }.sha256
      p.copy(id = id, sha256 = p.sha256.takeIf { it == selectedHash }.orEmpty())
    }
    require(projected.map { it.id }.distinct().size == projected.size) { "Portable part identity collision" }
    val provider = projected.map { it.provider }.distinct().joinToString(" + ").ifEmpty { raw.provider }
    val model = projected.map { it.model }.distinct().joinToString(" + ").ifEmpty { raw.model }
    val language = projected.map { it.language }.distinct().singleOrNull()
    val original = CloudOriginal(remapRawId, remapRawVersion, body, AlignmentProtocol.sha256(body), provider, model, language,
      projected, reportedText = null, contentScope = "portable_projection")
    val portable = CloudSourceDocument(rawId = remapRawId, rawVersion = remapRawVersion, textSha256 = original.textSha256,
      parts = projected, originals = listOf(original), overlays = emptyList(), alignmentBinding = "portable_unbound")
    return PortableProjection(body, encode(portable))
  }

  /** Strict imported/exported graph validation; true still means UNBOUND, never alignment approval. */
  fun validatePortable(raw: TranscriptEntity, selectedParts: List<AudioPartEntity>): Boolean {
    val value = raw.cloudSourceJson ?: return true
    if (value.isEmpty()) return true
    if (value.length > MAX_JSON) return false
    return runCatching {
      val doc = kotlinx.serialization.json.Json.decodeFromString(CloudSourceDocument.serializer(), value)
      validate(doc)
      if (!matches(doc, raw) || doc.alignmentBinding != "portable_unbound" || doc.overlays.isNotEmpty() || doc.originals.size != 1) return false
      val original = doc.originals.single()
      if (original.contentScope != "portable_projection" || original.reportedText != null || original.rawId != raw.id ||
        original.rawVersion != version(raw) || original.text != raw.text || original.parts != doc.parts) return false
      if (doc.parts.all { it.text != null } && doc.parts.joinToString("\n\n") { it.text!! } != raw.text) return false
      val ordered = selectedParts.sortedWith(compareBy({ it.position }, { it.id }))
      val byId = ordered.associateBy { it.id }
      if (ordered.map { it.id }.filter { id -> doc.parts.any { it.id == id } } != doc.parts.map { it.id }) return false
      doc.parts.all { p -> byId[p.id]?.let { p.sha256.isEmpty() || it.sha256 == p.sha256 } == true }
    }.getOrDefault(false)
  }

  /** Nullable legacy: single-part boundaries are lossless. Never infer multipart boundaries. */
  fun legacy(raw: TranscriptEntity, parts: List<AudioPartEntity>): CloudSourceDocument? {
    if (!cloud(raw.provider) || raw.cloudSourceJson != null) return null
    val mapped = parts.map { p -> CloudTextPart(p.id, p.sha256, if (parts.size == 1) raw.text else null, raw.language, raw.provider, raw.model) }
    return create(raw, raw.text, mapped)
  }
  fun create(raw: TranscriptEntity, exactText: String, parts: List<CloudTextPart>, reportedText: String? = null): CloudSourceDocument {
    val original = CloudOriginal(raw.id, version(raw), exactText, AlignmentProtocol.sha256(exactText), raw.provider, raw.model, raw.language, parts,
      reportedText?.takeUnless { it == exactText })
    return CloudSourceDocument(rawId = raw.id, rawVersion = version(raw), textSha256 = original.textSha256, parts = parts, originals = listOf(original))
  }

  /** Compose known parts exactly; unknown legacy portions use existing coarse display text only. */
  fun compose(parts: List<CloudTextPart>, segments: List<SegmentEntity>): String = parts.joinToString("\n\n") { p ->
    p.text ?: segments.filter { it.partId == p.id }.sortedWith(compareBy({ it.partStartMs }, { it.indexInPart })).joinToString(" ") { it.text }
  }

  fun project(doc: CloudSourceDocument, raw: TranscriptEntity, orderedIds: List<String>, segments: List<SegmentEntity>): Pair<String, CloudSourceDocument> {
    val selected = orderedIds.mapNotNull { id -> doc.parts.find { it.id == id } }
    val body = if (selected == doc.parts && matches(doc, raw)) raw.text else compose(selected, segments)
    return body to doc.copy(rawId = raw.id, rawVersion = version(raw), textSha256 = AlignmentProtocol.sha256(body), parts = selected,
      overlays = doc.overlays.filter { o -> selected.any { it.id == o.partId } })
  }

  /** Retains every original snapshot, including the full pre-split/deleted text. */
  fun combine(raw: TranscriptEntity, ordered: List<AudioPartEntity>, documents: List<CloudSourceDocument>, segments: List<SegmentEntity>): Pair<String, CloudSourceDocument> {
    val all = documents.flatMap { it.parts }.associateBy { it.id }
    val mapped = ordered.mapNotNull { all[it.id] }
    val body = compose(mapped, segments)
    val originals = documents.flatMap { it.originals }.groupBy { it.rawId to it.rawVersion }.map { (_, entries) ->
      require(entries.distinct().size == 1) { "Conflicting immutable cloud originals" }; entries.first()
    }
    return body to CloudSourceDocument(rawId = raw.id, rawVersion = version(raw), textSha256 = AlignmentProtocol.sha256(body), parts = mapped,
      originals = originals, overlays = documents.flatMap { it.overlays }.distinctBy { it.partId }.filter { o -> mapped.any { it.id == o.partId } },
      alignmentBinding = if (documents.any { it.alignmentBinding != "source_snapshot" || it.originals.any { o -> o.contentScope != "complete_original" } }) "portable_unbound" else "source_snapshot")
  }

  /** Exact text including untouched whitespace; absent times are deliberately nullable. */
  data class DisplaySpan(val textStart: Int, val textEnd: Int, val text: String, val startMs: Long?, val endMs: Long?, val evidence: String)
  fun display(part: CloudTextPart, overlay: CloudOverlay?): List<DisplaySpan> {
    val text = part.text ?: return emptyList()
    val valid = overlay?.takeIf { it.partId == part.id && it.language == part.language && it.textSha256 == AlignmentProtocol.sha256(text) && validOverlay(text, it.timings) }
    val evidence = valid?.timings.orEmpty().associateBy { it.textStart }
    val result = mutableListOf<DisplaySpan>()
    var cursor = 0
    for (span in AlignmentProtocol.tokens(text)) {
      if (span.first > cursor) result += DisplaySpan(cursor, span.first, text.substring(cursor, span.first), null, null, "whitespace")
      val t = evidence[span.first]?.takeIf { it.textEnd == span.last + 1 }
      result += DisplaySpan(span.first, span.last + 1, text.substring(span), t?.startMs, t?.endMs, t?.status ?: "missing")
      cursor = span.last + 1
    }
    if (cursor < text.length) result += DisplaySpan(cursor, text.length, text.substring(cursor), null, null, "whitespace")
    return result
  }
}
