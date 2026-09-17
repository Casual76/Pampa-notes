package dev.pampa.pampanotes.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.pampa.pampanotes.R

/** La ricerca vera arriva con M1, quando ci sono note da cercare. */
@Composable
fun SearchRoute() {
  FluidScreen(title = stringResource(R.string.search_title)) {
    item {
      FluidEmptyState(
        title = stringResource(R.string.search_empty_title),
        detail = stringResource(R.string.search_empty_detail),
      )
    }
  }
}
