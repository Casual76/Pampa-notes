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
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSegmentedControl
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.export.ExportFormat
import dev.pampa.pampanotes.core.export.ExportOptions
import dev.pampa.pampanotes.core.export.ExportTarget
import dev.pampa.pampanotes.core.export.TranscriptChoice
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.SelectionMark

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
      ExportTarget.entries.forEachIndexed { index, target ->
        if (index > 0) FluidListDivider()
        FluidListRow(
          title = target.label(),
          subtitle = target.detail(),
          leading = { SelectionMark(selected = options.target == target) },
          onClick = { if (options.target != target) onOptions(options.withTarget(target)) },
        )
      }
    }

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
        onClick = { expanded = !expanded },
      )
    }

    if (expanded) CustomizeBody(options = options, onOptions = onOptions, audioDurationMs = audioDurationMs)
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
    SwitchRow(
      title = stringResource(R.string.export_timestamps),
      subtitle = stringResource(R.string.export_timestamps_detail),
      checked = options.timestamps,
      onChange = { onOptions(options.copy(timestamps = it)) },
    )
    // Regole e allegati contano solo nello ZIP: i file sciolti hanno le regole in instructions.md
    // sempre, e il testo da incollare in cima. Un interruttore che non cambia niente e' un
    // interruttore che insegna a non fidarsi degli altri.
    if (options.carriesAttachments) {
      FluidListDivider()
      SwitchRow(
        title = stringResource(R.string.export_skill),
        subtitle = stringResource(R.string.export_skill_effect),
        checked = options.includeSkill,
        onChange = { onOptions(options.copy(includeSkill = it)) },
      )
      FluidListDivider()
      SwitchRow(
        title = stringResource(R.string.export_audio),
        subtitle = if (audioDurationMs != null && audioDurationMs > 0) {
          stringResource(R.string.export_audio_effect_size, Formats.durationShort(audioDurationMs))
        } else {
          stringResource(R.string.export_audio_effect)
        },
        checked = options.includeAudio,
        onChange = { onOptions(options.copy(includeAudio = it)) },
      )
      FluidListDivider()
      SwitchRow(
        title = stringResource(R.string.export_sources),
        subtitle = stringResource(R.string.export_sources_effect),
        checked = options.includeSources,
        onChange = { onOptions(options.copy(includeSources = it)) },
      )
    }
  }

  when (options.format) {
    ExportFormat.FILES -> FluidSectionFootnote(text = stringResource(R.string.export_files_no_attachments))
    ExportFormat.SINGLE -> FluidSectionFootnote(text = stringResource(R.string.export_single_no_attachments))
    ExportFormat.BUNDLE -> Unit
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

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  FluidListRow(
    title = title,
    subtitle = subtitle,
    onClick = { onChange(!checked) },
    badge = { FluidSwitch(checked = checked, onCheckedChange = onChange) },
  )
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
  },
)

@Composable
private fun ExportTarget.detail(): String = stringResource(
  when (this) {
    ExportTarget.CHAT -> R.string.export_target_chat_detail
    ExportTarget.PROJECT -> R.string.export_target_project_detail
    ExportTarget.AGENT -> R.string.export_target_agent_detail
    ExportTarget.PASTE -> R.string.export_target_paste_detail
  },
)
