package dev.pampa.pampanotes.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.transcription.ComputerOverrides
import dev.pampa.pampanotes.core.transcription.Pieces
import dev.pampa.pampanotes.core.transcription.TranscribeOverrides

/**
 * Le impostazioni di **una** trascrizione: si apre da «Ritrascrivi» per chi vuole sistemare qualcosa
 * (la lingua, un nome che non e' venuto, un altro modello perche' il computer ne aveva scelto uno
 * troppo leggero) senza toccare le impostazioni dell'app.
 *
 * Tutto parte da «come nelle impostazioni»: chi non cambia niente ottiene la trascrizione di sempre, e
 * solo le scelte toccate finiscono nel lavoro ([TranscribeOverrides]). Quello che il computer di casa
 * non puo' fare — Groq non divide in pezzi ne' separa le voci, un computer vecchio non sceglie il
 * modello per una lezione sola, un ospite non cambia il computer di un altro — non si mostra.
 */
@Composable
fun TranscribeOptionsSheet(
  onDismiss: () -> Unit,
  onConfirm: (TranscribeOverrides) -> Unit,
  viewModel: TranscribeOptionsViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  LaunchedEffect(Unit) { viewModel.load() }

  val settings = state.settings
  val ready = state.ready

  // Le scelte: null e' «come nelle impostazioni».
  var language by remember { mutableStateOf<String?>(null) }
  var vocabulary by remember(settings?.vocabulary) { mutableStateOf(settings?.vocabulary.orEmpty()) }
  var diarize by remember { mutableStateOf<Boolean?>(null) }
  var pieces by remember { mutableStateOf<Pieces?>(null) }
  var model by remember { mutableStateOf<String?>(null) }
  var vramGb by remember { mutableStateOf<Double?>(null) }
  var batchMax by remember { mutableStateOf<Int?>(null) }

  val computerChoice = ComputerOverrides(model = model, vramGb = vramGb, batchMax = batchMax)
  // La stima si rifa' a ogni scelta (con un attimo di pazienza, vedi il ViewModel).
  LaunchedEffect(computerChoice, state.canTuneComputer) { viewModel.estimate(computerChoice) }

  fun overrides(): TranscribeOverrides {
    val tune = state.canTuneComputer
    return TranscribeOverrides(
      language = language,
      // Il vocabolario e' una scelta solo se e' diverso da quello delle impostazioni.
      vocabulary = vocabulary.takeIf { settings != null && it.trim() != settings.vocabulary.trim() },
      diarize = diarize.takeIf { state.onComputer && ready?.diarize == true },
      pieces = pieces.takeIf { state.onComputer },
      computer = computerChoice.takeIf { tune && !it.isEmpty },
    )
  }

  FluidGlassModalPortal(
    visible = true,
    onDismissRequest = onDismiss,
    presentation = FluidGlassModalPresentation.FullScreen,
    paneTitle = stringResource(R.string.tx_opts_title),
    footer = {
      PageActions {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          FluidButton(
            text = stringResource(R.string.action_cancel),
            onClick = onDismiss,
            style = FluidButtonStyle.Plain,
            modifier = Modifier.weight(1f),
            fillWidth = true,
          )
          FluidButton(
            text = stringResource(R.string.tx_opts_action),
            onClick = { onConfirm(overrides()) },
            // Finche' le impostazioni non sono lette, «Ritrascrivi» non sa cosa e' cambiato.
            enabled = settings != null,
            modifier = Modifier.weight(1f),
            fillWidth = true,
          )
        }
      }
    },
  ) {
    SheetBody(scrollable = false) {
      Text(text = stringResource(R.string.tx_opts_title), style = MaterialTheme.typography.titleLarge)
      Text(
        text = stringResource(R.string.tx_opts_explain),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      // --- Cosa si sente ---------------------------------------------------------------------
      FluidSectionHeader(title = stringResource(R.string.settings_language_header))
      val asNow = stringResource(R.string.tx_opts_as_now)
      val autoLabel = stringResource(R.string.tx_opts_language_detect)
      Choices(
        options = listOf<String?>(null, TranscribeOverrides.LANGUAGE_AUTO, "it", "en", "fr", "de", "es", "la"),
        selected = language,
        onSelect = { language = it },
        label = {
          when (it) {
            null -> asNow
            TranscribeOverrides.LANGUAGE_AUTO -> autoLabel
            else -> it.uppercase()
          }
        },
      )

      FluidTextField(
        value = vocabulary,
        onValueChange = { vocabulary = it },
        label = stringResource(R.string.settings_vocabulary),
        placeholder = stringResource(R.string.settings_vocabulary_hint),
        singleLine = false,
        minLines = 3,
        showClearButton = false,
        modifier = Modifier.fillMaxWidth(),
      )
      FluidSectionFootnote(text = stringResource(R.string.tx_opts_vocabulary_detail))

      // --- Il computer di casa ---------------------------------------------------------------
      if (state.onComputer) {
        ComputerChoices(
          state = state,
          diarize = diarize,
          onDiarize = { diarize = it },
          pieces = pieces,
          onPieces = { pieces = it },
          model = model,
          onModel = { model = it },
          vramGb = vramGb,
          onVramGb = { vramGb = it },
          batchMax = batchMax,
          onBatchMax = { batchMax = it },
        )
      }
    }
  }
}

/** Le scelte che hanno senso solo con il computer di casa. */
@Composable
private fun ColumnScope.ComputerChoices(
  state: TranscribeOptionsUiState,
  diarize: Boolean?,
  onDiarize: (Boolean?) -> Unit,
  pieces: Pieces?,
  onPieces: (Pieces?) -> Unit,
  model: String?,
  onModel: (String?) -> Unit,
  vramGb: Double?,
  onVramGb: (Double?) -> Unit,
  batchMax: Int?,
  onBatchMax: (Int?) -> Unit,
) {
  val asNow = stringResource(R.string.tx_opts_as_now)
  if (state.companion == null) {
    FluidSectionFootnote(text = stringResource(R.string.settings_vram_loading))
    return
  }
  val ready = state.ready
  if (ready == null) {
    // Spento, fuori rete, o di una versione che non risponde come si deve: si dice, e il resto
    // (lingua e vocabolario) vale lo stesso.
    FluidSectionFootnote(text = stringResource(R.string.tx_opts_computer_silent))
    return
  }

  // --- Chi parla -----------------------------------------------------------------------------
  if (ready.diarize) {
    FluidSectionHeader(title = stringResource(R.string.settings_speakers_header))
    val on = stringResource(R.string.tx_opts_speakers_on)
    val off = stringResource(R.string.tx_opts_speakers_off)
    Choices(
      options = listOf<Boolean?>(null, true, false),
      selected = diarize,
      onSelect = onDiarize,
      label = { when (it) { null -> asNow; true -> on; false -> off } },
    )
  }

  // --- Pezzi ---------------------------------------------------------------------------------
  FluidSectionHeader(title = stringResource(R.string.settings_custom_pieces_header))
  val auto = stringResource(R.string.settings_custom_chunk_auto)
  val whole = stringResource(R.string.settings_custom_chunk_whole)
  Choices(
    options = listOf<Pieces?>(null, Pieces(auto = true)) + PIECE_MINUTES.map { Pieces(minutes = it) } + Pieces(),
    selected = pieces,
    onSelect = onPieces,
    label = {
      when {
        it == null -> asNow
        it.auto -> auto
        it.minutes == null -> whole
        else -> "${it.minutes} min"
      }
    },
  )
  FluidSectionFootnote(text = stringResource(R.string.tx_opts_pieces_detail))

  // --- Modello, memoria, lotti: solo il proprietario, e solo con un computer che li sa dare ----
  if (!ready.jobOptions) {
    FluidSectionFootnote(text = stringResource(R.string.tx_opts_computer_old))
    return
  }
  if (!state.canTuneComputer) return

  FluidSectionHeader(title = stringResource(R.string.settings_vram_model))
  Choices(
    options = listOf<String?>(null) + MODELS,
    selected = model,
    onSelect = onModel,
    label = { it ?: asNow },
  )
  FluidSectionFootnote(text = stringResource(R.string.tx_opts_model_detail))

  // La memoria si sceglie solo con una scheda: sul processore non conta.
  val total = ready.gpu?.totalGb
  if (ready.gpu != null) {
    FluidSectionHeader(title = stringResource(R.string.settings_vram_header))
    val measured = stringResource(R.string.tx_opts_memory_measured)
    val choices = listOf<Double?>(null) + MEMORY_GB.filter { total == null || it <= total + 0.5 }
    Choices(
      options = choices,
      selected = vramGb,
      onSelect = onVramGb,
      label = { gb -> if (gb == null) measured else gigabytes(gb) },
    )
    FluidSectionFootnote(text = stringResource(R.string.tx_opts_memory_detail))
  }

  FluidSectionHeader(title = stringResource(R.string.tx_opts_batch))
  Choices(
    options = listOf<Int?>(null) + BATCHES,
    selected = batchMax,
    onSelect = onBatchMax,
    label = { it?.toString() ?: asNow },
  )
  FluidSectionFootnote(text = stringResource(R.string.tx_opts_batch_detail))

  // La stima del computer per queste scelte, prima di partire.
  state.estimate?.let { estimate ->
    val detail = buildList {
      estimate.model?.let { m -> add(if (estimate.batchSize != null) stringResource(R.string.settings_vram_estimate_detail, m, estimate.batchSize!!) else m) }
      estimate.budgetGb?.let { add(stringResource(R.string.settings_vram_estimate_budget, gigabytes(it))) }
    }.joinToString(" · ")
    FluidInlineMessage(
      title = stringResource(R.string.settings_vram_estimate, gigabytes(estimate.estimateGb)),
      message = detail.ifBlank { stringResource(R.string.settings_vram_header) },
      tone = if (estimate.exceedsBudget) FluidTone.Warning else FluidTone.Info,
    )
  }
}

/** Una riga di scelte, che scorre se non ci sta: la prima e' sempre «come nelle impostazioni». */
@Composable
private fun <T> Choices(
  options: List<T>,
  selected: T,
  onSelect: (T) -> Unit,
  label: @Composable (T) -> String,
) {
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    options.forEach { option ->
      FluidChip(label = label(option), selected = option == selected, onClick = { onSelect(option) })
    }
  }
}

/** «12 GB», «5,9 GB»: un decimale solo quando dice qualcosa, nella lingua del telefono. */
@Composable
private fun gigabytes(value: Double): String {
  val rounded = kotlin.math.round(value)
  val number = if (kotlin.math.abs(value - rounded) < 0.05) {
    rounded.toLong().toString()
  } else {
    String.format(LocalConfiguration.current.locales[0], "%.1f", value)
  }
  return stringResource(R.string.settings_gb, number)
}

private val PIECE_MINUTES = listOf(15, 30, 60, 120)
private val MODELS = listOf("large-v3", "medium", "small")
private val MEMORY_GB = listOf(2.0, 3.0, 4.0, 6.0, 8.0, 10.0, 12.0, 16.0, 20.0, 24.0, 32.0, 48.0)
private val BATCHES = listOf(2, 4, 8, 16)
