package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.SessionWithParts
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

  fun rows(rows: List<SessionWithParts>, ids: Set<String>?): List<SessionWithParts> =
    if (ids == null) rows else rows.filter { it.session.id in ids }

  /** Also fence caller-supplied native graphs. Associated material has note-level ownership. */
  fun native(note: PampaBundleNote, ids: Set<String>, retainOriginals: Boolean = false): PampaBundleNote {
    val sessions = note.sessions.filter { it.id in ids }
    val allowed = sessions.mapTo(HashSet()) { it.id }
    val transcripts = note.transcripts.filter { it.sessionId in allowed }
    val transcriptIds = transcripts.mapTo(HashSet()) { it.id }
    val parts = note.parts.filter { it.sessionId in allowed }
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
