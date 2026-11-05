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
  /** Null means omitted or unavailable, never playable. */
  val path: String? = null,
  val bytes: Long = 0,
  val sha256: String? = null,
)

data class PampaBundlePreview(
  val noteCount: Int,
  val updateCount: Int,
  val audioCount: Int,
  val imageCount: Int,
  val titles: List<String>,
  val hasPreciseTimings: Boolean,
  val warnings: List<String> = emptyList(),
)

data class PampaBundleImportResult(
  val noteIds: List<String>,
  val importedNotes: Int,
  val updatedNotes: Int,
  val recordings: Int,
  val warnings: List<String> = emptyList(),
)
