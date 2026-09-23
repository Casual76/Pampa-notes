package dev.pampa.pampanotes.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.fluidRowPressable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidBarAction
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.fluidExpandOrigin
import dev.pampa.pampanotes.R

/**
 * I tre pallini: un tocco apre le azioni della pagina.
 *
 * Il tasto della barra dell'engine ([FluidBarAction]) apre il suo menu solo **tenendo premuto**, e al
 * tocco fa un'azione sola. Andava bene finche' quell'azione era quella che si voleva quasi sempre;
 * nella nota non lo e' piu' — nella scheda Audio «modifica gli appunti» non c'entra niente — e un
 * menu che si scopre solo tenendo premuto e' un menu che la meta' delle persone non trova. Qui il
 * tocco apre un popover ancorato al tasto, fatto dello stesso vetro, con le stesse azioni che si
 * avrebbero tenendo premuto: chi lo tiene premuto per abitudine trova quello di sempre.
 */
@Composable
fun OverflowMenuButton(
  actions: () -> List<FluidContextAction>,
  modifier: Modifier = Modifier,
  contentDescription: String = stringResource(R.string.action_more),
) {
  var open by remember { mutableStateOf(false) }
  var origin by remember { mutableStateOf<Rect?>(null) }

  FluidBarAction(
    icon = Icons.Rounded.MoreHoriz,
    contentDescription = contentDescription,
    onClick = { open = true },
    modifier = modifier.fluidExpandOrigin(open = { open }, onMeasured = { origin = it }),
    actions = actions,
  )

  FluidGlassModalPortal(
    visible = open,
    onDismissRequest = { open = false },
    origin = { origin },
    presentation = FluidGlassModalPresentation.Popover,
    paneTitle = contentDescription,
  ) {
    val items = remember(open) { if (open) actions() else emptyList() }
    items.forEach { action ->
      MenuRow(action) {
        // Prima si chiude, poi si agisce: un'azione che apre un'altra finestra (una conferma, un
        // pannello) la troverebbe altrimenti sotto questo popover ancora aperto.
        open = false
        action.onClick()
      }
    }
  }
}

/**
 * Una riga del menu, uguale a quella che l'engine disegna quando il menu si apre tenendo premuto
 * (`FluidContextMenuRow`, privata li'): stessa altezza, stessi margini, stesso rosso per l'azione
 * distruttiva. Due menu dello stesso tasto che si aprono in due modi non devono sembrare due menu.
 */
@Composable
private fun MenuRow(action: FluidContextAction, onChoose: () -> Unit) {
  val color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 48.dp)
      .fluidRowPressable(onClick = { if (action.enabled) onChoose() }, enabled = action.enabled)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = action.label,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.Medium,
      color = if (action.enabled) color else color.copy(alpha = 0.38f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
