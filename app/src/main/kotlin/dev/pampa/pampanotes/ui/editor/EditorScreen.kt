package dev.pampa.pampanotes.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.TextRange
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
import dev.antigravity.fluidengine.ui.fluid.FluidTextEdit
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

  // Il cursore vive qui: il ViewModel conosce il testo, non dove si sta scrivendo. Il testo del
  // ViewModel vince solo quando cambia per conto suo — il caricamento della nota — e non quando e'
  // l'eco di quello che si e' appena battuto, altrimenti ogni tasto rimetterebbe il cursore in fondo.
  var bodyValue by remember { mutableStateOf(TextFieldValue(body, TextRange(body.length))) }
  var knownBody by remember { mutableStateOf(body) }
  if (body != knownBody) {
    knownBody = body
    if (bodyValue.text != body) bodyValue = TextFieldValue(body, TextRange(body.length))
  }
  val updateBody: (TextFieldValue) -> Unit = { value ->
    bodyValue = value
    if (value.text != knownBody) {
      knownBody = value.text
      onBodyChange(value.text)
    }
  }

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
        MarkdownToolbar(onEdit = { edit -> updateBody(edit(bodyValue)) })
      }
      item {
        FluidTextField(
          value = bodyValue,
          onValueChange = updateBody,
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

/** Un segno Markdown e cosa fa al testo, dal cursore in poi. */
private class MarkdownTool(val label: String, val apply: (TextFieldValue) -> TextFieldValue)

/**
 * I cinque segni che servono davvero mentre si sistema un testo importato. Quelli di riga (titolo,
 * elenco, citazione) vanno a inizio riga e si tolgono con un secondo tocco; quelli di parola
 * (grassetto, codice) avvolgono la selezione.
 */
private val markdownTools = listOf(
  MarkdownTool("##") { FluidTextEdit.toggleLinePrefix(it, "## ") },
  MarkdownTool("-") { FluidTextEdit.toggleLinePrefix(it, "- ") },
  MarkdownTool("**") { FluidTextEdit.wrap(it, "**") },
  MarkdownTool(">") { FluidTextEdit.toggleLinePrefix(it, "> ") },
  MarkdownTool("`") { FluidTextEdit.wrap(it, "`") },
)

@Composable
private fun MarkdownToolbar(onEdit: ((TextFieldValue) -> TextFieldValue) -> Unit) {
  val scroll = rememberScrollState()
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(scroll),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    markdownTools.forEach { tool ->
      FluidChip(label = tool.label, selected = false, onClick = { onEdit(tool.apply) })
    }
  }
}
