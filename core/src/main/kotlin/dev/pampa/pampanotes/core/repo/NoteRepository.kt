package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.NoteRow
import dev.pampa.pampanotes.core.db.NoteTagDao
import dev.pampa.pampanotes.core.model.Ids
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class NoteRepository @Inject constructor(
  private val notes: NoteDao,
  private val tags: NoteTagDao,
  private val storage: StorageRepository,
) {
  fun observeRows(folderId: String): Flow<List<NoteRow>> = notes.observeRows(folderId)
  fun observeRecent(limit: Int = 8): Flow<List<NoteRow>> = notes.observeRecent(limit)
  fun observe(id: String): Flow<NoteEntity?> = notes.observe(id)
  fun observeCount(): Flow<Int> = notes.observeCount()
  fun observeTags(noteId: String): Flow<List<String>> = tags.observe(noteId)
  fun observeAllTags(): Flow<List<String>> = tags.observeAllTags()

  suspend fun get(id: String): NoteEntity? = notes.get(id)
  suspend fun byFolder(folderId: String): List<NoteEntity> = notes.byFolder(folderId)
  suspend fun all(): List<NoteEntity> = notes.all()
  suspend fun tags(noteId: String): List<String> = tags.tags(noteId)
  suspend fun searchByTitle(query: String, limit: Int = 20): List<NoteEntity> = notes.byTitle(query, limit)

  suspend fun create(folderId: String, title: String, body: String = "", language: String? = null): NoteEntity {
    val now = System.currentTimeMillis()
    val note = NoteEntity(
      id = Ids.newId(),
      folderId = folderId,
      title = title.trim().ifEmpty { "Senza titolo" },
      body = body,
      language = language,
      createdAt = now,
      updatedAt = now,
    )
    notes.upsert(note)
    return note
  }

  suspend fun setTitle(id: String, title: String) =
    notes.updateTitle(id, title.trim().ifEmpty { "Senza titolo" }, System.currentTimeMillis())

  suspend fun setBody(id: String, body: String) = notes.updateBody(id, body, System.currentTimeMillis())

  /** Aggiunge testo in fondo alla nota, separato da una riga vuota: quello che fa ogni import. */
  suspend fun appendBody(id: String, text: String) {
    val note = notes.get(id) ?: return
    val merged = if (note.body.isBlank()) text.trim() else note.body.trimEnd() + "\n\n" + text.trim()
    notes.updateBody(id, merged, System.currentTimeMillis())
  }

  suspend fun move(id: String, folderId: String) = notes.move(id, folderId, System.currentTimeMillis())

  suspend fun setPinned(id: String, pinned: Boolean) = notes.setPinned(id, pinned, System.currentTimeMillis())

  suspend fun setTags(id: String, values: List<String>) {
    tags.replace(id, values.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct())
    notes.touch(id, System.currentTimeMillis())
  }

  suspend fun touch(id: String) = notes.touch(id, System.currentTimeMillis())

  /**
   * Cancella la nota e, subito dopo, i file che erano solo suoi.
   *
   * Le righe se ne vanno in cascata dal database; i file no, e restare senza padrone e' il modo in
   * cui una cartella di audio diventa il doppio dello spazio che l'app dice di occupare.
   */
  suspend fun delete(id: String) {
    notes.delete(id)
    storage.sweepOrphans()
  }
}
