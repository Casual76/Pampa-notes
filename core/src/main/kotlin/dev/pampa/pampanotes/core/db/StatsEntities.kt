package dev.pampa.pampanotes.core.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/*
 * Le statistiche delle trascrizioni: quanto veloce, quanto, su cosa.
 *
 * Dalla versione 7 la tabella **si sincronizza** (sta in `SYNCED_TABLES`): la home dice la velocita'
 * e i record di tutti i dispositivi dell'account, e ogni riga dice chi l'ha misurata
 * ([TranscriptionRunEntity.deviceName]). Prima restava sul telefono che l'aveva scritta, e il tablet
 * che riceveva la trascrizione dall'indice in cloud non sapeva quanto ci aveva messo il computer di
 * casa. Quello che si puo' contare dalle trascrizioni stesse — ore, parole, ritmo di chi parla — si
 * conta ancora da li' ([StatsDao.observeTranscribedSessions]), non da qui.
 */

/**
 * Una trascrizione finita, con i suoi numeri. Si scrive una volta sola, quando il lavoro arriva a
 * `DONE`, e non si tocca piu': e' storia, non stato.
 *
 * Nessuna FK verso la sessione, apposta: cancellare una lezione non cancella il fatto che il
 * computer di casa l'abbia trascritta a cinquanta volte il tempo reale, e la velocita' media della
 * home non deve cambiare perche' si e' fatto ordine. Per lo stesso motivo nel sync non ha un padre:
 * una corsa arriva anche se la sua sessione, altrove, non c'e' piu'.
 */
@Serializable
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
  /**
   * Il dispositivo che l'ha misurata, col nome che usa nel sync. Vuoto per le corse di prima della
   * versione 7 finche' il primo giro di sync non le rivendica ([StatsDao.claimUnnamed]).
   */
  @ColumnInfo(defaultValue = "") val deviceName: String = "",
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

  /** Per il sync: una corsa arrivata da un altro dispositivo. `@Upsert`, mai `REPLACE` (vedi `SYNC_TRIGGERS`). */
  @Upsert
  suspend fun upsert(run: TranscriptionRunEntity)

  @Query("SELECT * FROM transcription_runs WHERE id = :id")
  suspend fun get(id: String): TranscriptionRunEntity?

  @Query("DELETE FROM transcription_runs WHERE id = :id")
  suspend fun delete(id: String)

  /**
   * Le corse senza nome e mai concordate con l'indice sono di questo dispositivo: sono quelle scritte
   * prima della versione 7, quando la tabella non viaggiava e non si diceva chi le aveva fatte. Si
   * prendono il nome di qui, e l'`UPDATE` fa scattare il trigger dell'outbox: e' cosi' che la storia
   * di prima sale al primo giro, senza una seminatura a parte. Una corsa gia' arrivata dal sync ha
   * la sua voce in `sync_meta`, e non si tocca anche se e' senza nome.
   */
  @Query(
    "UPDATE transcription_runs SET deviceName = :deviceName WHERE deviceName = '' " +
      "AND id NOT IN (SELECT rowId FROM sync_meta WHERE tbl = 'transcription_runs')",
  )
  suspend fun claimUnnamed(deviceName: String): Int

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
