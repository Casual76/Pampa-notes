package dev.pampa.pampanotes.ui.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.ui.common.SheetScaffold
import dev.pampa.pampanotes.ui.common.Formats
import java.time.LocalDate

/**
 * Titolo e data di una sessione.
 *
 * La data e' la meta' che conta davvero, ed e' anche quella che una tastiera rende scomoda: si
 * scrive sbagliata, si scrive in tre formati diversi, e su una lezione di ieri non si vuole
 * scrivere niente. Quindi prima le scorciatoie — oggi, ieri, i giorni della settimana appena
 * passata — e il campo libero solo per chi deve andare piu' indietro.
 */
@Composable
fun SessionRenameSheet(
  title: String,
  date: String,
  onDismiss: () -> Unit,
  onConfirm: (title: String, date: String) -> Unit,
  /** Il tasto da cui nasce: il pannello si apre su di lui, e nessuno perde il filo. */
  origin: () -> Rect? = { null },
) {
  var text by remember(title) { mutableStateOf(title) }
  var chosen by remember(date) { mutableStateOf(date) }

  val today = remember { LocalDate.now() }
  val shortcuts = remember(today) { (0L..6L).map { today.minusDays(it) } }

  FluidGlassModalPortal(
    visible = true,
    onDismissRequest = onDismiss,
    // Due campi: un pop-up sul comando, non un foglio che copre la lezione.
    presentation = FluidGlassModalPresentation.Popover,
    origin = origin,
    paneTitle = stringResource(R.string.session_rename),
  ) {
    SheetScaffold(
      insetBottom = false,
      actions = {
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
            onClick = { onConfirm(text, Formats.typedDate(chosen) ?: chosen) },
            // Una data che non si sa leggere non si salva: meglio un tasto spento di una riga di
            // database con dentro "lunedi'".
            enabled = Formats.typedDate(chosen) != null,
            modifier = Modifier.weight(1f),
            fillWidth = true,
          )
        }
      },
    ) {
      FluidTextField(
        value = text,
        onValueChange = { text = it },
        label = stringResource(R.string.session_title_label),
        placeholder = stringResource(R.string.session_title_placeholder),
        modifier = Modifier.fillMaxWidth(),
      )

      FluidSectionFootnote(text = stringResource(R.string.session_date_label))
      val scroll = rememberScrollState()
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        shortcuts.forEach { candidate ->
          val iso = Formats.isoDate(candidate)
          FluidChip(
            label = Formats.relativeDate(candidate),
            selected = iso == Formats.typedDate(chosen),
            onClick = { chosen = iso },
          )
        }
      }

      FluidTextField(
        value = chosen,
        onValueChange = { chosen = it },
        label = stringResource(R.string.session_date_custom),
        placeholder = remember { Formats.isoDate(LocalDate.now()) },
        modifier = Modifier.fillMaxWidth(),
      )

    }
  }
}
