package dev.pampa.pampanotes.ui.importing

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.importing.AudioGrouping
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
  /** Nel percorso corto di Samsung Notes: aggiornare la nota che c'e' gia', invece di crearne una nuova. */
  val updateExisting: Boolean = true,
  val existingSessions: List<SessionEntity> = emptyList(),
  val appendToSessionId: String? = null,
  val sessionDate: String = Dates.today(),
  val progressLabel: String = "",
  val progress: Float = 0f,
  val outcome: ImportOutcome? = null,
  /** Le note toccate dall'import, nell'ordine: piu' di una quando le registrazioni sono state divise. */
  val createdNotes: List<CreatedNote> = emptyList(),
  val error: String? = null,
  /** Un errore che l'app sa dire con le sue parole; [error] e' quello che arriva da sotto, cosi' com'e'. */
  @param:androidx.annotation.StringRes val errorRes: Int? = null,
  /** Come dividere le registrazioni. Nasce entrando nel passo audio, e segue le esclusioni. */
  val grouping: AudioGrouping? = null,
) {
  val included: List<ImportCandidate> get() = candidates.filterNot { it.id in excluded }
  val hasAudio: Boolean get() = included.any { it.isAudio || (it.sdocx?.recordings?.isNotEmpty() == true) }

  /** Le registrazioni nell'ordine in cui [AudioImporter] le importa: per nome, come le numera un registratore. */
  val audioInOrder: List<ImportCandidate> get() = included.filter { it.isAudio }.sortedBy { it.displayName.lowercase() }

  /** Dividere ha senso solo con almeno due registrazioni, e non quando vanno in coda a una sessione che c'e' gia'. */
  val canGroup: Boolean get() = appendToSessionId == null && audioInOrder.size >= 2

  /** I gruppi come li vede la schermata: le registrazioni di ciascuno e il titolo, scelto o proposto. */
  val groupsView: List<AudioGroupView>
    get() {
      val grouping = grouping ?: return emptyList()
      val byId = audioInOrder.associateBy { it.id }
      return grouping.groups.mapIndexed { index, ids ->
        val items = ids.mapNotNull { byId[it] }
        AudioGroupView(
          index = index,
          firstId = ids.first(),
          items = items,
          title = grouping.title(ids).orEmpty(),
          defaultTitle = defaultGroupTitle(index, items, grouping.asNotes),
        )
      }
    }

  /**
   * Il titolo che un gruppo avrebbe se non gliene si da' uno. Per le note: il primo gruppo eredita
   * il titolo gia' scritto nel passo prima, gli altri prendono il nome del loro primo file. Per le
   * lezioni della stessa nota il titolo e' facoltativo, come lo e' per ogni sessione.
   */
  private fun defaultGroupTitle(index: Int, items: List<ImportCandidate>, asNotes: Boolean): String = when {
    !asNotes -> ""
    index == 0 && newNoteTitle.isNotBlank() -> newNoteTitle
    else -> items.firstOrNull()?.displayName?.substringBeforeLast('.').orEmpty()
  }
  /**
   * Una nota di Samsung Notes da sola: il caso di tutti i giorni, e quello con il percorso corto.
   * Non quando la nota di destinazione e' gia' decisa: il percorso corto sceglie cartella e titolo
   * e propone «Aggiorna» su un'altra nota, e chi ha premuto «Importa qui» dentro una nota ha gia'
   * detto dove va.
   */
  val samsungNote: ImportCandidate? get() = candidates.singleOrNull()?.takeIf { it.isSamsungNote && !targetIsFixed }
  val hasDocuments: Boolean get() = included.any { !it.isAudio }
  /** Quando la nota e' gia' decisa (import da dentro una nota) il passo destinazione non serve. */
  val targetIsFixed: Boolean get() = selectedNoteId != null && selectedFolderId == null
}

/** Un gruppo di registrazioni nel passo audio: quello che diventera' una nota o una lezione. */
data class AudioGroupView(
  val index: Int,
  val firstId: String,
  val items: List<ImportCandidate>,
  val title: String,
  val defaultTitle: String,
) {
  val effectiveTitle: String get() = title.ifBlank { defaultTitle }
}

data class CreatedNote(val id: String, val title: String, val recordings: Int)

@HiltViewModel
class ImportViewModel @Inject constructor(
  @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
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
    // Le sessioni erano quelle della nota scelta prima: con una nota nuova non c'e' dove accodare,
    // e un «accoda alla lezione di oggi» rimasto indietro metterebbe l'audio nella nota sbagliata.
    _uiState.update { it.copy(selectedFolderId = folderId, selectedNoteId = null, existingSessions = emptyList(), appendToSessionId = null) }
    viewModelScope.launch {
      val inFolder = notes.byFolder(folderId)
      _uiState.update { it.copy(notesInFolder = inFolder) }
    }
  }

  fun selectNote(noteId: String?) {
    _uiState.update { it.copy(selectedNoteId = noteId, existingSessions = emptyList(), appendToSessionId = null) }
    if (noteId != null) loadSessions(noteId)
  }

  fun setNewNoteTitle(title: String) = _uiState.update { it.copy(newNoteTitle = title) }

  fun setUpdateExisting(update: Boolean) = _uiState.update { it.copy(updateExisting = update) }

  fun setAppendToSession(sessionId: String?) = _uiState.update { it.copy(appendToSessionId = sessionId) }

  fun setSessionDate(date: String) = _uiState.update { it.copy(sessionDate = date) }

  fun toggleGroupStart(candidateId: String) = updateGrouping { it.toggle(candidateId) }
  fun splitAllGroups() = updateGrouping { it.splitAll() }
  fun joinAllGroups() = updateGrouping { it.joinAll() }
  fun setGroupTitle(firstId: String, title: String) = updateGrouping { it.withTitle(firstId, title) }
  fun setGroupsAsNotes(asNotes: Boolean) = updateGrouping { it.copy(asNotes = asNotes) }

  private fun updateGrouping(transform: (AudioGrouping) -> AudioGrouping) = _uiState.update { state ->
    state.copy(grouping = transform(state.grouping ?: AudioGrouping(state.audioInOrder.map { it.id })))
  }

  fun createFolder(name: String) {
    viewModelScope.launch {
      val folder = folders.create(name, untitled = context.getString(dev.pampa.pampanotes.R.string.import_folder))
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
            // La versione nuova di una nota che c'e' gia': non c'e' niente da chiedere.
            it.samsungNote?.canUpdate == true && it.updateExisting -> ImportStep.RUNNING
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
    if (_uiState.value.step == ImportStep.AUDIO) {
      // La divisione segue l'elenco: chi torna al primo passo e spegne un file non perde gli
      // stacchi messi davanti agli altri.
      _uiState.update { state ->
        val order = state.audioInOrder.map { it.id }
        state.copy(grouping = state.grouping?.withOrder(order) ?: AudioGrouping(order))
      }
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
        // Due tocchi veloci su due note: arriva per ultima la risposta della prima, e non vale piu'.
        if (state.selectedNoteId != noteId) return@update state
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
    // [ImportUiState.samsungNote] e' gia' null quando la nota e' fissata: lo si aggiorna solo
    // dal percorso corto, mai da «Importa qui».
    val updating = state.samsungNote?.takeIf { it.canUpdate && state.updateExisting }
    val target = when {
      updating != null -> ImportTarget.UpdateNote(updating.updateOfNoteId!!)
      state.selectedNoteId != null -> ImportTarget.ExistingNote(state.selectedNoteId)
      state.selectedFolderId != null -> ImportTarget.NewNote(state.selectedFolderId, state.newNoteTitle)
      else -> null
    }
    if (target == null) {
      _uiState.update { it.copy(step = ImportStep.REVIEW, errorRes = dev.pampa.pampanotes.R.string.import_error_no_target) }
      return
    }
    val placement = state.appendToSessionId
      ?.let { AudioPlacement.Append(it) }
      ?: AudioPlacement.NewSession(date = state.sessionDate)

    val groups = state.groupsView.takeIf { state.canGroup && it.size > 1 }

    viewModelScope.launch {
      runCatching {
        if (groups == null) {
          val outcome = coordinator.importAll(items, target, placement) { done, total, label -> publish(done, total, label) }
          listOf(outcome to CreatedNote(outcome.noteId, updating?.updateOfNoteTitle ?: state.newNoteTitle, state.audioInOrder.size))
        } else {
          runGrouped(groups, target, state)
        }
      }.onSuccess { results ->
        val outcomes = results.map { it.first }
        _uiState.update {
          it.copy(
            step = ImportStep.DONE,
            // Un esito solo, con dentro tutto: la schermata finale elenca i file, non le note.
            outcome = ImportOutcome(noteId = outcomes.first().noteId, imported = outcomes.flatMap { o -> o.imported }),
            createdNotes = results.map { r -> r.second }.distinctBy { n -> n.id },
            progress = 1f,
          )
        }
        outcomes.map { it.noteId }.distinct().forEach { transcribeIfAsked(it) }
      }.onFailure { error ->
        _uiState.update {
          if (error.message.isNullOrBlank()) it.copy(step = ImportStep.DONE, errorRes = dev.pampa.pampanotes.R.string.import_error_failed)
          else it.copy(step = ImportStep.DONE, error = error.message)
        }
      }
    }
  }

  /**
   * Un import per gruppo, in fila.
   *
   * Tre forme, decise da dove si e' scelto di andare. In una nota che c'e' gia', ogni gruppo e'
   * una lezione nuova di quella nota. In una nota nuova, o ogni gruppo e' una nota per conto suo
   * (il caso «cinque lezioni, cinque note»), oppure la prima crea la nota e le altre ci entrano
   * come lezioni. I documenti, se ce ne sono, vanno con il primo gruppo: sono l'eccezione, e
   * un'eccezione non merita una domanda in piu'.
   */
  private suspend fun runGrouped(
    groups: List<AudioGroupView>,
    target: ImportTarget,
    state: ImportUiState,
  ): List<Pair<ImportOutcome, CreatedNote>> {
    val documents = state.included.filterNot { it.isAudio }
    val total = state.included.size
    var done = 0
    val results = mutableListOf<Pair<ImportOutcome, CreatedNote>>()
    var sharedNoteId: String? = (target as? ImportTarget.ExistingNote)?.noteId
    val asNotes = target is ImportTarget.NewNote && state.grouping?.asNotes != false

    groups.forEach { group ->
      val items = if (group.index == 0) group.items + documents else group.items
      val groupTarget: ImportTarget
      val placement: AudioPlacement
      val noteTitle: String
      when {
        asNotes -> {
          groupTarget = ImportTarget.NewNote((target as ImportTarget.NewNote).folderId, group.effectiveTitle)
          placement = AudioPlacement.NewSession(date = state.sessionDate)
          noteTitle = group.effectiveTitle
        }
        sharedNoteId == null -> {
          groupTarget = ImportTarget.NewNote((target as ImportTarget.NewNote).folderId, state.newNoteTitle)
          placement = AudioPlacement.NewSession(date = state.sessionDate, title = group.effectiveTitle)
          noteTitle = state.newNoteTitle
        }
        else -> {
          groupTarget = ImportTarget.ExistingNote(sharedNoteId)
          placement = AudioPlacement.NewSession(date = state.sessionDate, title = group.effectiveTitle)
          noteTitle = state.newNoteTitle
        }
      }
      val offset = done
      val outcome = coordinator.importAll(items, groupTarget, placement) { groupDone, _, label ->
        publish(offset + groupDone, total, label)
      }
      done += items.size
      if (!asNotes) sharedNoteId = outcome.noteId
      results += outcome to CreatedNote(outcome.noteId, noteTitle, group.items.size)
    }
    return results
  }

  private fun publish(done: Int, total: Int, label: String) {
    _uiState.update { it.copy(progress = if (total == 0) 0f else done.toFloat() / total, progressLabel = label) }
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
    // Quello che e' appena entrato e' anche quello che vale di piu' avere fuori dal dispositivo.
    if (settings.archiveEnabled) scheduler.archiveNow(settings.archiveOnlyUnmetered)
    if (settings.syncEnabled) scheduler.syncNow()
    if (!settings.autoTranscribeOnImport) return
    val pending = sessions.byNote(noteId).filter { it.parts.isNotEmpty() && it.session.activeTranscriptId == null }
    if (pending.isEmpty()) return
    pending.forEach { transcription.enqueue(it.session.id, settings.transcriptionProvider) }
    scheduler.kick(settings.transcriptionProvider.id)
  }

  /**
   * Il nome di un testo incollato o condiviso.
   *
   * La prima frase, non la prima riga: un testo condiviso spesso e' un paragrafo intero su una riga
   * sola, e prenderla tutta produceva un titolo lungo quanto la nota, tagliato a meta' parola nella
   * barra e inutile in un elenco. Se la prima frase e' comunque lunga si taglia a una parola intera.
   */
  private fun defaultTextName(text: String): String {
    val pasted = context.getString(dev.pampa.pampanotes.R.string.import_pasted_text)
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("#")?.trim()
      ?: return pasted
    val firstSentence = firstLine.split(SentenceEnd).firstOrNull()?.trim().orEmpty().ifEmpty { firstLine }
    val candidate = firstSentence.trimEnd('.', '!', '?', ';', ':')
    if (candidate.length <= MAX_TITLE_CHARS) return candidate.ifEmpty { pasted }
    return candidate.take(MAX_TITLE_CHARS).substringBeforeLast(' ').trimEnd(',', ';', '-').ifEmpty { candidate.take(MAX_TITLE_CHARS) } + "…"
  }

  private companion object {
    /** Quanto sta nel titolo di una riga di elenco su un telefono, su due righe. */
    const val MAX_TITLE_CHARS = 48

    /** La fine di una frase: un segno di punteggiatura seguito da spazio. */
    val SentenceEnd = Regex("(?<=[.!?;:])\\s+")
  }
}
