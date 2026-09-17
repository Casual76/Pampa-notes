package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.model.Dates
import dev.pampa.pampanotes.core.model.slugify
import dev.pampa.pampanotes.core.transcription.TranscriptParagraphs
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Una nota come file Markdown.
 *
 * Il formato non e' una scelta estetica: e' quello che Claude, ChatGPT e Gemini leggono meglio come
 * fonte, ed e' anche quello che una persona puo' riaprire fra dieci anni con un editor qualsiasi. Il
 * front-matter YAML serve alla macchina — da dove viene un pezzo, con quale modello, in che giorno —
 * e il corpo serve a tutti e due.
 *
 * Una distinzione attraversa tutto il file, ed e' la ragione per cui l'export vale la pena: **gli
 * appunti li ha scritti una persona, la trascrizione l'ha scritta una macchina**. Un assistente che
 * non sa quale delle due sta leggendo tratta un errore di Whisper come un'affermazione dell'autore.
 * Qui stanno sotto due titoli diversi, e `README-FOR-AI.md` spiega cosa vuol dire.
 */
class MarkdownWriter(private val labels: ExportLabels = ExportLabels()) {

  /** Il file di una nota, front-matter compreso. */
  fun note(note: ExportNote, options: ExportOptions, generator: String = GENERATOR_PLACEHOLDER): String = buildString {
    append(frontMatter(note, options, generator))
    append(NL)
    append("# ").append(note.note.title.ifBlank { fileNameOf(note).removeSuffix(".md") }).append(NL)
    append(NL)

    append("## ").append(labels.notes).append(NL).append(NL)
    val body = note.note.body.trim()
    append(if (body.isEmpty()) "_${labels.noNotes}_" else body).append(NL)

    note.sessions.forEach { session ->
      append(NL)
      append(sessionSection(session, options))
    }

    if (options.includeSources && note.sources.isNotEmpty()) {
      append(NL).append("## ").append(labels.sources).append(NL).append(NL)
      note.sources.forEach { source ->
        append("- `").append(source.originalName).append("` — ").append(source.kind.name.lowercase())
        if (source.sizeBytes > 0) append(", ").append(bytes(source.sizeBytes))
        append(NL)
      }
    }
  }

  /** Dove il file di questa nota sta dentro lo ZIP. */
  fun pathOf(note: ExportNote): String {
    val folders = note.folderPath.map { it.slugify() }.filter { it.isNotBlank() }
    return (listOf("notes") + folders + fileNameOf(note)).joinToString("/")
  }

  fun fileNameOf(note: ExportNote): String = "${note.note.title.slugify()}.md"

  // -----------------------------------------------------------------------------------------------

  private fun sessionSection(session: ExportSession, options: ExportOptions): String = buildString {
    append("## ").append(labels.session).append(' ').append(session.number)
    append(" — ").append(prettyDate(session.date))
    if (session.title.isNotBlank()) append(" · ").append(session.title)
    append(NL).append(NL)

    // Una riga di contesto: quanto dura e di quanti pezzi e' fatta. Serve a distinguere una lacuna
    // vera nel testo dalla fine della registrazione.
    val count = session.parts.size
    append(count).append(' ').append(if (count == 1) labels.recording else labels.recordings)
    if (session.durationMs > 0) append(", ").append(minutes(session.durationMs)).append(' ').append(labels.minutes)
    append('.').append(NL).append(NL)

    val transcript = session.transcript
    if (transcript == null) {
      append('_').append(labels.noTranscript).append('_').append(NL)
      return@buildString
    }

    val kind = if (transcript.kind == TranscriptKind.REFINED) labels.transcriptRefined else labels.transcriptRaw
    append("### ").append(labels.transcript).append(" (").append(kind)
    if (transcript.model.isNotBlank()) append(", ").append(transcript.model)
    append(')').append(NL).append(NL)

    // Chiesti i tempi ma quello che si stampa non ne ha: lo si dice invece di stampare un testo
    // senza tempi come se fosse quello che era stato chiesto.
    if (options.timestamps && !session.hasTimings) {
      append("> ").append(labels.refinedHasNoTimings).append(NL).append(NL)
    }

    append(body(session, options)).append(NL)
  }

  /**
   * Il testo della trascrizione.
   *
   * Con i tempi si ricostruisce dai segmenti, paragrafo per paragrafo. Ai confini fra registrazioni
   * compare una riga che dice quale file comincia: una citazione deve poter tornare al file giusto, e
   * il cronometro da solo non lo dice. Senza tempi si stampa il testo com'e' salvato.
   */
  private fun body(session: ExportSession, options: ExportOptions): String {
    val transcript = session.transcript ?: return ""
    if (!options.timestamps || !session.hasTimings) return transcript.text.trim()

    val paragraphs = TranscriptParagraphs.split(session.segments, TranscriptParagraphs.MAX_SEGMENTS_IN_DOCUMENT)
    if (paragraphs.isEmpty()) return transcript.text.trim()

    return buildString {
      var currentPart = paragraphs.first().partId
      paragraphs.forEachIndexed { index, paragraph ->
        if (index > 0) append(NL).append(NL)
        if (paragraph.partId != currentPart) {
          currentPart = paragraph.partId
          val position = session.parts.indexOfFirst { it.id == paragraph.partId }
          session.parts.getOrNull(position)?.let { part ->
            append("> ").append(labels.part).append(' ').append(position + 1)
            append(" — `").append(part.originalName).append('`').append(NL).append(NL)
          }
        }
        append('[').append(timestamp(paragraph.startMs)).append("] ").append(paragraph.text)
      }
    }
  }

  private fun frontMatter(note: ExportNote, options: ExportOptions, generator: String): String = buildString {
    append("---").append(NL)
    append("title: ").append(yaml(note.note.title)).append(NL)
    append("id: ").append(note.note.id).append(NL)
    if (note.folderPath.isNotEmpty()) {
      append("folder: ").append(yaml(note.folderName)).append(NL)
      append("path: ").append(yaml(note.folderPath.joinToString("/"))).append(NL)
    }
    if (note.tags.isNotEmpty()) {
      append("tags: [").append(note.tags.joinToString(", ") { yaml(it) }).append(']').append(NL)
    }
    append("created: ").append(isoDay(note.note.createdAt)).append(NL)
    append("updated: ").append(isoDay(note.note.updatedAt)).append(NL)
    note.note.language?.takeIf { it.isNotBlank() }?.let { append("language: ").append(it).append(NL) }

    if (options.includeSources && note.sources.isNotEmpty()) {
      append("sources:").append(NL)
      note.sources.forEach { source ->
        append("  - name: ").append(yaml(source.originalName)).append(NL)
        append("    kind: ").append(source.kind.name.lowercase()).append(NL)
        append("    sha256: ").append(source.sha256).append(NL)
      }
    }

    if (note.sessions.isNotEmpty()) {
      append("sessions:").append(NL)
      note.sessions.forEach { session ->
        append("  - date: ").append(session.date).append(NL)
        append("    parts: ").append(session.parts.size).append(NL)
        append("    duration_min: ").append(minutes(session.durationMs)).append(NL)
        session.transcript?.let { transcript ->
          append("    transcript: ").append(transcript.kind.name.lowercase()).append(NL)
          if (transcript.provider.isNotBlank()) append("    provider: ").append(transcript.provider).append(NL)
          if (transcript.model.isNotBlank()) append("    model: ").append(yaml(transcript.model)).append(NL)
        }
      }
    }

    append("generator: ").append(yaml(generator)).append(NL)
    append("---").append(NL)
  }

  companion object {
    internal const val GENERATOR_PLACEHOLDER = "Pampa Notes"

    /**
     * Sempre una riga sola, sempre la stessa.
     *
     * `System.lineSeparator()` avrebbe dato a Windows dei CRLF dentro uno ZIP scritto da un telefono,
     * ed e' il genere di differenza che rende due esportazioni identiche diverse per un diff.
     */
    internal const val NL = "\n"

    /** Una stringa YAML sempre fra virgolette: un titolo con i due punti dentro romperebbe il documento. */
    fun yaml(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun isoDay(epochMillis: Long): String =
      Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    /** "1:04:22" oppure "04:22": le ore compaiono solo quando ci sono. */
    fun timestamp(millis: Long): String {
      val seconds = (millis / 1000).coerceAtLeast(0)
      val hours = seconds / 3600
      val minutes = (seconds % 3600) / 60
      val rest = seconds % 60
      return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
      else String.format(Locale.ROOT, "%02d:%02d", minutes, rest)
    }

    /** Minuti arrotondati: mezzo minuto in piu' o in meno non cambia niente a chi legge. */
    fun minutes(millis: Long): Long = (millis + 30_000) / 60_000

    fun bytes(value: Long): String {
      if (value < 1024) return "$value B"
      val kb = value / 1024.0
      if (kb < 1024) return String.format(Locale.ROOT, "%.0f kB", kb)
      return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0)
    }

    /**
     * La data per esteso, scritta come la scrive la lingua del telefono.
     *
     * Non un pattern a mano: "d MMMM yyyy" pesca la forma isolata del mese, e in italiano da'
     * "9 Ottobre 2025" con la maiuscola, che dentro una data non si scrive.
     */
    private val longDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)

    fun prettyDate(iso: String): String =
      Dates.parseOrNull(iso)?.format(longDate.withLocale(Locale.getDefault())) ?: iso
  }
}
