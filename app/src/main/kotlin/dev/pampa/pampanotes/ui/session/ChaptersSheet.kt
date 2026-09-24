package dev.pampa.pampanotes.ui.session

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Toc
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.transcription.Chapters
import dev.pampa.pampanotes.core.transcription.TranscriptParagraphs
import dev.pampa.pampanotes.ui.common.Formats
import dev.pampa.pampanotes.ui.common.PageActions
import dev.pampa.pampanotes.ui.common.SheetBody

/**
 * L'elenco dei capitoli di una registrazione lunga (vedi [Chapters]).
 *
 * Un pannello a tutta pagina e non una sezione in cima al testo: in una registrazione di diciannove
 * ore la cima del testo e' a mezz'ora di scorrimento, e l'indice serve proprio quando si e' in mezzo.
 * Si apre dalla barra in alto (sempre raggiungibile) o dalla riga «12 capitoli» sopra il testo.
 *
 * Ogni riga dice solo fatti: quando comincia e finisce, quanto si parla, quante parole, chi parla, e
 * le prime parole fra virgolette — una citazione, non un titolo. Il capitolo che si sta ascoltando ha
 * l'occhiello nel colore della materia e «adesso», e TalkBack lo dice come stato. Un tocco porta il
 * lettore all'inizio del capitolo e il testo con lui, e chiude.
 */
@Composable
fun ChaptersSheet(
  chapters: List<Chapters.Chapter>,
  /** Il capitolo in ascolto, o -1 se il lettore non e' ancora partito. */
  current: Int,
  onPick: (Chapters.Chapter) -> Unit,
  onDismiss: () -> Unit,
  /** Il nome dato a una voce («Marco»), se c'e': senza, resta «Voce N». */
  voiceName: (Int) -> String? = { null },
) {
  FluidGlassModalPortal(
    visible = true,
    onDismissRequest = onDismiss,
    presentation = FluidGlassModalPresentation.FullScreen,
    paneTitle = stringResource(R.string.session_chapters),
    footer = {
      PageActions {
        FluidButton(
          text = stringResource(R.string.action_cancel),
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
      FluidSectionFootnote(text = stringResource(R.string.session_chapters_detail))
      FluidListGroup {
        chapters.forEachIndexed { index, chapter ->
          if (index > 0) FluidListDivider()
          ChapterRow(chapter = chapter, isCurrent = index == current, onClick = { onPick(chapter) }, voiceName = voiceName)
        }
      }
    }
  }
}

@Composable
private fun ChapterRow(chapter: Chapters.Chapter, isCurrent: Boolean, onClick: () -> Unit, voiceName: (Int) -> String?) {
  val nowState = stringResource(R.string.session_chapter_now_state)
  FluidListRow(
    title = chapterQuote(chapter),
    subtitle = chapterRange(chapter),
    eyebrow = stringResource(if (isCurrent) R.string.session_chapter_now else R.string.session_chapter_number, chapter.number),
    meta = chapterMeta(chapter, voiceName),
    // Il tono sta sull'occhiello: il capitolo di adesso si riconosce senza colorare la riga intera.
    tone = if (isCurrent) FluidTone.Primary else FluidTone.Neutral,
    onClick = onClick,
    modifier = Modifier
      .heightIn(min = 48.dp)
      .then(if (isCurrent) Modifier.semantics { stateDescription = nowState } else Modifier),
  )
}

/**
 * La riga sopra il testo: quanti capitoli, e quale si sta ascoltando. Apre il pannello.
 *
 * Una riga sola e non l'elenco intero: quaranta righe sopra la trascrizione la spingerebbero a due
 * schermate di distanza, e chi apre una lezione vuole leggere.
 */
@Composable
fun ChaptersEntry(chapters: List<Chapters.Chapter>, current: Int, onOpen: () -> Unit) {
  val now = chapters.getOrNull(current)
  FluidListGroup {
    FluidListRow(
      title = pluralStringResource(R.plurals.session_chapters_count, chapters.size, chapters.size),
      subtitle = now?.let { stringResource(R.string.session_chapters_entry_now, it.number, chapterQuote(it)) }
        ?: stringResource(R.string.session_chapters_entry),
      leading = { Icon(Icons.AutoMirrored.Rounded.Toc, contentDescription = null) },
      tone = FluidTone.Primary,
      onClick = onOpen,
      modifier = Modifier.heightIn(min = 48.dp),
    )
  }
}

/** «“No, certo che no…”»: le prime parole fra virgolette, che dicono che non le ha scritte l'app. */
@Composable
private fun chapterQuote(chapter: Chapters.Chapter): String =
  if (chapter.quote.isBlank()) stringResource(R.string.session_chapter_no_quote)
  else stringResource(R.string.session_chapter_quote, chapter.quote)

/** «13:58:00 – 14:40:12 · 42 min di parlato». */
@Composable
private fun chapterRange(chapter: Chapters.Chapter): String = stringResource(
  R.string.session_chapter_range,
  Formats.timestamp(chapter.startMs),
  Formats.timestamp(chapter.endMs),
  TranscriptParagraphs.silenceDuration(
    chapter.speechMs,
    hours = stringResource(R.string.session_silence_hours),
    minutes = stringResource(R.string.export_label_minutes),
  ),
)

/** «1240 parole · Voce 1, Voce 2». */
@Composable
private fun chapterMeta(chapter: Chapters.Chapter, voiceName: (Int) -> String?): String {
  val words = pluralStringResource(R.plurals.session_words, chapter.words, chapter.words)
  if (chapter.voices.isEmpty()) return words
  val voices = chapter.voices.map { voiceName(it) ?: stringResource(R.string.session_voice, it) }
  return "$words · ${voices.joinToString(", ")}"
}
