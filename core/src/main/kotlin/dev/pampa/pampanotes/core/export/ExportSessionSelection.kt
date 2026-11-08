package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.SessionWithParts
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.alignment.CloudSources
import dev.pampa.pampanotes.core.model.wordCount
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.importing.PampaBundleNote

/** Applied BEFORE reading versions/segments or planning attachments. Null means whole-note scope. */
internal object ExportSessionSelection {
  /** There is no source-to-session link in the frozen schema. Keep metadata, omit opaque bytes. */
  fun attachmentAllowed(source: SourceEntity, selected: List<SessionWithParts>): Boolean = when (source.kind) {
    SourceKind.AUDIO -> source.sha256.isNotBlank() && selected.any { row -> row.parts.any { it.sha256 == source.sha256 } }
    SourceKind.SDOCX, SourceKind.DOCX, SourceKind.OTHER -> false
    else -> true
  }

  /** Recheck native callers too: an absent presentation row must never authorize opaque bytes. */
  fun attachmentAllowed(note: ExportNote, source: SourceEntity): Boolean {
    if (note.sourceFiles.any { it.id == source.id && !it.attachmentAllowed } ||
      note.sources.any { it.id == source.id && !it.attachmentAllowed }) return false
    val ids = note.selectedSessionIds ?: return true
    val parts = note.native?.parts?.filter { it.sessionId in ids }
    return when (source.kind) {
      SourceKind.AUDIO -> source.sha256.isNotBlank() && (parts?.any { it.sha256 == source.sha256 }
        ?: note.sessions.filter { it.id in ids }.any { s -> s.parts.any { it.sha256 == source.sha256 } })
      SourceKind.SDOCX, SourceKind.DOCX, SourceKind.OTHER -> false
      else -> true
    }
  }

  fun nativeParts(note: ExportNote): List<AudioPartEntity> = note.native?.parts.orEmpty().filter {
    note.selectedSessionIds == null || it.sessionId in note.selectedSessionIds
  }

  fun rows(rows: List<SessionWithParts>, ids: Set<String>?): List<SessionWithParts> =
    if (ids == null) rows else rows.filter { it.session.id in ids }

  data class TranscriptSelection(val versions: List<TranscriptEntity>, val activeId: String?)

  /**
   * A refinement has no per-part text mapping. Its parent ID alone is not scope evidence: timeline
   * edits can replace that parent's visible text while retaining the complete refinement.
   * Restricted exports therefore require every immutable source boundary to be authorized. With
   * missing/partial lineage use the selected refinement's RAW parent, never an unrelated newer RAW.
   * Whole-note callers keep every public history; this rule does not trim their native graph.
   */
  fun transcripts(versions: List<TranscriptEntity>, activeId: String?, authorizedParts: List<AudioPartEntity>,
    retainOriginals: Boolean = false): TranscriptSelection {
    val expanded = dev.pampa.pampanotes.core.importing.PampaBundleCloudSources.expanded(versions)
    if (retainOriginals) return TranscriptSelection(expanded, activeId)
    val byId = expanded.associateBy { it.id }
    val authorized = authorizedParts.associateBy { it.id }
    require(byId.size == versions.size && authorized.size == authorizedParts.size) { "Ambiguous export lineage" }
    fun confined(refined: TranscriptEntity): Boolean {
      val raw = byId[refined.parentId]?.takeIf { it.kind == TranscriptKind.RAW && it.sessionId == refined.sessionId } ?: return false
      val doc = CloudSources.decode(raw.cloudSourceJson)?.takeIf { CloudSources.matches(it, raw) } ?: return false
      fun included(id: String, hash: String) = hash.isNotEmpty() && authorized[id]?.sha256 == hash
      return doc.parts.isNotEmpty() && doc.parts.all { included(it.id, it.sha256) } && doc.originals.all { original ->
        original.contentScope == "complete_original" && original.parts.isNotEmpty() && original.parts.all { included(it.id, it.sha256) }
      }
    }
    val retained = expanded.filter { it.kind == TranscriptKind.RAW || confined(it) }
    val requested = byId[activeId]
    val selectedId = if (requested != null && requested !in retained) {
      val parent = byId[requested.parentId]
      require(parent?.kind == TranscriptKind.RAW && parent.sessionId == requested.sessionId) { "Cannot scope refinement without its RAW parent" }
      parent.id
    } else activeId
    return TranscriptSelection(retained, selectedId)
  }

  /** Also fence caller-supplied native graphs. Associated material has note-level ownership. */
  fun native(note: PampaBundleNote, ids: Set<String>, retainOriginals: Boolean = false): PampaBundleNote {
    val sessions = note.sessions.filter { it.id in ids }
    val allowed = sessions.mapTo(HashSet()) { it.id }
    val parts = note.parts.filter { it.sessionId in allowed }
    val expanded = dev.pampa.pampanotes.core.importing.PampaBundleCloudSources.expanded(note.transcripts.filter { it.sessionId in allowed })
    val transcripts = sessions.flatMap { session ->
      val versions = expanded.filter { it.sessionId == session.id }
      val selection = transcripts(versions, session.activeTranscriptId, parts, retainOriginals)
      // A caller's human selection can differ from this graph's active ID. Refuse any unsafe
      // refinement supplied here, rather than dropping it while Markdown could still print it.
      // Gather consumes the same rule before collecting either representation.
      require(selection.versions.size == versions.size && selection.activeId == session.activeTranscriptId) {
        "Native refinement is not confined to authorized source parts; consume scoped RAW selection before export"
      }
      selection.versions
    }
    val transcriptIds = transcripts.mapTo(HashSet()) { it.id }
    val partIds = parts.mapTo(HashSet()) { it.id }
    val portable = transcripts.map { raw ->
      if (raw.kind != TranscriptKind.RAW) {
        require(raw.cloudSourceJson.isNullOrEmpty()) { "Cloud source metadata requires a RAW transcript" }
        raw
      } else {
        val projection = CloudSources.portableProjection(raw, parts.filter { it.sessionId == raw.sessionId },
          retainOriginals = retainOriginals, authorizedOriginalParts = parts)
        raw.copy(text = projection.text, cloudSourceJson = projection.cloudSourceJson,
          wordCount = if (projection.text == raw.text) raw.wordCount else projection.text.wordCount())
      }
    }
    return note.copy(sessions = sessions, parts = parts, transcripts = portable,
      segments = note.segments.filter { it.transcriptId in transcriptIds && it.partId in partIds },
      attachments = note.attachments.filter { !it.audio || it.id in partIds })
  }
}
