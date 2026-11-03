package dev.pampa.pampanotes.ui.common

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.antigravity.fluidengine.ui.theme.FluidToneTile

/**
 * La piastrella colorata di una riga: il tono sta qui, mai sullo sfondo della riga.
 *
 * Una riga intera colorata si legge come uno stato — selezionata, in errore — e in un elenco di
 * voci che non hanno nessuno stato e' un allarme che non vuol dire niente.
 *
 * La piastrella e' `FluidToneTile` dell'engine; i **colori** sono quelli dell'app — l'accento per il
 * tono primario, il secondario e il terziario del tema per gli altri — e non quelli dei toni
 * dell'engine (il primario li' e' un grigio-lilla, il «successo» un verde): l'app e' riconoscibile
 * anche da questo, e le righe che la usano passano `leadingTile = false` perche' la piastrella e' gia' questa.
 */
@Composable
fun RowIcon(icon: ImageVector, tone: FluidTone) {
  val scheme = MaterialTheme.colorScheme
  val color = when (tone) {
    FluidTone.Primary -> scheme.primary
    FluidTone.Info -> scheme.secondary
    FluidTone.Success -> scheme.tertiary
    FluidTone.Warning -> scheme.secondary
    FluidTone.Danger -> scheme.error
    FluidTone.Neutral -> scheme.onSurfaceVariant
  }
  FluidToneTile(color = color) {
    Icon(imageVector = icon, contentDescription = null)
  }
}
