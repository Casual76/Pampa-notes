package dev.pampa.pampanotes.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Il segno di una riga in selezione multipla: un cerchio vuoto, o pieno e del colore dell'accento.
 *
 * Sta nello spazio `leading` di `FluidListRow`: e' l'unica cosa che cambia fra una riga normale e
 * una in selezione, cosi' l'elenco non salta quando si entra e si esce.
 */
@Composable
fun SelectionMark(selected: Boolean) {
  val scheme = MaterialTheme.colorScheme
  Icon(
    imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
    contentDescription = null,
    tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
    modifier = Modifier.size(24.dp),
  )
}
