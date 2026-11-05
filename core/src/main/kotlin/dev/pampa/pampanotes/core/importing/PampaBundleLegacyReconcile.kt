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
  private fun legacy(note: PampaBundleNote) = isFolder(note.note.folderId) || note.sessions.any { isSynthetic(it.id) }

  data class Plan(
    val local: PampaBundleNote?, val incoming: PampaBundleNote,
    val sessions: Map<String, String> = emptyMap(), val parts: Map<String, String> = emptyMap(),
    val sources: Map<String, String> = emptyMap(),
    val incomingPartKeys: Map<String, String> = emptyMap(), val incomingSourceKeys: Map<String, String> = emptyMap(),
    val preserveLocalNote: Boolean = false,
  )

  fun plan(local: PampaBundleNote?, incoming: PampaBundleNote): Plan {
    require(local == null || local.note.id == incoming.note.id) { "Legacy note identity conflict" }
    if (local == null || legacy(local) == legacy(incoming)) return Plan(local, incoming)
    val forward = legacy(local)
    val old = if (forward) local else incoming
    val target = if (forward) incoming else local
    val partAliases = linkedMapOf<String, String>()
    val sessionAliases = linkedMapOf<String, String>()
    val sourceAliases = linkedMapOf<String, String>()
    fun text(value: String) = value.trim().replace(Regex("[\\s\\p{Z}]+"), " ")
    fun hash(a: String, b: String) = a.isNotBlank() && b.isNotBlank() && a.equals(b, true)

    old.sessions.filter { isSynthetic(it.id) && target.sessions.none { t -> t.id == it.id } }.forEach { session ->
      val exportedParts = old.parts.filter { it.sessionId == session.id && isSynthetic(it.id) }
      val candidates = target.sessions.filterNot { isSynthetic(it.id) }.mapNotNull { candidate ->
        val candidatesParts = target.parts.filter { it.sessionId == candidate.id }
        val mapping = linkedMapOf<String, String>()
        if (exportedParts.isNotEmpty()) {
          for (part in exportedParts) {
            var matches = candidatesParts.filter { hash(it.sha256, part.sha256) }
            if (matches.size > 1) matches = matches.filter { text(it.originalName) == text(part.originalName) }
            val match = matches.singleOrNull() ?: return@mapNotNull null
            if (match.id in mapping.values) return@mapNotNull null
            mapping[part.id] = match.id
          }
        } else {
          val sameLabel = session.title.isNotBlank() && text(session.title) == text(candidate.title) && session.date == candidate.date
          val oldTexts = old.transcripts.filter { it.sessionId == session.id }.map { text(it.text) }.filter { it.isNotBlank() }
          val sameText = target.transcripts.any { it.sessionId == candidate.id && text(it.text) in oldTexts }
          if (!sameLabel || candidatesParts.isNotEmpty() && !sameText) return@mapNotNull null
        }
        candidate.id to mapping
      }
      val match = candidates.singleOrNull() ?: error("Legacy session identity is unavailable or ambiguous; use a complete original bundle")
      require(match.first !in sessionAliases.values) { "Ambiguous legacy session identity" }
      sessionAliases[session.id] = match.first
      partAliases += match.second.filter { (oldId, newId) -> oldId != newId }
    }
    old.sources.filter { isSynthetic(it.id) && !it.id.startsWith("pampa-legacy-placeholder-") && target.sources.none { t -> t.id == it.id } }.forEach { source ->
      var matches = target.sources.filter { it.kind == source.kind && hash(it.sha256, source.sha256) }
      if (matches.size > 1) matches = matches.filter { text(it.originalName) == text(source.originalName) }
      require(matches.size <= 1) { "Ambiguous legacy source identity" }
      matches.singleOrNull()?.let { sourceAliases[source.id] = it.id }
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
      // Legacy exports contain no voice aliases, so any local names were explicitly added here.
      val withLocalVoices = incoming.copy(sessions = incoming.sessions.map { session ->
        val localSession = changed.sessions.firstOrNull { it.id == session.id }
        session.copy(voiceNames = VoiceNames.encode(VoiceNames.decode(session.voiceNames) + VoiceNames.decode(localSession?.voiceNames)))
      })
      Plan(changed, withLocalVoices, sessionAliases, partAliases, sourceAliases)
    } else {
      // Public legacy Markdown has no native body boundaries. Never downgrade native content or selection.
      val adopted = changed.copy(note = target.note, sessions = target.sessions, parts = target.parts,
        sources = target.sources + changed.sources.filter { source -> target.sources.none { it.id == source.id } },
        transcripts = changed.transcripts.filter { transcript -> target.transcripts.none { it.id == transcript.id } })
      Plan(local, adopted, incomingPartKeys = partAliases.entries.associate { it.value to it.key },
        incomingSourceKeys = sourceAliases.entries.filterNot { it.key.startsWith("pampa-legacy-placeholder-") }.associate { it.value to it.key }, preserveLocalNote = true)
    }
  }

  private fun rekey(note: PampaBundleNote, sessions: Map<String, String>, parts: Map<String, String>, sources: Map<String, String>): PampaBundleNote {
    fun sid(id: String) = sessions[id] ?: id
    fun pid(id: String) = parts[id] ?: id
    fun source(id: String) = sources[id] ?: id
    val movedSessions = note.sessions.sortedBy { if (isSynthetic(it.id)) 0 else 1 }.associate { s -> sid(s.id) to s.copy(id = sid(s.id),
      voiceNames = VoiceNames.encode(VoiceNames.decode(s.voiceNames).mapKeys { (key, _) -> pid(key.substringBefore('|')) + "|" + key.substringAfter('|') })) }.values.toList()
    val movedParts = note.parts.sortedBy { if (isSynthetic(it.id)) 0 else 1 }.associate { p -> pid(p.id) to p.copy(id = pid(p.id), sessionId = sid(p.sessionId)) }.values.toList()
    val movedSources = note.sources.filterNot { it.id.startsWith("pampa-legacy-placeholder-") && it.id in sources }
      .sortedBy { if (!isSynthetic(it.id)) 2 else if (it.derivedFromId != null) 1 else 0 }
      .associate { s -> source(s.id) to s.copy(id = source(s.id), derivedFromId = s.derivedFromId?.let(::source)) }.values.toList()
    return note.copy(sessions = movedSessions, parts = movedParts, sources = movedSources,
      transcripts = note.transcripts.map { it.copy(sessionId = sid(it.sessionId)) },
      segments = note.segments.map { it.copy(partId = pid(it.partId)) },
      attachments = note.attachments.map { it.copy(id = if (it.audio) pid(it.id) else source(it.id)) }.distinctBy { it.audio to it.id })
  }
}
