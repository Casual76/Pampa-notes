package dev.pampa.pampanotes.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.repo.NoteRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class EditorUiState(
  val title: String = "",
  val body: String = "",
  val saving: Boolean = false,
  val loaded: Boolean = false,
  /** Mentre si scriveva e' arrivata un'altra versione: e' finita in una nota «(conflitto)». */
  val conflictTitle: String? = null,
)

/**
 * L'editor scrive da solo, poco dopo che le dita si fermano.
 *
 * Un tasto "salva" che si puo' dimenticare, in un'app che raccoglie appunti, e' il modo piu' rapido
 * di perdere mezz'ora di lavoro. Il salvataggio automatico c'e' comunque, e il tasto in barra
 * resta perche' chiude anche la schermata.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
  savedStateHandle: SavedStateHandle,
  private val notes: NoteRepository,
) : ViewModel() {

  private val noteId: String = savedStateHandle.get<String>("noteId").orEmpty()
  private val _uiState = MutableStateFlow(EditorUiState())
  val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

  private var pendingSave: Job? = null

  /** Il corpo su cui si sta scrivendo: quello caricato, poi l'ultimo salvato. */
  private var baseBody: String = ""

  /** Qualcosa di battuto che non e' ancora nel database. */
  @Volatile private var dirty = false

  /** Il salvataggio col timer e quello dell'uscita non si pestano: uno alla volta. */
  private val writing = Mutex()

  init {
    viewModelScope.launch {
      val note = notes.get(noteId)
      baseBody = note?.body.orEmpty()
      _uiState.value = EditorUiState(
        title = note?.title.orEmpty(),
        body = note?.body.orEmpty(),
        loaded = true,
      )
    }
  }

  fun setTitle(value: String) {
    _uiState.update { it.copy(title = value) }
    dirty = true
    scheduleSave()
  }

  fun setBody(value: String) {
    _uiState.update { it.copy(body = value) }
    dirty = true
    scheduleSave()
  }

  /**
   * Salva adesso: all'uscita dalla schermata, dove aspettare il timer sarebbe una scommessa.
   *
   * In [NonCancellable]: il tasto indietro toglie la schermata, e poco dopo la navigazione smonta il
   * ViewModel e annulla il suo scope. Una scrittura avviata e annullata a meta' e' l'ultima frase
   * persa, che e' proprio quella che si e' appena scritta.
   */
  fun saveNow() {
    pendingSave?.cancel()
    pendingSave = null
    viewModelScope.launch { withContext(NonCancellable) { persist() } }
  }

  /**
   * Il ViewModel se ne va senza che nessuno abbia premuto indietro — sul tablet, scegliere un'altra
   * materia dalla barra laterale chiude il dettaglio — con dentro un salvataggio col timer ancora da
   * fare. Il suo scope e' gia' annullato: si salva in uno che resta.
   */
  override fun onCleared() {
    if (dirty && _uiState.value.loaded) flushScope.launch { persist() }
    super.onCleared()
  }

  private fun scheduleSave() {
    pendingSave?.cancel()
    pendingSave = viewModelScope.launch {
      _uiState.update { it.copy(saving = true) }
      delay(SAVE_DELAY_MS)
      withContext(NonCancellable) { persist() }
    }
  }

  private suspend fun persist() = writing.withLock {
    if (!_uiState.value.loaded || !dirty) {
      _uiState.update { it.copy(saving = false) }
      return@withLock
    }
    val state = _uiState.value
    dirty = false
    val saved = runCatching { notes.saveEdit(noteId, state.title, state.body, baseBody) }
    // Quello che e' nel database adesso e' quello che si e' appena scritto: la base del prossimo
    // confronto. Se la scrittura non e' passata si riprova al prossimo giro, con la base di prima.
    if (saved.isSuccess) baseBody = state.body else dirty = true
    val conflict = saved.getOrNull()
    _uiState.update { it.copy(saving = false, conflictTitle = conflict?.title ?: it.conflictTitle) }
  }

  private companion object {
    /** Abbastanza da non scrivere a ogni lettera, abbastanza poco da non perdere un pensiero. */
    const val SAVE_DELAY_MS = 700L

    /** Per l'ultimo salvataggio di un editor gia' smontato: vive quanto il processo. */
    val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  }
}
