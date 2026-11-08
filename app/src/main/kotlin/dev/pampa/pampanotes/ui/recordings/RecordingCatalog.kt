package dev.pampa.pampanotes.ui.recordings

/** Pure identities used by the personal library. A note is storage, never a recording identity. */
object RecordingCatalog {
  data class Material(val id: String, val folderId: String, val body: String, val sourceCount: Int)
  data class Session(val id: String, val noteId: String)
  data class Projection(val sessionIds: List<String>, val materialIds: List<String>)
  data class Move(val noteId: String, val sessionIds: Set<String>, val wholeNote: Boolean)

  fun project(
    notes: List<Material>,
    sessions: List<Session>,
    personalFolderIds: Set<String>,
    folderId: String? = null,
  ): Projection {
    val inScope = notes.filter { it.folderId in personalFolderIds && (folderId == null || it.folderId == folderId) }
    val noteIds = inScope.mapTo(HashSet()) { it.id }
    val visible = sessions.filter { it.noteId in noteIds }.distinctBy { it.id }
    val withSessions = visible.mapTo(HashSet()) { it.noteId }
    return Projection(
      sessionIds = visible.map { it.id },
      // Even a body-only, source-only or empty legacy material stays reachable. Metadata and tags
      // on notes with sessions are also available through every session's associated-material action.
      materialIds = inScope.filter { it.body.isNotBlank() || it.sourceCount > 0 || it.id !in withSessions }.map { it.id },
    )
  }

  /** Intersect saved selections with current scope after moves, sync or deletion. Never expand to siblings. */
  fun selected(ids: Collection<String>, visibleSessionIds: Collection<String>): Set<String> =
    ids.toSet().intersect(visibleSessionIds.toSet())

  /** Move a whole backing note only when ALL of its sessions were explicitly selected. */
  fun moves(ids: Set<String>, allSessions: List<Session>): List<Move> =
    allSessions.groupBy { it.noteId }.mapNotNull { (noteId, siblings) ->
      val selected = siblings.mapTo(LinkedHashSet()) { it.id }.intersect(ids)
      selected.takeIf { it.isNotEmpty() }?.let { Move(noteId, it, it.size == siblings.size) }
    }
}
