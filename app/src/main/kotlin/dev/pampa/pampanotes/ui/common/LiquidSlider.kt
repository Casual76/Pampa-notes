/*
 * La maniglia, la copia schiacciata della traccia che rifrange e i numeri che danno forma a tutte e
 * due vengono dal LiquidSlider del catalogo di Kyant0.
 * https://github.com/Kyant0/AndroidLiquidGlass — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Portato e non reinventato, come `FluidSwitch` nell'engine fa col LiquidToggle: lo slider
 * dell'engine (`FluidSlider`) e' una traccia spessa con una lente sempre accesa, che e' un'altra
 * cosa. Qui la maniglia e' una pillola bianca a riposo, e diventa vetro solo mentre il dito la
 * tiene: e' quello che fa sembrare vetro anche lo switch.
 *
 * Quello che e' cambiato, detto dove succede: il colore e' l'accento dell'app (cioe' quello della
 * materia) invece del blu di iOS; ci sono gli scatti (`snap`) con un tocco aptico per ognuno,
 * `enabled` e la fine del gesto (`onValueChangeFinished`), perche' li hanno tutti gli altri
 * controlli; e la pagina sotto non si rifrange, perche' lo sfondo dell'engine non si puo' prendere
 * da fuori (`GlassBackdropState.backdrop` e' interno): la lente mostra la traccia che si apre sotto
 * il dito, che e' la parte che conta.
 *
 * Sta nell'app e non nell'engine perche' l'engine non si tocca da qui; e' scritto con le sole API
 * pubbliche dell'engine, cosi' spostarlo la' e' un copia e incolla.
 */
package dev.pampa.pampanotes.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.glass.backdrop.backdrops.layerBackdrop
import dev.antigravity.fluidengine.ui.glass.backdrop.backdrops.rememberBackdrop
import dev.antigravity.fluidengine.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import dev.antigravity.fluidengine.ui.glass.backdrop.drawBackdrop
import dev.antigravity.fluidengine.ui.glass.backdrop.effects.blur
import dev.antigravity.fluidengine.ui.glass.backdrop.effects.lens
import dev.antigravity.fluidengine.ui.glass.backdrop.highlight.Highlight
import dev.antigravity.fluidengine.ui.glass.backdrop.isRenderEffectSupported
import dev.antigravity.fluidengine.ui.glass.backdrop.shadow.InnerShadow
import dev.antigravity.fluidengine.ui.glass.backdrop.shadow.Shadow
import dev.antigravity.fluidengine.ui.glass.interaction.GlassDragAnimation
import dev.antigravity.fluidengine.ui.haptics.FluidHapticEvent
import dev.antigravity.fluidengine.ui.haptics.LocalFluidHaptics
import kotlinx.coroutines.flow.collectLatest

/**
 * Uno slider di vetro: traccia sottile, riempita con l'accento, e una maniglia che si fa lente
 * mentre la si tiene.
 *
 * @param snap dove il valore si ferma: per uno slider a scatti, l'arrotondamento allo scatto piu'
 *   vicino. Il dito scorre libero, il valore (e il tocco aptico) cambia solo a ogni scatto, e al
 *   rilascio la maniglia va a posarsi sullo scatto.
 * @param onValueChangeFinished alla fine di un trascinamento o di un tocco sulla traccia: e' il
 *   momento di salvare, non a ogni scatto.
 */
@Composable
fun LiquidSlider(
  value: Float,
  onValueChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
  valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
  enabled: Boolean = true,
  snap: ((Float) -> Float)? = null,
  onValueChangeFinished: (() -> Unit)? = null,
  contentDescription: String? = null,
) {
  val scheme = MaterialTheme.colorScheme
  val haptics = LocalFluidHaptics.current
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val dark = scheme.surface.luminance() < 0.5f
  val accentColor = scheme.primary
  // Il grigio neutro della sorgente, lo stesso di `FluidSwitch`: la parte vuota della traccia non
  // e' dell'accento, ed e' proprio quella che dice «fin qui».
  val trackColor = if (dark) Color(0xFF787880).copy(alpha = 0.36f) else Color(0xFF787878).copy(alpha = 0.2f)

  val currentValue by rememberUpdatedState(value)
  val currentOnValueChange by rememberUpdatedState(onValueChange)
  val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
  val currentSnap by rememberUpdatedState(snap)

  fun snapped(raw: Float): Float = (currentSnap?.invoke(raw) ?: raw).coerceIn(valueRange)

  val trackBackdrop = rememberLayerBackdrop()

  BoxWithConstraints(
    modifier
      .fillMaxWidth()
      // Alta come un dito: la traccia e' di 6 dp, ma si prende da tutta la riga.
      .height(ThumbHeight * 2)
      .alpha(if (enabled) 1f else 0.5f)
      .semantics {
        contentDescription?.let { this.contentDescription = it }
        progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
        if (enabled) {
          setProgress { target ->
            currentOnValueChange(snapped(target))
            currentOnFinished?.invoke()
            true
          }
        } else {
          disabled()
        }
      }
      // La maniglia sporge di un quarto oltre le estremita' della traccia, come nell'originale: lo
      // spazio glielo si lascia qui, o `alpha` (che taglia ai bordi) la mozzava a fine corsa.
      .padding(horizontal = ThumbWidth / 4),
    contentAlignment = Alignment.CenterStart,
  ) {
    val trackWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    // Dove sta il dito, libero; il valore che esce e' lo scatto piu' vicino a questo.
    var raw by remember { mutableFloatStateOf(value) }

    fun emit(next: Float) {
      val stop = snapped(next)
      if (stop != currentValue) {
        haptics.play(FluidHapticEvent.Tick)
        currentOnValueChange(stop)
      }
    }

    val drag = remember(animationScope, trackWidth) {
      GlassDragAnimation(
        animationScope = animationScope,
        initialValue = value,
        valueRange = valueRange,
        visibilityThreshold = (valueRange.endInclusive - valueRange.start) / 1000f,
        initialScale = 1f,
        pressedScale = 1.5f,
        onDragStarted = {
          didDrag = false
          raw = currentValue
        },
        onDragStopped = {
          if (didDrag) {
            // Al rilascio la maniglia si posa sullo scatto: e' quello il valore, non il punto in
            // cui il dito si e' alzato.
            animateToValue(snapped(raw))
            currentOnFinished?.invoke()
          }
        },
        onDrag = { _, dragAmount ->
          if (!didDrag) didDrag = dragAmount.x != 0f
          val delta = (valueRange.endInclusive - valueRange.start) * (dragAmount.x / trackWidth)
          raw = (if (isLtr) raw + delta else raw - delta).coerceIn(valueRange)
          updateValue(raw)
          emit(raw)
        },
      )
    }

    // Il valore che arriva da fuori (un ripristino, «Automatico» che mostra la scelta del computer)
    // sposta la maniglia; durante un trascinamento comanda il dito.
    LaunchedEffect(drag, reducedMotion) {
      snapshotFlow { currentValue }.collectLatest { outside ->
        if (drag.pressProgress > 0f && didDrag) return@collectLatest
        if (snapped(drag.targetValue) != outside) {
          raw = outside
          if (reducedMotion) drag.snapToValue(outside) else drag.animateToValue(outside)
        }
      }
    }

    Box(Modifier.layerBackdrop(trackBackdrop)) {
      Box(
        Modifier
          .clip(FluidCapsuleShape)
          .drawBehind { drawRect(trackColor) }
          .then(
            if (enabled) {
              Modifier.pointerInput(animationScope, trackWidth) {
                detectTapGestures { position ->
                  val fraction = (position.x / trackWidth).fastCoerceIn(0f, 1f)
                  val span = valueRange.endInclusive - valueRange.start
                  val target = snapped(if (isLtr) valueRange.start + span * fraction else valueRange.endInclusive - span * fraction)
                  raw = target
                  drag.animateToValue(target)
                  emit(target)
                  currentOnFinished?.invoke()
                }
              }
            } else {
              Modifier
            },
          )
          .height(TrackHeight)
          .fillMaxWidth(),
      )
      Box(
        Modifier
          .clip(FluidCapsuleShape)
          .drawBehind { drawRect(accentColor) }
          .height(TrackHeight)
          .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val width = (constraints.maxWidth * drag.progress).fastRoundToInt()
            layout(width, placeable.height) { placeable.place(0, 0) }
          },
      )
    }

    Box(
      Modifier
        .graphicsLayer {
          translationX =
            (-size.width / 2f + trackWidth * drag.progress)
              .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (isLtr) 1f else -1f
        }
        .then(if (enabled) drag.modifier else Modifier)
        .then(
          if (isRenderEffectSupported()) {
            Modifier.drawBackdrop(
              // La traccia, schiacciata: a riposo non si vede, sotto il dito si apre tutta.
              backdrop = rememberBackdrop(trackBackdrop) { drawTrack ->
                val progress = drag.pressProgress
                scale(lerp(2f / 3f, 1f, progress), lerp(0f, 1f, progress)) { drawTrack() }
              },
              shape = { FluidCapsuleShape },
              effects = {
                val progress = drag.pressProgress
                blur(8f.dp.toPx() * (1f - progress))
                lens(10f.dp.toPx() * progress, 14f.dp.toPx() * progress, chromaticAberration = true)
              },
              highlight = {
                val progress = drag.pressProgress
                Highlight.Ambient.copy(
                  width = Highlight.Ambient.width / 1.5f,
                  blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                  alpha = progress,
                )
              },
              shadow = { Shadow(radius = 4f.dp, color = Color.Black.copy(alpha = 0.05f)) },
              innerShadow = {
                val progress = drag.pressProgress
                InnerShadow(radius = 4f.dp * progress, alpha = progress)
              },
              layerBlock = {
                scaleX = drag.scaleX
                scaleY = drag.scaleY
                val velocity = drag.velocity / 10f
                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
              },
              // Bianca a riposo, sparita sotto il dito: e' questo bianco che la lente sostituisce.
              onDrawSurface = { drawRect(Color.White.copy(alpha = 1f - drag.pressProgress)) },
            )
          } else {
            // Prima di Android 12 non c'e' vetro da fare: una pillola bianca, come lo switch.
            Modifier
              .shadow(2.dp, FluidCapsuleShape)
              .clip(FluidCapsuleShape)
              .drawBehind { drawRect(Color.White) }
          },
        )
        .size(ThumbWidth, ThumbHeight),
    )
  }
}

private val TrackHeight = 6.dp
private val ThumbWidth = 40.dp
private val ThumbHeight = 24.dp
