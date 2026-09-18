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
import dev.pampa.pampanotes.ui.common.FolderIcon
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
  val hasAudio: Boolean get() = included.any { it.isAudio || (it.sdocx?.recordings?.isNotEmpty() == true) }
  /** Una nota di Samsung Notes da sola: il caso di tutti i giorni, e quello con il percorso corto. */
  val samsungNote: ImportCandidate? get() = candidates.singleOrNull()?.takeIf { it.isSamsungNote }
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
  private val settingsStore: dev.pampa.pampanotes.core.settings.PampaSettingsStore,
  private val transcription: dev.pampa.pampanotes.core.repo.TranscriptionRepository,
  private val scheduler: dev.pampa.pampanotes.work.WorkScheduler,
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
      val paths = allFolders.associate { it.id to folders.parentPathString(it.id) }

      if (all.isEmpty()) {
        _uiState.update { it.copy(step = ImportStep.REVIEW, candidates = emptyList(), folders = allFolders, folderPaths = paths) }
        return@launch
      }

      // Una nota di Samsung Notes porta il suo titolo: e' quello, non il nome del file.
      val samsung = all.singleOrNull()?.sdocx?.takeIf { !it.isEmpty }
      val defaultTitle = samsung?.title?.takeIf { it.isNotBlank() }
        ?: all.firstOrNull { !it.isAudio }?.displayName?.substringBeforeLast('.')
        ?: all.first().displayName.substringBeforeLast('.')
      // E dal titolo si indovina la materia: «Fichte» da solo non basta, ma «Storia» o «Filosofia»
      // nel titolo o nelle prime righe si'. Quando non si indovina resta la prima cartella.
      val guessedFolder = samsung?.let { doc ->
        val hint = FolderIcon.guessFrom(listOfNotNull(doc.title, doc.body.take(400)).joinToString(" "))
        if (hint == FolderIcon.Folder) null else allFolders.firstOrNull { FolderIcon.guessFrom(it.name) == hint }
      }

      _uiState.update {
        it.copy(
          step = ImportStep.REVIEW,
          candidates = all,
          // I doppioni partono esclusi: reimportare due volte lo stesso PDF e' quasi sempre un errore.
          // Non nel percorso corto, che non ha un interruttore per riammetterli: li' si avvisa e si
          // lascia decidere con il tasto, perche' una nota Samsung ricondivisa dopo una modifica e'
          // un caso normale, non un errore.
          excluded = if (samsung != null) emptySet() else all.filter { candidate -> candidate.isDuplicate }.map { candidate -> candidate.id }.toSet(),
          folders = allFolders,
          folderPaths = paths,
          selectedFolderId = if (intoNoteId != null) null else (guessedFolder ?: allFolders.firstOrNull())?.id,
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
      val paths = allFolders.associate { it.id to folders.parentPathString(it.id) }
      _uiState.update { it.copy(folders = allFolders, folderPaths = paths, selectedFolderId = folder.id, notesInFolder = emptyList()) }
    }
  }

  /** Avanti di un passo, saltando quelli che non hanno niente da chiedere. */
  fun next() {
    val state = _uiState.value
    when (state.step) {
      ImportStep.REVIEW -> _uiState.update {
        it.copy(
          step = when {
            it.targetIsFixed -> if (it.hasAudio) ImportStep.AUDIO else ImportStep.RUNNING
            // Il percorso corto: la nota Samsung ha gia' scelto titolo e cartella nella prima
            // schermata, e le sue registrazioni vanno in una sessione loro. Non c'e' altro da chiedere.
            it.samsungNote != null && it.selectedFolderId != null -> ImportStep.RUNNING
            else -> ImportStep.DESTINATION
          },
        )
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
        transcribeIfAsked(outcome.noteId)
      }.onFailure { error ->
        _uiState.update { it.copy(step = ImportStep.DONE, error = error.message ?: "Import non riuscito") }
      }
    }
  }

  /**
   * Mette in coda la trascrizione di quello che e' appena arrivato, se l'impostazione lo chiede.
   *
   * L'impostazione «Trascrivi appena importi» esisteva, era accesa di default, e non la applicava
   * nessuno. Qui si guardano le sessioni della nota rimaste senza trascrizione — quelle appena
   * create, ma anche una vecchia mai trascritta — e si mettono in coda una per una.
   */
  private suspend fun transcribeIfAsked(noteId: String) {
    val settings = settingsStore.current()
    if (!settings.autoTranscribeOnImport) return
    val pending = sessions.byNote(noteId).filter { it.parts.isNotEmpty() && it.session.activeTranscriptId == null }
    if (pending.isEmpty()) return
    pending.forEach { transcription.enqueue(it.session.id, settings.preferredProvider) }
    scheduler.kick(settings.preferredProvider.id)
  }

  /**
   * Il nome di un testo incollato o condiviso.
   *
   * La prima frase, non la prima riga: un testo condiviso spesso e' un paragrafo intero su una riga
   * sola, e prenderla tutta produceva un titolo lungo quanto la nota, tagliato a meta' parola nella
   * barra e inutile in un elenco. Se la prima frase e' comunque lunga si taglia a una parola intera.
   */
  private fun defaultTextName(text: String): String {
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("#")?.trim()
      ?: return "Testo incollato"
    val firstSentence = firstLine.split(SentenceEnd).firstOrNull()?.trim().orEmpty().ifEmpty { firstLine }
    val candidate = firstSentence.trimEnd('.', '!', '?', ';', ':')
    if (candidate.length <= MAX_TITLE_CHARS) return candidate.ifEmpty { "Testo incollato" }
    return candidate.take(MAX_TITLE_CHARS).substringBeforeLast(' ').trimEnd(',', ';', '-').ifEmpty { candidate.take(MAX_TITLE_CHARS) } + "…"
  }

  private companion object {
    /** Quanto sta nel titolo di una riga di elenco su un telefono, su due righe. */
    const val MAX_TITLE_CHARS = 48

    /** La fine di una frase: un segno di punteggiatura seguito da spazio. */
    val SentenceEnd = Regex("(?<=[.!?;:])\\s+")
  }
}
