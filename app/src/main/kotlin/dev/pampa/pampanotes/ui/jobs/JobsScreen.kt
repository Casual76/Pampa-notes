package dev.pampa.pampanotes.ui.jobs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.pampa.pampanotes.R

/** La coda dei lavori arriva con M2, insieme alla prima trascrizione. */
@Composable
fun JobsRoute() {
  FluidScreen(
    title = stringResource(R.string.jobs_title),
    subtitle = stringResource(R.string.jobs_subtitle),
  ) {
    item {
      FluidEmptyState(
        title = stringResource(R.string.jobs_empty_title),
        detail = stringResource(R.string.jobs_empty_detail),
      )
    }
  }
}
