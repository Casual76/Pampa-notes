package dev.pampa.pampanotes.ui.session

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleAction
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidFloatingCapsule
import dev.antigravity.fluidengine.ui.fluid.FluidScrubber
import dev.antigravity.fluidengine.ui.fluid.FluidScrubbing
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.fluidReadingWidth
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
 * Due oggetti, e sono dell'engine: lo **scrubber** (`FluidScrubber`: la maniglia a goccia dello slider,
 * il tocco salta, il trascinamento e' del dito e non del lettore, i capitoli come punti sulla pista) e
 * la **capsula** di comandi (`FluidFloatingCapsule`: una lastra di vetro sola, la stessa della barra
 * delle schede). Dentro la capsula i comandi sono icone piatte che crescono sotto il dito, e l'unico
 * pieno e' il play, che e' quello che si cerca.
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
  /** Dove cominciano i capitoli, in millisecondi dall'inizio: punti sulla pista (vedi [chapterMarks]). */
  marks: List<Long> = emptyList(),
) {
  val resources = LocalContext.current.resources
  // «1:05 di 3:20» nella lingua dell'app: quello dell'engine e' scritto in italiano.
  val spoken = remember(resources) {
    { position: Float, range: ClosedFloatingPointRange<Float> ->
      resources.getString(
        R.string.player_scrubber_spoken,
        FluidScrubbing.Elapsed(position, range),
        FluidScrubbing.Total(position, range),
      )
    }
  }
  Column(
    // L'overlay non passa dalla lista, quindi la misura di lettura se la da' da solo: lo scrubber
    // corre sotto le colonne del testo, non da un bordo all'altro del tablet.
    modifier = modifier.fluidReadingWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FluidScrubber(
        position = { state.positionMs.toFloat() },
        onSeek = { onSeek(it.toLong()) },
        modifier = Modifier.weight(1f),
        valueRange = 0f..state.durationMs.toFloat(),
        enabled = state.durationMs > 0,
        marks = marks.map { it.toFloat() },
        contentDescription = stringResource(R.string.player_scrubber),
        valueDescription = spoken,
        // Una freccia, o la tastiera, sposta di dieci secondi: come i tasti della capsula.
        keyStep = SessionPlayer.SKIP_MS.toFloat(),
        stepBackLabel = stringResource(R.string.player_back),
        stepForwardLabel = stringResource(R.string.player_forward),
        backdrop = backdrop,
      )
      EndTime(positionMs = state.positionMs, durationMs = state.durationMs)
    }

    FluidFloatingCapsule(backdrop = backdrop) {
      TimeOrNotice(positionMs = state.positionMs, notice = notice, skippingSilence = skippingSilence)
      // Tenuti premuti saltano cinque minuti: in una registrazione di ore quindici secondi alla volta
      // non portano da nessuna parte, e un altro tasto nella capsula non ci sta.
      SkipAction(
        icon = Icons.Rounded.Replay10,
        label = stringResource(R.string.player_back),
        longLabel = stringResource(R.string.player_back_long),
        onClick = { onSkip(-SessionPlayer.SKIP_MS) },
        onLongClick = { onSkip(-LONG_SKIP_MS) },
      )
      PlayButton(playing = state.playing, onClick = onPlayPause)
      SkipAction(
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
 * I capitoli come punti sulla pista dello scrubber: gli inizi, senza lo zero (c'e' gia' il bordo) e
 * solo finche' si contano. Su una registrazione di diciannove ore i capitoli sono centinaia, e cento
 * punti da 5 dp su una pista da 300 sono una riga continua: meglio nessun punto di un tratteggio.
 */
fun chapterMarks(startsMs: List<Long>, durationMs: Long): List<Long> {
  if (durationMs <= 0L) return emptyList()
  val marks = startsMs.filter { it in 1 until durationMs }.distinct()
  return if (marks.size <= MAX_CHAPTER_MARKS) marks else emptyList()
}

/** Oltre questo numero di capitoli la pista non li disegna. */
const val MAX_CHAPTER_MARKS = 40

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
 * Un salto di dieci secondi, e di cinque minuti tenendo premuto: l'azione piatta della capsula. Il
 * salto lungo e' anche un'azione di TalkBack, con il suo nome: tenere premuto non si scopre con la voce.
 */
@Composable
private fun SkipAction(icon: ImageVector, label: String, longLabel: String, onClick: () -> Unit, onLongClick: () -> Unit) {
  FluidCapsuleAction(
    icon = icon,
    contentDescription = label,
    onClick = onClick,
    onLongClick = onLongClick,
    tint = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.semantics { customActions = listOf(CustomAccessibilityAction(longLabel) { onLongClick(); true }) },
  )
}

/**
 * In fondo allo scrubber, quanto manca («−48:10»), o — con un tocco — quanto dura tutta la
 * registrazione. In una lezione da un'ora il punto in cui si e' arrivati dice poco senza la fine, e
 * in una registrazione di diciannove ore dice ancora meno.
 *
 * Un testo toccabile a parte e non l'`endLabel` dello scrubber: quello non si tocca.
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

/**
 * Il play e' l'unico pieno: nel colore della materia, e' la cosa che il pollice cerca. Dentro il suo
 * bersaglio da 48 dp, il disco e' da 44.
 */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
  val label = stringResource(if (playing) R.string.player_pause else R.string.player_play)
  Box(
    modifier = Modifier
      .size(MinTouchTarget)
      .fluidPressable(onClick = onClick, pressedScale = 0.9f, role = Role.Button)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier
        .size(PlayDiscSize)
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

/**
 * L'altezza che il lettore occupa: quanto spazio la lista deve lasciarsi sotto per non finirci dietro.
 * Lo scrubber (48 dp, la riga del dito) piu' la capsula (60 dp, come la barra delle schede).
 */
val PlayerBarHeight = 112.dp

/** Il bersaglio minimo di un tocco, come chiede Android: 48 dp per lato. */
internal val MinTouchTarget = 48.dp

/** Il disco del play, dentro il suo bersaglio. */
private val PlayDiscSize = 44.dp

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

/** Il salto lungo, tenendo premuti i tasti dei secondi. */
private const val LONG_SKIP_MS = 5 * 60_000L
