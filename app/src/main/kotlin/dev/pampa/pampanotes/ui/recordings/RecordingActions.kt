package dev.pampa.pampanotes.ui.recordings

import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.NoteTagDao
import dev.pampa.pampanotes.core.repo.DatabaseTransactions
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.model.Ids
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.PersonalScope
import dev.pampa.pampanotes.core.repo.SessionRepository
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import javax.inject.Inject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Session-scoped operations; no note deletion or expansion of a selection to unselected siblings. */
class RecordingActions @Inject constructor(
  private val transactions: DatabaseTransactions,
  private val notes: NoteDao,
  private val sessions: SessionDao,
  private val tags: NoteTagDao,
  private val sources: SourceDao,
  private val folders: FolderRepository,
  private val repository: SessionRepository,
  private val transcription: TranscriptionRepository,
  private val alignment: dev.pampa.pampanotes.work.AlignmentScheduler,
  private val files: AppFiles,
  private val inUse: FilesInUse,
) {
  suspend fun delete(ids: Set<String>, folderId: String?) = withContext(NonCancellable) {
    val personal = PersonalScope.folderIds(folders.all())
    val valid = sessions.getAll(ids.toList()).filter { session ->
      notes.get(session.noteId)?.let { it.folderId in personal && (folderId == null || it.folderId == folderId) } == true
    }
    val selected = valid.mapTo(HashSet()) { it.id }
    selected.forEach { runCatching { alignment.cancelSession(it) } }
    transcription.observeActive().first().filter { it.sessionId in selected }.forEach { transcription.requestCancel(it.id) }
    valid.forEach { repository.deleteSession(it.id) }
    // Keep the backing material, including body, tags, language and sources, even after the last session.
  }

  suspend fun move(ids: Set<String>, targetFolderId: String, folderId: String?): Unit = withContext(NonCancellable + Dispatchers.IO) {
    val copied = mutableListOf<File>()
    val held = mutableSetOf<String>()
    val owner = Any()
    try {
      transactions.run {
        val allFolders = folders.all()
        require(allFolders.any { it.id == targetFolderId }) { "Missing destination folder" }
        val personal = PersonalScope.folderIds(allFolders)
        val selected = sessions.getAll(ids.toList()).filter { session ->
          notes.get(session.noteId)?.let { it.folderId in personal && (folderId == null || it.folderId == folderId) } == true
        }
        val siblings = selected.map { it.noteId }.distinct().flatMap { sessions.plainByNote(it) }
        val plans = RecordingCatalog.moves(selected.mapTo(HashSet()) { it.id }, siblings.map { RecordingCatalog.Session(it.id, it.noteId) })
        val now = System.currentTimeMillis()
        plans.forEach { plan ->
          val note = notes.get(plan.noteId) ?: return@forEach
          if (note.folderId == targetFolderId) return@forEach
          if (plan.wholeNote) {
            notes.move(note.id, targetFolderId, now)
          } else {
            // A subset needs its own backing material. Copy the user's material so the moved sessions
            // retain it, and leave the original completely intact for unselected siblings.
            val target = note.copy(id = Ids.newId(), folderId = targetFolderId, updatedAt = now, bundleBodyBase = null, bundleNativeAdopted = null)
            notes.upsert(target)
            tags.replace(target.id, tags.tags(note.id))
            val originalSources = sources.byNote(note.id)
            val sourceIds = originalSources.associate { it.id to Ids.newId() }
            originalSources.forEach { source ->
              val sourceId = sourceIds.getValue(source.id)
              val stored = source.storedFileName?.let { originalName ->
                // Separate paths are essential: computer-only eviction of the old personal material
                // must not remove bytes still needed by the moved material (possibly at school).
                val newName = files.newSourceName(sourceId, source.originalName, source.mime)
                val names = listOf(FilesInUse.source(originalName), FilesInUse.source(newName))
                held.addAll(names)
                inUse.hold(names, ttlMillis = AppFiles.ORPHAN_MIN_AGE_MS, owner = owner)
                val original = files.sourceFile(originalName)
                if (original.exists()) {
                  val destination = files.sourceFile(newName)
                  copied += destination
                  original.copyTo(destination, overwrite = false)
                }
                newName
              }
              sources.upsert(source.copy(id = sourceId, noteId = target.id, storedFileName = stored,
                derivedFromId = sourceIds[source.derivedFromId] ?: source.derivedFromId))
            }
            siblings.filter { it.id in plan.sessionIds }.sortedBy { it.position }.forEachIndexed { position, session ->
              sessions.upsert(session.copy(noteId = target.id, position = position, updatedAt = now))
            }
            notes.touch(note.id, now)
          }
        }
      }
    } catch (error: Throwable) {
      // The Room transaction rolled back, so only files created by this move may be removed.
      copied.forEach { runCatching { it.delete() } }
      throw error
    } finally {
      inUse.release(held, owner = owner)
    }
  }
}
