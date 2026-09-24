package dev.pampa.pampanotes.ui.session

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.fluid.fluidExpandOrigin
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidTextField
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.transcription.VoiceNames
import dev.pampa.pampanotes.ui.common.SheetScaffold

/**
 * Una voce della sessione, com'e' a schermo: la chiave a cui si attacca il nome, il numero di
 * «Voce N» e il nome dato, se c'e'.
 */
data class VoiceLabel(val key: String, val number: Int, val name: String?)

/**
 * «0:42 · Marco»: il tempo del paragrafo e la voce, nella riga piccola in cima alla card.
 *
 * Due nodi per TalkBack, non uno: il tempo si legge («Al minuto 0:42»), la voce e' un tasto che dice
 * cosa fa («Marco, tasto, Dai un nome alla voce»). Il tocco sta sulla sola voce, alto un dito
 * ([touchHeight]) ma nel layout alto quanto il testo, cosi' la card non cresce; il bordo in piu'
 * sborda sopra, nel margine della card — sotto c'e' il testo, disegnato dopo, che vince il tocco e
 * resta quello che fa saltare il lettore.
 */
@Composable
internal fun VoiceTimeLabel(
  time: String,
  voice: VoiceLabel,
  color: Color,
  onVoiceClick: ((VoiceLabel, Rect?) -> Unit)?,
) {
  val atLabel = stringResource(R.string.session_at, time)
  val shown = voice.name ?: stringResource(R.string.session_voice, voice.number)
  val renameAction = stringResource(R.string.session_voice_rename_action)
  var bounds by remember { mutableStateOf<Rect?>(null) }
  val open = { onVoiceClick?.invoke(voice, bounds) }

  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = "$time ·",
      style = MaterialTheme.typography.labelMedium,
      color = color,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.semantics { contentDescription = atLabel },
    )
    Text(
      text = shown,
      style = MaterialTheme.typography.labelMedium,
      color = color,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier
        .fluidExpandOrigin(onMeasured = { bounds = it })
        .touchHeight()
        .fluidPressable(onClick = { open() }, enabled = onVoiceClick != null, pressedScale = 0.94f, role = Role.Button)
        .semantics {
          contentDescription = shown
          if (onVoiceClick != null) onClick(label = renameAction) { open(); true }
        }
        .wrapContentHeight()
        .clip(FluidCapsuleShape)
        .padding(horizontal = 6.dp, vertical = 2.dp),
    )
  }
}

/**
 * «Chi e' Voce 2?»: il nome di una voce separata dal computer.
 *
 * Un campo solo, e sotto i nomi gia' usati nella nota — di solito sono le stesse persone, e
 * riscriverli a ogni sessione e' il modo di ritrovarsi «Marco» in una e «marco» nell'altra. Il nome
 * vale per questa sessione ([VoiceNames]): l'etichetta del computer non dice niente fuori dalla
 * separazione che l'ha data. «Torna a Voce 2» toglie il nome.
 */
@Composable
fun VoiceRenameSheet(
  voice: VoiceLabel,
  /** I nomi da proporre (vedi [VoiceNames.suggestions]), gia' senza quello che la voce ha. */
  suggestions: List<String>,
  onDismiss: () -> Unit,
  /** Il nome nuovo, o null per tornare a «Voce N». */
  onConfirm: (String?) -> Unit,
  /** L'etichetta da cui nasce: il pannello si apre su di lei. */
  origin: () -> Rect? = { null },
) {
  var text by remember(voice.key) { mutableStateOf(voice.name.orEmpty()) }
  val numbered = stringResource(R.string.session_voice, voice.number)

  fun save() = onConfirm(VoiceNames.normalize(text))

  FluidGlassModalPortal(
    visible = true,
    onDismissRequest = onDismiss,
    // Un campo e un tasto: un pop-up sull'etichetta, non un foglio che copre la lezione.
    presentation = FluidGlassModalPresentation.Popover,
    origin = origin,
    paneTitle = stringResource(R.string.session_voice_rename_title, numbered),
  ) {
    SheetScaffold(
      insetBottom = false,
      actions = {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          if (voice.name != null) {
            FluidButton(
              text = stringResource(R.string.session_voice_reset, numbered),
              onClick = { onConfirm(null) },
              style = FluidButtonStyle.Plain,
              modifier = Modifier.weight(1f),
              fillWidth = true,
            )
          } else {
            FluidButton(
              text = stringResource(R.string.action_cancel),
              onClick = onDismiss,
              style = FluidButtonStyle.Plain,
              modifier = Modifier.weight(1f),
              fillWidth = true,
            )
          }
          FluidButton(
            text = stringResource(R.string.action_save),
            onClick = ::save,
            // Un nome vuoto non e' un nome: per tornare a «Voce 2» c'e' il tasto accanto.
            enabled = VoiceNames.normalize(text) != null,
            modifier = Modifier.weight(1f),
            fillWidth = true,
          )
        }
      },
    ) {
      FluidTextField(
        value = text,
        // Il tetto si tiene mentre si scrive, non al salvataggio: un nome tagliato di nascosto e'
        // un nome diverso da quello che si e' visto.
        onValueChange = { typed -> if (typed.codePointCount(0, typed.length) <= VoiceNames.MAX_LENGTH) text = typed },
        label = stringResource(R.string.session_voice_name_label),
        placeholder = stringResource(R.string.session_voice_name_placeholder),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (VoiceNames.normalize(text) != null) save() }),
        modifier = Modifier.fillMaxWidth(),
      )

      if (suggestions.isNotEmpty()) {
        FluidSectionFootnote(text = stringResource(R.string.session_voice_suggestions))
        val scroll = rememberScrollState()
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(scroll),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          suggestions.forEach { name ->
            FluidChip(
              label = name,
              selected = VoiceNames.normalize(text)?.equals(name, ignoreCase = true) == true,
              onClick = { text = name },
            )
          }
        }
      }

      FluidSectionFootnote(text = stringResource(R.string.session_voice_rename_footnote))
    }
  }
}
