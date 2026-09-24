package dev.pampa.pampanotes.core.archive

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.FolderDao
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SyncDao
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.PersonalScope
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Quello che una regola «solo sul computer» copre, risolto fino ai file.
 *
 * [folderIds] e' gia' chiuso sulle sottocartelle, [noteIds] comprende le note delle cartelle e
 * quelle scelte una per una. Le fonti sono solo quelle con un file e importate dall'utente: le
 * pagine scritte a mano ricavate da un `.sdocx` restano qui (vedi `StorageRepository.evictArchived`).
 */
class ComputerOnlyItems(
  val folderIds: Set<String> = emptySet(),
  val noteIds: Set<String> = emptySet(),
  val sessionIds: Set<String> = emptySet(),
  val parts: List<AudioPartEntity> = emptyList(),
  val sources: List<SourceEntity> = emptyList(),
) {
  private val partIds: Set<String> = parts.mapTo(HashSet()) { it.id }
  private val sourceIds: Set<String> = sources.mapTo(HashSet()) { it.id }

  fun covers(part: AudioPartEntity): Boolean = part.id in partIds
  fun covers(source: SourceEntity): Boolean = source.id in sourceIds

  val isEmpty: Boolean get() = parts.isEmpty() && sources.isEmpty()

  /** Il peso dalle righe, non dal disco: e' quello che la regola tiene fuori da qui, che il file ci sia o no. */
  val bytes: Long get() = parts.sumOf { it.sizeBytes } + sources.sumOf { it.sizeBytes }

  companion object {
    val NONE = ComputerOnlyItems()
  }
}

/**
 * «Solo sul computer»: le cartelle pesanti, o le singole note, le cui registrazioni e i cui originali
 * questo dispositivo non tiene.
 *
 * La regola sta nelle impostazioni di questo dispositivo (`computerOnlyFolders`, `computerOnlyNotes`)
 * e qui si risolve ogni volta che serve, invece di scriverla sulle righe: una nota spostata dentro
 * una cartella esclusa e' esclusa da quel momento, una registrazione arrivata dal sync pure, senza
 * che nessuno debba ricordarsi di marcarla. Le cartelle della sezione Registrazioni ci stanno di
 * serie, come se avessero la regola (vedi [current]). Due posti la leggono: «tieni tutto anche qui», che salta
 * quello che copre, e `StorageRepository.evictComputerOnly`, che lo toglie una volta archiviato.
 * Chi chiede un file per usarlo — il lettore, l'export, la trascrizione, una fonte toccata — lo
 * scarica lo stesso: la regola dice dove stanno i file, non che non si possono avere.
 */
@Singleton
class ComputerOnlyScope @Inject constructor(
  private val settingsStore: PampaSettingsStore,
  private val folders: FolderDao,
  private val sync: SyncDao,
) {
  /**
   * Quello che coprono le regole di adesso: quelle scritte e, se questo dispositivo non ha chiesto di
   * tenerle qui, le Registrazioni ([PampaSettingsStore.keepPersonalHere]).
   */
  suspend fun current(): ComputerOnlyItems =
    resolve(
      settingsStore.computerOnlyFolders.first(),
      settingsStore.computerOnlyNotes.first(),
      includePersonal = !settingsStore.keepPersonalHere.first(),
    )

  /**
   * Quello che coprirebbero queste regole: serve anche alla conferma, prima di accenderne una.
   *
   * @param includePersonal le cartelle della sezione Registrazioni come se avessero la regola: e'
   *   quello che valgono di serie. Non nella conferma di una regola nuova, che conta solo quello che
   *   quella regola toglierebbe.
   */
  suspend fun resolve(folderRules: Set<String>, noteRules: Set<String>, includePersonal: Boolean = false): ComputerOnlyItems = withContext(Dispatchers.IO) {
    if (folderRules.isEmpty() && noteRules.isEmpty() && !includePersonal) return@withContext ComputerOnlyItems.NONE
    val all = if (folderRules.isEmpty() && !includePersonal) emptyList() else folders.all()
    val rules = if (includePersonal) folderRules + PersonalScope.rootIds(all) else folderRules
    if (rules.isEmpty() && noteRules.isEmpty()) return@withContext ComputerOnlyItems.NONE
    val folderIds = if (rules.isEmpty()) emptySet() else closure(rules, all)
    val noteIds = inChunks(folderIds.toList()) { sync.noteIdsInFolders(it) }.toSet() + noteRules
    val sessionIds = inChunks(noteIds.toList()) { sync.sessionIdsOfNotes(it) }.toSet()
    ComputerOnlyItems(
      folderIds = folderIds,
      noteIds = noteIds,
      sessionIds = sessionIds,
      parts = inChunks(sessionIds.toList()) { sync.partsOfSessions(it) },
      sources = inChunks(noteIds.toList()) { sync.sourcesOfNotes(it) }.filter(::eligible),
    )
  }

  companion object {
    /** Le cartelle scelte e tutte quelle dentro. */
    fun closure(rules: Set<String>, all: List<FolderEntity>): Set<String> =
      rules.flatMapTo(LinkedHashSet()) { FolderRepository.descendants(it, all) }

    /**
     * Una fonte che la regola puo' tenere lontana da qui: ha un file, e l'ha importata l'utente. Le
     * pagine a mano pesano poco, stanno a schermo dentro la nota, e senza computer la nota
     * resterebbe con dei buchi al posto degli appunti.
     */
    fun eligible(source: SourceEntity): Boolean = source.storedFileName != null && source.derivedFromId == null

    private const val IN_CHUNK = 500

    /** Una `IN (...)` a pezzi: SQLite su Android vecchi non accetta piu' di 999 parametri. */
    private suspend fun <T> inChunks(ids: List<String>, query: suspend (List<String>) -> List<T>): List<T> =
      if (ids.isEmpty()) emptyList() else ids.chunked(IN_CHUNK).flatMap { query(it) }
  }
}
