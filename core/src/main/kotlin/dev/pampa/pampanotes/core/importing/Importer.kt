package dev.pampa.pampanotes.core.importing

import android.net.Uri
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import java.io.File

/**
 * Un candidato all'importazione, gia' guardato ma non ancora importato.
 *
 * Il wizard mostra questo: cos'e', quanto pesa, e se ce l'abbiamo gia'. Il file e' gia' stato
 * copiato in uno spazio nostro, perche' un URI che arriva da una condivisione vale una volta sola
 * e la schermata di destinazione arriva dopo.
 */
data class ImportCandidate(
  val id: String,
  val uri: Uri?,
  /** La copia in `cacheDir/tmp`, gia' fatta. Null solo per il testo incollato. */
  val file: File?,
  val displayName: String,
  val kind: SourceKind,
  val mime: String,
  val sizeBytes: Long,
  val sha256: String,
  /** Il testo, quando arriva dagli appunti o da una condivisione di solo testo. */
  val inlineText: String? = null,
  /** La nota in cui lo stesso contenuto e' gia' stato importato, se c'e'. */
  val duplicateOfNoteId: String? = null,
  val duplicateOfNoteTitle: String? = null,
  /** La durata, per l'audio: serve al wizard per dire "42 min" prima di importare. */
  val durationMs: Long = 0,
  /**
   * Quello che si e' letto da un file di Samsung Notes, gia' all'ispezione.
   *
   * Serve prima dell'import, non dopo: e' il momento in cui il wizard puo' dire «Fichte, quattro
   * paragrafi, due registrazioni» e proporre titolo e cartella, invece di mostrare un nome di file.
   */
  val sdocx: SdocxDocument? = null,
  /**
   * La nota gia' importata da Samsung Notes con lo stesso titolo, se c'e': una versione nuova
   * dello stesso file. Con lo stesso contenuto e' un doppione ([duplicateOfNoteId]); con un
   * contenuto diverso e' un aggiornamento, e il wizard lo propone per primo.
   */
  val updateOfNoteId: String? = null,
  val updateOfNoteTitle: String? = null,
) {
  val isAudio: Boolean get() = kind == SourceKind.AUDIO

  /** Si puo' aggiornare una nota che c'e' gia', invece di crearne una seconda. */
  val canUpdate: Boolean get() = updateOfNoteId != null && !isDuplicate

  /** Una nota di Samsung Notes letta bene: ha qualcosa dentro e sappiamo cos'e'. */
  val isSamsungNote: Boolean get() = sdocx != null && !sdocx.isEmpty
  val isDuplicate: Boolean get() = duplicateOfNoteId != null
}

/** Quello che un lettore tira fuori da un file. */
data class ExtractedText(
  val text: String,
  val status: SourceStatus = SourceStatus.OK,
  /** Una riga per l'utente quando qualcosa e' andato storto a meta': "3 pagine saltate". */
  val detail: String? = null,
)

/**
 * Chi sa leggere un tipo di file.
 *
 * Solo l'estrazione del testo: dove va a finire lo decide [ImportCoordinator]. Un lettore che
 * sapesse anche in quale nota scrivere sarebbe un lettore da riscrivere al primo cambio di UI.
 */
interface TextExtractor {
  val kind: SourceKind

  /** Null quando il file non contiene testo (un audio, un'immagine senza OCR). */
  suspend fun extract(file: File, displayName: String): ExtractedText?
}
