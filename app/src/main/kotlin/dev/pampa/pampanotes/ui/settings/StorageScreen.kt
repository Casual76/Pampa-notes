package dev.pampa.pampanotes.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.nav.SettingsSection

/**
 * Archiviazione: cosa occupa lo spazio, e cosa si puo' togliere senza perdere niente.
 *
 * Le due pulizie che offre non cancellano **mai** una nota o una registrazione citata da una riga:
 * tolgono i file rimasti indietro e i pacchetti gia' condivisi. Una pagina che libera spazio
 * cancellando anche dati e' una pagina che si tocca una volta sola, e con paura.
 */
@Composable
fun StorageSectionRoute(
  onBack: () -> Unit,
  viewModel: StorageViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val usage = state.usage

  FluidScreen(
    title = SettingsSection.STORAGE.label(),
    onBack = onBack,
    ambient = FluidAmbient(tone = FluidHeroTone.PrimaryToSecondary, motif = FluidHeroMotif.Bars),
  ) {
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.storage_audio),
          subtitle = pluralStringResource(
            R.plurals.export_recordings,
            usage?.audio?.count ?: 0,
            usage?.audio?.count ?: 0,
          ),
          meta = Formats.bytes(usage?.audio?.bytes ?: 0),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.storage_sources),
          subtitle = stringResource(R.string.storage_sources_detail),
          meta = Formats.bytes(usage?.sources?.bytes ?: 0),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.storage_database),
          subtitle = stringResource(R.string.storage_database_detail),
          meta = Formats.bytes(usage?.databaseBytes ?: 0),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.storage_jobs),
          subtitle = stringResource(R.string.storage_jobs_detail),
          meta = Formats.bytes(usage?.jobsBytes ?: 0),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.storage_exports),
          subtitle = stringResource(R.string.storage_exports_detail),
          meta = Formats.bytes(usage?.exportsBytes ?: 0),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.storage_total),
          subtitle = stringResource(R.string.storage_total_detail),
          meta = Formats.bytes(usage?.totalBytes ?: 0),
        )
      }
    }

    item {
      FluidSectionHeader(
        title = stringResource(R.string.storage_cleanup),
        detail = stringResource(R.string.storage_cleanup_detail),
      )
    }

    item {
      FluidButton(
        text = stringResource(R.string.storage_sweep),
        onClick = viewModel::sweep,
        enabled = !state.working,
        loading = state.working,
        style = FluidButtonStyle.Tinted,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    item {
      FluidButton(
        text = stringResource(R.string.storage_clear_exports),
        onClick = viewModel::clearExports,
        enabled = !state.working,
        style = FluidButtonStyle.Plain,
        fillWidth = true,
        modifier = Modifier.fillMaxWidth(),
      )
    }

    state.event?.let { event ->
      item {
        val message = when (event) {
          is StorageEvent.Swept -> pluralStringResource(R.plurals.storage_swept, event.files, event.files)
          is StorageEvent.ExportsCleared -> pluralStringResource(
            R.plurals.storage_exports_cleared,
            event.files,
            event.files,
          )
        }
        FluidInlineMessage(
          title = stringResource(R.string.storage_cleanup),
          message = message,
          tone = FluidTone.Success,
          onDismiss = viewModel::dismissEvent,
        )
      }
    }

    item { FluidSectionFootnote(text = stringResource(R.string.storage_footnote)) }
  }
}
