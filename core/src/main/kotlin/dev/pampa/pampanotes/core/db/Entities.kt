package dev.pampa.pampanotes.core.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * Il modello dei dati, tutto in un posto.
 *
 * Gli id sono UUID in forma di stringa: un backup ripristinato su un altro telefono non deve
 * rinumerare niente, e due dispositivi che un giorno si fondessero non collidono. Ogni riga porta
 * createdAt/updatedAt in millisecondi epoch. Gli enum vanno su colonna come nome (Room lo fa da
 * solo), cosi' lo schema resta leggibile con un client SQLite qualsiasi.
 */

/** Una cartella. Puo' stare dentro un'altra: la UI mostra un livello per volta con il breadcrumb. */
@Serializable
@Entity(
  tableName = "folders",
  foreignKeys = [
    ForeignKey(entity = FolderEntity::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.CASCADE),
  ],
  indices = [Index("parentId")],
)
data class FolderEntity(
  @PrimaryKey val id: String,
  val name: String,
  val parentId: String? = null,
  val sortOrder: Int = 0,
  /** Il nome di un FluidTone: da qui la tessera prende il suo colore e la schermata il suo fondale. */
  val tone: String? = null,
  /** La chiave di un'icona del catalogo, oppure un'emoji: la cartella si riconosce da lontano. */
  val icon: String? = null,
  val createdAt: Long,
  val updatedAt: Long,
)

/** Una nota: il testo dell'utente in Markdown, piu' tutto quello che le si aggancia. */
@Serializable
@Entity(
  tableName = "notes",
  foreignKeys = [
    ForeignKey(entity = FolderEntity::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE),
  ],
  indices = [Index("folderId"), Index("updatedAt")],
)
data class NoteEntity(
  @PrimaryKey val id: String,
  val folderId: String,
  val title: String,
  val body: String = "",
  val pinned: Boolean = false,
  /** ISO 639-1 (it, en, ...) o null: la lingua guida la trascrizione di default delle sue sessioni. */
  val language: String? = null,
  val createdAt: Long,
  val updatedAt: Long,
)

@Serializable
@Entity(
  tableName = "note_tags",
  primaryKeys = ["noteId", "tag"],
  foreignKeys = [
    ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
  ],
  indices = [Index("tag")],
)
data class NoteTagEntity(
  val noteId: String,
  val tag: String,
)

/**
 * Una sessione: una lezione, un incontro, un giorno. Le sue parti audio si trascrivono come un
 * testo solo; una registrazione interrotta e ripresa e' la stessa sessione, un altro giorno no.
 */
@Serializable
@Entity(
  tableName = "sessions",
  foreignKeys = [
    ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
  ],
  indices = [Index(value = ["noteId", "position"])],
)
data class SessionEntity(
  @PrimaryKey val id: String,
  val noteId: String,
  val title: String = "",
  /** yyyy-MM-dd, per stampare "Sessione 2 - 12 settembre" e ordinare. */
  val date: String,
  val position: Int,
  /** La trascrizione che si mostra e si esporta: grezza o una delle raffinate. Nessuna FK, apposta: il ciclo lo evitiamo a mano. */
  val activeTranscriptId: String? = null,
  val createdAt: Long,
  val updatedAt: Long,
  /**
   * Il dispositivo che la sta trascrivendo adesso, col nome che ha nel sync (`deviceLabel()`), e da
   * quando. Null quando nessuno ci lavora. `jobs` resta di ogni dispositivo; questo e' il riflesso
   * che viaggia, perche' il tablet non offra «Trascrivi» per una lezione su cui il telefono sta gia'
   * lavorando. Lo scrive solo il dispositivo che trascrive, e senza alzare [updatedAt]: e' uno stato,
   * non una modifica, e non deve vincere su un titolo cambiato altrove (vedi `TranscribingMarker`).
   */
  val transcribingOn: String? = null,
  val transcribingSince: Long? = null,
)

/** Un file audio dentro una sessione, copiato in filesDir/audio: gli URI condivisi non durano. */
@Serializable
@Entity(
  tableName = "audio_parts",
  foreignKeys = [
    ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
  ],
  indices = [Index(value = ["sessionId", "position"]), Index("sha256")],
)
data class AudioPartEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
  val position: Int,
  /** Relativo a filesDir/audio. */
  val fileName: String,
  val originalName: String,
  val mime: String,
  val sizeBytes: Long,
  val durationMs: Long,
  val sha256: String,
  val createdAt: Long,
  /** Quando il computer di casa ha ricevuto questo file. Zero: non ancora, o mai. */
  @ColumnInfo(defaultValue = "0") val archivedAt: Long = 0,
)

enum class TranscriptKind { RAW, REFINED }

enum class TranscriptStatus { OK, SUSPICIOUS }

/** Un testo prodotto da una macchina: la grezza di Whisper, o una raffinata da un LLM che la cita come genitore. */
@Serializable
@Entity(
  tableName = "transcripts",
  foreignKeys = [
    ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
  ],
  indices = [Index("sessionId"), Index("parentId")],
)
data class TranscriptEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
  val kind: TranscriptKind,
  /** "groq" | "custom" per la grezza; "groq" per le raffinate. */
  val provider: String,
  val model: String,
  val language: String? = null,
  val text: String,
  /** Il preset di raffinamento (CLEAN, STRUCTURED, CUSTOM) per le raffinate. */
  val preset: String? = null,
  val promptHash: String? = null,
  /** La grezza da cui una raffinata e' nata. Cancellare la grezza cancella anche loro (a mano, nel repository). */
  val parentId: String? = null,
  val wordCount: Int,
  val status: TranscriptStatus = TranscriptStatus.OK,
  val createdAt: Long,
)

/** Un segmento con i tempi, sia relativi alla parte sia assoluti nella sessione: il lettore salta per questi. */
@Serializable
@Entity(
  tableName = "segments",
  foreignKeys = [
    ForeignKey(entity = TranscriptEntity::class, parentColumns = ["id"], childColumns = ["transcriptId"], onDelete = ForeignKey.CASCADE),
  ],
  indices = [Index(value = ["transcriptId", "sessionStartMs"])],
)
data class SegmentEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val transcriptId: String,
  val partId: String,
  val indexInPart: Int,
  val partStartMs: Long,
  val partEndMs: Long,
  val sessionStartMs: Long,
  val sessionEndMs: Long,
  val text: String,
  val noSpeechProb: Float? = null,
  val avgLogProb: Float? = null,
  /**
   * Le parole con i loro tempi, relativi a [partStartMs]. Vedi `WordTimings.encode`.
   *
   * Relativi e non assoluti perche' i tempi dentro la parte sono un fatto sul file audio e non
   * cambiano mai, mentre quelli di sessione cambiano a ogni riordino: cosi' riordinare resta una
   * ricomposizione e non una riscrittura di ogni parola di ogni segmento.
   */
  val wordsJson: String? = null,
  /**
   * Vero quando le parole sono una stima (Groq) e non un allineamento fonetico (WhisperX).
   *
   * Il default sta anche nella colonna e non solo in Kotlin: senza, la migrazione automatica non
   * saprebbe cosa scrivere nelle righe che c'erano gia'.
   */
  @ColumnInfo(defaultValue = "0")
  val wordsEstimated: Boolean = false,
)

enum class SourceKind { TEXT, MARKDOWN, PDF, DOCX, IMAGE, AUDIO, SDOCX, CLIPBOARD, SHARE, OTHER }

enum class SourceStatus { OK, PARTIAL, FAILED }

/** Da dove viene un pezzo di nota: il file originale resta in filesDir/sources, per rileggerlo o esportarlo. */
@Serializable
@Entity(
  tableName = "sources",
  foreignKeys = [
    ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
  ],
  indices = [Index("noteId"), Index("sha256")],
)
data class SourceEntity(
  @PrimaryKey val id: String,
  val noteId: String,
  val kind: SourceKind,
  val originalName: String,
  val mime: String,
  val sizeBytes: Long,
  val sha256: String,
  /** Relativo a filesDir/sources; null per gli appunti incollati. */
  val storedFileName: String? = null,
  val extractedChars: Int = 0,
  val status: SourceStatus = SourceStatus.OK,
  /** Una riga per l'utente: "3 pagine saltate", "testo non trovato nell'archivio". */
  val detail: String? = null,
  val importedAt: Long,
  /** Quando il computer di casa ha ricevuto il file originale. Zero: non ancora, o niente da mandare. */
  @ColumnInfo(defaultValue = "0") val archivedAt: Long = 0,
  /**
   * La fonte da cui questa e' stata ricavata: una pagina scritta a mano disegnata da un `.sdocx`
   * punta al `.sdocx`. Null per tutto quello che l'utente ha importato lui. Quando il `.sdocx` se ne
   * va — un aggiornamento della nota — se ne vanno anche le pagine ricavate da lui.
   */
  val derivedFromId: String? = null,
)

enum class JobType { TRANSCRIBE, REFINE }

enum class JobState {
  QUEUED, PREPARING, UPLOADING, TRANSCRIBING, STITCHING, DONE, FAILED, CANCELLED, CANCEL_REQUESTED;

  val isActive: Boolean get() = this == QUEUED || this == PREPARING || this == UPLOADING || this == TRANSCRIBING || this == STITCHING || this == CANCEL_REQUESTED
  val isTerminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
  val isRunning: Boolean get() = this == PREPARING || this == UPLOADING || this == TRANSCRIBING || this == STITCHING
}

/** Un lavoro in coda: la UI lo guarda, il worker lo porta avanti, entrambi lo aggiornano solo da qui. */
@Serializable
@Entity(
  tableName = "jobs",
  foreignKeys = [
    ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
  ],
  indices = [Index(value = ["state", "createdAt"]), Index("sessionId")],
)
data class JobEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
  val type: JobType,
  /** "groq" | "custom". Una coda per provider. */
  val provider: String,
  val model: String? = null,
  val state: JobState = JobState.QUEUED,
  val chunkTotal: Int = 0,
  val chunkDone: Int = 0,
  /** 0..1 dentro la fase corrente. */
  val progress: Float = 0f,
  /** Una riga per l'utente: "Chunk 3 di 7", "In attesa del limite Groq (42 s)". */
  val phase: String? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
  val attempts: Int = 0,
  /** Per i raffinamenti: il preset e il prompt personalizzato. Per le trascrizioni: opzioni in JSON. */
  val optionsJson: String? = null,
  val createdAt: Long,
  val updatedAt: Long,
  val finishedAt: Long? = null,
)

@Serializable
@Entity(tableName = "export_presets")
data class ExportPresetEntity(
  @PrimaryKey val id: String,
  val name: String,
  val optionsJson: String,
  val lastUsedAt: Long,
  val isDefault: Boolean = false,
)

/**
 * L'indice di ricerca delle note. Tenuto in passo dai trigger in [PampaDatabase.SEARCH_TRIGGERS],
 * non da Room: un contenuto esterno si aggancia al rowid, e il rowid di una tabella con chiave
 * testuale non promette di restare lo stesso dopo un VACUUM.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, tokenizerArgs = ["remove_diacritics=2"], notIndexed = ["noteId"])
@Entity(tableName = "notes_fts")
data class NoteFts(
  val noteId: String,
  val title: String,
  val body: String,
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, tokenizerArgs = ["remove_diacritics=2"], notIndexed = ["transcriptId", "sessionId", "noteId"])
@Entity(tableName = "transcripts_fts")
data class TranscriptFts(
  val transcriptId: String,
  val sessionId: String,
  val noteId: String,
  val text: String,
)
