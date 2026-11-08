package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.alignment.CloudSources
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.importing.PampaBundleCloudSources
import dev.pampa.pampanotes.core.importing.PampaBundleNote

/** A PN cannot promise complete history after silently reducing a restricted recording scope. */
class PampaHistoryScopeException(val noteIds: List<String>) :
  IllegalArgumentException("Complete Pampa history requires an explicitly authorized whole-note scope")

/** PN-only boundary. AI exports deliberately retain their existing selected-history policy. */
internal object PampaHistoryScope {
  fun validate(set: ExportSet) {
    try {
      set.notes.forEach { item ->
        val ids = item.selectedSessionIds ?: return@forEach
        restrict(requireNotNull(item.native) { "Pampa note export requires the complete native graph" }, ids)
      }
    } catch (failure: PampaHistoryScopeException) {
      throw PampaHistoryScopeException(set.notes.map { it.note.id }.distinct()).also { it.initCause(failure) }
    }
  }

  /** Validate before any attachment discovery/read. Never infer consent from today's note shape. */
  fun restrict(note: PampaBundleNote, ids: Set<String>): PampaBundleNote {
    try {
      val before = PampaBundleCloudSources.expanded(note.transcripts.filter { it.sessionId in ids })
      // native() refuses unsafe refinements, even unselected ones, instead of trimming history.
      val scoped = ExportSessionSelection.native(note, ids)
      require(before.map { it.id }.toSet() == scoped.transcripts.map { it.id }.toSet())
      val versionIds = before.mapTo(HashSet()) { it.id }
      require(note.segments.filter { it.transcriptId in versionIds } == scoped.segments) {
        "Restricted PN would omit historical timing rows"
      }
      val byId = scoped.transcripts.associateBy { it.id }
      before.forEach { row ->
        val after = byId.getValue(row.id)
        require(row.text == after.text) { "Restricted PN would change historical text" }
        if (row.kind == TranscriptKind.RAW && !row.cloudSourceJson.isNullOrEmpty()) {
          val original = requireNotNull(CloudSources.decode(row.cloudSourceJson))
          val portable = requireNotNull(CloudSources.decode(after.cloudSourceJson))
          // A transport binding change is expected; lost originals/parts/overlays are not.
          require(original.copy(alignmentBinding = "portable_unbound") == portable) {
            "Restricted PN would reduce public source history"
          }
        }
      }
      return scoped
    } catch (failure: IllegalArgumentException) {
      throw PampaHistoryScopeException(listOf(note.note.id)).also { it.initCause(failure) }
    }
  }
}
