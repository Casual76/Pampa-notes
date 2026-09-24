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
 * Le note come file Markdown.
 *
 * Il formato non e' una scelta estetica: e' quello che Claude, ChatGPT e Gemini leggono meglio come
 * fonte, ed e' anche quello che una persona puo' riaprire fra dieci anni con un editor qualsiasi. Il
 * front-matter YAML serve alla macchina — da dove viene un pezzo, con quale modello, in che giorno —
 * e il corpo serve a tutti e due.
 *
 * Una distinzione attraversa tutto, ed e' la ragione per cui l'export vale la pena: **gli appunti li
 * ha scritti una persona, la trascrizione l'ha scritta una macchina**. Un assistente che non sa quale
 * delle due sta leggendo tratta un errore di Whisper come un'affermazione dell'autore. Nel pacchetto
 * stanno in file diversi — gli appunti in `<nota>.md`, ogni lezione in `<nota>--<giorno>.md` — e ogni
 * file di trascrizione lo ripete in cima.
 */
class MarkdownWriter(private val labels: ExportLabels = ExportLabels()) {

  /**
   * Il file degli appunti di una nota, dentro un pacchetto.
   *
   * Le trascrizioni non ci sono: qui c'e' l'elenco delle lezioni con il collegamento a ciascuna, e un
   * agente che cerca con `grep` vede dal nome del file se ha trovato un appunto o una trascrizione.
   */
  fun noteFile(note: ExportNote, layout: BundleLayout, options: ExportOptions, generator: String = GENERATOR_PLACEHOLDER): String = buildString {
    val files = layout.of(note)
    append(frontMatter(note, options, generator, files))
    append(NL)
    append("# ").append(titleOf(note)).append(NL).append(NL)

    append("## ").append(labels.notes).append(NL).append(NL)
    append(bodyOf(note)).append(NL)

    if (note.handwriting.isNotEmpty()) {
      append(NL).append("## ").append(labels.handwriting).append(NL).append(NL)
      append('_').append(labels.handwritingDetail).append('_').append(NL).append(NL)
      note.handwriting.forEachIndexed { index, image ->
        if (index > 0) append(NL)
        append("![").append(labels.page).append(' ').append(image.page).append("](")
        append(layout.link(files.notes, files.images[index])).append(')').append(NL)
      }
    }

    if (note.sessions.isNotEmpty()) {
      append(NL).append("## ").append(labels.sessions).append(NL).append(NL)
      note.sessions.forEach { session ->
        append("- **").append(labels.session).append(' ').append(session.number).append(" — ").append(prettyDate(session.date))
        if (session.title.isNotBlank()) append(" · ").append(session.title)
        append("** · ").append(recordingsLine(session))
        val pieces = files.transcripts[session.id]
        if (pieces == null) {
          append(" · _").append(labels.noTranscript).append('_').append(NL)
          return@forEach
        }
        append(" · ").append(kindOf(session)).append(", ").append(pieces.sumOf { it.words }).append(' ').append(labels.words)
        if (pieces.size == 1) {
          append(": ").append(mdLink(fileName(pieces.single().path), layout.link(files.notes, pieces.single().path))).append(NL)
        } else {
          append(NL)
          pieces.forEach { piece ->
            append("  - ").append(mdLink("${piece.index} ${labels.of} ${piece.count}", layout.link(files.notes, piece.path)))
            rangeOf(piece)?.let { append(" · ").append(it) }
            append(" · ").append(piece.words).append(' ').append(labels.words).append(NL)
          }
        }
      }
    }

    sourcesSection(note, options)?.let { append(NL).append(it) }
  }

  /**
   * Un file di trascrizione: una lezione, o un suo pezzo.
   *
   * Si legge da solo, perche' spesso e' l'unico che un agente apre: il front-matter e il titolo dicono
   * di quale nota e di quale giorno e', che pezzo e' e che tratto di lezione copre; la riga in cima
   * ricorda che e' testo di una macchina e dove stanno gli appunti; in fondo ci sono il pezzo prima e
   * quello dopo.
   */
  fun transcriptFile(
    note: ExportNote,
    piece: TranscriptFile,
    layout: BundleLayout,
    generator: String = GENERATOR_PLACEHOLDER,
  ): String = buildString {
    val files = layout.of(note)
    val session = piece.session
    val transcript = session.transcript
    val siblings = files.transcripts.getValue(session.id)

    append("---").append(NL)
    append("note: ").append(yaml(titleOf(note))).append(NL)
    if (note.folderPath.isNotEmpty()) append("folder: ").append(yaml(note.folderPath.joinToString("/"))).append(NL)
    append("session: ").append(session.number).append(NL)
    append("date: ").append(session.date).append(NL)
    if (piece.count > 1) append("piece: ").append(yaml("${piece.index}/${piece.count}")).append(NL)
    rangeOf(piece)?.let { append("time: ").append(yaml(it)).append(NL) }
    transcript?.let {
      append("transcript: ").append(it.kind.name.lowercase()).append(NL)
      if (it.provider.isNotBlank()) append("provider: ").append(it.provider).append(NL)
      if (it.model.isNotBlank()) append("model: ").append(yaml(it.model)).append(NL)
    }
    append("words: ").append(piece.words).append(NL)
    append("notes_file: ").append(yaml(layout.link(piece.path, files.notes))).append(NL)
    append("generator: ").append(yaml(generator)).append(NL)
    append("---").append(NL).append(NL)

    append("# ").append(labels.transcript).append(" — ").append(titleOf(note)).append(" · ").append(prettyDate(session.date))
    if (piece.count > 1) append(" (").append(piece.index).append(' ').append(labels.of).append(' ').append(piece.count).append(')')
    append(NL).append(NL)

    append("> ").append(labels.machineText).append(' ').append(labels.notesAreIn).append(' ')
    append(mdLink(fileName(files.notes), layout.link(piece.path, files.notes))).append('.').append(NL).append(NL)

    append(labels.session).append(' ').append(session.number)
    if (session.title.isNotBlank()) append(" · ").append(session.title)
    append(" · ").append(recordingsLine(session)).append(" · ").append(kindOf(session)).append('.').append(NL).append(NL)

    // Una raffinata non ha i tempi: lo si dice invece di stampare un testo senza tempi come se fosse
    // quello che era stato chiesto.
    if (transcript?.kind == TranscriptKind.REFINED && !session.hasTimings) {
      append("> ").append(labels.refinedHasNoTimings).append(NL).append(NL)
    }

    append(blocksText(session, piece.blocks, announceFirst = piece.index > 1)).append(NL)

    if (piece.count > 1) {
      append(NL).append("---").append(NL).append(NL)
      val links = buildList {
        siblings.getOrNull(piece.index - 2)?.let { add("← " + mdLink(labels.previous, layout.link(piece.path, it.path))) }
        siblings.getOrNull(piece.index)?.let { add(mdLink(labels.next, layout.link(piece.path, it.path)) + " →") }
      }
      append(links.joinToString(" · ")).append(NL)
    }
  }

  /**
   * Una nota intera, trascrizioni comprese: per il file da incollare, dove non ci sono altri file da
   * collegare.
   */
  fun singleNote(note: ExportNote, options: ExportOptions, generator: String = GENERATOR_PLACEHOLDER): String = buildString {
    append(frontMatter(note, options, generator, files = null))
    append(NL)
    append("# ").append(titleOf(note)).append(NL).append(NL)
    append("## ").append(labels.notes).append(NL).append(NL)
    append(bodyOf(note)).append(NL)

    note.sessions.forEach { session ->
      append(NL)
      append("## ").append(labels.session).append(' ').append(session.number).append(" — ").append(prettyDate(session.date))
      if (session.title.isNotBlank()) append(" · ").append(session.title)
      append(NL).append(NL)
      append(recordingsLine(session)).append('.').append(NL).append(NL)

      if (session.transcript == null) {
        append('_').append(labels.noTranscript).append('_').append(NL)
        return@forEach
      }
      append("### ").append(labels.transcript).append(" (").append(kindOf(session, withLabel = false)).append(')').append(NL).append(NL)
      if (options.timestamps && !session.hasTimings) append("> ").append(labels.refinedHasNoTimings).append(NL).append(NL)
      append(blocksText(session, TranscriptPieces.blocks(session, options), announceFirst = false)).append(NL)
    }

    sourcesSection(note, options)?.let { append(NL).append(it) }
  }

  // -----------------------------------------------------------------------------------------------

  private fun titleOf(note: ExportNote): String = note.note.title.ifBlank { note.note.title.slugify() }

  private fun bodyOf(note: ExportNote): String = note.note.body.trim().ifEmpty { "_${labels.noNotes}_" }

  /** "trascrizione grezza, whisper-large-v3", oppure solo "grezza, whisper-large-v3". */
  private fun kindOf(session: ExportSession, withLabel: Boolean = true): String {
    val transcript = session.transcript ?: return labels.noTranscript
    val kind = if (transcript.kind == TranscriptKind.REFINED) labels.transcriptRefined else labels.transcriptRaw
    val base = if (withLabel) "${labels.transcript.lowercase()} $kind" else kind
    return if (transcript.model.isNotBlank()) "$base, ${transcript.model}" else base
  }

  /**
   * "2 registrazioni, 82 min". Serve a distinguere una lacuna vera nel testo dalla fine della
   * registrazione.
   */
  private fun recordingsLine(session: ExportSession): String = buildString {
    val count = session.parts.size
    append(count).append(' ').append(if (count == 1) labels.recording else labels.recordings)
    if (session.durationMs > 0) append(", ").append(minutes(session.durationMs)).append(' ').append(labels.minutes)
  }

  /** "41:10–1:22:05", quando il pezzo ha i tempi. */
  internal fun rangeOf(piece: TranscriptFile): String? {
    val start = piece.startMs ?: return null
    val end = piece.endMs ?: return null
    return "${timestamp(start)}–${timestamp(end)}"
  }

  /**
   * I blocchi in fila, coi tempi davanti quando ci sono.
   *
   * Ai confini fra registrazioni compare una riga che dice quale file comincia e a che punto della
   * sessione: i `[mm:ss]` contano dall'inizio della lezione, non del file, e una citazione deve poter
   * tornare al file giusto. Un pezzo che non e' il primo dice subito in quale registrazione si trova.
   */
  private fun blocksText(session: ExportSession, blocks: List<TranscriptBlock>, announceFirst: Boolean): String = buildString {
    var currentPart: String? = if (announceFirst) null else blocks.firstOrNull()?.partId
    blocks.forEachIndexed { index, block ->
      if (index > 0) append(NL).append(NL)
      // Un silenzio lungo si dice, su una riga sua, in corsivo e fra parentesi quadre: senza, sedici
      // minuti di niente sembravano la pausa di un respiro, e la frase dopo la risposta a quella
      // prima. Le parentesi dicono a chi legge — e a un assistente — che non l'ha detto nessuno.
      block.silenceBeforeMs?.let { silence ->
        append("*[").append(silenceLine(silence)).append("]*").append(NL).append(NL)
      }
      if (block.partId != null && block.partId != currentPart && session.parts.size > 1) {
        currentPart = block.partId
        val position = session.parts.indexOfFirst { it.id == block.partId }
        session.parts.getOrNull(position)?.let { part ->
          append("> ").append(labels.part).append(' ').append(position + 1).append(" (`").append(part.originalName).append("`) — ")
          append(labels.startsAt).append(' ').append(timestamp(part.startMs)).append(NL).append(NL)
        }
      }
      block.startMs?.let { append('[').append(timestamp(it)).append("] ") }
      // «Chi parla»: davanti a ogni paragrafo, non solo al cambio. Un assistente cita un paragrafo
      // alla volta, e un «ha detto» senza chi l'ha detto nel paragrafo stesso si perde.
      // Il nome dato alla voce («Rinomina le voci») al posto di «Voce N», con la punteggiatura del
      // Markdown neutralizzata: un «*» in un nome chiuderebbe il grassetto a meta'.
      block.voice?.let { number ->
        val label = block.voiceName?.let(::escapeInline) ?: labels.voice.replace("%1\$d", number.toString())
        append("**").append(label).append(":** ")
      }
      append(block.text)
    }
  }

  /** Un testo scritto dall'utente dentro una riga di Markdown, coi segni che contano resi letterali. */
  private fun escapeInline(text: String): String = buildString {
    text.forEach { char ->
      if (char in MARKDOWN_INLINE) append('\\')
      append(char)
    }
  }

  /** «— 16 min di silenzio —», nella lingua del pacchetto. */
  internal fun silenceLine(millis: Long): String =
    labels.silence.replace("%1\$s", TranscriptParagraphs.silenceDuration(millis, labels.hours, labels.minutes))

  private fun sourcesSection(note: ExportNote, options: ExportOptions): String? {
    if (!options.includeSources || note.sources.isEmpty()) return null
    return buildString {
      append("## ").append(labels.sources).append(NL).append(NL)
      note.sources.forEach { source ->
        append("- `").append(source.originalName).append("` — ").append(source.kind.name.lowercase())
        if (source.sizeBytes > 0) append(", ").append(bytes(source.sizeBytes))
        append(NL)
      }
    }
  }

  private fun frontMatter(note: ExportNote, options: ExportOptions, generator: String, files: NoteFiles?): String = buildString {
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
    if (note.handwriting.isNotEmpty()) append("handwritten_pages: ").append(note.handwriting.size).append(NL)

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
        files?.transcripts?.get(session.id)?.let { pieces ->
          append("    files: [").append(pieces.joinToString(", ") { yaml(fileName(it.path)) }).append(']').append(NL)
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

    /** I segni che dentro una riga cambiano il senso del testo: enfasi, codice, collegamenti, HTML. */
    private const val MARKDOWN_INLINE = "\\`*_[]<>"

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

    /** L'ultimo pezzo di un percorso. */
    fun fileName(path: String): String = path.substringAfterLast('/')

    /**
     * Un collegamento Markdown che regge qualunque titolo.
     *
     * Una parentesi quadra nel testo chiuderebbe il collegamento a meta', e uno spazio nel percorso
     * lo spezzerebbe: il testo si protegge con le barre, il percorso con le parentesi angolari.
     */
    fun mdLink(text: String, target: String): String {
      val safeText = text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
      val safeTarget = if (target.any { it == ' ' || it == '(' || it == ')' }) "<$target>" else target
      return "[$safeText]($safeTarget)"
    }
  }
}
