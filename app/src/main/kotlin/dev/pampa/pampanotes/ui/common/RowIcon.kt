package dev.pampa.pampanotes.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.theme.FluidTone

/**
 * La piastrella colorata di una riga: il tono sta qui, mai sullo sfondo della riga.
 *
 * Una riga intera colorata si legge come uno stato — selezionata, in errore — e in un elenco di
 * voci che non hanno nessuno stato e' un allarme che non vuol dire niente.
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
  Box(
    modifier = Modifier
      .size(30.dp)
      .clip(ContinuousCornerShape(FluidRadius.Small))
      .background(color.copy(alpha = 0.16f)),
    contentAlignment = Alignment.Center,
  ) {
    Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
  }
}
