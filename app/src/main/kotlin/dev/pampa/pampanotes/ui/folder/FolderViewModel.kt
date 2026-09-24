package dev.pampa.pampanotes.ui.folder

import dev.pampa.pampanotes.core.db.JobEntity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.FolderRow
import dev.pampa.pampanotes.core.db.NoteRow
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.NoteRepository
import dev.pampa.pampanotes.core.repo.PersonalScope
import dev.pampa.pampanotes.core.repo.SessionRepository
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.NoteTranscribingElsewhere
import dev.pampa.pampanotes.core.transcription.TranscribingMarker
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FolderUiState(
  val folder: FolderEntity? = null,
  val path: List<FolderEntity> = emptyList(),
  val subfolders: List<FolderRow> = emptyList(),
  val notes: List<NoteRow> = emptyList(),
  val query: String = "",
  /** Tutte le cartelle: dove una nota selezionata puo' andare. */
  val allFolders: List<FolderEntity> = emptyList(),
  /** Le note con lezioni che un altro dispositivo sta trascrivendo: il badge dice dove, non «da trascrivere». */
  val elsewhere: Map<String, NoteTranscribingElsewhere> = emptyMap(),
  /** I lavori di qui, per nota: il badge dice «In coda» o a che punto e', come nella home. */
  val jobs: Map<String, JobEntity> = emptyMap(),
  /**
   * La cartella sta in Registrazioni (lei o la sua radice): non e' una materia, e la schermata non
   * ne prende il colore; «Importa» porta dentro di lei.
   */
  val personal: Boolean = false,
  val loading: Boolean = true,
) {
  /** Solo una cartella di primo livello cambia sezione: le sottocartelle seguono lei. */
  val canChangeSection: Boolean get() = folder != null && folder.parentId == null

  /** Le sessioni senza trascrizione che nessun altro dispositivo sta gia' trascrivendo. */
  fun toTranscribe(row: NoteRow): Int = row.untranscribedSessions - (elsewhere[row.note.id]?.untranscribed ?: 0)

  val visibleNotes: List<NoteRow>
    get() = if (query.isBlank()) notes else notes.filter {
      it.note.title.contains(query, ignoreCase = true) || it.note.body.contains(query, ignoreCase = true)
    }
}

@HiltViewModel
class FolderViewModel @Inject constructor(
  savedStateHandle: SavedStateHandle,
  private val folders: FolderRepository,
  private val notes: NoteRepository,
  private val sessions: SessionRepository,
  private val transcription: TranscriptionRepository,
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
) : ViewModel() {

  private val folderId: String = savedStateHandle.get<String>("folderId").orEmpty()
  private val query = MutableStateFlow("")
  private val path = MutableStateFlow<List<FolderEntity>>(emptyList())

  init {
    viewModelScope.launch { path.value = folders.pathTo(folderId) }
  }

  val uiState: StateFlow<FolderUiState> = combine(
    folders.observe(folderId),
    folders.observeChildren(folderId),
    notes.observeRows(folderId),
    query,
    path,
    folders.observeAll(),
    transcription.observeElsewhere().map { TranscribingMarker.byNote(it.values) },
    transcription.observeActive().map { active ->
      active.mapNotNull { job -> sessions.get(job.sessionId)?.noteId?.let { it to job } }.toMap()
    },
  ) { values ->
    @Suppress("UNCHECKED_CAST")
    FolderUiState(
      folder = values[0] as FolderEntity?,
      path = values[4] as List<FolderEntity>,
      subfolders = values[1] as List<FolderRow>,
      notes = values[2] as List<NoteRow>,
      query = values[3] as String,
      allFolders = values[5] as List<FolderEntity>,
      elsewhere = values[6] as Map<String, NoteTranscribingElsewhere>,
      jobs = values[7] as Map<String, JobEntity>,
      personal = PersonalScope.isPersonal(folderId, values[5] as List<FolderEntity>),
      loading = false,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderUiState())

  fun setQuery(value: String) {
    query.value = value
  }

  fun createSubfolder(name: String, tone: String?, icon: String?) {
    viewModelScope.launch { folders.create(name = name, parentId = folderId, tone = tone, icon = icon) }
  }

  fun createNote(title: String, onCreated: (String) -> Unit = {}) {
    viewModelScope.launch {
      val note = notes.create(folderId = folderId, title = title)
      onCreated(note.id)
    }
  }

  fun updateFolder(id: String, name: String, tone: String?, icon: String?) {
    viewModelScope.launch { folders.update(id, name, tone, icon) }
  }

  /** «Sposta in Registrazioni» / «Sposta fra le materie», per la cartella che si sta guardando. */
  fun setPersonal(personal: Boolean) {
    viewModelScope.launch { folders.setKind(folderId, if (personal) FolderEntity.KIND_PERSONAL else FolderEntity.KIND_SCHOOL) }
  }

  fun deleteFolder(id: String) {
    // Chi cancella la cartella che sta guardando torna subito indietro, e il ViewModel se ne va
    // con la pagina: la cancellazione non deve andarsene con lui.
    viewModelScope.launch { withContext(NonCancellable) { folders.delete(id) } }
  }

  fun deleteNote(id: String) {
    viewModelScope.launch { notes.delete(id) }
  }

  fun togglePinned(id: String, pinned: Boolean) {
    viewModelScope.launch { notes.setPinned(id, pinned) }
  }

  // --- la selezione ---

  fun deleteNotes(ids: Collection<String>) {
    viewModelScope.launch { ids.forEach { notes.delete(it) } }
  }

  fun moveNotes(ids: Collection<String>, targetFolderId: String) {
    viewModelScope.launch { ids.forEach { notes.move(it, targetFolderId) } }
  }

  /** Le sessioni senza trascrizione delle note scelte vanno in coda, col provider delle impostazioni. */
  fun transcribePending(ids: Collection<String>) {
    viewModelScope.launch {
      val provider = settingsStore.current().transcriptionProvider
      var any = false
      ids.forEach { noteId ->
        sessions.byNote(noteId)
          .filter { it.parts.isNotEmpty() && it.session.activeTranscriptId == null }
          // Quelle che un altro dispositivo sta trascrivendo le salta `enqueue`.
          .forEach { if (transcription.enqueue(it.session.id, provider) != null) any = true }
      }
      if (any) scheduler.kick(provider.id)
    }
  }
}
