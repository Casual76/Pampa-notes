package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.FolderDao
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.SyncDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Quello che sta nella sezione Registrazioni, risolto fino alle sessioni. */
data class PersonalItems(
  val folderIds: Set<String> = emptySet(),
  val noteIds: Set<String> = emptySet(),
  val sessionIds: Set<String> = emptySet(),
) {
  companion object {
    val NONE = PersonalItems()
  }
}

/**
 * La sezione Registrazioni: l'audio che non e' una lezione — una registrazione di diciannove ore,
 * un'intervista, un viaggio — tenuto accanto alle materie senza mescolarsi con loro.
 *
 * La regola sta sulla cartella di primo livello (`FolderEntity.kind`), e qui si risolve ogni volta
 * che serve, come fa [dev.pampa.pampanotes.core.archive.ComputerOnlyScope] per «solo sul computer»:
 * una nota spostata dentro Registrazioni ne fa parte da quel momento, una sottocartella pure, senza
 * che nessuno debba marcarle. Le query che contano in SQL usano la stessa regola
 * ([dev.pampa.pampanotes.core.db.PersonalSql]).
 */
@Singleton
class PersonalScope @Inject constructor(
  private val folders: FolderDao,
  private val sync: SyncDao,
) {
  suspend fun current(): PersonalItems = withContext(Dispatchers.IO) {
    val folderIds = folderIds(folders.all())
    if (folderIds.isEmpty()) return@withContext PersonalItems.NONE
    val noteIds = folderIds.toList().chunked(IN_CHUNK).flatMap { sync.noteIdsInFolders(it) }.toSet()
    val sessionIds = noteIds.toList().chunked(IN_CHUNK).flatMap { sync.sessionIdsOfNotes(it) }.toSet()
    PersonalItems(folderIds, noteIds, sessionIds)
  }

  companion object {
    /** Le cartelle di primo livello che aprono una sezione Registrazioni. */
    fun rootIds(all: List<FolderEntity>): Set<String> =
      all.filterTo(ArrayList()) { it.parentId == null && it.isPersonal }.mapTo(LinkedHashSet()) { it.id }

    /** Le cartelle personali, sottocartelle comprese: quello che sta sotto una radice personale. */
    fun folderIds(all: List<FolderEntity>): Set<String> =
      rootIds(all).flatMapTo(LinkedHashSet()) { FolderRepository.descendants(it, all) }

    /**
     * Se una cartella sta in Registrazioni: si risale fino alla radice, e decide lei. Un ciclo nei
     * genitori (che `move` rifiuta, ma un sync fatto male potrebbe portare) si ferma e vale «no».
     */
    fun isPersonal(folderId: String?, all: List<FolderEntity>): Boolean {
      val byId = all.associateBy { it.id }
      var current = folderId?.let { byId[it] } ?: return false
      val seen = HashSet<String>()
      while (true) {
        if (!seen.add(current.id)) return false
        val parent = current.parentId ?: return current.isPersonal
        current = byId[parent] ?: return false
      }
    }

    private const val IN_CHUNK = 500
  }
}
