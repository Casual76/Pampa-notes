package dev.pampa.pampanotes.ui.search

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
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
import dev.pampa.pampanotes.core.repo.SearchResult

@Composable
fun SearchRoute(
  onBack: () -> Unit,
  onOpenNote: (String) -> Unit,
  viewModel: SearchViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()

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

      else -> item {
        FluidListGroup(glass = true) {
          state.results.forEachIndexed { index, result ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = result.note.title,
              subtitle = resultSubtitle(result),
              eyebrow = result.folderPath.takeIf { it.isNotBlank() },
              meta = if (result.transcriptHits.isNotEmpty()) {
                pluralStringResource(R.plurals.search_in_transcripts, result.transcriptHits.size, result.transcriptHits.size)
              } else {
                null
              },
              onClick = { onOpenNote(result.note.id) },
            )
          }
        }
      }
    }
  }
}

/**
 * Il frammento trovato, ripulito.
 *
 * FTS4 restituisce la parola fra parentesi quadre: qui si tolgono, perche' una riga di lista non ha
 * modo di mostrare il grassetto e "[Kant]" scritto cosi' sembra un errore.
 */
@Composable
private fun resultSubtitle(result: SearchResult): String {
  val raw = result.noteSnippet ?: result.transcriptHits.firstOrNull()?.snippet
  val cleaned = raw?.replace("[", "")?.replace("]", "")?.replace(Regex("\\s+"), " ")?.trim()
  return cleaned?.takeIf { it.isNotBlank() } ?: stringResource(R.string.search_match_in_title)
}
