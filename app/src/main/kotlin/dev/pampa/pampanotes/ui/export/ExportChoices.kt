package dev.pampa.pampanotes.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChoiceRow
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidToggleRow
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.fluid.fluidRowPressable
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.export.ExportFormat
import dev.pampa.pampanotes.core.export.ExportOptions
import dev.pampa.pampanotes.core.export.ExportTarget
import dev.pampa.pampanotes.core.export.TranscriptChoice
import dev.pampa.pampanotes.ui.common.Formats

/**
 * «Dove lo usi?» e «Personalizza»: le scelte di un export, uguali nel pannello e nelle impostazioni.
 *
 * Una domanda sola con una risposta gia' data, e una riga che dice cosa succede. Il resto sta dietro
 * «Personalizza», chiuso: chi non lo apre esporta la cosa giusta per dove sta andando, chi lo apre
 * trova solo gli interruttori che contano per quella destinazione, ognuno con una frase che dice
 * cosa cambia. Nelle impostazioni la stessa cosa sceglie da dove parte il pannello la prossima
 * volta: due posti con due linguaggi diversi per la stessa scelta sarebbero due cose da imparare.
 *
 * @param audioDurationMs quanto audio c'e' nell'ambito, per dire quanto pesano le registrazioni;
 *   `null` dove non c'e' un ambito (le impostazioni).
 */
@Composable
fun ExportChoices(
  options: ExportOptions,
  onOptions: (ExportOptions) -> Unit,
  modifier: Modifier = Modifier,
  audioDurationMs: Long? = null,
) {
  var expanded by rememberSaveable { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(text = stringResource(R.string.export_where_title), style = MaterialTheme.typography.titleMedium)
    FluidListGroup {
      FluidChoiceRow(
        title = ExportTarget.PAMPA.label(),
        subtitle = ExportTarget.PAMPA.detail(),
        selected = options.isPampa,
        onSelect = { if (!options.isPampa) onOptions(options.withTarget(ExportTarget.PAMPA)) },
      )
    }
    if (options.isPampa) {
      FluidSectionFootnote(text = stringResource(R.string.pn_export_contents))
      FluidSectionFootnote(text = stringResource(R.string.pn_export_lossless))
    }
    Text(text = stringResource(R.string.pn_export_ai_title), style = MaterialTheme.typography.titleMedium)
    FluidListGroup {
      ExportTarget.entries.filter { it != ExportTarget.PAMPA }.forEachIndexed { index, target ->
        if (index > 0) FluidListDivider()
        // Una scelta singola con la spunta a destra: niente freccia «›», che promette una pagina dietro,
        // e questa e' una scelta che si fa qui. Scegliere la destinazione gia' scelta non cambia niente.
        FluidChoiceRow(
          title = target.label(),
          subtitle = target.detail(),
          selected = !options.isPampa && options.target == target,
          onSelect = { if (options.isPampa || options.target != target) onOptions(options.withTarget(target)) },
        )
      }
    }

    if (!options.isPampa) {
      FluidListGroup {
        FluidListRow(
          title = stringResource(R.string.export_customize),
          subtitle = summaryOf(options),
          meta = if (options.isCustomized) stringResource(R.string.export_customized) else null,
          badge = {
            Icon(
              imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          },
          modifier = Modifier.fluidRowPressable(onClick = { expanded = !expanded }),
        )
      }

      if (expanded) CustomizeBody(options = options, onOptions = onOptions, audioDurationMs = audioDurationMs)
    }
  }
}

@Composable
private fun CustomizeBody(options: ExportOptions, onOptions: (ExportOptions) -> Unit, audioDurationMs: Long?) {
  FluidSectionFootnote(text = stringResource(R.string.export_transcript_label))
  val bestLabel = stringResource(R.string.export_transcript_best)
  val rawLabel = stringResource(R.string.export_transcript_raw)
  FluidSegmentedControl(
    options = listOf(TranscriptChoice.BEST, TranscriptChoice.RAW),
    selected = options.transcript,
    onSelect = { onOptions(options.copy(transcript = it)) },
    label = { if (it == TranscriptChoice.BEST) bestLabel else rawLabel },
    modifier = Modifier.fillMaxWidth(),
  )
  FluidSectionFootnote(
    text = stringResource(
      if (options.transcript == TranscriptChoice.BEST) R.string.export_transcript_best_effect else R.string.export_transcript_raw_effect,
    ),
  )

  FluidListGroup {
    FluidToggleRow(
      title = stringResource(R.string.export_timestamps),
      subtitle = stringResource(R.string.export_timestamps_detail),
      checked = options.timestamps,
      onCheckedChange = { onOptions(options.copy(timestamps = it)) },
    )
    // Regole e allegati contano solo nello ZIP: i file sciolti hanno le regole in instructions.md
    // sempre, e il testo da incollare in cima. Un interruttore che non cambia niente e' un
    // interruttore che insegna a non fidarsi degli altri.
    if (options.carriesAttachments) {
      FluidListDivider()
      FluidToggleRow(
        title = stringResource(R.string.export_skill),
        subtitle = stringResource(R.string.export_skill_effect),
        checked = options.includeSkill,
        onCheckedChange = { onOptions(options.copy(includeSkill = it)) },
      )
      FluidListDivider()
      FluidToggleRow(
        title = stringResource(R.string.export_audio),
        subtitle = if (audioDurationMs != null && audioDurationMs > 0) {
          stringResource(R.string.export_audio_effect_size, Formats.durationShort(audioDurationMs))
        } else {
          stringResource(R.string.export_audio_effect)
        },
        checked = options.includeAudio,
        onCheckedChange = { onOptions(options.copy(includeAudio = it)) },
      )
      FluidListDivider()
      FluidToggleRow(
        title = stringResource(R.string.export_sources),
        subtitle = stringResource(R.string.export_sources_effect),
        checked = options.includeSources,
        onCheckedChange = { onOptions(options.copy(includeSources = it)) },
      )
    }
  }

  when (options.format) {
    ExportFormat.FILES -> FluidSectionFootnote(text = stringResource(R.string.export_files_no_attachments))
    ExportFormat.SINGLE -> FluidSectionFootnote(text = stringResource(R.string.export_single_no_attachments))
    ExportFormat.BUNDLE, ExportFormat.PAMPA -> Unit
  }

  if (options.isCustomized) {
    FluidButton(
      text = stringResource(R.string.export_reset_to, options.target.label()),
      onClick = { onOptions(options.target.defaults()) },
      style = FluidButtonStyle.Plain,
      fillWidth = true,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/** «Trascrizione: la migliore · Tempi: sì · Registrazioni: no · Originali: no». */
@Composable
private fun summaryOf(options: ExportOptions): String {
  val yes = stringResource(R.string.export_yes)
  val no = stringResource(R.string.export_no)
  fun flag(value: Boolean) = if (value) yes else no
  val pieces = buildList {
    add(
      stringResource(
        R.string.export_summary_transcript,
        stringResource(if (options.transcript == TranscriptChoice.BEST) R.string.export_summary_best else R.string.export_summary_raw),
      ),
    )
    add(stringResource(R.string.export_summary_timestamps, flag(options.timestamps)))
    if (options.carriesAttachments) {
      add(stringResource(R.string.export_summary_audio, flag(options.includeAudio)))
      add(stringResource(R.string.export_summary_sources, flag(options.includeSources)))
      if (!options.includeSkill) add(stringResource(R.string.export_summary_no_rules))
    }
  }
  return pieces.joinToString(" · ")
}

@Composable
fun ExportTarget.label(): String = stringResource(
  when (this) {
    ExportTarget.CHAT -> R.string.export_target_chat
    ExportTarget.PROJECT -> R.string.export_target_project
    ExportTarget.AGENT -> R.string.export_target_agent
    ExportTarget.PASTE -> R.string.export_target_paste
    ExportTarget.PAMPA -> R.string.pn_export_target
  },
)

@Composable
private fun ExportTarget.detail(): String = stringResource(
  when (this) {
    ExportTarget.CHAT -> R.string.export_target_chat_detail
    ExportTarget.PROJECT -> R.string.export_target_project_detail
    ExportTarget.AGENT -> R.string.export_target_agent_detail
    ExportTarget.PASTE -> R.string.export_target_paste_detail
    ExportTarget.PAMPA -> R.string.pn_export_target_detail
  },
)
