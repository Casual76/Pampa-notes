package dev.pampa.pampanotes.ui.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAlert
import dev.antigravity.fluidengine.ui.fluid.FluidAlertAction
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidBarAction
import dev.antigravity.fluidengine.ui.fluid.FluidContextAction
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidVividCard
import dev.antigravity.fluidengine.ui.fluid.FluidVividEffect
import dev.antigravity.fluidengine.ui.theme.FluidEmptyState
import dev.antigravity.fluidengine.ui.theme.FluidQuickAction
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.FolderRow
import dev.pampa.pampanotes.ui.common.FolderEditorSheet
import dev.pampa.pampanotes.ui.common.rememberComputerOnly
import dev.pampa.pampanotes.ui.common.rememberPullToSync
import dev.pampa.pampanotes.ui.common.folderIconOf
import dev.pampa.pampanotes.ui.common.folderVividColors
import dev.pampa.pampanotes.ui.common.toneFromName
import dev.pampa.pampanotes.core.export.ExportScope
import dev.pampa.pampanotes.ui.export.ExportSheet
import androidx.compose.foundation.layout.BoxWithConstraints
import dev.antigravity.fluidengine.ui.fluid.FluidScreenDefaults
import dev.antigravity.fluidengine.ui.fluid.fluidGridColumns
import dev.antigravity.fluidengine.ui.fluid.fluidScreenPadding

/**
 * Le cartelle, come tessere.
 *
 * Due per riga, ognuna con il suo colore pieno e la sua icona: una materia si riconosce prima di
 * aver letto il nome, ed e' l'unico punto dell'app in cui il colore sta su tutta la superficie
 * invece che sulla sola piastrella — una tessera e' un elemento separato, non una riga in un gruppo.
 */
@Composable
fun FoldersRoute(
  onOpenFolder: (String) -> Unit,
  onImport: () -> Unit,
  viewModel: FoldersViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var editing by remember { mutableStateOf<FolderEdit?>(null) }
  var pendingDelete by remember { mutableStateOf<FolderRow?>(null) }
  var exporting by remember { mutableStateOf<FolderRow?>(null) }
  val computerOnly = rememberComputerOnly()
  // Tirando giu' la griglia si sincronizza: vedi PullToSync.
  val pull = rememberPullToSync()

  val newLabel = stringResource(R.string.home_new_folder)
  val editLabel = stringResource(R.string.action_edit)
  val deleteLabel = stringResource(R.string.action_delete)
  val exportLabel = stringResource(R.string.action_export)

  // Le colonne dalla misura, non dal tipo di schermo: una tessera vale 180 dp, e quante ne stanno
  // nella colonna di lettura lo dice la larghezza.
  BoxWithConstraints {
    val sidePadding = fluidScreenPadding(maxWidth, FluidScreenDefaults.HorizontalPadding, FluidScreenDefaults.ContentMaxWidth)
    val columns = fluidGridColumns(maxWidth - sidePadding * 2)
  FluidScreen(
    title = stringResource(R.string.folders_title),
    subtitle = stringResource(R.string.folders_subtitle),
    ambient = FluidAmbient(tone = FluidHeroTone.Primary, motif = FluidHeroMotif.Cards),
    isRefreshing = pull.isRefreshing,
    onRefresh = pull.onRefresh,
    actions = {
      FluidBarAction(
        icon = Icons.Rounded.Add,
        contentDescription = newLabel,
        onClick = { editing = FolderEdit.New },
      )
    },
  ) {
    if (state.isEmpty) {
      item {
        FluidEmptyState(
          title = stringResource(R.string.home_empty_title),
          detail = stringResource(R.string.home_empty_detail),
        )
      }
      item {
        FluidQuickAction(label = newLabel, onClick = { editing = FolderEdit.New }, modifier = Modifier.fillMaxWidth())
      }
      item {
        FluidQuickAction(label = stringResource(R.string.action_import), onClick = onImport, modifier = Modifier.fillMaxWidth())
      }
    } else {
      // Due colonne a mano invece di una griglia pigra: il contenuto della schermata e' gia' una
      // lista pigra, e annidarne un'altra dentro le toglie l'altezza da misurare.
      items(state.folders.chunked(columns), key = { row -> row.first().folder.id }) { pair ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          pair.forEach { row ->
            FolderTile(
              row = row,
              onComputer = if (computerOnly.folderMarked(row.folder.id)) computerOnly.marker else null,
              modifier = Modifier.weight(1f),
              onClick = { onOpenFolder(row.folder.id) },
              contextActions = {
                listOf(
                  FluidContextAction(label = editLabel) { editing = FolderEdit.Existing(row) },
                  // Dove uno lo cerca: tenendo premuta la materia da dare all'assistente.
                  FluidContextAction(label = exportLabel) { exporting = row },
                  computerOnly.folderAction(row.folder.id, row.folder.name),
                  FluidContextAction(label = deleteLabel, destructive = true) { pendingDelete = row },
                )
              },
            )
          }
          // L'ultima riga non lascia tessere piu' larghe delle altre: sarebbero diverse per il solo
          // fatto di essere le ultime.
          repeat(columns - pair.size) { Box(modifier = Modifier.weight(1f)) }
        }
      }
    }
  }
  }

  exporting?.let { row ->
    ExportSheet(scope = ExportScope.Folder(row.folder.id), onDismiss = { exporting = null })
  }

  editing?.let { request ->
    val existing = (request as? FolderEdit.Existing)?.row?.folder
    FolderEditorSheet(
      title = if (existing == null) newLabel else editLabel,
      initialName = existing?.name.orEmpty(),
      initialTone = existing?.tone,
      initialIcon = existing?.icon,
      onDismiss = { editing = null },
      onConfirm = { name, tone, icon ->
        if (existing == null) viewModel.create(name, tone, icon) else viewModel.update(existing.id, name, tone, icon)
        editing = null
      },
    )
  }

  pendingDelete?.let { row ->
    FluidAlert(
      onDismissRequest = { pendingDelete = null },
      title = stringResource(R.string.folder_delete_title, row.folder.name),
      message = stringResource(R.string.folder_delete_message),
      actions = listOf(
        FluidAlertAction(
          label = deleteLabel,
          emphasis = FluidAlertAction.Emphasis.Destructive,
          onClick = {
            viewModel.delete(row.folder.id)
            pendingDelete = null
          },
        ),
        FluidAlertAction(label = stringResource(R.string.action_cancel), onClick = { pendingDelete = null }),
      ),
    )
  }
}

private sealed interface FolderEdit {
  data object New : FolderEdit
  data class Existing(val row: FolderRow) : FolderEdit
}

@Composable
private fun FolderTile(
  row: FolderRow,
  /** «sul computer» in coda al sottotitolo, se una regola la copre. */
  onComputer: String?,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
  contextActions: () -> List<FluidContextAction>,
) {
  val colors = folderVividColors(toneFromName(row.folder.tone))

  FluidVividCard(
    colors = colors,
    modifier = modifier.aspectRatio(1.15f),
    // Le righe del quaderno su tutte, perche' non sono una decorazione: sono quello che una materia
    // e', un raccoglitore di appunti. Il luccichio invece su tutte sarebbe carta da parati.
    effect = FluidVividEffect.Ruled,
    onClick = onClick,
    contextActions = contextActions,
  ) {
    Icon(
      imageVector = folderIconOf(row.folder.icon),
      contentDescription = null,
      tint = colors.content,
      modifier = Modifier.size(30.dp),
    )
    Box(modifier = Modifier.weight(1f))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = row.folder.name,
        style = MaterialTheme.typography.titleMedium,
        color = colors.content,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = tileSubtitle(row).let { if (onComputer != null) "$it · $onComputer" else it },
        style = MaterialTheme.typography.labelMedium,
        color = colors.content.copy(alpha = 0.78f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun tileSubtitle(row: FolderRow): String {
  val parts = buildList {
    if (row.noteCount > 0) add(pluralStringResource(R.plurals.folder_note_count, row.noteCount, row.noteCount))
    if (row.childCount > 0) add(pluralStringResource(R.plurals.folder_child_count, row.childCount, row.childCount))
  }
  return if (parts.isEmpty()) stringResource(R.string.folder_empty_meta) else parts.joinToString(" · ")
}
