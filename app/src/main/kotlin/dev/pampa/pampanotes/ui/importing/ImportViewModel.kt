package dev.pampa.pampanotes.ui.importing

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.importing.AudioPlacement
import dev.pampa.pampanotes.core.importing.ImportCandidate
import dev.pampa.pampanotes.core.importing.ImportCoordinator
import dev.pampa.pampanotes.core.importing.ImportOutcome
import dev.pampa.pampanotes.core.importing.ImportTarget
import dev.pampa.pampanotes.core.model.Dates
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.NoteRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** I passi del wizard, nell'ordine in cui si attraversano. */
enum class ImportStep { INSPECTING, REVIEW, DESTINATION, AUDIO, RUNNING, DONE }

data class ImportUiState(
  val step: ImportStep = ImportStep.INSPECTING,
  val candidates: List<ImportCandidate> = emptyList(),
  val excluded: Set<String> = emptySet(),
  val folders: List<FolderEntity> = emptyList(),
  val folderPaths: Map<String, String> = emptyMap(),
  val notesInFolder: List<NoteEntity> = emptyList(),
  val selectedFolderId: String? = null,
  val selectedNoteId: String? = null,
  val newNoteTitle: String = "",
  val existingSessions: List<SessionEntity> = emptyList(),
  val appendToSessionId: String? = null,
  val sessionDate: String = Dates.today(),
  val progressLabel: String = "",
  val progress: Float = 0f,
  val outcome: ImportOutcome? = null,
  val error: String? = null,
) {
  val included: List<ImportCandidate> get() = candidates.filterNot { it.id in excluded }
  val hasAudio: Boolean get() = included.any { it.isAudio }
  val hasDocuments: Boolean get() = included.any { !it.isAudio }
  /** Quando la nota e' gia' decisa (import da dentro una nota) il passo destinazione non serve. */
  val targetIsFixed: Boolean get() = selectedNoteId != null && selectedFolderId == null
}

@HiltViewModel
class ImportViewModel @Inject constructor(
  private val coordinator: ImportCoordinator,
  private val folders: FolderRepository,
  private val notes: NoteRepository,
  private val sessions: SessionDao,
  private val requests: ImportRequestHolder,
) : ViewModel() {

  private val _uiState = MutableStateFlow(ImportUiState())
  val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

  init {
    // La richiesta e' gia' li' quando il wizard si apre: la si prende e la si svuota, cosi' una
    // rotazione non la importa una seconda volta.
    val request = requests.take()
    if (request == null) {
      _uiState.value = ImportUiState(step = ImportStep.REVIEW)
    } else {
      start(request.uris, request.text, request.intoNoteId)
    }
  }

  /**
   * Prepara l'import.
   *
   * @param intoNoteId quando si importa da dentro una nota: la destinazione e' gia' quella e il
   *   wizard salta il passo che la chiede.
   */
  private fun start(uris: List<Uri>, sharedText: String?, intoNoteId: String? = null) {
    viewModelScope.launch {
      _uiState.value = ImportUiState(step = ImportStep.INSPECTING, selectedNoteId = intoNoteId)
      val fromFiles = coordinator.inspect(uris)
      val fromText = sharedText?.takeIf { it.isNotBlank() }?.let {
        listOf(coordinator.inspectText(it, defaultTextName(it)))
      }.orEmpty()
      val all = fromFiles + fromText

      val allFolders = folders.all()
      val paths = allFolders.associate { it.id to folders.pathString(it.id) }

      if (all.isEmpty()) {
        _uiState.update { it.copy(step = ImportStep.REVIEW, candidates = emptyList(), folders = allFolders, folderPaths = paths) }
        return@launch
      }

      val defaultTitle = all.firstOrNull { !it.isAudio }?.displayName?.substringBeforeLast('.')
        ?: all.first().displayName.substringBeforeLast('.')

      _uiState.update {
        it.copy(
          step = ImportStep.REVIEW,
          candidates = all,
          // I doppioni partono esclusi: reimportare due volte lo stesso PDF e' quasi sempre un errore.
          excluded = all.filter { candidate -> candidate.isDuplicate }.map { candidate -> candidate.id }.toSet(),
          folders = allFolders,
          folderPaths = paths,
          selectedFolderId = if (intoNoteId != null) null else allFolders.firstOrNull()?.id,
          newNoteTitle = defaultTitle,
        )
      }
      if (intoNoteId != null) loadSessions(intoNoteId)
    }
  }

  fun toggleCandidate(id: String) {
    _uiState.update { state ->
      state.copy(excluded = if (id in state.excluded) state.excluded - id else state.excluded + id)
    }
  }

  fun selectFolder(folderId: String) {
    _uiState.update { it.copy(selectedFolderId = folderId, selectedNoteId = null) }
    viewModelScope.launch {
      val inFolder = notes.byFolder(folderId)
      _uiState.update { it.copy(notesInFolder = inFolder) }
    }
  }

  fun selectNote(noteId: String?) {
    _uiState.update { it.copy(selectedNoteId = noteId) }
    if (noteId != null) loadSessions(noteId)
  }

  fun setNewNoteTitle(title: String) = _uiState.update { it.copy(newNoteTitle = title) }

  fun setAppendToSession(sessionId: String?) = _uiState.update { it.copy(appendToSessionId = sessionId) }

  fun setSessionDate(date: String) = _uiState.update { it.copy(sessionDate = date) }

  fun createFolder(name: String) {
    viewModelScope.launch {
      val folder = folders.create(name)
      val allFolders = folders.all()
      val paths = allFolders.associate { it.id to folders.pathString(it.id) }
      _uiState.update { it.copy(folders = allFolders, folderPaths = paths, selectedFolderId = folder.id, notesInFolder = emptyList()) }
    }
  }

  /** Avanti di un passo, saltando quelli che non hanno niente da chiedere. */
  fun next() {
    val state = _uiState.value
    when (state.step) {
      ImportStep.REVIEW -> _uiState.update {
        it.copy(step = if (it.targetIsFixed) (if (it.hasAudio) ImportStep.AUDIO else ImportStep.RUNNING) else ImportStep.DESTINATION)
      }
      ImportStep.DESTINATION -> _uiState.update {
        it.copy(step = if (it.hasAudio) ImportStep.AUDIO else ImportStep.RUNNING)
      }
      ImportStep.AUDIO -> _uiState.update { it.copy(step = ImportStep.RUNNING) }
      else -> Unit
    }
    if (_uiState.value.step == ImportStep.RUNNING) run()
  }

  fun back() {
    _uiState.update { state ->
      val previous = when (state.step) {
        ImportStep.AUDIO -> if (state.targetIsFixed) ImportStep.REVIEW else ImportStep.DESTINATION
        ImportStep.DESTINATION -> ImportStep.REVIEW
        else -> state.step
      }
      state.copy(step = previous)
    }
  }

  private fun loadSessions(noteId: String) {
    viewModelScope.launch {
      val list = sessions.byNote(noteId).map { it.session }
      _uiState.update { state ->
        state.copy(
          existingSessions = list,
          // Il default: se la nota ha gia' una sessione di oggi, il nuovo audio ci va dentro. E'
          // il caso della registrazione staccata per sbaglio e ripresa subito.
          appendToSessionId = list.lastOrNull { it.date == Dates.today() }?.id,
        )
      }
    }
  }

  private fun run() {
    val state = _uiState.value
    val items = state.included
    if (items.isEmpty()) {
      _uiState.update { it.copy(step = ImportStep.DONE) }
      return
    }
    val target = when {
      state.selectedNoteId != null -> ImportTarget.ExistingNote(state.selectedNoteId)
      state.selectedFolderId != null -> ImportTarget.NewNote(state.selectedFolderId, state.newNoteTitle)
      else -> null
    }
    if (target == null) {
      _uiState.update { it.copy(step = ImportStep.REVIEW, error = "Scegli dove mettere quello che importi") }
      return
    }
    val placement = state.appendToSessionId
      ?.let { AudioPlacement.Append(it) }
      ?: AudioPlacement.NewSession(date = state.sessionDate)

    viewModelScope.launch {
      runCatching {
        coordinator.importAll(items, target, placement) { done, total, label ->
          _uiState.update { it.copy(progress = if (total == 0) 0f else done.toFloat() / total, progressLabel = label) }
        }
      }.onSuccess { outcome ->
        _uiState.update { it.copy(step = ImportStep.DONE, outcome = outcome, progress = 1f) }
      }.onFailure { error ->
        _uiState.update { it.copy(step = ImportStep.DONE, error = error.message ?: "Import non riuscito") }
      }
    }
  }

  /** Il nome di un testo incollato: la sua prima riga, tagliata. */
  private fun defaultTextName(text: String): String =
    text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("#")?.trim()?.take(60)?.ifEmpty { null }
      ?: "Testo incollato"
}
