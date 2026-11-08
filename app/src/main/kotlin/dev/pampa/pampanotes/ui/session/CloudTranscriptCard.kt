package dev.pampa.pampanotes.ui.session

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import dev.antigravity.fluidengine.ui.fluid.FluidSpokenText
import dev.antigravity.fluidengine.ui.fluid.FluidSpokenWord
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.alignment.AlignmentReadingBlocks
import dev.pampa.pampanotes.ui.common.Formats

@Composable
internal fun CloudTranscriptCard(
  block: AlignmentReadingBlocks.Block,
  isActive: Boolean,
  positionMs: () -> Long,
  onSeek: (Long) -> Unit,
  highlights: List<IntRange> = emptyList(),
  currentHighlight: IntRange? = null,
) {
  var layout by remember(block) { mutableStateOf<TextLayoutResult?>(null) }
  val scheme = MaterialTheme.colorScheme
  val words = remember(block) {
    block.measured.map { FluidSpokenWord(it.textStart, it.textEnd, it.startMs!!, it.endMs!!) }
  }
  FluidCard(highlighted = isActive, onClick = null, animateContent = false) {
    Text(
      text = block.measured.firstOrNull()?.startMs?.let {
        stringResource(R.string.alignment_ui_first_word, Formats.timestamp(it))
      } ?: stringResource(R.string.alignment_ui_no_word_times),
      style = MaterialTheme.typography.labelMedium,
      color = scheme.onSurfaceVariant,
    )
    // FluidSpokenText paints a continuous prefix. Make that pass transparent: sparse evidence must
    // not light up missing words between two measured ones. Paint ONLY the current measured range
    // behind the exact text, reading playback in the draw phase (no per-tick text layout).
    FluidSpokenText(
      text = block.text,
      words = words,
      positionMs = positionMs,
      style = MaterialTheme.typography.bodyLarge,
      spokenColor = Color.Transparent,
      pendingColor = scheme.onSurface,
      onTextLayout = { layout = it },
      modifier = Modifier
        .fillMaxWidth()
        .searchHighlights(
          layout = { layout },
          ranges = highlights,
          current = currentHighlight,
          color = scheme.primary.copy(alpha = 0.20f),
          currentColor = scheme.primary.copy(alpha = 0.45f),
        )
        .drawWithContent {
          val result = layout
          val now = positionMs()
          if (result != null) {
            block.measured.firstOrNull { now >= it.startMs!! && now < it.endMs!! }?.let { word ->
              drawPath(result.getPathForRange(word.textStart, word.textEnd), scheme.primary.copy(alpha = 0.20f))
            }
          }
          drawContent()
        }
        .pointerInput(block, onSeek) {
          detectTapGestures { position ->
            val result = layout ?: return@detectTapGestures
            val offset = result.getOffsetForPosition(position)
            // getOffsetForPosition clamps outside glyphs; a tap in a margin is not word evidence.
            if (offset in block.text.indices && result.getBoundingBox(offset).contains(position)) {
              block.timeAt(offset)?.let(onSeek)
            }
          }
        },
    )
  }
}
