package dev.pampa.pampanotes.ui.recordings

import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.NoteRow
import dev.pampa.pampanotes.core.db.SessionWithParts
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.PersonalScope
import dev.pampa.pampanotes.core.repo.SessionRepository
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.transcription.RemoteTranscribing
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

data class RecordingItem(
  val session: SessionWithParts,
  val note: NoteEntity,
  val folder: FolderEntity?,
  val job: JobEntity? = null,
  val elsewhere: RemoteTranscribing? = null,
  val failed: JobEntity? = null,
) {
  val id: String get() = session.session.id
  val toTranscribe: Int get() = if (session.parts.isNotEmpty() && session.session.activeTranscriptId == null && elsewhere == null) 1 else 0
  val worthTranscribing: Int get() = if (failed?.errorCode == "no_speech") 0 else toTranscribe
}

data class RecordingLibraryContent(
  val allFolders: List<FolderEntity> = emptyList(),
  val recordings: List<RecordingItem> = emptyList(),
  val materials: List<NoteRow> = emptyList(),
)

/** Observe sessions themselves, including edits to parts/transcripts that leave note counts unchanged. */
class RecordingLibrary @Inject constructor(
  private val noteDao: NoteDao,
  private val folders: FolderRepository,
  private val sessions: SessionRepository,
  private val transcription: TranscriptionRepository,
  private val jobs: JobDao,
) {
  @OptIn(ExperimentalCoroutinesApi::class)
  fun observe(folderId: String?): Flow<RecordingLibraryContent> =
    combine(noteDao.observePersonalRows(), folders.observeAll()) { rows, all -> rows to all }
      .flatMapLatest { (rows, all) ->
        val personal = PersonalScope.folderIds(all)
        val scoped = rows.filter { it.note.folderId in personal && (folderId == null || it.note.folderId == folderId) }
        val observed: Flow<List<SessionWithParts>> = if (scoped.isEmpty()) flowOf(emptyList()) else
          combine(scoped.map { sessions.observeByNote(it.note.id) }) { groups -> groups.flatMap { it } }
        combine(observed, transcription.observeActive(), transcription.observeElsewhere(), jobs.observeLatestFailed()) { list, active, remote, failed ->
          val projection = RecordingCatalog.project(
            scoped.map { RecordingCatalog.Material(it.note.id, it.note.folderId, it.note.body, it.sourceCount) },
            list.map { RecordingCatalog.Session(it.session.id, it.session.noteId) }, personal, folderId,
          )
          val byNote = scoped.associateBy { it.note.id }
          val byFolder = all.associateBy { it.id }
          val bySession = list.associateBy { it.session.id }
          val activeBySession = active.associateBy { it.sessionId }
          val failedBySession = failed.associateBy { it.sessionId }
          RecordingLibraryContent(
            allFolders = all,
            recordings = projection.sessionIds.mapNotNull { id ->
              val session = bySession[id] ?: return@mapNotNull null
              val note = byNote[session.session.noteId]?.note ?: return@mapNotNull null
              RecordingItem(session, note, byFolder[note.folderId], activeBySession[id], remote[id], failedBySession[id])
            }.sortedWith(compareByDescending<RecordingItem> { it.session.session.date }.thenByDescending { it.session.session.createdAt }.thenBy { it.id }),
            materials = projection.materialIds.mapNotNull(byNote::get),
          )
        }
      }
}
