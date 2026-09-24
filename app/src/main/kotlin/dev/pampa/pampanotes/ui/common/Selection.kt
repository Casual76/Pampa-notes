package dev.pampa.pampanotes.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.pampa.pampanotes.R

/**
 * Il segno di una riga in selezione multipla: un cerchio vuoto, o pieno e del colore dell'accento.
 *
 * Sta nello spazio `leading` di `FluidListRow`: e' l'unica cosa che cambia fra una riga normale e
 * una in selezione, cosi' l'elenco non salta quando si entra e si esce.
 *
 * @param tint il colore del segno quando sta sopra una superficie piena (la tessera di una materia),
 *   dove l'accento dell'app non si leggerebbe; `null` sulle righe normali.
 */
@Composable
fun SelectionMark(selected: Boolean, tint: Color? = null) {
  val scheme = MaterialTheme.colorScheme
  Icon(
    imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
    // Letto insieme alla riga: senza, TalkBack non diceva mai quali erano scelte.
    contentDescription = stringResource(if (selected) R.string.a11y_selected else R.string.a11y_not_selected),
    tint = tint ?: if (selected) scheme.primary else scheme.onSurfaceVariant,
    modifier = Modifier.size(24.dp),
  )
}

/**
 * La selezione multipla di una schermata: se e' aperta e quali id ha dentro.
 *
 * E' la stessa in ogni elenco — cartella, home, Registrazioni, ricerca, materie — perche' si
 * comporta allo stesso modo dappertutto: si entra da «Seleziona» (dai tre pallini, o tenendo premuta
 * una riga, che allora e' gia' scelta), il tocco sceglie invece di aprire, la barra in alto dice
 * «N selezionate» e indietro chiude la selezione prima di chiudere la pagina. Sul tablet vive nel
 * pannello dell'elenco: e' li' che si sceglie, e il dettaglio accanto resta quello che era.
 */
@Stable
class SelectionState internal constructor(active: Boolean, ids: Set<String>) {
  var active by mutableStateOf(active)
    private set
  var ids by mutableStateOf(ids)
    private set

  val count: Int get() = ids.size

  operator fun contains(id: String): Boolean = id in ids

  /** Apre la selezione; con [first], quella riga e' gia' scelta (il «Seleziona» tenendola premuta). */
  fun start(first: String? = null) {
    active = true
    ids = setOfNotNull(first)
  }

  fun toggle(id: String) {
    ids = if (id in ids) ids - id else ids + id
  }

  fun exit() {
    active = false
    ids = emptySet()
  }

  companion object {
    internal val Saver: Saver<SelectionState, Any> = Saver(
      save = { arrayListOf<Any>(it.active, ArrayList(it.ids)) },
      restore = { saved ->
        val list = saved as List<*>
        SelectionState(list[0] as Boolean, (list[1] as List<*>).mapTo(HashSet()) { it as String })
      },
    )
  }
}

/**
 * Una selezione che sopravvive alla rotazione (e al cambio di regime dei pannelli), col tasto
 * indietro gia' collegato: aperta, indietro la chiude e basta.
 */
@Composable
fun rememberSelection(): SelectionState {
  val state = rememberSaveable(saver = SelectionState.Saver) { SelectionState(false, emptySet()) }
  BackHandler(enabled = state.active) { state.exit() }
  return state
}

/** Il titolo della barra in selezione: «3 selezionate». */
@Composable
fun selectionTitle(selection: SelectionState): String =
  pluralStringResource(R.plurals.selection_count, selection.count, selection.count)

/** Il nome del pacchetto di un export di note scelte a mano da piu' materie: «4 note». */
@Composable
fun selectedNotesLabel(count: Int): String = pluralStringResource(R.plurals.selection_export_notes, count, count)

/** La conferma prima di eliminare le note scelte: con loro se ne vanno registrazioni e originali. */
@Composable
fun ConfirmDeleteNotes(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  FluidAlert(
    onDismissRequest = onDismiss,
    title = stringResource(R.string.selection_delete_title),
    message = pluralStringResource(R.plurals.selection_delete_notes_message, count, count),
    actions = listOf(
      FluidAlertAction(
        label = stringResource(R.string.action_delete),
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
