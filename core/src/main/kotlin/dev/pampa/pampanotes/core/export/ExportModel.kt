package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import dev.pampa.pampanotes.core.db.TranscriptEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Cosa si esporta: una nota, una cartella con tutto quello che contiene, o l'archivio intero. */
sealed interface ExportScope {
  /** Tutte le materie: la sezione Registrazioni no (vedi `PersonalScope`), si esporta cartella per cartella. */
  data object Everything : ExportScope
  data class Folder(val id: String) : ExportScope
  data class Note(val id: String) : ExportScope
  /** Alcune note scelte a mano; [label] e' il nome che prende il pacchetto. */
  data class Notes(val ids: List<String>, val label: String) : ExportScope
}

enum class ExportFormat {
  /**
   * Uno ZIP con dentro una cartella sola: l'indice, le istruzioni, la skill, un file per gli appunti
   * di ogni nota e uno per ogni lezione trascritta. Claude lo carica come skill cosi' com'e', ChatGPT e
   * un agente col terminale lo estraggono e partono dall'indice.
   */
  BUNDLE,

  /** Un Markdown solo, da incollare in una conversazione. */
  SINGLE,

  /**
   * Gli stessi file del pacchetto, sciolti e senza cartelle.
   *
   * Per chi non apre gli ZIP: un Progetto di Claude prende file, non archivi, e appiattisce le
   * cartelle. I nomi sono gia' unici e i collegamenti fra i file sono nomi nudi, quindi appiattire
   * non rompe niente.
   */
  FILES,
}

enum class TranscriptChoice {
  /** La versione ripulita quando c'e', altrimenti la grezza. */
  BEST,

  /** Sempre la grezza: e' l'unica che porta i tempi, e l'unica che nessun modello ha riscritto. */
  RAW,
}

/**
 * Dove si usera' il pacchetto: e' la prima e l'unica domanda che il pannello fa.
 *
 * Formato, allegati e regole erano quattro interruttori e tre formati da capire prima di poter
 * esportare, e chi li guardava non sapeva quale combinazione serviva a cosa. Chi esporta sa invece
 * benissimo dove andra' il file: la risposta sceglie tutto il resto, e «Personalizza» resta per chi
 * vuole cambiare un pezzo.
 */
enum class ExportTarget {
  /**
   * Una conversazione di Claude o ChatGPT: uno ZIP leggero, testo e pagine a mano, da allegare.
   * Niente registrazioni ne' originali: una chat che riceve cento megabyte va in timeout, e un
   * m4a non lo legge comunque.
   */
  CHAT,

  /** Un Progetto di Claude o ChatGPT: file sciolti, perche' un Progetto prende file e non archivi. */
  PROJECT,

  /** Un agente col terminale (Claude Code, Codex): tutto, anche registrazioni e originali. */
  AGENT,

  /** Un testo solo da incollare: per quando non si puo' allegare niente. */
  PASTE,
  ;

  /** Le opzioni che questa risposta sceglie da sola. */
  fun defaults(): ExportOptions = when (this) {
    CHAT -> ExportOptions(target = this, format = ExportFormat.BUNDLE, includeSkill = true, includeAudio = false, includeSources = false)
    PROJECT -> ExportOptions(target = this, format = ExportFormat.FILES, includeSkill = true, includeAudio = false, includeSources = false)
    AGENT -> ExportOptions(target = this, format = ExportFormat.BUNDLE, includeSkill = true, includeAudio = true, includeSources = true)
    PASTE -> ExportOptions(target = this, format = ExportFormat.SINGLE, includeSkill = true, includeAudio = false, includeSources = false)
  }

  companion object {
    /**
     * La risposta che si deduce da opzioni salvate prima che la domanda esistesse.
     *
     * Chi aveva scelto i file sciolti li usava per un Progetto, chi metteva dentro registrazioni o
     * originali li dava a un agente: ripartire da «Chat» per tutti gli avrebbe cambiato il pacchetto
     * sotto i piedi, e mostrato «personalizzato» senza che avesse toccato niente.
     */
    fun inferFrom(options: ExportOptions): ExportTarget = when (options.format) {
      ExportFormat.FILES -> PROJECT
      ExportFormat.SINGLE -> PASTE
      ExportFormat.BUNDLE -> if (options.includeAudio || options.includeSources) AGENT else CHAT
    }
  }
}

@Serializable
data class ExportOptions(
  val format: ExportFormat = ExportFormat.BUNDLE,
  val transcript: TranscriptChoice = TranscriptChoice.BEST,
  /** I `[mm:ss]` davanti a ogni paragrafo: servono per citare un punto preciso della lezione. */
  val timestamps: Boolean = true,
  val includeAudio: Boolean = false,
  val includeSources: Boolean = false,
  /** `SKILL.md` e `instructions.md`: le regole con cui un assistente deve trattare queste fonti. */
  val includeSkill: Boolean = true,
  /** La risposta a «Dove lo usi?». In fondo, perche' i JSON salvati prima non l'hanno. */
  val target: ExportTarget = ExportTarget.CHAT,
) {
  /** Vero se qualcosa e' stato cambiato rispetto a quello che [target] sceglie da solo. */
  val isCustomized: Boolean get() = this != target.defaults()

  /**
   * Allegati e regole contano solo nello ZIP: i file sciolti e il testo da incollare non hanno dove
   * mettere un m4a, e le regole le hanno gia' (in `instructions.md` e in cima al testo).
   */
  val carriesAttachments: Boolean get() = format == ExportFormat.BUNDLE

  /**
   * Un'altra risposta a «Dove lo usi?».
   *
   * Formato, allegati e regole seguono la nuova risposta; quale trascrizione e i tempi restano come
   * erano, perche' riguardano il testo e non dove va: chi vuole sempre la grezza la vuole anche in
   * un Progetto.
   */
  fun withTarget(next: ExportTarget): ExportOptions = next.defaults().copy(transcript = transcript, timestamps = timestamps)
}

/**
 * Le opzioni salvate come testo, per le preferenze.
 *
 * Indulgente in lettura: una stringa vuota, o scritta da una versione che aveva un'opzione in
 * meno, torna ai default invece di far fallire l'apertura del pannello di export. Un valore che
 * questa versione non conosce (un destinatario aggiunto da una versione dopo) torna al default del
 * campo, non butta via le altre scelte.
 */
object ExportOptionsCodec {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

  fun decode(raw: String): ExportOptions {
    if (raw.isBlank()) return ExportOptions()
    return runCatching {
      val tree = json.parseToJsonElement(raw).jsonObject
      val options = json.decodeFromJsonElement(ExportOptions.serializer(), tree)
      // Scritto prima di «Dove lo usi?»: la risposta si ricava da quello che c'era.
      if ("target" in tree) options else options.copy(target = ExportTarget.inferFrom(options))
    }.getOrDefault(ExportOptions())
  }

  fun encode(options: ExportOptions): String = json.encodeToString(ExportOptions.serializer(), options)
}

// -------------------------------------------------------------------------------------------------
// Quello che si e' raccolto dal database, pronto da scrivere. Nessun DAO oltre questo punto: i
// writer sono funzioni pure, e per questo si possono provare senza un telefono.
// -------------------------------------------------------------------------------------------------

data class ExportSource(
  val originalName: String,
  val kind: SourceKind,
  val sha256: String,
  val sizeBytes: Long,
  val storedFileName: String?,
  val status: SourceStatus,
  val extractedChars: Int,
  /** La riga in `sources`: serve per andarla a prendere dal computer di casa. */
  val id: String = "",
  /** Il computer di casa ha l'originale: se qui manca, si puo' scaricare prima di scrivere. */
  val archived: Boolean = false,
)

data class ExportPart(
  val id: String,
  val originalName: String,
  val fileName: String,
  val durationMs: Long,
  /** A che millisecondo della sessione comincia questa parte. */
  val startMs: Long,
  val sizeBytes: Long,
  /** Il computer di casa ha la registrazione: se qui manca, si puo' scaricare prima di scrivere. */
  val archived: Boolean = false,
)

data class ExportSession(
  val id: String,
  /** Il numero che si stampa: "Sessione 2". */
  val number: Int,
  val title: String,
  val date: String,
  val parts: List<ExportPart>,
  /** La versione che si esporta, secondo [ExportOptions.transcript]. */
  val transcript: TranscriptEntity?,
  /** La grezza, sempre: e' lei che porta i segmenti, anche quando a stampare e' l'altra. */
  val raw: TranscriptEntity?,
  val segments: List<SegmentEntity>,
  /**
   * «Rinomina le voci»: i nomi dati alle voci di questa sessione, dalla chiave della voce
   * (`VoiceNames.key`). Una voce senza nome resta «Voce N».
   */
  val voiceNames: Map<String, String> = emptyMap(),
) {
  val durationMs: Long get() = parts.sumOf { it.durationMs }

  /** Vero quando quello che si stampa ha i tempi da stampare accanto. */
  val hasTimings: Boolean get() = transcript != null && transcript.id == raw?.id && segments.isNotEmpty()
}

/**
 * Una pagina scritta a mano, gia' disegnata come immagine all'import.
 *
 * Non e' una fonte da allegare quando lo si chiede: e' contenuto, scritto dall'autore come gli
 * appunti, e un modello lo legge con la vista. Per questo entra nel pacchetto sempre.
 */
data class ExportImage(
  /** Il file in `filesDir/sources`. */
  val storedFileName: String,
  /** "Pagina 1", "Pagina 2": l'ordine in cui stavano nel quaderno. */
  val page: Int,
  val sizeBytes: Long,
  /** La riga in `sources`, per scaricarla quando su questo dispositivo non c'e'. */
  val id: String = "",
  val archived: Boolean = false,
)

data class ExportNote(
  val note: NoteEntity,
  /** La catena di cartelle dalla radice, per nome. */
  val folderPath: List<String>,
  val tags: List<String>,
  val sources: List<ExportSource>,
  val sessions: List<ExportSession>,
  val handwriting: List<ExportImage> = emptyList(),
) {
  val folderName: String get() = folderPath.lastOrNull().orEmpty()
  val audioDurationMs: Long get() = sessions.sumOf { it.durationMs }
  val partCount: Int get() = sessions.sumOf { it.parts.size }
}

/** L'insieme da esportare, con il nome di quello che e': "Storia", "Tutto l'archivio". */
data class ExportSet(
  val scopeLabel: String,
  val scopeSlug: String,
  val notes: List<ExportNote>,
  /** Versione dell'app che ha scritto il bundle, per il front-matter e il manifest. */
  val generator: String,
  val exportedAtMillis: Long,
  /**
   * Tutto quello che c'e' dentro sta in Registrazioni: le regole e l'indice parlano di
   * «registrazioni», non di «lezioni». Un'intervista presentata a un assistente come la lezione di
   * un professore e' letta come una lezione.
   */
  val personal: Boolean = false,
) {
  val folderCount: Int get() = notes.map { it.folderPath.joinToString("/") }.distinct().size
  val audioDurationMs: Long get() = notes.sumOf { it.audioDurationMs }
  val sessionCount: Int get() = notes.sumOf { it.sessions.size }
  val wordCount: Int get() = notes.sumOf { note -> note.sessions.sumOf { it.transcript?.wordCount ?: 0 } }
}
