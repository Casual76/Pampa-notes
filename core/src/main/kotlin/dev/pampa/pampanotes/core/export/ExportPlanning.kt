package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.model.wordCount

// -------------------------------------------------------------------------------------------------
// Prima di scrivere: quanto pesera', e quali file servono che qui non ci sono.
//
// Tutto puro: legge un [ExportSet] gia' raccolto e basta. Il pannello lo chiede a ogni interruttore
// toccato, quindi deve costare niente; e si prova in JVM, perche' «quanto pesa» e «cosa manca» sono
// le due cose che, sbagliate, fanno fallire un export senza che nessuno capisca perche'.
// -------------------------------------------------------------------------------------------------

/** Quanto pesera' il pacchetto, e quanto ne leggera' un modello. */
data class ExportEstimate(
  /** Il file (o i file) che escono: quello che una chat deve caricare. */
  val bytes: Long,
  /** I token che un modello spende per leggere tutto il testo e guardare tutte le pagine. */
  val tokens: Long,
  val words: Long,
  /** Le pagine scritte a mano che entrano come immagini. */
  val pages: Int,
  /** La parte di [bytes] fatta di registrazioni: e' quella che puo' arrivare ai gigabyte. */
  val audioBytes: Long,
  val sourceBytes: Long,
)

/** Un avviso prima di esportare. Il tono lo sceglie la schermata; qui si dice solo cosa succede. */
enum class ExportWarning {
  /** Uno ZIP troppo pesante per l'allegato di una chat: il caricamento va in timeout. */
  CHAT_TOO_HEAVY,

  /** Piu' testo di quanto una conversazione tenga in mente insieme alla domanda. */
  CHAT_TOO_LONG,

  /** Lo stesso, per il testo da incollare: e' tutto dentro il messaggio. */
  PASTE_TOO_LONG,

  /** Le registrazioni dentro: un semestre sono gigabyte. */
  AUDIO_HEAVY,
}

object ExportEstimator {

  /**
   * Byte per parola di Markdown, spazi, punteggiatura e `[mm:ss]` compresi. L'italiano ha parole di
   * cinque o sei lettere; il resto sono i marcatori dei tempi e i titoli.
   */
  internal const val BYTES_PER_WORD = 7

  /** Quanto resta del testo dopo deflate: il Markdown si comprime a un terzo, poco piu'. */
  internal const val ZIP_TEXT_RATIO = 0.35

  /** Un token ogni tre quarti di parola in italiano: le parole lunghe si spezzano in piu' pezzi. */
  internal const val TOKENS_PER_WORD = 1.4

  /**
   * Una pagina a mano, guardata. Un modello conta un'immagine per i pixel che ha, e una pagina
   * disegnata all'import sta sui mille e cinquecento token.
   */
  internal const val TOKENS_PER_PAGE = 1_500

  /** Indice, README, manifest: il testo che il pacchetto ha comunque, in parole. */
  internal const val FIXED_WORDS = 600

  /** Le regole (SKILL.md e instructions.md, o la testa del file singolo), in parole. */
  internal const val RULES_WORDS = 900

  /** Front-matter, titoli e collegamenti di una nota, in parole. */
  internal const val WORDS_PER_NOTE = 80

  /**
   * Oltre questo, l'allegato di una chat comincia a fallire: Claude e ChatGPT accettano file ben piu'
   * grandi sulla carta, ma dal telefono, su una rete qualsiasi, venticinque megabyte sono il punto
   * in cui il caricamento smette di finire.
   */
  const val CHAT_MAX_BYTES = 25L * 1024 * 1024

  /**
   * Oltre questo, il testo non sta piu' nel contesto di una conversazione insieme alla domanda e alle
   * risposte: il modello comincia a leggerne pezzi, o si rifiuta.
   */
  const val CHAT_MAX_TOKENS = 180_000L

  fun estimate(set: ExportSet, options: ExportOptions): ExportEstimate {
    val noteWords = set.notes.sumOf { note -> note.note.body.wordCount().toLong() + WORDS_PER_NOTE }
    val transcriptWords = set.notes.sumOf { note -> note.sessions.sumOf { (it.transcript?.wordCount ?: 0).toLong() } }
    val rules = if (options.includeSkill || options.format != ExportFormat.BUNDLE) RULES_WORDS else 0
    val fixed = if (options.format == ExportFormat.SINGLE) 0 else FIXED_WORDS
    val words = noteWords + transcriptWords + rules + fixed

    // Il testo da incollare e' testo e basta: un'immagine non si incolla.
    val images = if (options.format == ExportFormat.SINGLE) emptyList() else set.notes.flatMap { it.handwriting }
    val imageBytes = images.sumOf { it.sizeBytes }
    val audioBytes = if (options.carriesAttachments && options.includeAudio) set.notes.sumOf { note -> note.sessions.sumOf { s -> s.parts.sumOf { it.sizeBytes } } } else 0L
    val sourceBytes = if (options.carriesAttachments && options.includeSources) {
      set.notes.sumOf { note -> note.sources.filter { it.storedFileName != null }.sumOf { it.sizeBytes } }
    } else {
      0L
    }

    val textBytes = words * BYTES_PER_WORD
    val packedText = if (options.format == ExportFormat.BUNDLE) (textBytes * ZIP_TEXT_RATIO).toLong() else textBytes
    return ExportEstimate(
      bytes = packedText + imageBytes + audioBytes + sourceBytes,
      tokens = (words * TOKENS_PER_WORD).toLong() + images.size.toLong() * TOKENS_PER_PAGE,
      words = words,
      pages = images.size,
      audioBytes = audioBytes,
      sourceBytes = sourceBytes,
    )
  }

  /**
   * Gli avvisi, nell'ordine in cui dirli. Solo quelli che cambiano una decisione: un pacchetto per un
   * agente e' grande per costruzione, e dirlo ogni volta insegna a non leggere gli avvisi.
   */
  fun warnings(estimate: ExportEstimate, options: ExportOptions): List<ExportWarning> = buildList {
    when (options.target) {
      ExportTarget.CHAT -> {
        if (estimate.bytes > CHAT_MAX_BYTES) add(ExportWarning.CHAT_TOO_HEAVY)
        if (estimate.tokens > CHAT_MAX_TOKENS) add(ExportWarning.CHAT_TOO_LONG)
      }

      ExportTarget.PASTE -> if (estimate.tokens > CHAT_MAX_TOKENS) add(ExportWarning.PASTE_TOO_LONG)
      ExportTarget.PROJECT, ExportTarget.AGENT -> Unit
    }
    // Per la chat lo dice gia' il peso, se e' troppo; altrimenti le registrazioni sono poche e va bene.
    if (estimate.audioBytes > 0 && ExportWarning.CHAT_TOO_HEAVY !in this && options.target != ExportTarget.CHAT) {
      add(ExportWarning.AUDIO_HEAVY)
    }
  }
}

// -------------------------------------------------------------------------------------------------
// I file che servono e qui non ci sono
// -------------------------------------------------------------------------------------------------

enum class ExportFileKind { AUDIO, SOURCE, PAGE }

/** Un file che il pacchetto vuole dentro. */
data class ExportFileRef(
  val kind: ExportFileKind,
  /** La riga: `audio_parts.id` per una registrazione, `sources.id` per un originale o una pagina. */
  val id: String,
  val name: String,
  /** Il nome in `audio/` o in `sources/`. */
  val fileName: String,
  val sizeBytes: Long,
  /** Il computer di casa ce l'ha. */
  val archived: Boolean,
)

/** Perche' un file chiesto non entrera'. */
enum class MissingReason {
  /** E' solo sul dispositivo che l'ha registrato: il computer di casa non l'ha mai ricevuto. */
  NOT_ARCHIVED,

  /** E' sul computer di casa, che adesso non risponde (o non e' configurato). */
  UNREACHABLE,

  /** Il computer ha risposto, ma il file non e' arrivato intero. */
  FAILED,
}

data class MissingFile(val file: ExportFileRef, val reason: MissingReason)

/**
 * Cosa scaricare prima di scrivere, e cosa non si potra' avere comunque.
 *
 * Chi chiede le registrazioni le vuole dentro: un pacchetto che esce «esportato» senza, con i file
 * sul computer di casa a portata di mano, e' il difetto per cui questa pianificazione esiste.
 */
data class ExportFetchPlan(
  val toFetch: List<ExportFileRef>,
  /** Mancano e il computer non li ha: si sa gia' prima di provare. */
  val unobtainable: List<MissingFile>,
) {
  val isEmpty: Boolean get() = toFetch.isEmpty() && unobtainable.isEmpty()
}

/** Quanti file di un tipo mancano, e per quale motivo. Una riga della schermata. */
data class MissingGroup(val kind: ExportFileKind, val reason: MissingReason, val count: Int)

object ExportFiles {

  /** I file che il pacchetto vuole dentro, con queste opzioni. */
  fun wanted(set: ExportSet, options: ExportOptions): List<ExportFileRef> = buildList {
    set.notes.forEach { note ->
      // Le pagine a mano entrano sempre — sono appunti — tranne nel testo da incollare, dove
      // un'immagine non ci sta.
      if (options.format != ExportFormat.SINGLE) {
        note.handwriting.forEach { page ->
          add(ExportFileRef(ExportFileKind.PAGE, page.id, "${note.note.title} · ${page.page}", page.storedFileName, page.sizeBytes, page.archived))
        }
      }
      if (options.carriesAttachments && options.includeAudio) {
        note.sessions.forEach { session ->
          session.parts.forEach { part ->
            add(ExportFileRef(ExportFileKind.AUDIO, part.id, part.originalName, part.fileName, part.sizeBytes, part.archived))
          }
        }
      }
      if (options.carriesAttachments && options.includeSources) {
        note.sources.forEach { source ->
          val stored = source.storedFileName ?: return@forEach
          add(ExportFileRef(ExportFileKind.SOURCE, source.id, source.originalName, stored, source.sizeBytes, source.archived))
        }
      }
    }
  }

  /**
   * Divide i file che mancano in quelli da scaricare e quelli che non si possono avere.
   *
   * @param isPresent se il file e' gia' su questo dispositivo.
   */
  fun plan(set: ExportSet, options: ExportOptions, isPresent: (ExportFileRef) -> Boolean): ExportFetchPlan {
    val missing = wanted(set, options).filterNot(isPresent)
    val (fetchable, stuck) = missing.partition { it.archived }
    return ExportFetchPlan(
      toFetch = fetchable,
      unobtainable = stuck.map { MissingFile(it, MissingReason.NOT_ARCHIVED) },
    )
  }

  /** I file mancanti per tipo e per motivo, nell'ordine in cui la schermata li dice. */
  fun groups(missing: List<MissingFile>): List<MissingGroup> =
    missing.groupBy { it.file.kind to it.reason }
      .map { (key, files) -> MissingGroup(key.first, key.second, files.size) }
      .sortedWith(compareBy({ it.kind.ordinal }, { it.reason.ordinal }))

  /**
   * Il pacchetto senza le pagine a mano che non ci sono.
   *
   * Una registrazione mancante il writer la salta e basta, ma una pagina e' collegata dagli appunti:
   * un collegamento a un'immagine che non c'e' e' peggio di niente. I numeri restano quelli del
   * quaderno, cosi' «pagina 3» e' sempre la terza anche se la seconda manca.
   */
  fun withoutMissingPages(set: ExportSet, isPresent: (ExportImage) -> Boolean): ExportSet =
    set.copy(notes = set.notes.map { note -> note.copy(handwriting = note.handwriting.filter(isPresent)) })
}
