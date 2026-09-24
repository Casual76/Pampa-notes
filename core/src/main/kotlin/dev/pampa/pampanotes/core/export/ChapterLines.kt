package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.export.MarkdownWriter.Companion.timestamp
import dev.pampa.pampanotes.core.transcription.Chapters
import dev.pampa.pampanotes.core.transcription.TranscriptParagraphs

/**
 * I capitoli di una sessione nel pacchetto: in cima al file della trascrizione e sotto la sessione
 * in `INDEX.md` (vedi [Chapters]).
 *
 * Una registrazione di diciannove ore sono venti file da seimila parole, e un assistente non sa quale
 * aprire per «quella sera al ristorante»: l'indice glielo dice con i tempi — lo stesso `[13:58:00]`
 * che apre il paragrafo, cosi' un `grep` porta dal capitolo al testo — e con le prime parole dette.
 * Mai un riassunto: sono confini e citazioni, e la riga in cima lo dice anche a chi legge.
 *
 * Solo dove i tempi si stampano: una raffinata non li ha, e un indice di tempi sopra un testo senza
 * tempi non si potrebbe seguire.
 */
internal class ChapterLines(private val labels: ExportLabels) {

  /** I capitoli da scrivere per [session]: vuota senza tempi o sotto [Chapters.MIN_SHOWN]. */
  fun of(session: ExportSession): List<Chapters.Chapter> {
    if (!session.hasTimings) return emptyList()
    return Chapters.index(session.segments, session.durationMs.takeIf { it > 0 })
  }

  /** Quelli che cominciano dentro il tratto di un file: ogni pezzo dice i suoi. */
  fun inPiece(session: ExportSession, piece: TranscriptFile): List<Chapters.Chapter> {
    val start = piece.startMs ?: return emptyList()
    val end = piece.endMs ?: return emptyList()
    return of(session).filter { it.startMs in start..end }
  }

  /**
   * «[13:58:00]–14:40:00 · 42 min di parlato · 1240 parole · Voce 1, Voce 2 · “No, certo che no…”».
   * Le parole si dicono ma non si contano: questa riga non e' testo della registrazione.
   */
  fun line(chapter: Chapters.Chapter): String = buildString {
    append('[').append(timestamp(chapter.startMs)).append("]–").append(timestamp(chapter.endMs))
    val speech = TranscriptParagraphs.silenceDuration(chapter.speechMs, labels.hours, labels.minutes)
    append(" · ").append(labels.speech.replace("%1\$s", speech))
    append(" · ").append(chapter.words).append(' ').append(labels.words)
    if (chapter.voices.isNotEmpty()) {
      append(" · ").append(chapter.voices.joinToString(", ") { labels.voice.replace("%1\$d", it.toString()) })
    }
    if (chapter.quote.isNotBlank()) append(" · ").append(labels.quote.replace("%1\$s", chapter.quote))
  }

  /** La sezione in cima a un file di trascrizione: titolo, la riga che dice cosa sono, l'elenco. */
  fun section(chapters: List<Chapters.Chapter>, level: Int = 2): String = buildString {
    if (chapters.isEmpty()) return@buildString
    append("#".repeat(level)).append(' ').append(labels.chapters).append(MarkdownWriter.NL).append(MarkdownWriter.NL)
    append('_').append(labels.chaptersDetail).append('_').append(MarkdownWriter.NL).append(MarkdownWriter.NL)
    // Un elenco numerato che parte dal numero del primo capitolo del pezzo: «3.» in un pezzo e' il
    // terzo capitolo della sessione, come in INDEX.md.
    chapters.forEach { chapter ->
      append(chapter.number).append(". ").append(line(chapter)).append(MarkdownWriter.NL)
    }
  }
}
