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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.fluidReadingWidth
import dev.antigravity.fluidengine.ui.fluid.glassControlSurface
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.player.PlaybackState
import dev.pampa.pampanotes.player.SessionPlayer
import dev.pampa.pampanotes.ui.common.Formats

/**
 * Il lettore, sospeso sopra la trascrizione.
 *
 * Sta nell'overlay della schermata e non nella lista apposta: una lezione dura un'ora e il punto in
 * cui si e' arrivati deve restare visibile mentre si scorre il testo.
 *
 * Due oggetti piccoli, non un blocco: una **capsula** di comandi larga quanto i comandi, e sopra
 * lo **scrubber** come una riga sottile senza niente sotto. Il vetro sta sulla capsula e basta —
 * un pannello a tutta larghezza che rifrange il testo non lo lascia leggere, e i tasti di vetro
 * dentro un pannello di vetro non rifrangono niente. Dentro la capsula i comandi sono icone
 * piatte, e l'unico pieno e' il play, che e' quello che si cerca.
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
    // L'overlay non passa dalla lista, quindi la misura di lettura se la da' da solo: lo scrubber
    // corre sotto le colonne del testo, non da un bordo all'altro del tablet.
    modifier = modifier.fluidReadingWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Scrubber(
      fraction = state.fraction,
      durationMs = state.durationMs,
      onSeek = onSeek,
    )

    Row(
      modifier = Modifier
        .clip(FluidCapsuleShape)
        .glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape, interactive = false)
        .padding(horizontal = 8.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = Formats.timestamp(state.positionMs),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 8.dp, end = 6.dp),
      )
      PlayerButton(
        icon = Icons.Rounded.Replay10,
        label = stringResource(R.string.player_back),
        onClick = { onSkip(-SessionPlayer.SKIP_MS) },
      )
      PlayButton(playing = state.playing, onClick = onPlayPause)
      PlayerButton(
        icon = Icons.Rounded.Forward10,
        label = stringResource(R.string.player_forward),
        onClick = { onSkip(SessionPlayer.SKIP_MS) },
      )
      // La velocita' e' un tasto, non un menu: su una lezione la si cambia spesso e sempre nello
      // stesso verso, e un menu che si apre per quattro voci e' due tocchi invece di uno.
      Text(
        text = stringResource(R.string.player_speed, formatSpeed(state.speed)),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
          .clip(FluidCapsuleShape)
          .fluidPressable(onClick = onCycleSpeed, pressedScale = 0.94f, role = Role.Button)
          .padding(horizontal = 10.dp, vertical = 10.dp),
      )
    }
  }
}

/** Un comando piatto dentro la capsula: l'icona e il suo bersaglio, niente superficie sua. */
@Composable
private fun PlayerButton(icon: ImageVector, label: String, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .size(44.dp)
      .clip(FluidCapsuleShape)
      .fluidPressable(onClick = onClick, pressedScale = 0.88f, role = Role.Button)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
  }
}

/** Il play e' l'unico pieno: nel colore della materia, e' la cosa che il pollice cerca. */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
  val label = stringResource(if (playing) R.string.player_pause else R.string.player_play)
  Box(
    modifier = Modifier
      .size(44.dp)
      .clip(FluidCapsuleShape)
      .background(MaterialTheme.colorScheme.primary)
      .fluidPressable(onClick = onClick, pressedScale = 0.9f, role = Role.Button)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onPrimary,
    )
  }
}

/**
 * La riga su cui si trascina.
 *
 * Senza pallino e senza superficie: il riempimento e' nel colore della materia, la traccia e' un
 * velo. Durante il trascinamento il valore mostrato e' quello del dito, non quello del lettore,
 * altrimenti la riga tornerebbe indietro a ogni battito del cronometro finche' la richiesta di
 * salto non arriva.
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
      .height(24.dp)
      // Per TalkBack e' una barra di avanzamento che si puo' spostare: prima era solo un nome, e
      // chi non vede non poteva saltare da nessuna parte.
      .semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
        setProgress { target ->
          onSeek((target.coerceIn(0f, 1f) * durationMs).toLong())
          true
        }
      }
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
        .height(if (dragging) 6.dp else 3.dp)
        .clip(FluidCapsuleShape)
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
    )
    Box(
      modifier = Modifier
        .fillMaxWidth(shown)
        .height(if (dragging) 6.dp else 3.dp)
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
val PlayerBarHeight = 92.dp
