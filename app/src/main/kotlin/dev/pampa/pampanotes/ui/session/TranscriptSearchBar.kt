package dev.pampa.pampanotes.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.fluidReadingWidth
import dev.antigravity.fluidengine.ui.fluid.glassControlSurface
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.transcription.TranscriptSearch

/**
 * Cosa si sta cercando dentro la sessione: aperta o no, la parola, e quale occorrenza e' la corrente.
 *
 * Sopravvive alla rotazione (le tre cose piccole); le occorrenze no, si ricalcolano dal testo. Il
 * numero dell'occorrenza si tiene anche quando le occorrenze arrivano di nuovo dopo la rotazione, e
 * si azzera solo quando cambia la parola ([onResults]).
 */
@Stable
class TranscriptSearchState(open: Boolean = false, query: String = "", current: Int = 0) {
  var open by mutableStateOf(open)
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
      save = { listOf(it.open, it.query, it.current) },
      restore = { saved ->
        val list = saved as List<*>
        TranscriptSearchState(list[0] as Boolean, list[1] as String, list[2] as Int)
      },
    )
  }
}

@Composable
fun rememberTranscriptSearchState(): TranscriptSearchState =
  rememberSaveable(saver = TranscriptSearchState.Saver) { TranscriptSearchState() }

/**
 * La barra della ricerca, sospesa sopra il lettore.
 *
 * Sta nell'overlay e non in cima alla lista: in una registrazione di ore l'occorrenza dopo e' dieci
 * schermi piu' giu', e i tasti «precedente» e «successiva» devono restare sotto il pollice mentre la
 * lista scorre. E' vetro come la capsula del lettore — un elemento piccolo che galleggia — e il campo
 * dentro e' quello dell'engine, con la sua crocetta per svuotarlo; la X a destra invece chiude.
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
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  val total = state.matches.size
  val counter = when {
    state.query.isBlank() -> null
    total == 0 -> stringResource(R.string.session_search_none)
    else -> stringResource(R.string.session_search_count, state.current + 1, total)
  }

  Row(
    modifier = modifier
      .fluidReadingWidth()
      .clip(FluidCapsuleShape)
      .glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape, interactive = false)
      .padding(horizontal = 6.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FluidTextField(
      value = state.query,
      onValueChange = { state.query = it },
      placeholder = stringResource(R.string.session_search_placeholder),
      leading = { Icon(Icons.Rounded.Search, contentDescription = null) },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { onNext() }),
      modifier = Modifier
        .weight(1f)
        .focusRequester(focus),
    )
    counter?.let {
      Text(
        text = it,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        // Letto da TalkBack quando cambia: chi non vede lo scorrere della lista sente «3 di 12».
        modifier = Modifier
          .padding(horizontal = 4.dp)
          .semantics { liveRegion = LiveRegionMode.Polite },
      )
    }
    SearchButton(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.session_search_previous), enabled = total > 0, onClick = onPrevious)
    SearchButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.session_search_next), enabled = total > 0, onClick = onNext)
    SearchButton(Icons.Rounded.Close, stringResource(R.string.session_search_close), enabled = true, onClick = onClose)
  }
}

/** Lo spazio che la barra della ricerca occupa sopra il lettore: la lista se lo lascia sotto. */
val SearchBarHeight = 72.dp

/**
 * Un comando piatto nella capsula, come quelli del lettore; spento, resta al suo posto ma velato.
 * Occupa 40 dp ma il dito ne prende 48 ([touchTarget]): la capsula resta com'era.
 */
@Composable
private fun SearchButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
  val tint = MaterialTheme.colorScheme.onSurface
  val visual = 40.dp
  Box(
    modifier = Modifier
      .touchTarget(visual)
      .fluidPressable(onClick = onClick, enabled = enabled, pressedScale = 0.88f, role = Role.Button)
      .semantics { contentDescription = label }
      .padding((MinTouchTarget - visual) / 2)
      .clip(FluidCapsuleShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(imageVector = icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
  }
}

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
