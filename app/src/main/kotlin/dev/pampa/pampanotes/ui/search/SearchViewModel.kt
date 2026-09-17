package dev.pampa.pampanotes.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.repo.SearchRepository
import dev.pampa.pampanotes.core.repo.SearchResult
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
  val query: String = "",
  val results: List<SearchResult> = emptyList(),
  val searching: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
  private val search: SearchRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(SearchUiState())
  val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      _uiState
        .map { it.query }
        .distinctUntilChanged()
        // Abbastanza da non interrogare il database a ogni lettera, abbastanza poco da non
        // sembrare in ritardo su chi scrive.
        .debounce(180)
        .collect { query ->
          if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), searching = false) }
            return@collect
          }
          _uiState.update { it.copy(searching = true) }
          val results = search.search(query)
          // Solo se nel frattempo la domanda non e' cambiata: una risposta vecchia che arriva
          // dopo una nuova e' un elenco che non corrisponde a quello che c'e' scritto.
          _uiState.update { if (it.query == query) it.copy(results = results, searching = false) else it }
        }
    }
  }

  fun setQuery(value: String) = _uiState.update { it.copy(query = value) }
}
