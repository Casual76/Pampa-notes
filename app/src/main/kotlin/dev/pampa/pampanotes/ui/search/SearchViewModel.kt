package dev.pampa.pampanotes.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.repo.SearchRepository
import dev.pampa.pampanotes.core.repo.SearchResult
import dev.pampa.pampanotes.core.transcription.TranscriptSearch
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
  /** La ricerca a cui rispondono [results]: e' quella da cercare dentro le registrazioni. */
  val resultsFor: String = "",
  val searching: Boolean = false,
)

/** Il momento di un risultato dentro una registrazione, quando lo si e' cercato. */
data class MomentLookup(
  /** Null se la grezza non lo contiene: il tocco apre la sessione con la ricerca scritta, e basta. */
  val moment: TranscriptSearch.Moment?,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
  private val search: SearchRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(SearchUiState())
  val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

  /**
   * «La ricerca che salta al minuto»: per ogni risultato in una trascrizione, il momento in cui si
   * dice quello che si e' cercato. Chiave: ricerca e sessione ([momentKey]); assente = non ancora
   * cercato. Si riempie solo per le righe che la lista compone, cioe' quelle a schermo o quasi
   * ([lookUp]): leggere i segmenti di ogni registrazione di ogni risultato, a ogni lettera, sarebbe
   * il costo della ricerca.
   */
  private val _moments = MutableStateFlow<Map<String, MomentLookup>>(emptyMap())
  val moments: StateFlow<Map<String, MomentLookup>> = _moments.asStateFlow()

  /** Le ricerche del momento gia' partite, per non rifarle a ogni ricomposizione della riga. */
  private val lookups = HashMap<String, Deferred<TranscriptSearch.Moment?>>()

  /** Due alla volta: una lista che scorre veloce non deve mettere in fila venti letture del database. */
  @OptIn(ExperimentalCoroutinesApi::class)
  private val lookupDispatcher = Dispatchers.IO.limitedParallelism(2)

  private var opening: Job? = null

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
            forgetMoments()
            _uiState.update { it.copy(results = emptyList(), resultsFor = "", searching = false) }
            return@collect
          }
          _uiState.update { it.copy(searching = true) }
          val results = search.search(query)
          // Solo se nel frattempo la domanda non e' cambiata: una risposta vecchia che arriva
          // dopo una nuova e' un elenco che non corrisponde a quello che c'e' scritto.
          if (_uiState.value.query == query) forgetMoments()
          _uiState.update { if (it.query == query) it.copy(results = results, resultsFor = query, searching = false) else it }
        }
    }
  }

  fun setQuery(value: String) = _uiState.update { it.copy(query = value) }

  fun momentKey(sessionId: String, query: String): String = "$query\u0000$sessionId"

  /** Cerca (una volta) il momento di [sessionId] per la ricerca dei risultati a schermo. */
  fun lookUp(sessionId: String): Deferred<TranscriptSearch.Moment?> {
    val query = _uiState.value.resultsFor
    val key = momentKey(sessionId, query)
    return lookups.getOrPut(key) {
      viewModelScope.async(lookupDispatcher) {
        val moment = try {
          search.momentOf(sessionId, query)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          // Un errore del database non toglie la riga: il tocco apre la sessione con la ricerca scritta.
          null
        }
        _moments.update { it + (key to MomentLookup(moment)) }
        moment
      }
    }
  }

  /**
   * Il tocco su un risultato in una trascrizione: se il momento non e' ancora arrivato lo si aspetta
   * (di solito e' gia' qui: la riga lo ha chiesto comparendo), poi si apre. Un secondo tocco mentre
   * si aspetta non apre due volte la stessa sessione.
   */
  fun openMoment(sessionId: String, open: (sessionId: String, atMs: Long?, query: String) -> Unit) {
    if (opening?.isActive == true) return
    val query = _uiState.value.resultsFor
    opening = viewModelScope.launch {
      val moment = lookUp(sessionId).await()
      open(sessionId, moment?.timeMs, moment?.query ?: query)
    }
  }

  private fun forgetMoments() {
    lookups.values.forEach { it.cancel() }
    lookups.clear()
    _moments.value = emptyMap()
  }
}
