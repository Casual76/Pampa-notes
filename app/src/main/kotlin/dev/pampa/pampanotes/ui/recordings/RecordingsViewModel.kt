package dev.pampa.pampanotes.ui.recordings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.FolderRow
import dev.pampa.pampanotes.core.db.NoteRow
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.repo.ComputerOnlyPreview
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.PersonalScope
import dev.pampa.pampanotes.core.repo.StatsRepository
import dev.pampa.pampanotes.core.repo.StorageRepository
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.core.stats.TranscriptionStats
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * I numeri della sezione, contati solo su di lei: le ore registrate, quelle trascritte, le parole, la
 * velocita'. Sono gli stessi conti della home ([TranscriptionStats.aggregate]) sull'altra meta'
 * dell'archivio.
 */
data class RecordingsStats(
  val recordedMs: Long = 0,
  val words: Long = 0,
  val transcription: TranscriptionStats = TranscriptionStats(),
)

/** Dove stanno i file della sezione su questo dispositivo: la nota in fondo alla scheda lo dice. */
enum class RecordingsWhere {
  /** Questo dispositivo le tiene anche qui. */
  HERE,

  /** «Tieni tutto anche qui»: questo dispositivo scarica tutto, loro comprese. */
  MIRROR,

  /** Solo sul computer: se ne vanno da qui quando ci sono arrivate. */
  COMPUTER,

  /** Sul computer di serie, ma l'archivio e' spento: non ci arrivano, e restano qui. */
  NO_ARCHIVE,

  /** Nessun computer: restano qui. */
  NO_COMPUTER,
}

data class RecordingsUiState(
  /** Le cartelle di primo livello della sezione. */
  val folders: List<FolderRow> = emptyList(),
  /** Le registrazioni di tutte le cartelle della sezione, dalla piu' recente. */
  val recordings: List<RecordingItem> = emptyList(),
  val materials: List<NoteRow> = emptyList(),
  val allFolders: List<FolderEntity> = emptyList(),
  val folder: FolderEntity? = null,
  val query: String = "",
  val stats: RecordingsStats = RecordingsStats(),
  /** Questo dispositivo tiene le Registrazioni anche qui, invece che solo sul computer. */
  val keepHere: Boolean = false,
  /** C'e' un computer di casa: senza, «solo sul computer» non ha dove tenere i file. */
  val hasComputer: Boolean = false,
  /** «Tieni tutto anche qui» e' acceso: la scelta della sezione qui non cambia niente. */
  val mirror: Boolean = false,
  val archiveEnabled: Boolean = false,
  val loading: Boolean = true,
) {
  val isEmpty: Boolean get() = !loading && recordings.isEmpty() && materials.isEmpty() && folders.isEmpty()

  /** C'e' qualcosa da trascrivere che non e' gia' in coda e non e' muto: «Trascrivi tutte» serve. */
  val canTranscribeAll: Boolean get() = recordings.any { it.worthTranscribing > 0 && it.job == null }

  val where: RecordingsWhere
    get() = when {
      mirror -> RecordingsWhere.MIRROR
      keepHere -> RecordingsWhere.HERE
      !hasComputer -> RecordingsWhere.NO_COMPUTER
      !archiveEnabled -> RecordingsWhere.NO_ARCHIVE
      else -> RecordingsWhere.COMPUTER
    }
}

/** «Trascrivi tutte», prima di partire: quante, quanto audio, e con chi. */
data class TranscribeAllAsk(
  val sessionIds: List<String>,
  val durationMs: Long,
  val provider: TranscriptionProviderId,
  /** Sessioni mute lasciate fuori: la conferma lo dice. */
  val skippedSilent: Int,
)

/**
 * La scheda Registrazioni: l'audio che non e' una lezione, con i suoi numeri.
 *
 * Le cartelle sono quelle di primo livello con `kind = personal` (vedi `PersonalScope`); le
 * registrazioni sono le sessioni di tutte, sottocartelle comprese, perche' qui si cerca «quella di
 * ieri» e non «quella nella cartella giusta».
 */
@HiltViewModel
class RecordingsViewModel @Inject constructor(
  savedStateHandle: SavedStateHandle,
  private val folders: FolderRepository,
  private val library: RecordingLibrary,
  private val actions: RecordingActions,
  private val transcription: TranscriptionRepository,
  private val storage: StorageRepository,
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
  audioParts: AudioPartDao,
  transcripts: TranscriptDao,
  stats: StatsRepository,
) : ViewModel() {

  private val sectionStats: Flow<RecordingsStats> = combine(
    audioParts.observeDurationIn(personal = true),
    transcripts.observeWordTotalIn(personal = true),
    stats.observe(personal = true),
  ) { recorded, words, transcriptionStats -> RecordingsStats(recorded, words, transcriptionStats) }

  val folderId: String? = savedStateHandle.get<String>("folderId")
  private val query = MutableStateFlow("")
  private val content = combine(library.observe(folderId), query) { content, query ->
    RecordingsUiState(
      folders = content.allFolders.filter { it.parentId == folderId && (folderId != null || it.isPersonal) }
        .map { FolderRow(it, 0, content.allFolders.count { child -> child.parentId == it.id }) },
      recordings = content.recordings,
      materials = content.materials,
      allFolders = content.allFolders,
      folder = content.allFolders.firstOrNull { it.id == folderId },
      query = query,
      loading = false,
    )
  }

  fun setQuery(value: String) { query.value = value }

  val uiState: StateFlow<RecordingsUiState> = combine(
    content,
    sectionStats,
    settingsStore.keepPersonalHere,
    settingsStore.settings.map { Triple(it.hasEndpoint, it.mirrorEnabled, it.archiveEnabled) }.distinctUntilChanged(),
  ) { state, sectionStats, keepHere, (hasComputer, mirror, archive) ->
    state.copy(stats = sectionStats, keepHere = keepHere, hasComputer = hasComputer, mirror = mirror, archiveEnabled = archive)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingsUiState())

  private val _transcribeAllAsk = MutableStateFlow<TranscribeAllAsk?>(null)
  val transcribeAllAsk: StateFlow<TranscribeAllAsk?> = _transcribeAllAsk.asStateFlow()

  private val _computerOnlyAsk = MutableStateFlow<ComputerOnlyPreview?>(null)

  /** «Registrazioni solo sul computer», prima di togliere: quanto se ne andrebbe da qui. */
  val computerOnlyAsk: StateFlow<ComputerOnlyPreview?> = _computerOnlyAsk.asStateFlow()

  /** «Nuova cartella» qui e' una cartella di Registrazioni. */
  fun createFolder(name: String, tone: String?, icon: String?) {
    viewModelScope.launch { folders.create(name = name, parentId = folderId, tone = tone, icon = icon, kind = FolderEntity.KIND_PERSONAL) }
  }

  fun updateFolder(id: String, name: String, tone: String?, icon: String?) {
    viewModelScope.launch { folders.update(id, name, tone, icon) }
  }

  fun deleteFolder(id: String) {
    viewModelScope.launch { withContext(NonCancellable) { folders.delete(id) } }
  }

  /** «Sposta fra le materie»: la cartella torna una tessera di Cartelle, con tutto quello che ha dentro. */
  fun moveToSchool(id: String) {
    viewModelScope.launch { folders.setKind(id, FolderEntity.KIND_SCHOOL) }
  }

  /** No folder creation step is required. The existing import contract receives a personal folder. */
  fun prepareImport(defaultFolderName: String, onReady: (String) -> Unit) {
    viewModelScope.launch {
      val all = folders.all()
      val personal = PersonalScope.folderIds(all)
      val destination = folderId?.takeIf { it in personal }
        ?: all.filter { it.parentId == null && it.isPersonal }.minByOrNull { it.sortOrder }?.id
        ?: folders.create(name = defaultFolderName, kind = FolderEntity.KIND_PERSONAL).id
      onReady(destination)
    }
  }

  private fun selected(ids: Collection<String>): Set<String> =
    RecordingCatalog.selected(ids, uiState.value.recordings.map { it.id })

  fun deleteRecordings(ids: Collection<String>) {
    val selected = selected(ids)
    viewModelScope.launch { actions.delete(selected, folderId) }
  }

  fun moveRecordings(ids: Collection<String>, targetFolderId: String) {
    val selected = selected(ids)
    viewModelScope.launch { withContext(NonCancellable) { actions.move(selected, targetFolderId, folderId) } }
  }

  fun askTranscribeAll() = askTranscribe(null)

  /** IDs are sessions, including when several selected recordings share one backing note. */
  fun askTranscribe(only: Set<String>?) {
    val wanted = uiState.value.recordings.filter { only == null || it.id in only }
    val pending = wanted.filter { it.toTranscribe > 0 && it.job == null }
    val audible = pending.filter { it.worthTranscribing > 0 }
    if (audible.isEmpty()) return
    viewModelScope.launch {
      _transcribeAllAsk.value = TranscribeAllAsk(
        sessionIds = audible.map { it.id },
        durationMs = audible.sumOf { it.session.durationMs },
        provider = settingsStore.current().transcriptionProvider,
        skippedSilent = pending.size - audible.size,
      )
    }
  }

  fun confirmTranscribeAll() {
    val ask = _transcribeAllAsk.value ?: return
    _transcribeAllAsk.value = null
    viewModelScope.launch { enqueue(ask.sessionIds, ask.provider) }
  }

  fun dismissTranscribeAll() {
    _transcribeAllAsk.value = null
  }

  private suspend fun enqueue(sessionIds: List<String>, provider: TranscriptionProviderId) {
    // Quelle che un altro dispositivo sta trascrivendo le salta `enqueue`.
    val any = sessionIds.map { transcription.enqueue(it, provider) }.any { it != null }
    if (any) scheduler.kick(provider.id)
  }

  /**
   * «Tieni le registrazioni anche qui»: la regola di serie non vale piu' su questo dispositivo, e
   * quello che sta solo sul computer torna subito — un giro di scarico della sola sezione, anche con
   * «tieni tutto anche qui» spento.
   */
  fun keepHere() {
    viewModelScope.launch {
      settingsStore.setKeepPersonalHere(true)
      val settings = settingsStore.current()
      if (settings.hasEndpoint) scheduler.fetchPersonal(settings.archiveOnlyUnmetered)
    }
  }

  /**
   * «Registrazioni solo sul computer»: prima si conta quello che se ne andrebbe da qui, e si chiede,
   * come per la regola su una cartella.
   */
  fun askComputerOnly() {
    viewModelScope.launch {
      val roots = PersonalScope.rootIds(folders.all())
      _computerOnlyAsk.value = runCatching { storage.computerOnlyPreview(roots, emptySet()) }.getOrDefault(ComputerOnlyPreview())
    }
  }

  /** Confermato: la regola di serie torna a valere, e quello che il computer ha gia' se ne va adesso. */
  fun confirmComputerOnly() {
    _computerOnlyAsk.value = null
    viewModelScope.launch {
      settingsStore.setKeepPersonalHere(false)
      runCatching { storage.evictComputerOnly() }
    }
  }

  fun dismissComputerOnly() {
    _computerOnlyAsk.value = null
  }
}
