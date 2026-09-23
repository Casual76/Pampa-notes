package dev.pampa.pampanotes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Chiude la pagina quando quello che mostra non esiste piu'.
 *
 * Sul tablet una nota cancellata dall'elenco accanto, da un'altra cartella o da un altro
 * dispositivo col sync restava aperta nel pannello di dettaglio come una nota vuota, e si poteva
 * perfino modificare. Solo con la pagina in primo piano: una pagina che sta gia' uscendo (dopo il
 * suo «Elimina», che torna indietro da se') e' scesa sotto RESUMED, e un secondo indietro
 * chiuderebbe anche quella di sotto.
 */
@Composable
fun CloseWhenGone(gone: Boolean, onBack: () -> Unit) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val currentOnBack by rememberUpdatedState(onBack)
  LaunchedEffect(gone) {
    if (gone && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) currentOnBack()
  }
}
