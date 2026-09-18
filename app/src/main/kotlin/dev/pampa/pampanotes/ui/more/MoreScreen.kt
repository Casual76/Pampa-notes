package dev.pampa.pampanotes.ui.more

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidStatusBadge
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.export.ExportScope
import dev.pampa.pampanotes.ui.common.RowIcon
import dev.pampa.pampanotes.ui.export.ExportSheet

/**
 * Tutto il resto: cerca, lavori, export, backup, impostazioni.
 *
 * Esiste perche' la barra in basso regge tre schede, non sei: quello che si usa ogni giorno sta
 * nelle prime due, e questa e' la porta per il resto.
 */
@Composable
fun MoreRoute(
  onOpenSearch: () -> Unit,
  onOpenJobs: () -> Unit,
  onOpenSettings: () -> Unit,
  onImport: () -> Unit,
  viewModel: MoreViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var exporting by remember { mutableStateOf(false) }

  FluidScreen(
    title = stringResource(R.string.more_title),
    ambient = FluidAmbient(tone = FluidHeroTone.SecondaryToTertiary, motif = FluidHeroMotif.Dots),
  ) {
    item { FluidSectionHeader(title = stringResource(R.string.more_section_work)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.search_title),
          subtitle = stringResource(R.string.more_search_detail),
          leading = { RowIcon(Icons.Rounded.Search, FluidTone.Primary) },
          onClick = onOpenSearch,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.jobs_title),
          subtitle = stringResource(R.string.more_jobs_detail),
          leading = { RowIcon(Icons.AutoMirrored.Rounded.ListAlt, FluidTone.Info) },
          badge = if (state.activeJobs > 0) {
            { FluidStatusBadge(label = state.activeJobs.toString(), tone = FluidTone.Warning) }
          } else {
            null
          },
          onClick = onOpenJobs,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.action_import),
          subtitle = stringResource(R.string.more_import_detail),
          leading = { RowIcon(Icons.Rounded.Download, FluidTone.Success) },
          onClick = onImport,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.action_export),
          subtitle = stringResource(R.string.more_export_detail),
          leading = { RowIcon(Icons.Rounded.CloudUpload, FluidTone.Primary) },
          onClick = { exporting = true },
        )
      }
    }

    item { FluidSectionHeader(title = stringResource(R.string.more_section_app)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_title),
          subtitle = stringResource(R.string.more_settings_detail),
          leading = { RowIcon(Icons.Rounded.Tune, FluidTone.Primary) },
          onClick = onOpenSettings,
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_version),
          subtitle = state.versionName,
          leading = { RowIcon(Icons.Rounded.Info, FluidTone.Neutral) },
          meta = state.engineVersion,
        )
      }
    }
  }

  if (exporting) {
    ExportSheet(scope = ExportScope.Everything, onDismiss = { exporting = false })
  }
}
