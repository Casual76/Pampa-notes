package dev.pampa.pampanotes.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidGlassIconButton
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassControlSurface
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.player.PlaybackState
import dev.pampa.pampanotes.player.SessionPlayer
import dev.pampa.pampanotes.ui.common.Formats

/**
 * Il lettore, sospeso sopra la trascrizione.
 *
 * Sta nell'overlay della schermata e non nella lista apposta: una lezione dura un'ora e il punto in
 * cui si e' arrivati deve restare visibile mentre si scorre il testo. Rifrange quello che gli passa
 * sotto, quindi non copre: si legge il paragrafo che ci sta dietro.
 */
@Composable
fun PlayerBar(
  state: PlaybackState,
  backdrop: GlassBackdropState,
  onPlayPause: () -> Unit,
  onSkip: (Long) -> Unit,
  onSeek: (Long) -> Unit,
  onCycleSpeed: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .glassControlSurface(backdrop = backdrop, shape = ContinuousCornerShape(FluidRadius.Group), interactive = false)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Scrubber(
      fraction = state.fraction,
      durationMs = state.durationMs,
      onSeek = onSeek,
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = Formats.timestamp(state.positionMs),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FluidGlassIconButton(
          onClick = { onSkip(-SessionPlayer.SKIP_MS) },
          backdrop = backdrop,
        ) {
          Icon(
            imageVector = Icons.Rounded.Replay10,
            contentDescription = stringResource(R.string.player_back),
          )
        }
        FluidGlassIconButton(
          onClick = onPlayPause,
          backdrop = backdrop,
          selected = state.playing,
        ) {
          Icon(
            imageVector = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = stringResource(if (state.playing) R.string.player_pause else R.string.player_play),
          )
        }
        FluidGlassIconButton(
          onClick = { onSkip(SessionPlayer.SKIP_MS) },
          backdrop = backdrop,
        ) {
          Icon(
            imageVector = Icons.Rounded.Forward10,
            contentDescription = stringResource(R.string.player_forward),
          )
        }
      }
      // La velocita' e' un tasto, non un menu: su una lezione la si cambia spesso e sempre nello
      // stesso verso, e un menu che si apre per quattro voci e' due tocchi invece di uno.
      Box(
        modifier = Modifier
          .clip(FluidCapsuleShape)
          .glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape)
          .padding(horizontal = 10.dp, vertical = 6.dp),
      ) {
        Text(
          text = stringResource(R.string.player_speed, formatSpeed(state.speed)),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.pointerInput(Unit) { detectTapGestures { onCycleSpeed() } },
        )
      }
    }
  }
}

/**
 * La barra su cui si trascina.
 *
 * Senza pallino: durante il trascinamento il valore mostrato e' quello del dito, non quello del
 * lettore, altrimenti la barra tornerebbe indietro a ogni battito del cronometro finche' la
 * richiesta di salto non arriva.
 */
@Composable
private fun Scrubber(
  fraction: Float,
  durationMs: Long,
  onSeek: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  var width by remember { mutableFloatStateOf(1f) }
  var dragging by remember { mutableStateOf(false) }
  var dragFraction by remember { mutableFloatStateOf(0f) }
  val shown = if (dragging) dragFraction else fraction
  val label = stringResource(R.string.player_scrubber)

  fun commit(x: Float) {
    val value = (x / width).coerceIn(0f, 1f)
    dragFraction = value
    onSeek((value * durationMs).toLong())
  }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(28.dp)
      .semantics { contentDescription = label }
      .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
      .pointerInput(durationMs) {
        detectTapGestures { offset -> commit(offset.x) }
      }
      .pointerInput(durationMs) {
        detectHorizontalDragGestures(
          onDragStart = { offset ->
            dragging = true
            dragFraction = (offset.x / width).coerceIn(0f, 1f)
          },
          onDragEnd = {
            commit(dragFraction * width)
            dragging = false
          },
          onDragCancel = { dragging = false },
        ) { change, _ ->
          dragFraction = (change.position.x / width).coerceIn(0f, 1f)
        }
      },
    contentAlignment = Alignment.CenterStart,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(if (dragging) 8.dp else 5.dp)
        .clip(FluidCapsuleShape)
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
    )
    Box(
      modifier = Modifier
        .fillMaxWidth(shown)
        .height(if (dragging) 8.dp else 5.dp)
        .clip(FluidCapsuleShape)
        .background(MaterialTheme.colorScheme.primary),
    )
  }
}

/**
 * "1", "1,25", "1,5" — e "1.25" dove il separatore e' il punto.
 *
 * Il separatore lo mette la lingua del telefono: scriverlo a mano vorrebbe dire una virgola in una
 * app inglese, che e' il genere di dettaglio per cui un'app si legge come tradotta male.
 */
private fun formatSpeed(speed: Float): String {
  val format = java.text.DecimalFormat.getNumberInstance(java.util.Locale.getDefault()).apply {
    maximumFractionDigits = 2
    minimumFractionDigits = 0
  }
  return format.format(speed)
}

/** L'altezza che il lettore occupa: quanto spazio la lista deve lasciarsi sotto per non finirci dietro. */
val PlayerBarHeight = 116.dp
