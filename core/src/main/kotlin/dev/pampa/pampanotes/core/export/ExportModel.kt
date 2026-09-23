package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import dev.pampa.pampanotes.core.db.TranscriptEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Cosa si esporta: una nota, una cartella con tutto quello che contiene, o l'archivio intero. */
sealed interface ExportScope {
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
)

/**
 * Le opzioni salvate come testo, per le preferenze.
 *
 * Indulgente in lettura: una stringa vuota, o scritta da una versione che aveva un'opzione in
 * meno, torna ai default invece di far fallire l'apertura del pannello di export.
 */
object ExportOptionsCodec {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  fun decode(raw: String): ExportOptions =
    if (raw.isBlank()) ExportOptions()
    else runCatching { json.decodeFromString(ExportOptions.serializer(), raw) }.getOrDefault(ExportOptions())

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
)

data class ExportPart(
  val id: String,
  val originalName: String,
  val fileName: String,
  val durationMs: Long,
  /** A che millisecondo della sessione comincia questa parte. */
  val startMs: Long,
  val sizeBytes: Long,
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
) {
  val folderCount: Int get() = notes.map { it.folderPath.joinToString("/") }.distinct().size
  val audioDurationMs: Long get() = notes.sumOf { it.audioDurationMs }
  val sessionCount: Int get() = notes.sumOf { it.sessions.size }
  val wordCount: Int get() = notes.sumOf { note -> note.sessions.sumOf { it.transcript?.wordCount ?: 0 } }
}
