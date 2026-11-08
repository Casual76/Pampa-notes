package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import kotlinx.serialization.Serializable

/** Portable content only: no account, jobs, archive ownership or server state. */
@Serializable
data class PampaBundleData(
  val version: Int = 1,
  val folders: List<FolderEntity>,
  val notes: List<PampaBundleNote>,
)

@Serializable
data class PampaBundleNote(
  val note: NoteEntity,
  val tags: List<String> = emptyList(),
  val sessions: List<SessionEntity> = emptyList(),
  val parts: List<AudioPartEntity> = emptyList(),
  val transcripts: List<TranscriptEntity> = emptyList(),
  val segments: List<SegmentEntity> = emptyList(),
  val sources: List<SourceEntity> = emptyList(),
  val attachments: List<PampaBundleAttachment> = emptyList(),
)

@Serializable
data class PampaBundleAttachment(
  val id: String,
  val audio: Boolean,
  /** Null means omitted/unavailable, or intrinsically fileless when [fileless] is true. */
  val path: String? = null,
  val bytes: Long = 0,
  val sha256: String? = null,
  /** Text clipboard/share provenance has no original file. Absent in older documents: omitted. */
  val fileless: Boolean = false,
)

/** Only positive domain evidence can waive an original; unknown/binary/derived sources still warn. */
object PampaBundleSourceFiles {
  fun isFileless(source: SourceEntity, declaration: PampaBundleAttachment? = null): Boolean =
    (source.kind == SourceKind.CLIPBOARD || source.kind == SourceKind.SHARE) && source.storedFileName == null &&
      source.derivedFromId == null && source.archivedAt == 0L &&
      source.mime.substringBefore(';').trim().startsWith("text/", ignoreCase = true) && declaration?.fileless != false
}

data class PampaBundlePreview(
  val noteCount: Int,
  val updateCount: Int,
  val audioCount: Int,
  val imageCount: Int,
  val titles: List<String>,
  val hasPreciseTimings: Boolean,
  val warnings: List<PampaBundleWarning> = emptyList(),
  val folderIds: Set<String> = emptySet(),
) {
  /** Updates cannot nest a hierarchy below itself; copies receive independent IDs. */
  fun canImportInto(folderId: String?, folders: List<FolderEntity>, updateExisting: Boolean): Boolean {
    if (folderId == null) return true
    if (folders.none { it.id == folderId }) return false
    if (!updateExisting) return true
    val parents = folders.associate { it.id to it.parentId }
    val seen = mutableSetOf<String>()
    var current: String? = folderId
    while (current != null) {
      if (current in folderIds || !seen.add(current)) return false
      current = parents[current]
    }
    return true
  }
}

data class PampaBundleImportResult(
  val noteIds: List<String>,
  val importedNotes: Int,
  val updatedNotes: Int,
  val recordings: Int,
  val warnings: List<PampaBundleWarning> = emptyList(),
)

/** UI maps these stable codes to its own localized resources. */
enum class PampaBundleWarning { LEGACY_FORMAT, OMITTED_ATTACHMENTS }
enum class PampaBundlePhase { VALIDATING, STAGING_ATTACHMENTS, IMPORTING_NOTES }
data class PampaBundleProgress(val phase: PampaBundlePhase, val noteTitle: String? = null)
