package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.FolderEntity

/**
 * La sezione Registrazioni: l'audio che non e' una lezione — una registrazione di diciannove ore,
 * un'intervista, un viaggio — tenuto accanto alle materie senza mescolarsi con loro.
 *
 * La regola sta sulla cartella di primo livello (`FolderEntity.kind`), e qui si risolve ogni volta
 * che serve, come fa [dev.pampa.pampanotes.core.archive.ComputerOnlyScope] per «solo sul computer»:
 * una nota spostata dentro Registrazioni ne fa parte da quel momento, una sottocartella pure, senza
 * che nessuno debba marcarle. Le query che contano in SQL usano la stessa regola
 * ([dev.pampa.pampanotes.core.db.PersonalSql]).
 *
 * Solo funzioni pure sulle cartelle: chi ha bisogno di note e sessioni le chiede in SQL, o le ricava
 * dalle cartelle come fa `ComputerOnlyScope`.
 */
object PersonalScope {
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
}
