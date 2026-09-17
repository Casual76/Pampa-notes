package dev.pampa.pampanotes.ui.jobs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.pampa.pampanotes.R

/** La coda dei lavori arriva con M2, insieme alla prima trascrizione. */
@Composable
fun JobsRoute(onBack: () -> Unit) {
  FluidScreen(
    title = stringResource(R.string.jobs_title),
    subtitle = stringResource(R.string.jobs_subtitle),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.Tertiary, motif = FluidHeroMotif.Ticks),
  ) {
    item {
      FluidEmptyState(
        title = stringResource(R.string.jobs_empty_title),
        detail = stringResource(R.string.jobs_empty_detail),
      )
    }
  }
}
