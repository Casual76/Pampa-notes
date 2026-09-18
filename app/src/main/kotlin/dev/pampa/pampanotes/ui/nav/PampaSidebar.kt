package dev.pampa.pampanotes.ui.nav

import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidSidebar
import dev.antigravity.fluidengine.ui.fluid.FluidSidebarRow
import dev.antigravity.fluidengine.ui.fluid.FluidSidebarSection
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.ui.common.folderAccent
import dev.pampa.pampanotes.ui.common.folderIconOf
import dev.pampa.pampanotes.ui.common.toneFromName
import dev.pampa.pampanotes.ui.folders.FoldersViewModel

/**
 * La barra laterale del tablet: la Home, le materie, e in fondo quello che stava dietro «Altro».
 *
 * Le materie sono la ragione per cui esiste: su una pagina larga si passa da Storia a Filosofia
 * con un tocco, senza tornare all'indice. Ogni materia porta il suo colore sulla piastrella, e la
 * riga scelta e' quella della pagina che si sta guardando.
 */
@Composable
fun PampaSidebar(
  selectedRoute: String?,
  selectedFolderId: String?,
  backdrop: GlassBackdropState,
  onHome: () -> Unit,
  onFolder: (String) -> Unit,
  onAllFolders: () -> Unit,
  onSearch: () -> Unit,
  onMore: () -> Unit,
  viewModel: FoldersViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  FluidSidebar(
    backdrop = backdrop,
    header = {
      Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
    },
    footer = {
      FluidSidebarRow(
        label = stringResource(R.string.search_title),
        selected = selectedRoute == Routes.SEARCH,
        icon = Icons.Rounded.Search,
        onClick = onSearch,
      )
      FluidSidebarRow(
        label = stringResource(R.string.tab_more),
        selected = selectedRoute == Routes.MORE || selectedRoute == Routes.JOBS || selectedRoute == Routes.SETTINGS,
        icon = Icons.Rounded.MoreHoriz,
        onClick = onMore,
      )
    },
  ) {
    item {
      FluidSidebarRow(
        label = stringResource(R.string.tab_home),
        selected = selectedRoute == Routes.HOME,
        icon = Icons.Rounded.Home,
        onClick = onHome,
      )
    }
    item { FluidSidebarSection(title = stringResource(R.string.sidebar_subjects)) }
    items(state.folders, key = { it.folder.id }) { row ->
      FluidSidebarRow(
        label = row.folder.name,
        selected = row.folder.id == selectedFolderId,
        icon = folderIconOf(row.folder.icon),
        tint = folderAccent(toneFromName(row.folder.tone)),
        detail = row.noteCount.takeIf { it > 0 }?.toString(),
        onClick = { onFolder(row.folder.id) },
      )
    }
    item {
      FluidSidebarRow(
        label = stringResource(R.string.sidebar_all_folders),
        selected = selectedRoute == Routes.FOLDERS,
        icon = Icons.Rounded.GridView,
        onClick = onAllFolders,
      )
    }
  }
}
