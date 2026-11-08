package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.FolderEntity

/** Resolve folder metadata before writes; preserving local moves must not create mixed cycles. */
internal object PampaBundleFolderPlan {
  fun build(existing: List<FolderEntity>, incoming: List<FolderEntity>, into: String?, publicLegacy: Boolean?): List<FolderEntity> {
    val old = existing.associateBy { it.id }
    val resolved = incoming.map { folder ->
      val previous = old[folder.id]
      if (previous != null && (publicLegacy == true || previous.updatedAt > folder.updatedAt)) previous
      else folder.copy(parentId = folder.parentId ?: into)
    }
    val final = old + resolved.associateBy { it.id }
    resolved.forEach { folder ->
      val visited = mutableSetOf<String>()
      var cursor: String? = folder.id
      while (cursor != null) {
        require(visited.add(cursor)) { "Folder hierarchy conflict" }
        cursor = (final[cursor] ?: error("Missing folder parent")).parentId
      }
    }
    val available = old.keys.toMutableSet()
    val pending = resolved.toMutableList()
    return buildList {
      while (pending.isNotEmpty()) {
        val next = pending.firstOrNull { it.parentId == null || it.parentId in available }
          ?: error("Folder hierarchy conflict")
        add(next)
        available += next.id
        pending.remove(next)
      }
    }
  }
}
