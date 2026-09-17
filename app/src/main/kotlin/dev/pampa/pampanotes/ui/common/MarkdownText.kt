package dev.pampa.pampanotes.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Il testo di una nota, reso come Markdown.
 *
 * I titoli scendono dai default della libreria: `displayLarge` per un `#` e' un cartello da
 * copertina, e in una nota che comincia con il proprio titolo sarebbe il titolo scritto due volte,
 * una delle quali alta un pollice. Qui un `#` e' grande quanto il titolo di una sezione.
 *
 * `retainState` e `immediate` evitano il fotogramma vuoto: l'analisi del Markdown e' sospesa, e
 * senza questi due, ogni volta che il testo cambia, lo slot torna vuoto per un fotogramma.
 */
@Composable
fun MarkdownText(
  markdown: String,
  modifier: Modifier = Modifier,
) {
  val typography = MaterialTheme.typography
  val code = typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
  androidx.compose.foundation.layout.Box(modifier = modifier) {
    Markdown(
      content = markdown,
      colors = markdownColor(),
      typography = markdownTypography(
        h1 = heading(1, typography),
        h2 = heading(2, typography),
        h3 = heading(3, typography),
        h4 = heading(4, typography),
        h5 = heading(5, typography),
        h6 = heading(6, typography),
        code = code,
        inlineCode = code,
      ),
      retainState = true,
      immediate = true,
    )
  }
}

private fun heading(level: Int, typography: Typography): TextStyle = when (level) {
  1 -> typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
  2 -> typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
  3 -> typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
  else -> typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
}
