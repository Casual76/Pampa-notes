package dev.pampa.pampanotes.ui.note

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.SessionWithParts
import dev.pampa.pampanotes.core.repo.LoudnessProgress
import dev.pampa.pampanotes.core.repo.NoteLoudnessResult
import dev.pampa.pampanotes.core.repo.NoteStatsRepository
import dev.pampa.pampanotes.core.stats.Loudness
import dev.pampa.pampanotes.core.stats.LoudnessComparison
import dev.pampa.pampanotes.core.stats.SpeakingPace
import dev.pampa.pampanotes.core.stats.Spoken
import dev.pampa.pampanotes.core.stats.SpokenStats
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.PageActions
import dev.pampa.pampanotes.ui.common.SheetBody
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NoteStatsUi(
  val spoken: Spoken? = null,
  val loudness: NoteLoudnessResult? = null,
  /** Mentre si ascoltano le registrazioni: quale, e a che punto. */
  val progress: LoudnessProgress? = null,
  val measuring: Boolean = false,
)

/**
 * Le statistiche di una nota: le parole subito, il volume ascoltando i file (vedi
 * [NoteStatsRepository]). L'ascolto parte quando il pannello si apre e si ferma quando si chiude:
 * quello che e' gia' stato misurato resta, e la volta dopo si riparte da li'.
 */
@HiltViewModel
class NoteStatsViewModel @Inject constructor(
  savedStateHandle: SavedStateHandle,
  private val stats: NoteStatsRepository,
) : ViewModel() {
  private val noteId: String = savedStateHandle.get<String>("noteId").orEmpty()
  private val _state = MutableStateFlow(NoteStatsUi())
  val state: StateFlow<NoteStatsUi> = _state
  private var listening: Job? = null

  fun start() {
    viewModelScope.launch {
      // Prima si conta, poi si scrive: dentro `update` il conto si rifarebbe a ogni scatto
      // dell'ascolto, che cambia lo stato cinque volte al secondo, e non arriverebbe mai.
      val spoken = stats.spoken(noteId)
      _state.update { it.copy(spoken = spoken) }
    }
    if (listening?.isActive == true) return
    listening = viewModelScope.launch {
      _state.update { it.copy(measuring = true) }
      try {
        val result = stats.loudness(noteId) { progress -> _state.update { it.copy(progress = progress) } }
        _state.update { it.copy(loudness = result) }
      } finally {
        _state.update { it.copy(measuring = false, progress = null) }
      }
    }
  }

  fun stop() {
    listening?.cancel()
    listening = null
  }
}

/**
 * «Statistiche», dal menu della nota: un'aggiunta per ridere, con numeri veri. Il volume medio e il
 * momento piu' forte (un tocco ci porta), quanto silenzio, quante parole e a che velocita', la
 * parola preferita e l'intercalare, e gli appunti contro il parlato.
 *
 * I decibel «della stanza» sono una stima a orecchio e il pannello lo dice: il numero misurato e'
 * quello in dBFS, sotto (vedi [Loudness]).
 */
@Composable
fun NoteStatsSheet(
  sessions: List<SessionWithParts>,
  personal: Boolean,
  onOpenMoment: (sessionId: String, atMs: Long) -> Unit,
  onDismiss: () -> Unit,
  viewModel: NoteStatsViewModel = hiltViewModel(),
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  DisposableEffect(Unit) {
    viewModel.start()
    onDispose { viewModel.stop() }
  }
  val partCount = sessions.sumOf { it.parts.size }

  FluidGlassModalPortal(
    visible = true,
    onDismissRequest = onDismiss,
    presentation = FluidGlassModalPresentation.FullScreen,
    paneTitle = stringResource(R.string.note_stats_title),
    footer = {
      PageActions {
        FluidButton(
          text = stringResource(R.string.action_close),
          onClick = onDismiss,
          style = FluidButtonStyle.Plain,
          fillWidth = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
  ) {
    // Il portale a tutto schermo scorre gia' da solo: un corpo scorrevole dentro crasha.
    SheetBody(scrollable = false) {
      if (partCount > 0) {
        FluidSectionHeader(title = stringResource(R.string.note_stats_volume))
        VolumeGroup(state, sessions, onOpenMoment)
      }
      state.spoken?.let { spoken ->
        FluidSectionHeader(title = stringResource(R.string.note_stats_words))
        WordsGroup(spoken, personal, hasAudio = partCount > 0)
      }
    }
  }
}

@Composable
private fun VolumeGroup(state: NoteStatsUi, sessions: List<SessionWithParts>, onOpenMoment: (String, Long) -> Unit) {
  val result = state.loudness
  val loudness = result?.loudness
  FluidListGroup {
    when {
      state.measuring -> {
        val progress = state.progress
        FluidListRow(
          title = stringResource(R.string.note_stats_listening),
          subtitle = progress?.let {
            stringResource(R.string.note_stats_listening_detail, it.part, it.parts, (it.fraction * 100).roundToInt())
          } ?: stringResource(R.string.note_stats_listening_start),
        )
      }

      loudness == null -> FluidListRow(
        title = stringResource(R.string.note_stats_volume_none),
        subtitle = stringResource(R.string.note_stats_volume_none_detail),
      )

      else -> {
        val meanSpl = Loudness.estimatedSpl(loudness.meanDbfs)
        val peakSpl = Loudness.estimatedSpl(loudness.peakDbfs)
        FluidListRow(
          title = stringResource(R.string.note_stats_db, meanSpl),
          subtitle = comparisonText(Loudness.comparisonOf(meanSpl)),
          eyebrow = stringResource(R.string.note_stats_mean),
          meta = stringResource(R.string.note_stats_dbfs, formatDb(loudness.meanDbfs)),
          tone = FluidTone.Primary,
        )
        FluidListDivider()
        val index = sessions.indexOfFirst { it.session.id == loudness.peakSessionId }
        val where = sessions.getOrNull(index)?.let { sessionTitle(index, it.session.title, it.session.date) }
        FluidListRow(
          title = stringResource(R.string.note_stats_db, peakSpl),
          subtitle = comparisonText(Loudness.comparisonOf(peakSpl)),
          eyebrow = stringResource(R.string.note_stats_peak),
          meta = listOfNotNull(where, Formats.timestamp(loudness.peakAtMs)).joinToString(" · "),
          onClick = { onOpenMoment(loudness.peakSessionId, loudness.peakAtMs) }.takeIf { index >= 0 },
          modifier = Modifier.heightIn(min = 48.dp),
        )
        FluidListDivider()
        FluidListRow(
          title = Formats.durationShort(loudness.quietMs),
          subtitle = stringResource(R.string.note_stats_quiet_detail, Formats.durationShort(loudness.measuredMs)),
          eyebrow = stringResource(R.string.note_stats_quiet),
        )
      }
    }
  }
  if (state.measuring) {
    FluidProgressBar(progress = { state.progress?.let { (it.part - 1 + it.fraction) / it.parts.coerceAtLeast(1) } ?: 0f })
  }
  val skipped = result?.let { it.missingParts + it.failedParts } ?: 0
  if (!state.measuring && skipped > 0) {
    FluidSectionFootnote(text = pluralStringResource(R.plurals.note_stats_skipped, skipped, skipped))
  }
  FluidSectionFootnote(text = stringResource(R.string.note_stats_volume_footnote))
}

@Composable
private fun WordsGroup(spoken: Spoken, personal: Boolean, hasAudio: Boolean) {
  // Le righe che hanno qualcosa da dire, e un filo fra l'una e l'altra.
  val rows = buildList<@Composable () -> Unit> {
    if (hasAudio) {
      add {
        FluidListRow(
          title = pluralStringResource(R.plurals.note_stats_spoken_words, spoken.words, Formats.compact(spoken.words.toLong())),
          subtitle = if (spoken.speechMs > 0) {
            stringResource(R.string.note_stats_speech_time, Formats.durationShort(spoken.speechMs))
          } else {
            stringResource(R.string.note_stats_not_transcribed)
          },
          eyebrow = stringResource(if (personal) R.string.note_stats_said_personal else R.string.note_stats_said),
        )
      }
      spoken.wordsPerMinute?.let { wpm ->
        add {
          FluidListRow(
            title = stringResource(R.string.note_stats_wpm, wpm),
            subtitle = paceText(SpokenStats.paceOf(wpm)),
            eyebrow = stringResource(R.string.note_stats_pace),
          )
        }
      }
      spoken.favorite?.let { favorite ->
        add {
          FluidListRow(
            title = stringResource(R.string.note_stats_quoted_word, favorite.word),
            subtitle = pluralStringResource(R.plurals.note_stats_times, favorite.count, favorite.count),
            eyebrow = stringResource(R.string.note_stats_favorite),
          )
        }
      }
      spoken.filler?.let { filler ->
        add {
          val times = pluralStringResource(R.plurals.note_stats_times, filler.count, filler.count)
          FluidListRow(
            title = stringResource(R.string.note_stats_quoted_word, filler.word),
            subtitle = spoken.fillerEveryMs?.let { stringResource(R.string.note_stats_filler_every, times, fillerInterval(it)) } ?: times,
            eyebrow = stringResource(R.string.note_stats_filler),
          )
        }
      }
    }
    add {
      FluidListRow(
        title = pluralStringResource(R.plurals.note_stats_written_words, spoken.writtenWords, Formats.compact(spoken.writtenWords.toLong())),
        subtitle = writtenVsSpoken(spoken, personal),
        eyebrow = stringResource(R.string.note_stats_written),
      )
    }
  }
  FluidListGroup {
    rows.forEachIndexed { index, row ->
      if (index > 0) FluidListDivider()
      row()
    }
  }
}

/** «Ogni 22 s», «ogni 3 min»: quanto passa, in media, fra un intercalare e l'altro. */
@Composable
private fun fillerInterval(ms: Long): String =
  if (ms < 60_000) stringResource(R.string.note_stats_seconds, (ms / 1000).coerceAtLeast(1))
  else Formats.durationShort(ms)

/** «La lezione ha detto 15 volte tanto»: il paragone che fa ridere, quando c'e' qualcosa da paragonare. */
@Composable
private fun writtenVsSpoken(spoken: Spoken, personal: Boolean): String {
  if (spoken.words == 0) return stringResource(R.string.note_stats_nothing_to_compare)
  if (spoken.writtenWords == 0) return stringResource(if (personal) R.string.note_stats_written_none_personal else R.string.note_stats_written_none)
  val ratio = spoken.words.toDouble() / spoken.writtenWords
  return when {
    ratio >= 2 -> stringResource(if (personal) R.string.note_stats_ratio_personal else R.string.note_stats_ratio, ratio.roundToInt())
    ratio <= 0.5 -> stringResource(R.string.note_stats_ratio_writer)
    else -> stringResource(R.string.note_stats_ratio_even)
  }
}

private fun formatDb(value: Float): String = String.format(java.util.Locale.getDefault(), "%.1f", value)

@Composable
private fun comparisonText(comparison: LoudnessComparison): String = stringResource(
  when (comparison) {
    LoudnessComparison.WHISPER -> R.string.note_stats_like_whisper
    LoudnessComparison.LIBRARY -> R.string.note_stats_like_library
    LoudnessComparison.QUIET_OFFICE -> R.string.note_stats_like_office
    LoudnessComparison.CONVERSATION -> R.string.note_stats_like_conversation
    LoudnessComparison.BREAK_TIME -> R.string.note_stats_like_break
    LoudnessComparison.TRAFFIC -> R.string.note_stats_like_traffic
    LoudnessComparison.BLENDER -> R.string.note_stats_like_blender
    LoudnessComparison.CONCERT -> R.string.note_stats_like_concert
  },
)

@Composable
private fun paceText(pace: SpeakingPace): String = stringResource(
  when (pace) {
    SpeakingPace.CALM -> R.string.note_stats_pace_calm
    SpeakingPace.LECTURE -> R.string.note_stats_pace_lecture
    SpeakingPace.BRISK -> R.string.note_stats_pace_brisk
    SpeakingPace.COMMENTATOR -> R.string.note_stats_pace_commentator
    SpeakingPace.RAPPER -> R.string.note_stats_pace_rapper
  },
)
