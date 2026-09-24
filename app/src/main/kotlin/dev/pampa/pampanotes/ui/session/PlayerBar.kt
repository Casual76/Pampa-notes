package dev.pampa.pampanotes.ui.session

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
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
  /** «Saltati 16 min», per un attimo al posto del cronometro: vedi [TimeOrNotice]. */
  notice: String? = null,
  /** «Salta i silenzi» e' acceso: un segno piccolo accanto al tempo, finche' resta acceso. */
  skippingSilence: Boolean = false,
) {
  Column(
    // L'overlay non passa dalla lista, quindi la misura di lettura se la da' da solo: lo scrubber
    // corre sotto le colonne del testo, non da un bordo all'altro del tablet.
    modifier = modifier.fluidReadingWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Scrubber(
        fraction = state.fraction,
        durationMs = state.durationMs,
        onSeek = onSeek,
        modifier = Modifier.weight(1f),
      )
      EndTime(positionMs = state.positionMs, durationMs = state.durationMs)
    }

    Row(
      modifier = Modifier
        .clip(FluidCapsuleShape)
        .glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape, interactive = false)
        .padding(horizontal = 8.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TimeOrNotice(positionMs = state.positionMs, notice = notice, skippingSilence = skippingSilence)
      // Tenuti premuti saltano cinque minuti: in una registrazione di ore quindici secondi alla volta
      // non portano da nessuna parte, e un altro tasto nella capsula non ci sta.
      PlayerButton(
        icon = Icons.Rounded.Replay10,
        label = stringResource(R.string.player_back),
        longLabel = stringResource(R.string.player_back_long),
        onClick = { onSkip(-SessionPlayer.SKIP_MS) },
        onLongClick = { onSkip(-LONG_SKIP_MS) },
      )
      PlayButton(playing = state.playing, onClick = onPlayPause)
      PlayerButton(
        icon = Icons.Rounded.Forward10,
        label = stringResource(R.string.player_forward),
        longLabel = stringResource(R.string.player_forward_long),
        onClick = { onSkip(SessionPlayer.SKIP_MS) },
        onLongClick = { onSkip(LONG_SKIP_MS) },
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

/**
 * Il cronometro, o per due secondi e mezzo quanto silenzio si e' appena saltato.
 *
 * Nel posto del tempo perche' e' il tempo che e' appena cambiato: chi guarda il lettore vede il
 * minuto saltare avanti e, nello stesso punto, perche'. Niente pannelli ne' notifiche — un salto ogni
 * dieci minuti di ascolto non merita di coprire il testo — e nel colore della materia, cosi' non si
 * scambia per un orario. TalkBack lo legge da se' (regione viva).
 */
@Composable
private fun TimeOrNotice(positionMs: Long, notice: String?, skippingSilence: Boolean) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    // «Salta i silenzi» acceso si vede anche quando non sta saltando: l'interruttore sta nel menu, e
    // senza un segno qui chi l'aveva acceso ieri non capiva perche' il minuto saltava avanti. Piccolo
    // e nel colore della materia, come l'avviso del salto che prende il posto del tempo.
    if (skippingSilence) {
      Icon(
        imageVector = Icons.Rounded.FastForward,
        contentDescription = stringResource(R.string.player_skipping_silence),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp).size(16.dp),
      )
    }
    Crossfade(targetState = notice, label = "skip-notice") { shown ->
      Text(
        text = shown ?: Formats.timestamp(positionMs),
        style = MaterialTheme.typography.labelLarge,
        color = if (shown != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier
          .padding(start = if (skippingSilence) 4.dp else 8.dp, end = 6.dp)
          .then(if (shown != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
      )
    }
  }
}

/**
 * Un comando piatto dentro la capsula: l'icona e il suo bersaglio, niente superficie sua. Il salto
 * lungo ([onLongClick]) e' anche un'azione di TalkBack, con il suo nome: tenere premuto non si scopre
 * con la voce.
 */
@Composable
private fun PlayerButton(icon: ImageVector, label: String, longLabel: String, onClick: () -> Unit, onLongClick: () -> Unit) {
  Box(
    modifier = Modifier
      .touchTarget(PlayerButtonSize)
      .fluidPressable(onClick = onClick, onLongClick = onLongClick, pressedScale = 0.88f, role = Role.Button)
      .semantics {
        contentDescription = label
        customActions = listOf(CustomAccessibilityAction(longLabel) { onLongClick(); true })
      }
      .padding((MinTouchTarget - PlayerButtonSize) / 2)
      .clip(FluidCapsuleShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
  }
}

/**
 * In fondo allo scrubber, quanto manca («−48:10»), o — con un tocco — quanto dura tutta la
 * registrazione. In una lezione da un'ora il punto in cui si e' arrivati dice poco senza la fine, e
 * in una registrazione di diciannove ore dice ancora meno.
 */
@Composable
private fun EndTime(positionMs: Long, durationMs: Long) {
  var showTotal by rememberSaveable { mutableStateOf(false) }
  val remaining = (durationMs - positionMs).coerceAtLeast(0L)
  val text = if (showTotal) Formats.timestamp(durationMs) else stringResource(R.string.player_remaining, Formats.timestamp(remaining))
  val description = if (showTotal) {
    stringResource(R.string.player_time_total, Formats.duration(durationMs))
  } else {
    stringResource(R.string.player_time_remaining, Formats.duration(remaining))
  }
  val toggleLabel = stringResource(R.string.player_time_toggle)
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    // Alto un dito per il tocco, ma nella riga dello scrubber occupa quanto il testo: vedi
    // [touchHeight].
    modifier = Modifier
      .touchHeight()
      .fluidPressable(onClick = { showTotal = !showTotal }, pressedScale = 0.94f, role = Role.Button)
      .semantics {
        contentDescription = description
        onClick(label = toggleLabel) { showTotal = !showTotal; true }
      }
      .wrapContentHeight()
      .clip(FluidCapsuleShape)
      .padding(horizontal = 6.dp, vertical = 4.dp),
  )
}

/** Il play e' l'unico pieno: nel colore della materia, e' la cosa che il pollice cerca. */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
  val label = stringResource(if (playing) R.string.player_pause else R.string.player_play)
  Box(
    modifier = Modifier
      .touchTarget(PlayerButtonSize)
      .fluidPressable(onClick = onClick, pressedScale = 0.9f, role = Role.Button)
      .semantics { contentDescription = label }
      .padding((MinTouchTarget - PlayerButtonSize) / 2)
      .clip(FluidCapsuleShape)
      .background(MaterialTheme.colorScheme.primary),
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
      // Il bersaglio e' alto un dito (48 dp) ma nella colonna del lettore ne occupa 24: la riga
      // sottile resta dov'era, la capsula sotto non si sposta, e il pollice non deve centrare 3 dp.
      // La parte che sborda sta sopra (sul testo, che il lettore copre gia') e sotto (sulla capsula,
      // che disegnata dopo prende i suoi tocchi per prima).
      .layout { measurable, constraints ->
        val touch = ScrubberTouchHeight.roundToPx()
        val shown = ScrubberHeight.roundToPx()
        val placeable = measurable.measure(constraints.copy(minHeight = touch, maxHeight = touch))
        layout(placeable.width, shown) { placeable.place(0, (shown - touch) / 2) }
      }
      .fillMaxWidth()
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

/** Quanto si vede di un tasto della capsula; il dito ne prende [MinTouchTarget]. */
private val PlayerButtonSize = 44.dp

/** Il bersaglio minimo di un tocco, come chiede Android: 48 dp per lato. */
internal val MinTouchTarget = 48.dp

/**
 * Un tasto che occupa [visual] per lato ma prende il dito su [MinTouchTarget]: i modificatori che
 * seguono vedono 48 dp, il layout intorno ne vede [visual]. Cosi' la capsula resta della sua misura
 * e il pollice non deve centrare un bersaglio di 40: il bordo in piu' sborda di qualche dp tutto
 * intorno, sulla capsula stessa. Chi lo usa rimette il disegno a [visual] con un `padding` dopo il
 * tocco, come fa lo scrubber con la sua riga sottile.
 */
internal fun Modifier.touchTarget(visual: Dp): Modifier = layout { measurable, _ ->
  val touch = MinTouchTarget.roundToPx()
  val shown = visual.roundToPx()
  val placeable = measurable.measure(Constraints.fixed(touch, touch))
  layout(shown, shown) { placeable.place((shown - touch) / 2, (shown - touch) / 2) }
}

/**
 * Lo stesso per un testo toccabile: alto [MinTouchTarget] per il dito, e nel layout alto quanto il
 * testo. Chi lo usa mette `wrapContentHeight()` dopo il tocco, cosi' il testo resta centrato.
 */
internal fun Modifier.touchHeight(): Modifier = layout { measurable, constraints ->
  val touch = MinTouchTarget.roundToPx()
  val placeable = measurable.measure(constraints.copy(minHeight = touch, maxHeight = maxOf(touch, constraints.maxHeight)))
  val shown = measurable.minIntrinsicHeight(placeable.width).coerceIn(0, placeable.height)
  layout(placeable.width, shown) { placeable.place(0, (shown - placeable.height) / 2) }
}

/** Lo spazio che lo scrubber occupa nella colonna, e quello in cui prende il dito. */
private val ScrubberHeight = 24.dp
private val ScrubberTouchHeight = 48.dp

/** Il salto lungo, tenendo premuti i tasti dei secondi. */
private const val LONG_SKIP_MS = 5 * 60_000L
