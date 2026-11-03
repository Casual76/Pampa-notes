package dev.pampa.pampanotes.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidSearchField
import dev.antigravity.fluidengine.ui.fluid.FluidSearchNavigation
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.fluidReadingWidth
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.transcription.TranscriptSearch
import dev.pampa.pampanotes.ui.common.rememberSearchLabels

/**
 * Cosa si sta cercando dentro la sessione: aperta o no, la parola, e quale occorrenza e' la corrente.
 *
 * Sopravvive alla rotazione (le tre cose piccole); le occorrenze no, si ricalcolano dal testo. Il
 * numero dell'occorrenza si tiene anche quando le occorrenze arrivano di nuovo dopo la rotazione, e
 * si azzera solo quando cambia la parola ([onResults]).
 */
@Stable
class TranscriptSearchState(open: Boolean = false, query: String = "", current: Int = 0, focusOnOpen: Boolean = true) {
  var open by mutableStateOf(open)
    private set

  /**
   * La barra prende il fuoco (e la tastiera sale) quando compare. Non quando la ricerca arriva gia'
   * scritta dalla ricerca di tutte le note: li' si e' venuti a leggere e ad ascoltare, e la tastiera
   * coprirebbe proprio il paragrafo trovato.
   */
  var focusOnOpen by mutableStateOf(focusOnOpen)
    private set
  var query by mutableStateOf(query)
  var current by mutableIntStateOf(current)
  var matches by mutableStateOf<List<TranscriptSearch.Match>>(emptyList())
    private set

  /** La parola a cui si riferiscono [matches]: null finche' non si e' cercato niente in questa vita. */
  private var matchedQuery: String? = null

  val currentMatch: TranscriptSearch.Match? get() = matches.getOrNull(current)

  /** Le occorrenze per blocco, per non filtrare tutta la lista a ogni paragrafo disegnato. */
  var byBlock by mutableStateOf<Map<Int, List<IntRange>>>(emptyMap())
    private set

  fun show() {
    open = true
    focusOnOpen = true
  }

  /** Aperta gia' scritta, senza tastiera: la ricerca che salta al minuto (vedi `SessionScreen`). */
  fun showWith(query: String) {
    open = true
    focusOnOpen = false
    this.query = query
  }

  /** Chiudere cancella: una ricerca riaperta domani non deve ritrovare le evidenziazioni di oggi. */
  fun close() {
    open = false
    query = ""
    current = 0
    matches = emptyList()
    byBlock = emptyMap()
    matchedQuery = null
  }

  /**
   * I blocchi in cui si cerca sono cambiati: le occorrenze di prima indicano posti che non ci sono
   * piu'. La parola e l'occorrenza corrente restano, e la ricerca riparte da sola sul testo nuovo.
   */
  fun clearMatches() {
    matches = emptyList()
    byBlock = emptyMap()
  }

  /** Arrivano le occorrenze di [forQuery]: se la parola e' nuova si riparte dalla prima. */
  fun onResults(forQuery: String, found: List<TranscriptSearch.Match>) {
    val sameQuery = matchedQuery == null || matchedQuery == forQuery
    matchedQuery = forQuery
    matches = found
    byBlock = found.groupBy({ it.block }, { it.start until it.end })
    current = if (sameQuery) current.coerceIn(0, (found.size - 1).coerceAtLeast(0)) else 0
  }

  companion object {
    val Saver: Saver<TranscriptSearchState, Any> = Saver(
      save = { listOf(it.open, it.query, it.current, it.focusOnOpen) },
      restore = { saved ->
        val list = saved as List<*>
        TranscriptSearchState(list[0] as Boolean, list[1] as String, list[2] as Int, list.getOrNull(3) as? Boolean ?: true)
      },
    )
  }
}

@Composable
fun rememberTranscriptSearchState(): TranscriptSearchState =
  rememberSaveable(saver = TranscriptSearchState.Saver) { TranscriptSearchState() }

/**
 * La barra della ricerca, sospesa sopra il lettore: `FluidSearchField` dell'engine, nel caso «ricerca
 * dentro una pagina» per cui e' fatto — il campo, il contatore «3/12», le frecce e la crocetta in una
 * lastra di vetro sola, con il fuoco e la tastiera all'apertura.
 *
 * Sta nell'overlay e non in cima alla lista: in una registrazione di ore l'occorrenza dopo e' dieci
 * schermi piu' giu', e i tasti «precedente» e «successiva» devono restare sotto il pollice mentre la
 * lista scorre. La crocetta chiude la ricerca (e la cancella: [TranscriptSearchState.close]).
 * «Invio» sulla tastiera va all'occorrenza dopo, come nelle ricerche di un browser.
 */
@Composable
fun TranscriptSearchBar(
  state: TranscriptSearchState,
  backdrop: GlassBackdropState,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  FluidSearchField(
    query = state.query,
    onQueryChange = { state.query = it },
    modifier = modifier.fluidReadingWidth(),
    backdrop = backdrop,
    placeholder = stringResource(R.string.session_search_placeholder),
    labels = rememberSearchLabels(),
    onSearch = onNext,
    expanded = true,
    onExpandedChange = { open -> if (!open) onClose() },
    navigation = FluidSearchNavigation(
      current = state.current,
      total = state.matches.size,
      onPrevious = onPrevious,
      onNext = onNext,
    ),
    // Non quando la ricerca arriva gia' scritta dalla ricerca di tutte le note: li' si e' venuti a
    // leggere e ad ascoltare, e la tastiera coprirebbe proprio il paragrafo trovato.
    autoFocus = state.focusOnOpen,
  )
}

/** Lo spazio che la barra della ricerca occupa sopra il lettore: la lista se lo lascia sotto. */
val SearchBarHeight = 72.dp

/**
 * Le occorrenze dipinte **dietro** il testo: un velo nel colore della materia, piu' pieno su quella
 * corrente. Dietro e non sopra perche' il testo resta quello che si legge, e perche' cosi' vale anche
 * per il paragrafo che si sta ascoltando, le cui parole si accendono sopra (`FluidSpokenText` non
 * sa niente della ricerca, e non deve).
 *
 * Il layout arriva come lambda, dall'`onTextLayout` del testo: il modificatore sta sullo stesso nodo
 * del testo, alle stesse coordinate, e ridisegna solo quando cambiano le occorrenze.
 */
fun Modifier.searchHighlights(
  layout: () -> TextLayoutResult?,
  ranges: List<IntRange>,
  current: IntRange?,
  color: Color,
  currentColor: Color,
): Modifier {
  if (ranges.isEmpty()) return this
  return drawBehind {
    val result = layout() ?: return@drawBehind
    val length = result.layoutInput.text.length
    ranges.forEach { range ->
      val start = range.first.coerceIn(0, length)
      val end = (range.last + 1).coerceIn(start, length)
      if (end > start) {
        drawPath(result.getPathForRange(start, end), color = if (range == current) currentColor else color)
      }
    }
  }
}
