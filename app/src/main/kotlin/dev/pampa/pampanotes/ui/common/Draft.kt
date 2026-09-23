package dev.pampa.pampanotes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Il testo di un campo che si salva da solo, tenuto qui mentre si scrive.
 *
 * Il campo leggeva il valore da DataStore e lo riscriveva a ogni tasto: il giro dal disco e ritorno
 * arrivava dopo il tasto seguente, e scrivendo veloci si perdevano lettere o il cursore saltava. Qui
 * si scrive sulla bozza, e la bozza si salva mezzo secondo dopo l'ultimo tasto, o quando si esce.
 * Un valore cambiato da fuori (il primo caricamento, un ripristino) prende il posto della bozza solo
 * se nessuno la stava cambiando.
 */
@Composable
fun rememberDraft(stored: String, save: (String) -> Unit): MutableState<String> {
  val draft = rememberSaveable { mutableStateOf(stored) }
  var lastStored by remember { mutableStateOf(stored) }
  val currentStored by rememberUpdatedState(stored)
  val currentSave by rememberUpdatedState(save)
  LaunchedEffect(stored) {
    if (draft.value == lastStored) draft.value = stored
    lastStored = stored
  }
  LaunchedEffect(draft.value) {
    delay(500)
    if (draft.value != currentStored) currentSave(draft.value)
  }
  DisposableEffect(Unit) {
    onDispose { if (draft.value != currentStored) currentSave(draft.value) }
  }
  return draft
}
