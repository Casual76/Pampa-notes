package dev.pampa.pampanotes.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidColorDot
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.pampanotes.R

/**
 * Crea o modifica una cartella: nome, colore, icona.
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
  initialIcon: String?,
  onDismiss: () -> Unit,
  onConfirm: (name: String, tone: String?, icon: String?) -> Unit,
) {
  // Chiavi sul contenuto: un secondo pannello aperto su un'altra cartella deve ripartire dai suoi
  // valori, non da quelli di prima.
  var name by remember(initialName) { mutableStateOf(initialName) }
  var tone by remember(initialTone) { mutableStateOf(initialTone ?: selectableTones.first().name) }
  var icon by remember(initialIcon) { mutableStateOf(initialIcon) }
  // Finche' nessuno ha scelto un'icona, quella proposta segue il nome che si sta scrivendo.
  val effectiveIcon = icon ?: FolderIcon.guessFrom(name).key

  FluidGlassModalPortal(
    visible = true,
    onDismissRequest = onDismiss,
    // Nome, colore e diciassette icone: e' un compito, e un compito ha la sua pagina.
    presentation = FluidGlassModalPresentation.FullScreen,
    paneTitle = title,
    footer = {
      PageActions {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          FluidButton(
            text = stringResource(R.string.action_cancel),
            onClick = onDismiss,
            style = FluidButtonStyle.Plain,
            modifier = Modifier.weight(1f),
            fillWidth = true,
          )
          FluidButton(
            text = stringResource(R.string.action_save),
            onClick = { onConfirm(name, tone, effectiveIcon) },
            enabled = name.isNotBlank(),
            modifier = Modifier.weight(1f),
            fillWidth = true,
          )
        }
      }
    },
  ) {
    SheetBody(scrollable = false) {
      FluidTextField(
        value = name,
        onValueChange = { name = it },
        label = title,
        placeholder = stringResource(R.string.folder_name_placeholder),
        modifier = Modifier.fillMaxWidth(),
      )

      FluidSectionFootnote(text = stringResource(R.string.folder_tone_label))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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

      FluidSectionFootnote(text = stringResource(R.string.folder_icon_label))
      val scroll = rememberScrollState()
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        FolderIcon.entries.forEach { candidate ->
          IconChoice(
            candidate = candidate,
            selected = candidate.key == effectiveIcon,
            onClick = { icon = candidate.key },
          )
        }
      }

    }
  }
}

/** Una delle icone fra cui scegliere: un quadrato che si accende quando e' quella scelta. */
@Composable
private fun IconChoice(
  candidate: FolderIcon,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val label = candidate.label()
  Box(
    modifier = Modifier
      .size(48.dp)
      .clip(ContinuousCornerShape(FluidRadius.Control))
      .background(if (selected) scheme.primary else scheme.surfaceContainerHighest)
      .fluidPressable(onClick = onClick)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = candidate.icon,
      contentDescription = null,
      tint = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
      modifier = Modifier.size(22.dp),
    )
  }
}
