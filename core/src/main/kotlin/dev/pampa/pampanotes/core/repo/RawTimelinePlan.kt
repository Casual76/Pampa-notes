package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.alignment.CloudSources
import dev.pampa.pampanotes.core.alignment.CloudTextPart
import dev.pampa.pampanotes.core.model.wordCount
import dev.pampa.pampanotes.core.transcription.SessionAssembler
import dev.pampa.pampanotes.core.transcription.SessionSegment
import java.util.UUID

/** A transaction's before image. A segment's transcriptId identifies its version, never merely
 * its audio part. All destinations are planned together before any source can be deleted. */
internal data class RawTimelineSnapshot(
  val sessions: List<SessionEntity>,
  val parts: List<AudioPartEntity>,
  val transcripts: List<TranscriptEntity>,
  val segments: List<SegmentEntity>,
)

internal object RawTimelinePlan {
  data class Result(val transcripts: List<TranscriptEntity>, val segments: List<SegmentEntity>, val active: Map<String, String?>)

  fun build(before: RawTimelineSnapshot, sessions: List<SessionEntity>, parts: List<AudioPartEntity>, now: Long,
    newRawIds: Map<String, String> = emptyMap(), relocatedSessions: Map<String, String> = emptyMap()): Result {
    val currentSessions = sessions.associateBy { it.id }
    val ordered = parts.groupBy { it.sessionId }.mapValues { (_, rows) -> rows.sortedWith(compareBy({ it.position }, { it.id })) }
    val byPart = parts.associateBy { it.id }
    val offsets = ordered.mapValues { (_, rows) -> SessionAssembler.offsets(rows.map { SessionAssembler.Part(it.id, it.durationMs) }) }
    val oldById = before.transcripts.associateBy { it.id }
    val own = before.segments.groupBy { it.transcriptId }
    val rows = linkedMapOf<String, TranscriptEntity>()
    val timed = linkedMapOf<String, List<SegmentEntity>>()
    val placement = mutableMapOf<Pair<String, String>, String>()
    val changed = mutableSetOf<String>()

    fun fresh(kind: String, source: String, destination: String): String {
      var salt = 0
      var id = derived(kind, source, destination)
      while (id in oldById || id in rows) id = derived(kind, source, "$destination:${++salt}")
      return id
    }
    fun movedSession(sessionId: String): String {
      relocatedSessions[sessionId]?.let { return it }
      val destinations = before.parts.filter { it.sessionId == sessionId }.mapNotNull { byPart[it.id]?.sessionId }.distinct()
      return destinations.singleOrNull() ?: sessionId
    }
    fun rebase(id: String, session: String, segments: List<SegmentEntity>): List<SegmentEntity> = segments
      .sortedWith(compareBy({ byPart.getValue(it.partId).position }, { it.partStartMs }, { it.indexInPart }))
      .map { s ->
        val offset = offsets.getValue(session).getValue(s.partId)
        s.copy(id = 0, transcriptId = id, sessionStartMs = Math.addExact(offset, s.partStartMs), sessionEndMs = Math.addExact(offset, s.partEndMs))
      }
    fun text(segments: List<SegmentEntity>, session: String): String = SessionAssembler.reassemble(
      ordered[session].orEmpty().map { SessionAssembler.Part(it.id, it.durationMs) },
      segments.map { s -> SessionSegment(s.partId, s.indexInPart, s.partStartMs, s.partEndMs, s.sessionStartMs, s.sessionEndMs, s.text,
        s.noSpeechProb, s.avgLogProb, s.wordsJson, s.wordsEstimated, s.speaker) },
    ).text

    before.transcripts.filter { it.kind == TranscriptKind.RAW }.forEach { raw ->
      val original = own[raw.id].orEmpty()
      val source = CloudSources.forTimeline(raw, before.parts.filter { it.sessionId == raw.sessionId })
      val coverage = (original.map { it.partId } + source?.parts.orEmpty().map { it.id }).distinct().filter { it in byPart }
      val groups = coverage.groupBy { byPart.getValue(it).sessionId }.mapValues { (_, ids) -> original.filter { it.partId in ids } }
      val primary = when {
        raw.sessionId in groups -> raw.sessionId
        groups.isNotEmpty() -> groups.keys.sortedWith(compareBy({ currentSessions[it]?.position ?: Int.MAX_VALUE }, { it })).first()
        else -> movedSession(raw.sessionId)
      }
      require(primary in currentSessions) { "Missing RAW destination" }
      // Keep an explicit empty branch when the selected version's recorded parts all leave,
      // but untranscribed parts remain. A null active ID would silently select unrelated RAWs.
      val staying = setOf(raw.sessionId).filter { ordered[it].orEmpty().isNotEmpty() }
      (groups.keys + primary + staying).forEach { destination ->
        val id = if (destination == primary) raw.id else fresh("split", raw.id, destination)
        require(id !in rows) { "RAW history identity collision" }
        val segments = rebase(id, destination, groups[destination].orEmpty())
        fun content(rows: List<SegmentEntity>) = rows.map { it.copy(id = 0, transcriptId = "", sessionStartMs = 0, sessionEndMs = 0) }
        val unchanged = original.isEmpty() || raw.text.isNotBlank() && content(original) == content(segments)
        val projected = source?.let { CloudSources.project(it, raw.copy(id = id), ordered[destination].orEmpty().map { p -> p.id }, segments) }
        val body = projected?.first ?: if (unchanged) raw.text else text(segments, destination)
        if (body != raw.text) changed += id
        rows[id] = raw.copy(id = id, sessionId = destination, text = body, wordCount = if (body == raw.text) raw.wordCount else body.wordCount(),
          cloudSourceJson = projected?.second?.let(CloudSources::encode) ?: raw.cloudSourceJson)
        timed[id] = segments
        placement[raw.id to destination] = id
      }
    }
    // Refinements are retained as histories. Whole-version moves retain their IDs and parent
    // links; a partial split cannot safely invent a partial refined text.
    before.transcripts.filter { it.kind != TranscriptKind.RAW }.forEach { refined ->
      val parent = refined.parentId?.let { rows[it] }
      val destination = parent?.sessionId ?: movedSession(refined.sessionId)
      require(destination in currentSessions) { "Missing refinement destination" }
      val segments = own[refined.id].orEmpty().filter { it.partId in byPart }
      require(segments.all { byPart.getValue(it.partId).sessionId == destination }) { "Cannot split timed refinement" }
      rows[refined.id] = refined.copy(sessionId = destination)
      timed[refined.id] = rebase(refined.id, destination, segments)
    }

    fun selected(session: SessionEntity): TranscriptEntity? = RawTranscripts.forSelection(before.transcripts.filter { it.sessionId == session.id }, session.activeTranscriptId)
    val active = linkedMapOf<String, String?>()
    sessions.forEach { session ->
      val originalSession = before.sessions.find { it.id == session.id }
      val selectedRaw = originalSession?.let(::selected)
      val preferred = buildList {
        selectedRaw?.let { placement[it.id to session.id]?.let(::add) }
        before.sessions.filter { it.id != session.id }.sortedWith(compareBy({ it.position }, { it.id })).forEach { source ->
          selected(source)?.let { placement[it.id to session.id]?.let(::add) }
        }
      }.distinct()
      val explicitWithoutRaw = originalSession?.activeTranscriptId?.let { oldById[it] } != null && selectedRaw == null
      val fallback = if (explicitWithoutRaw || preferred.isNotEmpty()) emptyList() else rows.values.filter { it.kind == TranscriptKind.RAW && it.sessionId == session.id }
        .sortedWith(compareByDescending<TranscriptEntity> { it.createdAt }.thenBy { it.id }).map { it.id }
      val candidates = (preferred + fallback).distinct()
      val chosen = mutableListOf<String>()
      val covered = mutableSetOf<String>()
      candidates.forEach { id ->
        val coverage = (timed[id].orEmpty().map { it.partId } + CloudSources.decode(rows.getValue(id).cloudSourceJson)?.parts.orEmpty().map { it.id }).toSet()
        if (chosen.isEmpty() || coverage.isNotEmpty() && coverage.none { it in covered } && covered.isNotEmpty()) {
          chosen += id; covered += coverage
        }
      }
      val repairId = newRawIds[session.id]?.takeIf { id -> before.transcripts.none { it.kind == TranscriptKind.RAW && it.sessionId == session.id } && id !in rows }
      val visibleRaw = if (chosen.size > 1 || repairId != null && chosen.isNotEmpty()) {
        val id = repairId ?: fresh("compose", session.id, chosen.joinToString("|"))
        require(id !in rows) { "Composed RAW identity collision" }
        val segments = rebase(id, session.id, chosen.flatMap { timed[it].orEmpty() })
        val origins = chosen.map { rows.getValue(it) }
        val draft = origins.first().copy(id = id, sessionId = session.id, createdAt = now)
        val cloud = if (origins.any { it.cloudSourceJson != null }) {
          val docs = origins.map { origin -> CloudSources.forTimeline(origin, ordered[session.id].orEmpty()) ?: run {
            val coveredIds = timed[origin.id].orEmpty().map { it.partId }.toSet()
            val mapped = ordered[session.id].orEmpty().filter { it.id in coveredIds }.map { p ->
              CloudTextPart(p.id, "", if (coveredIds.size == 1) origin.text else null,
                origin.language.takeIf { coveredIds.size == 1 }, origin.provider, origin.model)
            }
            // Segment coverage is presentation evidence, never historical audio attestation.
            CloudSources.create(origin, origin.text, mapped).let { doc -> doc.copy(alignmentBinding = "portable_unbound",
              originals = doc.originals.map { it.copy(contentScope = "derived_presentation") }) }
          } }
          CloudSources.combine(draft, ordered[session.id].orEmpty(), docs, segments)
        } else null
        val body = cloud?.first ?: if (chosen.size == 1 && segments.isEmpty()) origins.first().text else text(segments, session.id)
        rows[id] = draft.copy(parentId = null, text = body, wordCount = body.wordCount(), cloudSourceJson = cloud?.second?.let(CloudSources::encode),
          model = origins.map { it.model }.distinct().filter { it.isNotBlank() }.joinToString(" + "), createdAt = now)
        timed[id] = segments
        id
      } else chosen.firstOrNull()
      val oldActive = originalSession?.activeTranscriptId?.let { rows[it] }?.takeIf { it.sessionId == session.id }
      val related = if (chosen.size == 1) setOf(visibleRaw, chosen.first()) else setOf(visibleRaw)
      val carriedRefinement = before.sessions.asSequence().filter { it.id != session.id }.mapNotNull { it.activeTranscriptId?.let(rows::get) }
        .firstOrNull { it.kind == TranscriptKind.REFINED && it.sessionId == session.id && it.parentId in related && it.parentId !in changed }
      active[session.id] = when {
        oldActive?.kind == TranscriptKind.REFINED && oldActive.parentId in related && oldActive.parentId !in changed -> oldActive.id
        explicitWithoutRaw && oldActive != null && visibleRaw == null -> oldActive.id
        carriedRefinement != null -> carriedRefinement.id
        else -> visibleRaw
      }
    }
    return Result(rows.values.sortedWith(compareBy({ it.kind == TranscriptKind.REFINED }, { it.createdAt }, { it.id })),
      timed.values.flatten().sortedWith(compareBy({ it.transcriptId }, { it.sessionStartMs }, { it.partId }, { it.indexInPart })), active)
  }

  private fun derived(kind: String, source: String, destination: String): String =
    UUID.nameUUIDFromBytes("pampa-raw-$kind:$source:$destination".toByteArray(Charsets.UTF_8)).toString()
}
