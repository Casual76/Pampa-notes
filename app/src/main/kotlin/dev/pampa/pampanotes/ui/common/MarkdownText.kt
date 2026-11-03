package dev.pampa.pampanotes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.antigravity.fluidengine.ui.fluid.FluidMarkdown
import dev.antigravity.fluidengine.ui.fluid.FluidMarkdownDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidMarkdownOptions
import dev.antigravity.fluidengine.ui.fluid.FluidMarkdownStrings
import dev.pampa.pampanotes.R

/**
 * Il testo di una nota, reso come Markdown dall'engine (`FluidMarkdown`): niente libreria in piu' nell'APK.
 *
 * Tre scelte, tutte per il fatto che il testo e' **dell'utente** e arriva da `.md`, `.txt`, DOCX e PDF:
 *  - i titoli sono quelli di una pagina di testo (`documentHeadings`: un `#` e' grande quanto il titolo
 *    di una sezione, non un cartello da copertina, in una nota che comincia gia' con il proprio titolo);
 *  - **un a capo singolo resta un a capo** (`softBreak = "\n"`): il parser di DOCX scrive un `<w:br>`
 *    come una riga a capo dentro il paragrafo, e un `.txt` e' fatto di righe — in Markdown stretto si
 *    fonderebbero tutte in una riga sola;
 *  - il testo si seleziona e si copia, cosa che nessun testo dell'app permetteva.
 *
 * Quello che il parser dell'engine non disegna (immagini, liste di attivita', HTML) resta testo.
 */
@Composable
fun MarkdownText(
  markdown: String,
  modifier: Modifier = Modifier,
) {
  val resources = LocalContext.current.resources
  val strings = remember(resources) {
    FluidMarkdownStrings(
      codeLabel = resources.getString(R.string.markdown_code_label),
      copy = resources.getString(R.string.markdown_copy),
      copied = resources.getString(R.string.markdown_copied),
      copyDescription = resources.getString(R.string.markdown_copy_description),
      codeBlockDescription = resources.getString(R.string.markdown_code_block_description),
    )
  }
  FluidMarkdown(
    markdown = markdown,
    modifier = modifier,
    style = FluidMarkdownDefaults.style(headings = FluidMarkdownDefaults.documentHeadings()),
    strings = strings,
    options = FluidMarkdownOptions(softBreak = "\n"),
    selectable = true,
  )
}
