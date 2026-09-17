package dev.pampa.pampanotes.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.repo.NoteRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
  val title: String = "",
  val body: String = "",
  val saving: Boolean = false,
  val loaded: Boolean = false,
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

  init {
    viewModelScope.launch {
      val note = notes.get(noteId)
      _uiState.value = EditorUiState(
        title = note?.title.orEmpty(),
        body = note?.body.orEmpty(),
        loaded = true,
      )
    }
  }

  fun setTitle(value: String) {
    _uiState.update { it.copy(title = value) }
    scheduleSave()
  }

  fun setBody(value: String) {
    _uiState.update { it.copy(body = value) }
    scheduleSave()
  }

  /** Salva adesso: all'uscita dalla schermata, dove aspettare il timer sarebbe una scommessa. */
  fun saveNow() {
    pendingSave?.cancel()
    pendingSave = null
    // Fuori dallo scope del ViewModel no: qui la schermata si chiude ma il ViewModel resta vivo
    // finche' la navigazione non l'ha smontato, e questo dura molto meno di una scrittura su disco.
    viewModelScope.launch { persist() }
  }

  private fun scheduleSave() {
    pendingSave?.cancel()
    pendingSave = viewModelScope.launch {
      _uiState.update { it.copy(saving = true) }
      delay(SAVE_DELAY_MS)
      persist()
    }
  }

  private suspend fun persist() {
    if (!_uiState.value.loaded) return
    val state = _uiState.value
    notes.setTitle(noteId, state.title)
    notes.setBody(noteId, state.body)
    _uiState.update { it.copy(saving = false) }
  }

  private companion object {
    /** Abbastanza da non scrivere a ogni lettera, abbastanza poco da non perdere un pensiero. */
    const val SAVE_DELAY_MS = 700L
  }
}
