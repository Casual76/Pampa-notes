package dev.pampa.pampanotes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.pampa.pampanotes.R

/**
 * La conferma di una cosa che non torna indietro: un link revocato e' morto per chi ce l'ha, e il suo
 * audio se ne va dal cloud; un ospite revocato smette di trascrivere. Un tocco in un menu non basta.
 */
@Composable
fun ConfirmDestructive(
  title: String,
  message: String,
  confirmLabel: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  FluidAlert(
    onDismissRequest = onDismiss,
    title = title,
    message = message,
    actions = listOf(
      FluidAlertAction(
        label = confirmLabel,
        emphasis = FluidAlertAction.Emphasis.Destructive,
        onClick = {
          onDismiss()
          onConfirm()
        },
      ),
      FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = onDismiss),
    ),
  )
}
