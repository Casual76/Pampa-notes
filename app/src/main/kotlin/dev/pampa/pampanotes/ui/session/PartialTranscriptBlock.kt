package dev.pampa.pampanotes.ui.session

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.transcription.SessionPartial

/**
 * «Il testo che arriva a pezzi»: quello che il computer di casa ha gia' trascritto di una lezione
 * lunga, mentre fa il resto (vedi `PartialTranscripts`).
 *
 * Sta sotto la scheda del lavoro e solo finche' il lavoro va: finito, al suo posto arriva la
 * trascrizione vera, che e' un'altra cosa — passata dal filtro sulla lezione intera, con le voci se
 * chieste, salvata e sincronizzata. Per questo il blocco lo dice in testa, e le card sono le stesse
 * della grezza ma senza parole che si accendono: un tocco su una frase porta comunque il lettore li',
 * perche' i tempi sono gia' quelli della sessione. Con una trascrizione vecchia sotto (si sta
 * ritrascrivendo) il blocco sta sopra, e la vecchia resta dov'e' finche' la nuova non la sostituisce.
 */
internal fun LazyListScope.partialTranscriptItems(partial: SessionPartial?, onSeek: (Long) -> Unit) {
  val segments = partial?.segments?.takeIf { it.isNotEmpty() } ?: return
  item(key = "partial-head") {
    val detail = if (partial.piecesTotal > 0) {
      pluralStringResource(R.plurals.session_incoming_pieces, partial.piecesDone, partial.piecesDone, partial.piecesTotal)
    } else {
      // Una registrazione finita di una sessione che ne ha altre da fare: il conto e' per registrazione.
      pluralStringResource(R.plurals.session_incoming_parts, partial.partIndex + 1, partial.partIndex + 1, partial.partCount)
    }
    FluidInlineMessage(
      title = stringResource(R.string.session_incoming_title, detail),
      message = stringResource(R.string.session_incoming_note),
      tone = FluidTone.Info,
    )
  }
  // Pochi pezzi alla volta, uno ogni qualche minuto: la divisione in paragrafi si rifa' qui, ed e'
  // la stessa della grezza (`TranscriptParagraphs.split`).
  val paragraphs = paragraphsOf(segments)
  itemsIndexed(items = paragraphs, key = { index, _ -> "partial-$index" }) { _, paragraph ->
    ParagraphCard(
      paragraph = paragraph,
      voice = null,
      isActive = false,
      positionMs = { 0L },
      onSeek = onSeek,
    )
  }
}
