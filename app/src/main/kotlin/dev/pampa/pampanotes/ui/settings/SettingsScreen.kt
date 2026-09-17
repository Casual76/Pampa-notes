package dev.pampa.pampanotes.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import dev.antigravity.fluidengine.foundation.ThemeMode
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.pampanotes.BuildConfig
import dev.pampa.pampanotes.R

/**
 * Le impostazioni. In M0 c'e' solo l'aspetto, che e' l'unica cosa che ha gia' qualcosa da regolare;
 * servizi, trascrizione, raffinamento, export e backup arrivano con i rispettivi milestone.
 */
@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
  val engine by viewModel.engineSettings.collectAsStateWithLifecycle()
  val themeLabels = mapOf(
    ThemeMode.SYSTEM to stringResource(R.string.theme_system),
    ThemeMode.LIGHT to stringResource(R.string.theme_light),
    ThemeMode.DARK to stringResource(R.string.theme_dark),
    ThemeMode.AMOLED to stringResource(R.string.theme_amoled),
  )

  FluidScreen(title = stringResource(R.string.settings_title)) {
    item { FluidSectionHeader(title = stringResource(R.string.settings_section_appearance)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_theme),
          subtitle = stringResource(R.string.settings_theme_detail),
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_dynamic_color),
          subtitle = stringResource(R.string.settings_dynamic_color_detail),
          badge = {
            FluidSwitch(
              checked = engine.dynamicColorEnabled,
              onCheckedChange = viewModel::setDynamicColor,
            )
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_amoled),
          subtitle = stringResource(R.string.settings_amoled_detail),
          badge = {
            FluidSwitch(
              checked = engine.amoledEnabled,
              onCheckedChange = viewModel::setAmoled,
            )
          },
        )
        FluidListDivider()
        FluidListRow(
          title = stringResource(R.string.settings_haptics),
          subtitle = stringResource(R.string.settings_haptics_detail),
          badge = {
            FluidSwitch(
              checked = engine.hapticsEnabled,
              onCheckedChange = viewModel::setHaptics,
            )
          },
        )
      }
    }
    item {
      FluidSegmentedControl(
        options = ThemeMode.entries.toList(),
        selected = engine.themeMode,
        onSelect = viewModel::setThemeMode,
        // Le etichette sono gia' risolte: la lambda del controllo non e' composable.
        label = { mode -> themeLabels.getValue(mode) },
      )
    }

    item { FluidSectionHeader(title = stringResource(R.string.settings_section_about)) }
    item {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.settings_version),
          subtitle = BuildConfig.VERSION_NAME,
          meta = dev.antigravity.fluidengine.foundation.EngineBuild.VERSION,
        )
      }
    }
  }
}
