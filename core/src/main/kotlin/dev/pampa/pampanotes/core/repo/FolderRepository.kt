package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.FolderDao
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.FolderRow
import dev.pampa.pampanotes.core.model.Ids
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class FolderRepository @Inject constructor(
  private val folders: FolderDao,
) {
  fun observeChildren(parentId: String?): Flow<List<FolderRow>> = folders.observeChildren(parentId)
  fun observe(id: String): Flow<FolderEntity?> = folders.observe(id)
  fun observeAll(): Flow<List<FolderEntity>> = folders.observeAll()
  fun observeCount(): Flow<Int> = folders.observeCount()

  suspend fun get(id: String): FolderEntity? = folders.get(id)
  suspend fun all(): List<FolderEntity> = folders.all()

  suspend fun create(name: String, parentId: String? = null, tone: String? = null): FolderEntity {
    val now = System.currentTimeMillis()
    val siblings = folders.children(parentId)
    val folder = FolderEntity(
      id = Ids.newId(),
      name = uniqueName(name.trim().ifEmpty { "Cartella" }, siblings.map { it.name }),
      parentId = parentId,
      sortOrder = (siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1,
      tone = tone,
      createdAt = now,
      updatedAt = now,
    )
    folders.upsert(folder)
    return folder
  }

  suspend fun rename(id: String, name: String) {
    val folder = folders.get(id) ?: return
    val trimmed = name.trim().ifEmpty { return }
    folders.upsert(folder.copy(name = trimmed, updatedAt = System.currentTimeMillis()))
  }

  suspend fun setTone(id: String, tone: String?) {
    val folder = folders.get(id) ?: return
    folders.upsert(folder.copy(tone = tone, updatedAt = System.currentTimeMillis()))
  }

  /** Sposta una cartella sotto un'altra (o alla radice con null). Rifiuta di metterla dentro se stessa. */
  suspend fun move(id: String, newParentId: String?): Boolean {
    val folder = folders.get(id) ?: return false
    if (newParentId != null && (newParentId == id || pathTo(newParentId).any { it.id == id })) return false
    folders.upsert(folder.copy(parentId = newParentId, updatedAt = System.currentTimeMillis()))
    return true
  }

  suspend fun delete(id: String) = folders.delete(id)

  /** La catena dalla radice alla cartella, per il breadcrumb. */
  suspend fun pathTo(id: String): List<FolderEntity> {
    val path = ArrayDeque<FolderEntity>()
    var current = folders.get(id)
    var guard = 0
    while (current != null && guard++ < 64) {
      path.addFirst(current)
      current = current.parentId?.let { folders.get(it) }
    }
    return path.toList()
  }

  /** "Cartella/Sottocartella" per l'export e l'indice. */
  suspend fun pathString(id: String): String = pathTo(id).joinToString("/") { it.name }

  private fun uniqueName(name: String, taken: List<String>): String {
    if (taken.none { it.equals(name, ignoreCase = true) }) return name
    var n = 2
    while (taken.any { it.equals("$name $n", ignoreCase = true) }) n++
    return "$name $n"
  }
}
