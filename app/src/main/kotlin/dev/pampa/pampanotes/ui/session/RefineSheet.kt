package dev.pampa.pampanotes.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.settings.RefinementPreset
import dev.pampa.pampanotes.ui.common.SheetScaffold

/**
 * Il pannello che chiede quanto intervenire sulla trascrizione.
 *
 * La riga che conta e' la prima: **la grezza resta**. E' l'unica cosa che rende accettabile far
 * riscrivere una fonte a una macchina, e va detta prima della scelta, non dopo.
 */
@Composable
fun RefineSheet(
  initialPreset: RefinementPreset,
  initialCustomPrompt: String,
  hasKey: Boolean,
  onDismiss: () -> Unit,
  onConfirm: (RefinementPreset, String) -> Unit,
  /** Il tasto da cui nasce: il pannello si apre su di lui, e nessuno perde il filo. */
  origin: () -> Rect? = { null },
) {
  var preset by remember(initialPreset) { mutableStateOf(initialPreset) }
  var custom by remember(initialCustomPrompt) { mutableStateOf(initialCustomPrompt) }

  val cleanLabel = stringResource(R.string.refine_preset_clean)
  val structuredLabel = stringResource(R.string.refine_preset_structured)
  val customLabel = stringResource(R.string.refine_preset_custom)

  FluidGlassModalPortal(
    visible = true,
    onDismissRequest = onDismiss,
    // Una scelta fra tre e un tasto: un pop-up sul comando, non un foglio che copre la lezione.
    presentation = FluidGlassModalPresentation.Popover,
    origin = origin,
    paneTitle = stringResource(R.string.refine_title),
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
            text = stringResource(R.string.refine_action),
            onClick = { onConfirm(preset, custom) },
            // Senza chiave non c'e' niente da chiamare: meglio un tasto spento di un lavoro che
            // fallisce trenta secondi dopo con un 401.
            enabled = hasKey && (preset != RefinementPreset.CUSTOM || custom.isNotBlank()),
            modifier = Modifier.weight(1f),
            fillWidth = true,
          )
        }
      },
    ) {
      Text(text = stringResource(R.string.refine_title), style = MaterialTheme.typography.titleLarge)
      Text(
        text = stringResource(R.string.refine_explain),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      if (!hasKey) {
        FluidInlineMessage(
          title = stringResource(R.string.refine_title),
          message = stringResource(R.string.refine_no_key),
          tone = FluidTone.Warning,
        )
      }

      FluidSectionFootnote(text = stringResource(R.string.refine_preset_label))
      FluidSegmentedControl(
        options = listOf(RefinementPreset.CLEAN, RefinementPreset.STRUCTURED, RefinementPreset.CUSTOM),
        selected = preset,
        onSelect = { preset = it },
        label = {
          when (it) {
            RefinementPreset.CLEAN -> cleanLabel
            RefinementPreset.STRUCTURED -> structuredLabel
            RefinementPreset.CUSTOM -> customLabel
          }
        },
        modifier = Modifier.fillMaxWidth(),
      )
      FluidSectionFootnote(
        text = stringResource(
          when (preset) {
            RefinementPreset.CLEAN -> R.string.refine_preset_clean_detail
            RefinementPreset.STRUCTURED -> R.string.refine_preset_structured_detail
            RefinementPreset.CUSTOM -> R.string.refine_preset_custom_detail
          },
        ),
      )

      if (preset == RefinementPreset.CUSTOM) {
        FluidTextField(
          value = custom,
          onValueChange = { custom = it },
          placeholder = stringResource(R.string.refine_custom_hint),
          singleLine = false,
          minLines = 3,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}
