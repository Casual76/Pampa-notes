package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.export.MarkdownWriter.Companion.NL
import dev.pampa.pampanotes.core.export.MarkdownWriter.Companion.mdLink
import dev.pampa.pampanotes.core.export.MarkdownWriter.Companion.minutes
import dev.pampa.pampanotes.core.export.MarkdownWriter.Companion.prettyDate
import dev.pampa.pampanotes.core.model.wordCount

/**
 * `INDEX.md`: cosa c'e' nel pacchetto, in una pagina.
 *
 * Esiste per il modo in cui un assistente legge davvero un progetto: non apre venti file per
 * rispondere a una domanda, ne apre uno e decide. Senza indice o li apre tutti e finisce il contesto,
 * o ne apre uno a caso. Con l'indice sceglie, e per scegliere bene gli servono due cose per ogni
 * nota: di cosa parla — le prime parole degli appunti — e quale file copre quale giorno, quanto e'
 * lungo e che tratto di lezione contiene. Una riga per file, niente da aprire per saperlo.
 */
class IndexWriter(private val labels: ExportLabels = ExportLabels()) {

  fun index(layout: BundleLayout): String = buildString {
    val set = layout.set
    append("# ").append(labels.index).append(": ").append(set.scopeLabel).append(NL).append(NL)
    append(summary(set)).append(NL).append(NL)
    append(if (set.personal) labels.indexHowToPersonal else labels.indexHowTo).append(NL).append(NL)

    // Raggruppate per cartella, nell'ordine in cui compaiono: l'albero dell'archivio e' informazione,
    // ed e' la stessa che l'utente usa per orientarsi nell'app.
    set.notes.groupBy { it.folderPath.joinToString(" / ") }.forEach { (folder, notes) ->
      append("## ").append(folder.ifBlank { labels.notesPlural.replaceFirstChar { it.uppercase() } })
      append(NL).append(NL)
      notes.forEach { note -> append(noteSection(note, layout)) }
    }
  }

  private fun noteSection(note: ExportNote, layout: BundleLayout): String = buildString {
    val files = layout.of(note)
    append("### ").append(note.note.title.ifBlank { files.base }).append(NL).append(NL)

    val facts = facts(note)
    if (facts.isNotEmpty()) append(facts.joinToString(" · ")).append(NL).append(NL)
    excerpt(note.note.body)?.let { append("> ").append(it).append(NL).append(NL) }

    val bodyWords = note.note.body.wordCount()
    append("- ").append(labels.notes).append(": ").append(mdLink(MarkdownWriter.fileName(files.notes), layout.link(INDEX, files.notes)))
    if (bodyWords > 0) append(" · ").append(bodyWords).append(' ').append(labels.words)
    if (note.handwriting.isNotEmpty()) {
      val count = note.handwriting.size
      append(" · ").append(count).append(' ').append(if (count == 1) labels.pageSingular else labels.pages)
    }
    append(NL)

    note.sessions.forEach { session ->
      append("- ").append(prettyDate(session.date))
      if (session.title.isNotBlank()) append(" · ").append(session.title)
      if (session.durationMs > 0) append(" · ").append(minutes(session.durationMs)).append(' ').append(labels.minutes)
      val pieces = files.transcripts[session.id]
      when {
        pieces == null -> append(" · _").append(labels.noTranscript).append('_')
        pieces.size == 1 -> {
          append(" · ").append(pieces.single().words).append(' ').append(labels.words).append(": ")
          append(mdLink(MarkdownWriter.fileName(pieces.single().path), layout.link(INDEX, pieces.single().path)))
        }
        else -> {
          append(" · ").append(pieces.sumOf { it.words }).append(' ').append(labels.words).append(':')
          pieces.forEach { piece ->
            append(NL).append("  - ").append(mdLink("${piece.index} ${labels.of} ${piece.count}", layout.link(INDEX, piece.path)))
            val start = piece.startMs
            val end = piece.endMs
            if (start != null && end != null) {
              append(" · ").append(MarkdownWriter.timestamp(start)).append('–').append(MarkdownWriter.timestamp(end))
            }
            append(" · ").append(piece.words).append(' ').append(labels.words)
          }
        }
      }
      append(NL)
    }
    append(NL)
  }

  private fun summary(set: ExportSet): String {
    val noteWord = if (set.notes.size == 1) labels.note else labels.notesPlural
    val pieces = mutableListOf("${set.notes.size} $noteWord")
    // Le registrazioni sono i file, non le sessioni: una lezione interrotta e ripresa e' una
    // sessione sola con dentro due registrazioni, e dirne una sarebbe dire una cosa falsa.
    val recordings = set.notes.sumOf { it.partCount }
    if (recordings > 0) {
      pieces += "$recordings " + (if (recordings == 1) labels.recording else labels.recordings)
    }
    if (set.audioDurationMs > 0) pieces += "${minutes(set.audioDurationMs)} ${labels.minutes}"
    if (set.wordCount > 0) pieces += "${set.wordCount} ${labels.words}"
    val handwritten = set.notes.sumOf { it.handwriting.size }
    if (handwritten > 0) pieces += "$handwritten " + (if (handwritten == 1) labels.pageSingular else labels.pages)
    return pieces.joinToString(", ") + "."
  }

  private fun facts(note: ExportNote): List<String> = buildList {
    // Le date coperte: su una materia con venti lezioni e' il modo piu' rapido per trovare quella
    // giusta senza aprire niente.
    val dates = note.sessions.map { it.date }.distinct().sorted()
    when {
      dates.size == 1 -> add(prettyDate(dates.first()))
      dates.size > 1 -> add("${prettyDate(dates.first())} – ${prettyDate(dates.last())}")
    }
    if (note.partCount > 0) {
      add("${note.partCount} " + (if (note.partCount == 1) labels.recording else labels.recordings))
    }
    if (note.audioDurationMs > 0) add("${minutes(note.audioDurationMs)} ${labels.minutes}")
  }

  companion object {
    const val INDEX = "INDEX.md"
    private const val EXCERPT = 180

    /**
     * Le prime parole degli appunti, senza Markdown: e' il "di cosa parla" che il titolo da solo
     * spesso non dice — "Lezione 7" non dice niente, "Il trattato di Tordesillas divide..." si'.
     */
    internal fun excerpt(body: String): String? {
      val plain = body
        .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), " ")
        .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
        .lines()
        .map { it.trim().trimStart('#', '>', '-', '*', '+', ' ').trim() }
        .filter { it.isNotEmpty() && it != "---" }
        .joinToString(" ")
        .replace(Regex("""[*_`]+"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
      if (plain.isEmpty()) return null
      if (plain.length <= EXCERPT) return plain
      val cut = plain.take(EXCERPT)
      val space = cut.lastIndexOf(' ').takeIf { it > EXCERPT / 2 } ?: cut.length
      return cut.substring(0, space).trimEnd(',', ';', ':', '.', ' ') + "…"
    }
  }
}
