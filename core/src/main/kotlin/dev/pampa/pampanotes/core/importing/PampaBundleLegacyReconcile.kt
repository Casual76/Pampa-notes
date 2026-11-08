package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.transcription.VoiceNames
import java.util.UUID

/** Only marked schema 1/2 identities may alias native children. Native UUIDs never deduplicate by hash. */
internal object PampaBundleLegacyReconcile {
  private val marker = Regex("pampa-legacy-(folder|session|part|source|page|placeholder|transcript)-[0-9a-f-]{36}")
  fun id(kind: String, seed: String): String = "pampa-legacy-$kind-${UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8))}"
  fun isSynthetic(value: String): Boolean = marker.matches(value)
  fun isFolder(value: String): Boolean = value.startsWith("pampa-legacy-folder-") && isSynthetic(value)
  // Only a fallback for internal callers without an archive-format argument. Public imports
  // always supply the actual schema; a native payload may inherit any of these marked IDs.
  private fun legacy(note: PampaBundleNote) = isFolder(note.note.folderId) || note.sessions.any { isSynthetic(it.id) }
  private fun markedChildren(note: PampaBundleNote) = legacy(note) ||
    note.parts.any { isSynthetic(it.id) } || note.sources.any { isSynthetic(it.id) }

  data class Plan(
    val local: PampaBundleNote?, val incoming: PampaBundleNote,
    val sessions: Map<String, String> = emptyMap(), val parts: Map<String, String> = emptyMap(),
    val sources: Map<String, String> = emptyMap(),
    val incomingPartKeys: Map<String, String> = emptyMap(), val incomingSourceKeys: Map<String, String> = emptyMap(),
    val preserveLocalNote: Boolean = false,
    /** Reuse verified local files/metadata while applying a public legacy graph's new ordering. */
    val legacyUpdate: Boolean = false,
  )

  fun plan(local: PampaBundleNote?, incoming: PampaBundleNote, publicLegacy: Boolean? = null): Plan {
    require(local == null || local.note.id == incoming.note.id) { "Legacy note identity conflict" }
    val incomingLegacy = publicLegacy ?: legacy(incoming)
    // Trust the format read from the archive, never a portable note's private flags.
    val received = incoming.copy(note = incoming.note.copy(bundleNativeAdopted = if (!incomingLegacy) true else if (local != null) local.note.bundleNativeAdopted else false))
    if (local == null) return Plan(null, received)
    if (legacy(local) && incomingLegacy && local.note.bundleNativeAdopted == false) return legacyUpdate(local, received)
    if (!markedChildren(local) && !incomingLegacy) return Plan(local, received)
    // Body authority and child identity are independent: a native-adopted note can still have
    // marked children that need reconciliation on every later native update.
    val forward = !incomingLegacy
    val old = if (forward) local else received
    val target = if (forward) received else local
    val partAliases = linkedMapOf<String, String>()
    val sessionAliases = linkedMapOf<String, String>()
    val sourceAliases = linkedMapOf<String, String>()
    fun text(value: String) = value.trim().replace(Regex("[\\s\\p{Z}]+"), " ")
    fun hash(a: String, b: String) = a.isNotBlank() && b.isNotBlank() && a.equals(b, true)

    // Reserve ALL compatible exact child identities first. In particular a distinct real
    // native recording must never be consumed as a content alias for a marked recording.
    val reservedParts = target.parts.filter { candidate -> old.parts.any { it.id == candidate.id && hash(it.sha256, candidate.sha256) } }
      .mapTo(mutableSetOf()) { it.id }
    val matchedParts = linkedMapOf<String, String>()
    old.parts.forEach { part ->
      target.parts.firstOrNull { it.id == part.id && hash(it.sha256, part.sha256) }?.let { matchedParts[part.id] = it.id }
    }
    val legacyParts = old.parts.filter { isSynthetic(it.id) && it.id !in matchedParts }
    // Reserve named matches across the whole graph before hash-only fallback. A B visited
    // before an identical A must not consume native A when B was omitted from the update.
    for (named in listOf(true, false)) legacyParts.filterNot { it.id in matchedParts }.forEach { part ->
      val matches = target.parts.filter { it.id !in reservedParts && hash(it.sha256, part.sha256) &&
        (!named || text(it.originalName) == text(part.originalName)) }
      check(matches.size <= 1) { "Ambiguous legacy audio identity" }
      matches.singleOrNull()?.let { match ->
        val competitors = legacyParts.filter { it.id !in matchedParts && hash(it.sha256, match.sha256) &&
          (!named || text(it.originalName) == text(match.originalName)) }
        check(competitors.size == 1) { "Ambiguous repeated legacy audio identity" }
        matchedParts[part.id] = match.id
        reservedParts += match.id
      }
    }
    legacyParts.filterNot { it.id in matchedParts }.forEach { part ->
      // Unknown hashes cannot distinguish omission from an unidentified recording.
      check(target.parts.none { it.sha256.isBlank() } && (target.parts.isEmpty() || part.sha256.isNotBlank())) { "Legacy audio identity is unavailable" }
    }
    partAliases += matchedParts.filter { (oldId, newId) -> oldId != newId }

    val matchedSessions = linkedMapOf<String, String>()
    old.sessions.filter { isSynthetic(it.id) }.forEach { session ->
      val exportedParts = old.parts.filter { it.sessionId == session.id }
      val owners = exportedParts.mapNotNull { matchedParts[it.id] }.map { id -> target.parts.single { it.id == id }.sessionId }.distinct()
      check(owners.size <= 1) { "Legacy session regrouping has conflicting identity evidence" }
      val matched = owners.singleOrNull() ?: if (exportedParts.isEmpty()) {
        val oldVersions = old.transcripts.filter { it.sessionId == session.id }
        val oldTexts = oldVersions.map { text(it.text) }.filter { it.isNotBlank() }
        fun sameLabel(candidate: SessionEntity) = session.title.isNotBlank() && text(session.title) == text(candidate.title) && session.date == candidate.date
        var candidates = target.sessions.filter { candidate -> target.transcripts.any { version ->
          version.sessionId == candidate.id && (text(version.text).isNotBlank() && text(version.text) in oldTexts ||
            oldVersions.any { it.id == version.id && it.kind == version.kind }) } }
        if (candidates.size > 1) {
          candidates = candidates.filter(::sameLabel)
          check(candidates.size == 1) { "Ambiguous repeated legacy text identity" }
        }
        check(candidates.size <= 1) { "Ambiguous legacy text-only session identity" }
        if (candidates.isEmpty()) {
          candidates = target.sessions.filter { candidate -> sameLabel(candidate) && target.parts.none { it.sessionId == candidate.id } }
          check(candidates.size <= 1) { "Ambiguous legacy text-only session identity" }
        }
        // A sole text-only container may be renamed with no exported transcript. Multiple
        // positional containers require actual content/label evidence before trusting a shared ID.
        candidates.singleOrNull()?.id ?: target.sessions.singleOrNull()?.takeIf {
          old.sessions.size == 1 && it.id == session.id && target.parts.isEmpty()
        }?.id
      } else null
      if (matched != null) {
        check(matched !in matchedSessions.values) { "Ambiguous legacy session identity" }
        matchedSessions[session.id] = matched
        if (session.id != matched) sessionAliases[session.id] = matched
      }
    }
    // If an incoming positional ID now belongs to another session/recording, retain the omitted
    // local content under a deterministic fresh ID. Swaps are simultaneous maps, never merges.
    val claimedSessions = old.sessions.associate { it.id to (sessionAliases[it.id] ?: it.id) }
    old.sessions.filter { isSynthetic(it.id) && it.id !in matchedSessions }.forEach { session ->
      val claimedByOther = claimedSessions.any { (oldId, newId) -> oldId != session.id && newId == session.id }
      val conflictingShared = target.sessions.any { it.id == session.id }
      if (claimedByOther || conflictingShared) {
        val retained = id("session", "${old.note.id}:retained-session:${session.id}")
        check(retained !in old.sessions.map { it.id } && retained !in target.sessions.map { it.id } && retained !in sessionAliases.values) { "Retained legacy session identity collision" }
        sessionAliases[session.id] = retained
      }
    }
    old.parts.filter { isSynthetic(it.id) && it.id !in matchedParts && target.parts.any { targetPart -> targetPart.id == it.id } }.forEach { part ->
      val retained = id("part", "${old.note.id}:retained-audio:${part.id}:${part.sha256}")
      check(retained !in old.parts.map { it.id } && retained !in target.parts.map { it.id } && retained !in partAliases.values) { "Retained legacy audio identity collision" }
      partAliases[part.id] = retained
    }
    val reservedSources = target.sources.filter { t -> old.sources.any { it.id == t.id } }.mapTo(mutableSetOf()) { it.id }
    val legacySources = old.sources.filter { isSynthetic(it.id) && !it.id.startsWith("pampa-legacy-placeholder-") && target.sources.none { t -> t.id == it.id } }
    // Resolve named content matches globally before hash-only fallback. Reserving destinations
    // keeps an omitted B distinct from A, even when B is visited first and their bytes are equal.
    for (named in listOf(true, false)) legacySources.filterNot { it.id in sourceAliases }.forEach { source ->
      val matches = target.sources.filter { it.id !in reservedSources && it.kind == source.kind && hash(it.sha256, source.sha256) &&
        (!named || text(it.originalName) == text(source.originalName)) }
      require(matches.size <= 1) { "Ambiguous legacy source identity" }
      matches.singleOrNull()?.let { match ->
        val competitors = legacySources.filter { it.id !in sourceAliases && it.kind == match.kind && hash(it.sha256, match.sha256) &&
          (!named || text(it.originalName) == text(match.originalName)) }
        require(competitors.size == 1) { "Ambiguous legacy source identity" }
        sourceAliases[source.id] = match.id
        reservedSources += match.id
      }
    }
    // A legacy placeholder has no original content; its mapped pages identify their real native parent.
    val placeholders = old.sources.filter { it.id.startsWith("pampa-legacy-placeholder-") && isSynthetic(it.id) }
    placeholders.forEach { placeholder ->
      val children = old.sources.filter { it.derivedFromId == placeholder.id }
      val parents = children.mapNotNull { child -> sourceAliases[child.id]?.let { id -> target.sources.single { it.id == id }.derivedFromId } }.distinct()
      if (children.isNotEmpty() && children.all { it.id in sourceAliases } && parents.size == 1 &&
        placeholder.sizeBytes == 0L && placeholder.sha256.isBlank() && placeholder.storedFileName == null && parents.single() != placeholder.id) sourceAliases[placeholder.id] = parents.single()
    }
    val changed = rekey(old, sessionAliases, partAliases, sourceAliases)
    return if (forward) {
      // Merge applies timestamped voice-map authority after part IDs have been remapped.
      Plan(changed, received, sessionAliases, partAliases, sourceAliases)
    } else {
      // Public legacy Markdown has no native body boundaries. Never downgrade native content or selection.
      val retainedSessions = (target.sessions.sortedBy { it.position } + changed.sessions.filter { s -> target.sessions.none { it.id == s.id } }.sortedBy { it.position })
        .mapIndexed { index, session -> session.copy(position = index) }
      val retainedParts = retainedSessions.flatMap { session ->
        (target.parts.filter { it.sessionId == session.id }.sortedBy { it.position } +
          changed.parts.filter { part -> part.sessionId == session.id && target.parts.none { it.id == part.id } }.sortedBy { it.position })
          .mapIndexed { index, part -> part.copy(position = index) }
      }
      val adopted = changed.copy(note = target.note, sessions = retainedSessions, parts = retainedParts,
        sources = target.sources + changed.sources.filter { source -> target.sources.none { it.id == source.id } },
        transcripts = changed.transcripts.filter { transcript -> target.transcripts.none { it.id == transcript.id } })
      Plan(local, adopted, incomingPartKeys = partAliases.entries.associate { it.value to it.key },
        incomingSourceKeys = sourceAliases.entries.filterNot { it.key.startsWith("pampa-legacy-placeholder-") }.associate { it.value to it.key }, preserveLocalNote = true)
    }
  }

  /** Public manifests have no child identities. Even equal marked IDs are only positional hints. */
  private fun legacyUpdate(local: PampaBundleNote, incoming: PampaBundleNote): Plan {
    fun text(value: String) = value.trim().replace(Regex("[\\s\\p{Z}]+"), " ")
    val sha256 = Regex("[a-fA-F0-9]{64}")
    fun hash(a: String, b: String) = a.matches(sha256) && b.matches(sha256) && a.equals(b, true)
    val partIds = linkedMapOf<String, String>()
    val sessionIds = linkedMapOf<String, String>()
    val sourceIds = linkedMapOf<String, String>()
    val transcriptIds = linkedMapOf<String, String>()
    val localParts = local.parts.associateBy { it.id }
    val localSessions = local.sessions.associateBy { it.id }

    incoming.parts.forEach { part ->
      if (!isSynthetic(part.id)) { partIds[part.id] = part.id; return@forEach }
      require(part.sha256.matches(sha256)) { "Legacy audio identity is unavailable" }
      var matches = local.parts.filter { isSynthetic(it.id) && hash(it.sha256, part.sha256) }
      if (matches.size > 1) {
        matches = matches.filter { text(it.originalName) == text(part.originalName) }
        require(matches.size == 1) { "Ambiguous repeated legacy audio" }
      }
      require(matches.size <= 1) { "Ambiguous legacy audio identity" }
      val canonical = matches.singleOrNull()?.id ?: id("part", "${incoming.note.id}:audio:${part.sha256.lowercase()}:${text(part.originalName)}")
      require(canonical !in partIds.values) { "Repeated legacy audio has no unique identity" }
      require(localParts[canonical]?.let { hash(it.sha256, part.sha256) } != false) { "Legacy audio identity collision" }
      partIds[part.id] = canonical
    }

    incoming.sessions.forEach { session ->
      if (!isSynthetic(session.id)) { sessionIds[session.id] = session.id; return@forEach }
      val parts = incoming.parts.filter { it.sessionId == session.id }
      val owners = parts.mapNotNull { localParts[partIds.getValue(it.id)]?.sessionId }.distinct()
      require(owners.size <= 1) { "Legacy session regrouping has conflicting identity evidence" }
      val matched = owners.singleOrNull() ?: if (parts.isEmpty()) {
        val versions = incoming.transcripts.filter { it.sessionId == session.id }.map { text(it.text) }.filter { it.isNotBlank() }
        val candidates = local.sessions.filter { old -> isSynthetic(old.id) && session.title.isNotBlank() &&
          text(old.title) == text(session.title) && old.date == session.date &&
          (local.transcripts.any { it.sessionId == old.id && isSynthetic(it.id) && text(it.text) in versions } ||
            versions.isEmpty() && local.parts.none { it.sessionId == old.id } && local.transcripts.none { it.sessionId == old.id && isSynthetic(it.id) }) }
        require(candidates.size <= 1) { "Ambiguous legacy text-only session identity" }
        if (local.sessions.isNotEmpty()) require(candidates.isNotEmpty()) { "Legacy session identity is unavailable" }
        candidates.singleOrNull()?.id
      } else null
      val canonical = matched ?: id("session", "${incoming.note.id}:session-content:${session.date}:${text(session.title)}:" + parts.map { partIds.getValue(it.id) }.sorted().joinToString(","))
      require(canonical !in sessionIds.values) { "Two legacy sessions claim the same local session" }
      require(matched != null || canonical !in localSessions) { "Conflicting legacy session identity" }
      sessionIds[session.id] = canonical
    }

    fun placeholder(source: SourceEntity) = source.id.startsWith("pampa-legacy-placeholder-") &&
      source.kind == SourceKind.OTHER && source.sizeBytes == 0L && source.sha256.isBlank() && source.storedFileName == null
    incoming.sources.forEach { source ->
      if (!isSynthetic(source.id)) { sourceIds[source.id] = source.id; return@forEach }
      if (placeholder(source)) {
        require(local.sources.firstOrNull { it.id == source.id }?.let(::placeholder) != false) { "Legacy placeholder identity conflict" }
        sourceIds[source.id] = source.id
        return@forEach
      }
      require(source.sha256.matches(sha256)) { "Legacy source identity is unavailable" }
      var matches = local.sources.filter { isSynthetic(it.id) && it.kind == source.kind &&
        (it.derivedFromId == null) == (source.derivedFromId == null) && hash(it.sha256, source.sha256) }
      if (matches.size > 1) {
        matches = matches.filter { text(it.originalName) == text(source.originalName) }
        require(matches.size == 1) { "Ambiguous repeated legacy source" }
      }
      require(matches.size <= 1) { "Ambiguous legacy source identity" }
      val role = if (source.derivedFromId != null) "page" else "source"
      val canonical = matches.singleOrNull()?.id ?: id(role, "${incoming.note.id}:source-content:${source.kind}:${source.sha256.lowercase()}:${text(source.originalName)}")
      require(canonical !in sourceIds.values) { "Repeated legacy sources have no unique identity" }
      require(local.sources.firstOrNull { it.id == canonical }?.let { it.kind == source.kind && hash(it.sha256, source.sha256) } != false) { "Legacy source identity collision" }
      sourceIds[source.id] = canonical
    }

    // Reuse only equivalent versions, and never let a public document erase retained local timings.
    incoming.transcripts.sortedBy { if (it.kind == TranscriptKind.RAW) 0 else 1 }.forEach { transcript ->
      if (!isSynthetic(transcript.id)) { transcriptIds[transcript.id] = transcript.id; return@forEach }
      val moved = transcript.copy(sessionId = sessionIds.getValue(transcript.sessionId), parentId = transcript.parentId?.let { transcriptIds[it] ?: it })
      fun content(value: TranscriptEntity) = value.copy(id = "", createdAt = 0)
      val matches = local.transcripts.filter { isSynthetic(it.id) && content(it) == content(moved) }
      val exact = matches.firstOrNull { it.id == transcript.id }
      require(exact != null || matches.size <= 1) { "Ambiguous legacy transcript identity" }
      var canonical = exact?.id ?: matches.singleOrNull()?.id ?: id("transcript", "${incoming.note.id}:version:${content(moved)}")
      if (local.transcripts.any { it.id == canonical && content(it) != content(moved) }) {
        // A locally edited old version keeps its ID; the original public text becomes a new history.
        canonical = id("transcript", "${incoming.note.id}:public-version:${content(moved)}")
        require(local.transcripts.none { it.id == canonical && content(it) != content(moved) }) { "Legacy transcript identity collision" }
      }
      require(canonical !in transcriptIds.values) { "Repeated legacy transcript identity" }
      transcriptIds[transcript.id] = canonical
    }
    require(partIds.values.distinct().size == incoming.parts.size && sessionIds.values.distinct().size == incoming.sessions.size &&
      sourceIds.values.distinct().size == incoming.sources.size && transcriptIds.values.distinct().size == incoming.transcripts.size) { "Legacy reconciliation would merge distinct identities" }
    val sessions = incoming.sessions.map { session ->
      val canonical = sessionIds.getValue(session.id)
      val old = localSessions[canonical]?.takeIf { isSynthetic(session.id) }
      val active = old?.activeTranscriptId?.takeIf { id -> local.transcripts.any { it.id == id && it.sessionId == canonical } }
        ?: session.activeTranscriptId?.let { transcriptIds.getValue(it) }
      (old ?: session.copy(id = canonical)).copy(position = session.position, activeTranscriptId = active,
        voiceNames = VoiceNames.encode(VoiceNames.decode(session.voiceNames).mapKeys { (key, _) ->
          (partIds[key.substringBefore('|')] ?: key.substringBefore('|')) + "|" + key.substringAfter('|') } + VoiceNames.decode(old?.voiceNames)))
    }
    val transcripts = incoming.transcripts.map { t -> t.copy(id = transcriptIds.getValue(t.id), sessionId = sessionIds.getValue(t.sessionId), parentId = t.parentId?.let { transcriptIds[it] ?: it }) }
      .filter { t -> !isSynthetic(t.id) || local.transcripts.none { it.id == t.id } }
    val moved = incoming.copy(sessions = sessions,
      parts = incoming.parts.map { it.copy(id = partIds.getValue(it.id), sessionId = sessionIds.getValue(it.sessionId)) },
      sources = incoming.sources.map { it.copy(id = sourceIds.getValue(it.id), derivedFromId = it.derivedFromId?.let { parent -> sourceIds.getValue(parent) }) },
      transcripts = transcripts,
      segments = incoming.segments.filter { s -> !isSynthetic(s.transcriptId) || local.transcripts.none { it.id == transcriptIds[s.transcriptId] } }.map { s ->
        s.copy(transcriptId = transcriptIds.getValue(s.transcriptId), partId = partIds.getValue(s.partId)) },
      attachments = incoming.attachments.map { it.copy(id = if (it.audio) partIds.getValue(it.id) else sourceIds.getValue(it.id)) })
    return Plan(local, moved, incomingPartKeys = partIds.entries.associate { it.value to it.key },
      incomingSourceKeys = sourceIds.entries.associate { it.value to it.key }, legacyUpdate = true)
  }

  private fun rekey(note: PampaBundleNote, sessions: Map<String, String>, parts: Map<String, String>, sources: Map<String, String>): PampaBundleNote {
    fun sid(id: String) = sessions[id] ?: id
    fun pid(id: String) = parts[id] ?: id
    fun source(id: String) = sources[id] ?: id
    val movedSessions = note.sessions.sortedBy { if (isSynthetic(it.id)) 0 else 1 }.associate { s -> sid(s.id) to s.copy(id = sid(s.id),
      voiceNames = VoiceNames.encode(VoiceNames.decode(s.voiceNames).mapKeys { (key, _) -> pid(key.substringBefore('|')) + "|" + key.substringAfter('|') })) }.values.toList()
    val movedParts = note.parts.sortedBy { if (isSynthetic(it.id)) 0 else 1 }.associate { p -> pid(p.id) to p.copy(id = pid(p.id), sessionId = sid(p.sessionId)) }.values.toList()
    val retainedSources = note.sources.filterNot { it.id.startsWith("pampa-legacy-placeholder-") && it.id in sources }
    require(retainedSources.map { source(it.id) }.distinct().size == retainedSources.size) { "Legacy source identity conflict" }
    val movedSources = retainedSources
      .sortedBy { if (!isSynthetic(it.id)) 2 else if (it.derivedFromId != null) 1 else 0 }
      .associate { s -> source(s.id) to s.copy(id = source(s.id), derivedFromId = s.derivedFromId?.let(::source)) }.values.toList()
    return note.copy(sessions = movedSessions, parts = movedParts, sources = movedSources,
      transcripts = note.transcripts.map { it.copy(sessionId = sid(it.sessionId)) },
      segments = note.segments.map { it.copy(partId = pid(it.partId)) },
      attachments = note.attachments.map { it.copy(id = if (it.audio) pid(it.id) else source(it.id)) }.distinctBy { it.audio to it.id })
  }
}
