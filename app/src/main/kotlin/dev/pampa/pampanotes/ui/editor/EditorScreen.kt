package dev.pampa.pampanotes.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antigravity.fluidengine.ui.fluid.FluidAmbient
import dev.antigravity.fluidengine.ui.fluid.FluidBarAction
import dev.antigravity.fluidengine.ui.fluid.FluidHeroMotif
import dev.antigravity.fluidengine.ui.fluid.FluidHeroTone
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.antigravity.fluidengine.ui.theme.FluidCard
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.ui.common.MarkdownText

@Composable
fun EditorRoute(
  onDone: () -> Unit,
  viewModel: EditorViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  EditorScreen(
    title = state.title,
    body = state.body,
    saving = state.saving,
    onTitleChange = viewModel::setTitle,
    onBodyChange = viewModel::setBody,
    onDone = {
      viewModel.saveNow()
      onDone()
    },
  )
}

@Composable
private fun EditorScreen(
  title: String,
  body: String,
  saving: Boolean,
  onTitleChange: (String) -> Unit,
  onBodyChange: (String) -> Unit,
  onDone: () -> Unit,
) {
  var preview by remember { mutableStateOf(false) }
  val previewLabel = stringResource(R.string.editor_preview)
  val writeLabel = stringResource(R.string.editor_write)

  FluidScreen(
    title = stringResource(R.string.editor_title),
    subtitle = if (saving) stringResource(R.string.editor_saving) else stringResource(R.string.editor_saved),
    onBack = onDone,
    ambient = FluidAmbient(tone = FluidHeroTone.Secondary, motif = FluidHeroMotif.Glow),
    actions = {
      FluidBarAction(
        icon = Icons.Rounded.Check,
        contentDescription = stringResource(R.string.action_save),
        onClick = onDone,
      )
    },
  ) {
    item {
      FluidTextField(
        value = title,
        onValueChange = onTitleChange,
        label = stringResource(R.string.editor_note_title),
        modifier = Modifier.fillMaxWidth(),
      )
    }

    item {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FluidChip(label = writeLabel, selected = !preview, onClick = { preview = false })
        FluidChip(label = previewLabel, selected = preview, onClick = { preview = true })
      }
    }

    if (preview) {
      item {
        FluidCard(glass = true) {
          if (body.isBlank()) {
            FluidSectionFootnote(text = stringResource(R.string.editor_preview_empty))
          } else {
            MarkdownText(markdown = body, modifier = Modifier.fillMaxWidth())
          }
        }
      }
    } else {
      item {
        MarkdownToolbar(
          onInsert = { snippet -> onBodyChange(insertSnippet(body, snippet)) },
        )
      }
      item {
        FluidTextField(
          value = body,
          onValueChange = onBodyChange,
          placeholder = stringResource(R.string.editor_body_placeholder),
          singleLine = false,
          minLines = 16,
          showClearButton = false,
          modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
        )
      }
      item { FluidSectionFootnote(text = stringResource(R.string.editor_markdown_hint)) }
    }
  }
}

/** I quattro segni che servono davvero mentre si sistema un testo importato. */
@Composable
private fun MarkdownToolbar(onInsert: (String) -> Unit) {
  val scroll = rememberScrollState()
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(scroll),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    listOf("## ", "- ", "**", "> ", "`").forEach { snippet ->
      FluidChip(label = snippet.trim().ifEmpty { snippet }, selected = false, onClick = { onInsert(snippet) })
    }
  }
}

/**
 * Aggiunge il segno in fondo, su una riga sua quando il segno vale per una riga intera.
 *
 * Non c'e' un cursore da cui partire: `FluidTextField` espone una stringa, non un
 * `TextFieldValue`, quindi l'inserimento a meta' testo non e' possibile senza cambiare il
 * componente dell'engine. In fondo e' dove si scrive comunque.
 */
private fun insertSnippet(body: String, snippet: String): String {
  val lineMarker = snippet.endsWith(" ")
  return when {
    body.isEmpty() -> snippet
    lineMarker && body.endsWith("\n") -> body + snippet
    lineMarker -> body + "\n" + snippet
    else -> body + snippet
  }
}
