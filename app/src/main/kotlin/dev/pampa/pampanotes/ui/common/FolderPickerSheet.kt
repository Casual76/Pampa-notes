package dev.pampa.pampanotes.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.FolderEntity

/**
 * Dove mandare quello che si e' scelto: tutte le cartelle tranne quella in cui si e' gia', col
 * percorso intero sotto il nome, perche' «Novecento» da solo puo' stare sotto Storia o sotto
 * Italiano. Un tocco sceglie e chiude.
 */
@Composable
fun FolderPickerSheet(
  title: String,
  folders: List<FolderEntity>,
  excludeId: String?,
  onDismiss: () -> Unit,
  onPick: (String) -> Unit,
) {
  val rows = remember(folders, excludeId) {
    val byId = folders.associateBy { it.id }
    fun pathOf(folder: FolderEntity): String {
      val names = ArrayDeque<String>()
      var current: FolderEntity? = folder
      var guard = 0
      while (current != null && guard++ < 32) {
        names.addFirst(current.name)
        current = current.parentId?.let(byId::get)
      }
      return names.joinToString(" / ")
    }
    folders.filter { it.id != excludeId }.map { it to pathOf(it) }.sortedBy { it.second.lowercase() }
  }

  FluidGlassModalPortal(
    visible = true,
    onDismissRequest = onDismiss,
    presentation = FluidGlassModalPresentation.FullScreen,
    paneTitle = title,
    footer = {
      PageActions {
        FluidButton(
          text = stringResource(R.string.action_cancel),
          onClick = onDismiss,
          style = FluidButtonStyle.Plain,
          fillWidth = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
  ) {
    // Il portale a tutto schermo scorre gia' da solo: un corpo scorrevole dentro misura con
    // altezza infinita e crasha alla prima composizione (e' successo a ShareSheet, e qui).
    SheetBody(scrollable = false) {
      if (rows.isEmpty()) {
        FluidEmptyState(title = stringResource(R.string.folder_picker_empty), detail = "")
      } else {
        FluidListGroup {
          rows.forEachIndexed { index, (folder, path) ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = folder.name,
              // Una cartella di primo livello ha il percorso uguale al nome: ripeterlo sotto non dice niente.
              subtitle = if (path == folder.name) stringResource(R.string.import_folder_root) else path,
              tone = toneFromName(folder.tone),
              onClick = { onPick(folder.id) },
            )
          }
        }
      }
    }
  }
}
