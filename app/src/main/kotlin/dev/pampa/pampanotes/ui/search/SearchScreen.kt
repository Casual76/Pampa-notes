package dev.pampa.pampanotes.ui.search

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSpinner
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.model.Dates
import dev.pampa.pampanotes.core.repo.SearchResult
import dev.pampa.pampanotes.core.repo.TranscriptSnippet
import dev.pampa.pampanotes.ui.common.Formats

@Composable
fun SearchRoute(
  onBack: () -> Unit,
  onOpenNote: (String) -> Unit,
  /** Una registrazione nel momento in cui si dice quello che si e' cercato (vedi `Routes.SESSION`). */
  onOpenMoment: (sessionId: String, atMs: Long?, query: String) -> Unit,
  viewModel: SearchViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val moments by viewModel.moments.collectAsStateWithLifecycle()

  FluidScreen(
    title = stringResource(R.string.search_title),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Secondary, motif = FluidHeroMotif.Ripples),
  ) {
    item {
      FluidTextField(
        value = state.query,
        onValueChange = viewModel::setQuery,
        placeholder = stringResource(R.string.search_placeholder),
        leading = { Icon(Icons.Rounded.Search, contentDescription = null) },
        modifier = Modifier.fillMaxWidth(),
      )
    }

    when {
      state.query.isBlank() -> item {
        FluidEmptyState(
          title = stringResource(R.string.search_empty_title),
          detail = stringResource(R.string.search_empty_detail),
        )
      }

      state.searching && state.results.isEmpty() -> item { FluidSpinner() }

      state.results.isEmpty() -> item {
        FluidEmptyState(
          title = stringResource(R.string.search_no_results, state.query),
          detail = stringResource(R.string.search_no_results_detail),
        )
      }

      // Un gruppo per nota, e un elemento della lista per gruppo: e' la lista a decidere quali
      // gruppi comporre, e quindi di quali registrazioni cercare il minuto (vedi `lookUp`).
      else -> items(state.results, key = { it.note.id }) { result ->
        FluidListGroup {
          FluidListRow(
            title = result.note.title,
            subtitle = noteSubtitle(result),
            eyebrow = result.folderPath.takeIf { it.isNotBlank() },
            onClick = { onOpenNote(result.note.id) },
          )
          result.transcriptHits.forEach { hit ->
            FluidListDivider()
            val lookup = moments[viewModel.momentKey(hit.sessionId, state.resultsFor)]
            // La riga chiede il suo momento quando compare: solo le registrazioni che si vedono.
            LaunchedEffect(hit.sessionId, state.resultsFor) { viewModel.lookUp(hit.sessionId) }
            TranscriptHitRow(
              hit = hit,
              atMs = lookup?.moment?.timeMs,
              onClick = { viewModel.openMoment(hit.sessionId, onOpenMoment) },
            )
          }
        }
      }
    }
  }
}

/**
 * Una registrazione in cui si dice quello che si e' cercato: quale (titolo e giorno della sessione),
 * il frammento, e «Al minuto 1:04:12». Il tocco apre la registrazione in quel momento, col lettore
 * pronto li' e la parola evidenziata. Finche' il minuto non e' arrivato — o se la grezza non contiene
 * la parola, perche' il risultato veniva da una versione ripulita — la riga dice solo «Nella
 * registrazione», della stessa altezza, e il tocco apre la sessione con la ricerca gia' scritta.
 */
@Composable
private fun TranscriptHitRow(hit: TranscriptSnippet, atMs: Long?, onClick: () -> Unit) {
  FluidListRow(
    title = sessionHeading(hit.sessionTitle, hit.sessionDate),
    subtitle = cleanSnippet(hit.snippet) ?: stringResource(R.string.search_moment_somewhere),
    meta = if (atMs != null) {
      stringResource(R.string.session_at, Formats.duration(atMs))
    } else {
      stringResource(R.string.search_moment_somewhere)
    },
    leading = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
    onClick = onClick,
  )
}

/** La riga della nota: il frammento degli appunti, o dove altro e' stata trovata. */
@Composable
private fun noteSubtitle(result: SearchResult): String =
  cleanSnippet(result.noteSnippet)
    ?: if (result.transcriptHits.isNotEmpty()) {
      pluralStringResource(R.plurals.search_in_transcripts, result.transcriptHits.size, result.transcriptHits.size)
    } else {
      stringResource(R.string.search_match_in_title)
    }

@Composable
private fun sessionHeading(title: String, date: String): String {
  val pretty = Dates.parseOrNull(date)?.let { Formats.relativeDate(it) } ?: date
  return when {
    title.isNotBlank() && pretty.isNotBlank() -> "$title · $pretty"
    title.isNotBlank() -> title
    pretty.isNotBlank() -> pretty
    else -> stringResource(R.string.search_moment_session)
  }
}

/**
 * Il frammento trovato, ripulito.
 *
 * FTS4 restituisce la parola fra parentesi quadre: qui si tolgono, perche' una riga di lista non ha
 * modo di mostrare il grassetto e "[Kant]" scritto cosi' sembra un errore.
 */
private fun cleanSnippet(raw: String?): String? =
  raw?.replace("[", "")?.replace("]", "")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }
