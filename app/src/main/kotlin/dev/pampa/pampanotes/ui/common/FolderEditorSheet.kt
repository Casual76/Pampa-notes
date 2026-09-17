package dev.pampa.pampanotes.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidColorDot
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.pampa.pampanotes.R

/**
 * Crea o rinomina una cartella, o battezza una nota nuova.
 *
 * Un pannello di vetro dentro la composizione dell'app invece di un foglio di piattaforma: cosi'
 * rifrange la pagina che ha sotto invece di dipingerne una copia, ed e' quello che l'engine chiede
 * dalla 1.9 in poi.
 */
@Composable
fun FolderEditorSheet(
  title: String,
  initialName: String,
  initialTone: String?,
  showToneChooser: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (name: String, tone: String?) -> Unit,
) {
  // Chiavi sul contenuto della richiesta: un secondo pannello aperto su un'altra cartella deve
  // ripartire dal suo nome, non da quello di prima.
  var name by remember(initialName) { mutableStateOf(initialName) }
  var tone by remember(initialTone) { mutableStateOf(initialTone) }

  FluidGlassModalPortal(
    visible = true,
    onDismissRequest = onDismiss,
    presentation = FluidGlassModalPresentation.Sheet,
    paneTitle = title,
  ) {
    FluidSectionHeader(title = title)

    FluidTextField(
      value = name,
      onValueChange = { name = it },
      label = stringResource(R.string.folder_name_label),
      placeholder = stringResource(R.string.folder_name_placeholder),
      modifier = Modifier.fillMaxWidth(),
    )

    if (showToneChooser) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        selectableTones.forEach { candidate ->
          FluidColorDot(
            color = candidate.dotColor(),
            selected = tone.equals(candidate.name, ignoreCase = true),
            onClick = { tone = candidate.name },
            label = candidate.label(),
          )
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      FluidButton(
        text = stringResource(R.string.action_cancel),
        onClick = onDismiss,
        style = FluidButtonStyle.Plain,
        modifier = Modifier.weight(1f),
        fillWidth = true,
      )
      FluidButton(
        text = stringResource(R.string.action_save),
        onClick = { onConfirm(name, tone) },
        enabled = name.isNotBlank(),
        modifier = Modifier.weight(1f),
        fillWidth = true,
      )
    }
  }
}
