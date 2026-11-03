package dev.pampa.pampanotes.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidBarAction
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidMenuOpensOn
import dev.pampa.pampanotes.R

/**
 * I tre pallini: un tocco apre le azioni della pagina.
 *
 * Il tasto della barra dell'engine apriva il suo menu solo **tenendo premuto**, e al tocco faceva
 * un'azione sola: nella nota non va bene — nella scheda Audio «modifica gli appunti» non c'entra
 * niente — e un menu che si scopre solo tenendo premuto lo trova meta' delle persone. Dalla 3.0
 * `FluidBarAction(opensOn = FluidMenuOpensOn.Tap)` e' fatto per questo: il tocco apre il menu, che
 * e' lo stesso del tenuto premuto (si sceglie anche col dito che l'ha aperto) e ha il vetro e il
 * viaggio di tutti gli altri menu. Prima qui c'era un popover scritto a mano, con la sua riga.
 *
 * Resta una funzione sola per non ripetere in sette schermate l'icona, la parola di TalkBack e il
 * gesto: il menu e il tasto sono dell'engine.
 */
@Composable
fun OverflowMenuButton(
  actions: () -> List<FluidContextAction>,
  modifier: Modifier = Modifier,
  contentDescription: String = stringResource(R.string.action_more),
) {
  FluidBarAction(
    icon = Icons.Rounded.MoreHoriz,
    contentDescription = contentDescription,
    // Non viene chiamato: con `Tap` il tocco apre il menu.
    onClick = {},
    modifier = modifier,
    actions = actions,
    opensOn = FluidMenuOpensOn.Tap,
  )
}
