package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.model.slugify
import dev.pampa.pampanotes.core.model.wordCount
import dev.pampa.pampanotes.core.transcription.TranscriptParagraphs

/**
 * Un pezzo di trascrizione: un paragrafo coi tempi, oppure un blocco di testo che i tempi non li ha.
 *
 * E' l'unita' con cui si taglia una lezione lunga: un file non finisce mai a meta' di un paragrafo,
 * perche' una citazione deve poter tornare a un `[mm:ss]` che sta nello stesso file del testo.
 */
data class TranscriptBlock(
  val text: String,
  val startMs: Long? = null,
  val endMs: Long? = null,
  /** La registrazione da cui viene, quando lo si sa: al cambio si scrive quale comincia. */
  val partId: String? = null,
) {
  val words: Int get() = text.wordCount()
}

/**
 * Da una sessione ai suoi file.
 *
 * Una lezione da due ore sono quindicimila parole. In un file solo un agente la legge troncata — gli
 * strumenti di lettura si fermano a qualche migliaio di righe o di token — e un modello la tiene a
 * fatica nel contesto insieme alla domanda. Divisa in pezzi da qualche migliaio di parole, l'indice
 * dice quale pezzo copre quale tratto di lezione e si apre solo quello.
 */
object TranscriptPieces {

  /**
   * Il tetto di un file. Seimila parole di italiano sono circa quarantamila caratteri, diecimila
   * token: stanno in una lettura sola di qualunque strumento, e lasciano spazio al resto.
   */
  const val MAX_WORDS_PER_FILE = 6_000

  /** Sopra questa lunghezza un blocco senza tempi si divide alle frasi, per poterlo poi tagliare. */
  private const val MAX_WORDS_PER_BLOCK = 300

  private val sentenceEnd = Regex("""(?<=[.!?…])\s+""")

  /** I blocchi di una sessione, nella forma in cui si stampano. */
  fun blocks(session: ExportSession, options: ExportOptions): List<TranscriptBlock> {
    val transcript = session.transcript ?: return emptyList()
    if (options.timestamps && session.hasTimings) {
      val paragraphs = TranscriptParagraphs.split(session.segments, TranscriptParagraphs.MAX_SEGMENTS_IN_DOCUMENT)
      if (paragraphs.isNotEmpty()) {
        return paragraphs.map { TranscriptBlock(it.text, it.startMs, it.endMs, it.partId) }
      }
    }
    return textBlocks(transcript.text)
  }

  /**
   * Un testo senza tempi, a blocchi.
   *
   * Si rispettano i capoversi che ci sono; uno troppo lungo — una raffinata e' spesso un muro solo —
   * si spezza fra una frase e l'altra, e una frase che non finisce mai si spezza fra le parole. Si
   * tocca solo lo spazio bianco: le parole restano quelle.
   */
  fun textBlocks(text: String): List<TranscriptBlock> =
    text.trim().split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }.flatMap { paragraph ->
      if (paragraph.wordCount() <= MAX_WORDS_PER_BLOCK) {
        listOf(TranscriptBlock(paragraph))
      } else {
        group(paragraph.split(sentenceEnd).flatMap(::wordsChunks)).map { TranscriptBlock(it) }
      }
    }

  /** I blocchi divisi in file, ognuno sotto il tetto e tutti piu' o meno lunghi uguali. */
  fun split(blocks: List<TranscriptBlock>, maxWords: Int = MAX_WORDS_PER_FILE): List<List<TranscriptBlock>> {
    if (blocks.isEmpty()) return emptyList()
    val total = blocks.sumOf { it.words }
    if (total <= maxWords) return listOf(blocks)

    // Pezzi uguali invece di pezzi pieni: 6000 + 6000 + 200 lascerebbe un ultimo file con tre frasi
    // dentro, che nell'indice sembra una lezione a parte.
    val count = (total + maxWords - 1) / maxWords
    val target = total.toDouble() / count
    val pieces = mutableListOf<List<TranscriptBlock>>()
    var current = mutableListOf<TranscriptBlock>()
    var words = 0
    blocks.forEach { block ->
      current += block
      words += block.words
      if (words >= target && pieces.size < count - 1) {
        pieces += current.toList()
        current = mutableListOf()
        words = 0
      }
    }
    if (current.isNotEmpty()) pieces += current.toList()
    return pieces
  }

  private fun wordsChunks(sentence: String): List<String> {
    val words = sentence.split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (words.size <= MAX_WORDS_PER_BLOCK) return listOf(sentence.trim())
    return words.chunked(MAX_WORDS_PER_BLOCK).map { it.joinToString(" ") }
  }

  /** Frasi in fila fino a riempire un blocco. */
  private fun group(sentences: List<String>): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var words = 0
    sentences.filter { it.isNotBlank() }.forEach { sentence ->
      val count = sentence.wordCount()
      if (words > 0 && words + count > MAX_WORDS_PER_BLOCK) {
        result += current.toString()
        current.clear()
        words = 0
      }
      if (current.isNotEmpty()) current.append(' ')
      current.append(sentence.trim())
      words += count
    }
    if (current.isNotEmpty()) result += current.toString()
    return result
  }
}

/**
 * Un file di trascrizione: una sessione intera, o uno dei suoi pezzi.
 *
 * [path] e' relativo alla cartella del pacchetto.
 */
data class TranscriptFile(
  val path: String,
  val session: ExportSession,
  val index: Int,
  val count: Int,
  val blocks: List<TranscriptBlock>,
) {
  val words: Int get() = blocks.sumOf { it.words }
  val startMs: Long? get() = blocks.firstNotNullOfOrNull { it.startMs }
  val endMs: Long? get() = blocks.lastOrNull { it.endMs != null }?.endMs
}

/** Tutti i file di una nota dentro il pacchetto. */
data class NoteFiles(
  /** Il nome da cui discendono tutti gli altri: `storia--lezione-1`. */
  val base: String,
  /** Gli appunti. */
  val notes: String,
  /** Per ogni sessione trascritta i suoi file, in ordine. Una sessione senza trascrizione non ne ha. */
  val transcripts: Map<String, List<TranscriptFile>>,
  /** Le pagine scritte a mano, allineate a [ExportNote.handwriting]. */
  val images: List<String>,
  /** Le registrazioni, per id della parte. */
  val audio: Map<String, String>,
  /** Gli originali, allineati a [ExportNote.sources]; `null` dove il file non c'e'. */
  val sources: List<String?>,
)

/**
 * Dove sta ogni cosa, deciso una volta sola prima di scrivere.
 *
 * Tutti i nomi sono unici per costruzione — senza distinguere maiuscole e minuscole, come fanno
 * Windows e macOS — e i collegamenti fra i file si calcolano da qui: l'indice, il manifest e le note
 * non possono dire due cose diverse su dove sta un file, perche' leggono la stessa mappa.
 *
 * Le note e le trascrizioni stanno tutte nella stessa cartella piatta, `notes/`: i file di una nota
 * cominciano con lo stesso nome e un elenco ordinato li tiene insieme, e un Progetto di Claude che
 * appiattisce le cartelle non perde niente. Nel formato sciolto ([loose]) quella cartella sparisce e
 * tutto sta allo stesso livello.
 */
class BundleLayout(
  val set: ExportSet,
  private val options: ExportOptions,
  val loose: Boolean = false,
) {

  /** La cartella in cima allo ZIP. E' anche il nome della skill: Claude vuole che coincidano. */
  val root: String = SkillWriter.skillNameOf(set)

  private val notesDir = if (loose) "" else "notes/"

  /**
   * I nomi della radice sono presi prima di cominciare. Nel formato sciolto le note stanno accanto
   * a `INDEX.md` e `instructions.md`: una nota intitolata «Index» diventava `index.md`, e su un disco
   * che non distingue le maiuscole sovrascriveva l'indice — o l'indice sovrascriveva lei.
   */
  private val used = ROOT_FILES.map { it.lowercase() }.toMutableSet()
  private val stems = (if (loose) ROOT_FILES.map { it.substringBeforeLast('.').lowercase() } else emptyList()).toMutableSet()

  val notes: Map<String, NoteFiles> = set.notes.associate { note -> note.note.id to filesOf(note) }

  fun of(note: ExportNote): NoteFiles = notes.getValue(note.note.id)

  /**
   * Il collegamento da un file all'altro, come lo scrive il Markdown.
   *
   * Relativo, perche' il pacchetto si estrae dove capita; le note e le trascrizioni stanno nella
   * stessa cartella, quindi fra loro e' il nome nudo.
   */
  fun link(from: String, to: String): String {
    val fromDir = from.split('/').dropLast(1)
    val target = to.split('/')
    var common = 0
    while (common < fromDir.size && common < target.size - 1 && fromDir[common] == target[common]) common++
    val ups = List(fromDir.size - common) { ".." }
    return (ups + target.drop(common)).joinToString("/")
  }

  // -----------------------------------------------------------------------------------------------

  private fun filesOf(note: ExportNote): NoteFiles {
    val base = unique(baseOf(note))
    val notesFile = claim("$notesDir$base.md")

    val transcripts = LinkedHashMap<String, List<TranscriptFile>>()
    note.sessions.forEach { session ->
      val pieces = TranscriptPieces.split(TranscriptPieces.blocks(session, options))
      if (pieces.isEmpty()) return@forEach
      // Due sessioni nello stesso giorno sono due lezioni: la seconda prende un numero.
      val day = unique("$base--${session.date}")
      transcripts[session.id] = pieces.mapIndexed { index, blocks ->
        val suffix = if (pieces.size > 1) "--${index + 1}di${pieces.size}" else ""
        TranscriptFile(claim("$notesDir$day$suffix.md"), session, index + 1, pieces.size, blocks)
      }
    }

    val images = note.handwriting.map { image ->
      claim(if (loose) "$base--pagina-${image.page}.png" else "images/$base/pagina-${image.page}.png")
    }

    // Le registrazioni: la data e il numero davanti al nome originale, perche' Samsung Notes chiama
    // "Voce 001" la prima di ogni nota, e due lezioni con dentro "Voce 001.m4a" nello stesso ZIP sono
    // una voce doppia che fa fallire l'intera scrittura.
    val audio = LinkedHashMap<String, String>()
    note.sessions.forEach { session ->
      session.parts.forEachIndexed { index, part ->
        val name = "${session.date}-${(index + 1).toString().padStart(2, '0')}-${part.originalName.sanitized()}"
        audio[part.id] = claimUnique("audio/$base/$name")
      }
    }
    val sources = note.sources.map { source ->
      source.storedFileName?.let { claimUnique("sources/$base/${source.originalName.sanitized()}") }
    }

    return NoteFiles(base, notesFile, transcripts, images, audio, sources)
  }

  /**
   * `storia--novecento--lezione-1`: le cartelle e il titolo.
   *
   * Con le cartelle nel nome il file dice da dove viene anche fuori dal pacchetto. Se l'albero e'
   * profondo si tengono le cartelle piu' vicine alla nota: sono quelle che distinguono.
   */
  private fun baseOf(note: ExportNote): String {
    val title = note.note.title.slugify(60)
    val folders = note.folderPath.map { it.slugify(40) }.filter { it.isNotBlank() }.toMutableList()
    while (folders.isNotEmpty() && (folders + title).joinToString("--").length > MAX_BASE) folders.removeAt(0)
    return (folders + title).joinToString("--").windowsSafe()
  }

  /**
   * Il primo di `stem`, `stem-2`, `stem-3`... non ancora preso.
   *
   * Si tengono i nomi di base e non i file, perche' da un nome ne discendono piu' d'uno: la sessione
   * lunga di una nota e' `--1di2` e `--2di2`, e il nome da solo non e' mai stato scritto.
   */
  private fun unique(stem: String): String {
    var candidate = stem
    var counter = 2
    while (candidate.lowercase() in stems) candidate = "$stem-${counter++}"
    stems += candidate.lowercase()
    return candidate
  }

  /**
   * Prende un nome. I nomi che discendono da una base unica non dovrebbero mai scontrarsi, ma «non
   * dovrebbero» non basta: due voci uguali in uno ZIP fanno fallire tutta la scrittura, e due file
   * sciolti uguali si sovrascrivono in silenzio. Se il nome e' gia' preso, prende un numero.
   */
  private fun claim(path: String): String = claimUnique(path)

  /** Un nome gia' preso prende un numero prima dell'estensione. */
  private fun claimUnique(path: String): String {
    val dot = path.lastIndexOf('.').takeIf { it > path.lastIndexOf('/') } ?: path.length
    val stem = path.substring(0, dot)
    val extension = path.substring(dot)
    var candidate = path
    var counter = 2
    while (candidate.lowercase() in used) candidate = "$stem-${counter++}$extension"
    used += candidate.lowercase()
    return candidate
  }

  companion object {
    private const val MAX_BASE = 100

    /** I file che il pacchetto scrive nella sua radice, in tutti e due i formati. */
    val ROOT_FILES = listOf(IndexWriter.INDEX, "instructions.md", "SKILL.md", "README-FOR-AI.md", "manifest.json")

    /** I nomi che Windows non lascia creare, qualunque sia l'estensione. */
    private val reserved = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }

    internal fun String.windowsSafe(): String = if (lowercase() in reserved) "${this}_" else this

    /**
     * Un nome di file che sopravvive a Windows, a macOS e a un'unzip fatta male.
     *
     * Se ne vanno solo i caratteri che un filesystem rifiuta; spazi e accenti restano, perche' sono
     * quello che l'utente ha chiamato il file. Accorciando si salva l'estensione: un m4a senza
     * `.m4a` non lo apre piu' nessuno.
     */
    internal fun String.sanitized(): String {
      val cleaned = trim().replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "-").trim('.', ' ')
      if (cleaned.isBlank()) return "file"
      val dot = cleaned.lastIndexOf('.')
      val extension = if (dot > 0 && cleaned.length - dot <= 10) cleaned.substring(dot) else ""
      val stem = cleaned.removeSuffix(extension)
      val shortened = if (cleaned.length <= MAX_NAME) cleaned else stem.take(MAX_NAME - extension.length).trimEnd('.', ' ') + extension
      val dotIndex = shortened.lastIndexOf('.').takeIf { it > 0 } ?: shortened.length
      return shortened.substring(0, dotIndex).windowsSafe() + shortened.substring(dotIndex)
    }

    private const val MAX_NAME = 120
  }
}
