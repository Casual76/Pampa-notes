package dev.pampa.pampanotes.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * Le statistiche delle trascrizioni: quanto veloce, quanto, su cosa.
 *
 * Una tabella di questo dispositivo, che **non si sincronizza** (non sta in `SYNCED_TABLES`): quanto
 * ci ha messo il telefono a far trascrivere una lezione e' un fatto di questo telefono, e il tablet
 * che riceve la trascrizione dall'indice in cloud non l'ha fatta. Tutto quello che si puo' contare
 * anche su una trascrizione arrivata dal sync — ore, parole, ritmo di chi parla — si conta invece
 * dalle trascrizioni stesse ([StatsDao.observeTranscribedSessions]), non da qui.
 */

/**
 * Una trascrizione finita, con i suoi numeri. Si scrive una volta sola, quando il lavoro arriva a
 * `DONE`, e non si tocca piu': e' storia, non stato.
 *
 * Nessuna FK verso la sessione, apposta: cancellare una lezione non cancella il fatto che il
 * computer di casa l'abbia trascritta a cinquanta volte il tempo reale, e la velocita' media della
 * home non deve cambiare perche' si e' fatto ordine.
 */
@Entity(
  tableName = "transcription_runs",
  indices = [Index(value = ["sessionId", "finishedAt"]), Index("finishedAt")],
)
data class TranscriptionRunEntity(
  @PrimaryKey val id: String,
  val jobId: String,
  val sessionId: String,
  val noteId: String? = null,
  /** "groq" | "custom", come la coda. */
  val provider: String,
  val model: String,
  /** "groq", "cuda", "cpu"; null quando il computer di casa non l'ha detto. */
  val device: String? = null,
  /** L'audio trascritto: la somma delle parti della sessione. */
  val audioMs: Long,
  /**
   * Quanto e' durato il lavoro visto dal telefono: da quando e' uscito dalla fila a quando la
   * trascrizione e' stata salvata. Decodifica, invio e attesa del servizio compresi; l'attesa in
   * coda no, perche' quella dice quanto era piena la coda, non quanto e' veloce chi trascrive.
   */
  val wallMs: Long,
  /** Il tempo di lavoro dichiarato dal computer di casa (`processing_s`), se l'ha dichiarato. */
  val processingMs: Long? = null,
  val words: Int,
  val segments: Int,
  /**
   * Il lavoro e' ripartito dopo un'interruzione: i pezzi gia' fatti si sono riletti dal disco, e
   * [wallMs] misura solo l'ultimo giro. Le parole contano, la velocita' no — sarebbe una bugia per
   * eccesso.
   */
  val resumed: Boolean = false,
  val finishedAt: Long,
)

/**
 * Una lezione trascritta, come la vede la home: quante parole, quanto audio.
 *
 * [audioMs] e' la durata delle parti che la trascrizione copre davvero (quelle con almeno un
 * segmento): una parte importata dopo non allunga la lezione di minuti in cui nessuno ha scritto
 * niente. [spokenEndMs] e' il tempo dell'ultimo segmento, per quando la durata delle parti manca.
 */
data class TranscribedSessionRow(
  val sessionId: String,
  val noteId: String,
  val noteTitle: String,
  val sessionTitle: String,
  val sessionDate: String,
  val words: Int,
  val audioMs: Long,
  val spokenEndMs: Long,
)

data class SegmentSpan(val count: Int, val endMs: Long)

@Dao
interface StatsDao {
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(run: TranscriptionRunEntity)

  @Query("SELECT * FROM transcription_runs ORDER BY finishedAt DESC")
  fun observeRuns(): Flow<List<TranscriptionRunEntity>>

  /** Quanti segmenti ha una trascrizione e dove finisce l'ultimo, senza caricare le parole di ognuno. */
  @Query("SELECT COUNT(*) AS count, COALESCE(MAX(sessionEndMs), 0) AS endMs FROM segments WHERE transcriptId = :transcriptId")
  suspend fun segmentSpan(transcriptId: String): SegmentSpan

  @Query("SELECT * FROM transcription_runs WHERE sessionId = :sessionId ORDER BY finishedAt DESC LIMIT 1")
  fun observeLatest(sessionId: String): Flow<TranscriptionRunEntity?>

  /**
   * Le grezze con la loro durata. Solo le grezze: una raffinata e' la stessa lezione detta di nuovo,
   * e contarla raddoppierebbe ore e parole.
   */
  @Query(
    "SELECT t.sessionId AS sessionId, s.noteId AS noteId, n.title AS noteTitle, s.title AS sessionTitle, " +
      "s.date AS sessionDate, t.wordCount AS words, " +
      "COALESCE((SELECT SUM(p.durationMs) FROM audio_parts p WHERE p.id IN " +
      "(SELECT DISTINCT g.partId FROM segments g WHERE g.transcriptId = t.id)), 0) AS audioMs, " +
      "COALESCE((SELECT MAX(g.sessionEndMs) FROM segments g WHERE g.transcriptId = t.id), 0) AS spokenEndMs " +
      "FROM transcripts t JOIN sessions s ON s.id = t.sessionId JOIN notes n ON n.id = s.noteId " +
      "WHERE t.kind = 'RAW'",
  )
  fun observeTranscribedSessions(): Flow<List<TranscribedSessionRow>>
}
