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
  suspend fun getAll(ids: List<String>): List<NoteEntity> = if (ids.isEmpty()) emptyList() else notes.getAll(ids)
  suspend fun tags(noteId: String): List<String> = tags.tags(noteId)
  suspend fun searchByTitle(query: String, limit: Int = 20): List<NoteEntity> = notes.byTitle(query, limit)

  /** [untitled] e' il titolo di una nota senza titolo nella lingua di chi chiama: il modulo core non ha stringhe. */
  suspend fun create(folderId: String, title: String, body: String = "", language: String? = null, untitled: String = "Senza titolo"): NoteEntity {
    val now = System.currentTimeMillis()
    val note = NoteEntity(
      id = Ids.newId(),
      folderId = folderId,
      title = title.trim().ifEmpty { untitled },
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

  /**
   * Salva quello che l'editor ha in mano, senza schiacciare quello che e' arrivato nel frattempo.
   *
   * [baseBody] e' il corpo su cui l'editor ha cominciato a scrivere (o l'ultimo che ha salvato).
   * Se nel database adesso c'e' altro — un giro di sync, un `.sdocx` aggiornato mentre si scriveva —
   * le due versioni sono cambiate tutte e due. Si fa come il sync: vince la piu' recente, che e'
   * quella che si sta scrivendo adesso, e l'altra diventa una nota «(conflitto — altrove, data)»
   * nella stessa cartella. Il testo di qualcuno non si perde mai, e l'editor non si vede cambiare
   * le parole sotto le dita.
   *
   * Il titolo si scrive solo se l'editor l'ha cambiato ([title] non null): chi ha aperto la nota e
   * scrive nel corpo non deve riportare indietro un titolo rinominato altrove mentre scriveva.
   *
   * Lettura, confronto e scritture stanno in una transazione: un giro di sync che applica una
   * versione nuova fra la lettura e la scrittura verrebbe sovrascritto senza passare dal confronto.
   *
   * @param title il titolo nuovo, o null se l'editor non l'ha toccato
   * @return la nota di conflitto, se e' nata
   */
  suspend fun saveEdit(id: String, title: String?, body: String, baseBody: String): NoteEntity? = notes.inTransaction {
    val current = notes.get(id) ?: return@inTransaction null
    val now = System.currentTimeMillis()
    var conflict: NoteEntity? = null
    if (current.body != baseBody && current.body != body) {
      val stamp = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now))
      conflict = current.copy(
        id = Ids.newId(),
        title = "${current.title} (conflitto — altrove, $stamp)",
        pinned = false,
        createdAt = now,
        updatedAt = now,
      )
      notes.upsert(conflict)
    }
    val cleanTitle = title?.trim()?.ifEmpty { current.title } ?: current.title
    if (cleanTitle != current.title) notes.updateTitle(id, cleanTitle, now)
    if (body != current.body) notes.updateBody(id, body, now)
    conflict
  }

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
