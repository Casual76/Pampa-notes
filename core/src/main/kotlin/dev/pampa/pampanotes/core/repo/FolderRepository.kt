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
  private val storage: StorageRepository,
) {
  fun observeChildren(parentId: String?): Flow<List<FolderRow>> = folders.observeChildren(parentId)
  fun observe(id: String): Flow<FolderEntity?> = folders.observe(id)
  fun observeAll(): Flow<List<FolderEntity>> = folders.observeAll()
  fun observeCount(): Flow<Int> = folders.observeCount()

  suspend fun get(id: String): FolderEntity? = folders.get(id)
  suspend fun all(): List<FolderEntity> = folders.all()

  /** [untitled] e' il nome di ripiego nella lingua di chi chiama: il modulo core non ha stringhe. */
  suspend fun create(name: String, parentId: String? = null, tone: String? = null, icon: String? = null, untitled: String = "Cartella"): FolderEntity {
    val now = System.currentTimeMillis()
    val siblings = folders.children(parentId)
    val folder = FolderEntity(
      id = Ids.newId(),
      name = uniqueName(name.trim().ifEmpty { untitled }, siblings.map { it.name }),
      parentId = parentId,
      sortOrder = (siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1,
      tone = tone,
      icon = icon,
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

  /** Nome, colore e icona insieme: e' quello che il pannello di modifica cambia in un colpo solo. */
  suspend fun update(id: String, name: String, tone: String?, icon: String?) {
    val folder = folders.get(id) ?: return
    folders.upsert(
      folder.copy(
        name = name.trim().ifEmpty { folder.name },
        tone = tone,
        icon = icon,
        updatedAt = System.currentTimeMillis(),
      ),
    )
  }

  /** Sposta una cartella sotto un'altra (o alla radice con null). Rifiuta di metterla dentro se stessa. */
  suspend fun move(id: String, newParentId: String?): Boolean {
    val folder = folders.get(id) ?: return false
    if (newParentId != null && (newParentId == id || pathTo(newParentId).any { it.id == id })) return false
    folders.upsert(folder.copy(parentId = newParentId, updatedAt = System.currentTimeMillis()))
    return true
  }

  /**
   * Una cartella se ne va con tutto quello che ha dentro, in cascata: note, sessioni, parti, fonti.
   * Le righe spariscono tutte insieme e i file restano su disco; la pulizia li toglie subito, come
   * fa la cancellazione di una nota, invece di lasciarli li' finche' qualcuno apre Archiviazione.
   */
  suspend fun delete(id: String) {
    folders.delete(id)
    runCatching { storage.sweepOrphans() }
  }

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

  /** "Cartella/Sottocartella" per l'export e l'indice: il percorso completo, cartella compresa. */
  suspend fun pathString(id: String): String = pathTo(id).joinToString("/") { it.name }

  /**
   * Solo i genitori, senza la cartella stessa: vuoto per una cartella di primo livello.
   *
   * E' quello che va nel sottotitolo di una riga che gia' porta il nome come titolo — altrimenti si
   * legge "Storia" e sotto di nuovo "Storia", che sembra un difetto perche' lo e'.
   */
  suspend fun parentPathString(id: String): String = pathTo(id).dropLast(1).joinToString("/") { it.name }

  /** La cartella e tutte quelle dentro, a qualunque profondita', la cartella per prima. */
  suspend fun descendantIds(rootId: String): Set<String> = descendants(rootId, folders.all())

  companion object {
    /**
     * La cartella e tutte quelle dentro, a qualunque profondita': la cartella per prima, poi un
     * livello alla volta. Le cartelle sono poche, e chi chiama le ha gia' lette tutte. Un ciclo
     * nei genitori — che `move` rifiuta, ma che un sync fatto male potrebbe portare — non gira
     * per sempre: una cartella gia' vista non si rivisita.
     */
    fun descendants(rootId: String, all: List<FolderEntity>): Set<String> {
      val byParent = all.groupBy { it.parentId }
      val result = LinkedHashSet<String>()
      val queue = ArrayDeque(listOf(rootId))
      while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!result.add(id)) continue
        byParent[id]?.forEach { queue.addLast(it.id) }
      }
      return result
    }
  }

  private fun uniqueName(name: String, taken: List<String>): String {
    if (taken.none { it.equals(name, ignoreCase = true) }) return name
    var n = 2
    while (taken.any { it.equals("$name $n", ignoreCase = true) }) n++
    return "$name $n"
  }
}
