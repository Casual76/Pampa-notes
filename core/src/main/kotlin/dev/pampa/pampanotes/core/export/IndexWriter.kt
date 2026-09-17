package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.export.MarkdownWriter.Companion.NL
import dev.pampa.pampanotes.core.export.MarkdownWriter.Companion.minutes

/**
 * `INDEX.md`: cosa c'e' nel bundle, in una pagina.
 *
 * Esiste per il modo in cui un assistente legge davvero un progetto: non apre venti file per
 * rispondere a una domanda, ne apre uno e decide. Senza indice o li apre tutti e finisce il contesto,
 * o ne apre uno a caso. Con l'indice sceglie, e la riga di ogni nota gli dice abbastanza per
 * scegliere bene: di cosa parla, quanto e' lunga, se ha una registrazione dietro.
 */
class IndexWriter(private val labels: ExportLabels = ExportLabels()) {

  fun index(set: ExportSet, writer: MarkdownWriter): String = buildString {
    append("# ").append(labels.index).append(": ").append(set.scopeLabel).append(NL).append(NL)
    append(summary(set)).append(NL).append(NL)

    // Raggruppate per cartella, nell'ordine in cui compaiono: l'albero dell'archivio e' informazione,
    // ed e' la stessa che l'utente usa per orientarsi nell'app.
    set.notes.groupBy { it.folderPath.joinToString(" / ") }.forEach { (folder, notes) ->
      append("## ").append(folder.ifBlank { labels.notesPlural.replaceFirstChar { it.uppercase() } })
      append(NL).append(NL)
      notes.forEach { note ->
        append("- [").append(note.note.title).append("](").append(writer.pathOf(note)).append(')')
        val facts = facts(note)
        if (facts.isNotEmpty()) append(" — ").append(facts.joinToString(", "))
        append(NL)
      }
      append(NL)
    }
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
    return pieces.joinToString(", ") + "."
  }

  private fun facts(note: ExportNote): List<String> = buildList {
    if (note.partCount > 0) {
      add("${note.partCount} " + (if (note.partCount == 1) labels.recording else labels.recordings))
    }
    if (note.audioDurationMs > 0) add("${minutes(note.audioDurationMs)} ${labels.minutes}")
    val words = note.sessions.sumOf { it.transcript?.wordCount ?: 0 }
    if (words > 0) add("$words ${labels.words}")
    // Le date coperte: su una materia con venti lezioni e' il modo piu' rapido per trovare quella
    // giusta senza aprire niente.
    val dates = note.sessions.map { it.date }.distinct().sorted()
    when {
      dates.size == 1 -> add(MarkdownWriter.prettyDate(dates.first()))
      dates.size > 1 -> add("${MarkdownWriter.prettyDate(dates.first())} – ${MarkdownWriter.prettyDate(dates.last())}")
    }
  }
}
