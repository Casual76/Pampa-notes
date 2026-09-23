package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.StatsDao
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptionRunEntity
import dev.pampa.pampanotes.core.model.Ids
import dev.pampa.pampanotes.core.stats.TranscriptionStats
import dev.pampa.pampanotes.core.transcription.GroqWhisperProvider
import dev.pampa.pampanotes.core.transcription.ServerReport
import dev.pampa.pampanotes.core.transcription.ServerReports
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Le statistiche delle trascrizioni: chi le scrive (la coda, a fine lavoro) e chi le guarda (la
 * home, la sessione). Il conto vero lo fa [TranscriptionStats.aggregate], che e' puro.
 */
@Singleton
class StatsRepository @Inject constructor(
  private val stats: StatsDao,
  private val sessions: SessionDao,
  private val parts: AudioPartDao,
) {

  fun observe(): Flow<TranscriptionStats> =
    combine(stats.observeRuns(), stats.observeTranscribedSessions()) { runs, lessons ->
      TranscriptionStats.aggregate(runs, lessons)
    }

  fun observeLatest(sessionId: String): Flow<TranscriptionRunEntity?> = stats.observeLatest(sessionId)

  /**
   * Scrive i numeri di una trascrizione appena finita e li restituisce, per la notifica.
   *
   * Non lancia mai, se non per una cancellazione: e' chiamata dopo che il lavoro e' gia' `DONE`, e
   * una statistica che non si scrive non deve trasformare in fallito un lavoro riuscito. Null quando
   * qualcosa e' andato storto: la notifica torna a dire solo le parole.
   *
   * @param job la riga com'era quando e' stata presa dalla fila: `attempts` conta i giri di prima.
   * @param startedAt quando il lavoro e' uscito dalla fila: da li' si misura, e da li' si prendono i
   *   resoconti del computer di casa ([ServerReports]).
   */
  suspend fun recordTranscription(job: JobEntity, startedAt: Long, transcript: TranscriptEntity): TranscriptionRunEntity? = try {
    val now = System.currentTimeMillis()
    val report = if (job.provider == GroqWhisperProvider.ID) null else ServerReport.merge(ServerReports.drainSince(startedAt))
    val partsMs = parts.bySession(job.sessionId).sumOf { it.durationMs }
    val span = stats.segmentSpan(transcript.id)
    val run = TranscriptionRunEntity(
      id = Ids.newId(),
      jobId = job.id,
      sessionId = job.sessionId,
      noteId = sessions.get(job.sessionId)?.noteId,
      provider = job.provider,
      model = job.model ?: transcript.model,
      device = if (job.provider == GroqWhisperProvider.ID) DEVICE_GROQ else report?.device,
      // Le durate delle parti prima: sono quelle che l'utente vede. Poi quello che il server dice di
      // aver ascoltato, poi l'ultimo segmento — una parte arrivata dal sync puo' avere durata zero.
      audioMs = partsMs.takeIf { it > 0 } ?: report?.audioMs?.takeIf { it > 0 } ?: span.endMs,
      wallMs = (now - startedAt).coerceAtLeast(0),
      processingMs = report?.processingMs,
      words = transcript.wordCount,
      segments = span.count,
      // Un tentativo dopo il primo riparte dai pezzi gia' su disco: il tempo misurato e' solo quello
      // dell'ultimo giro, e la velocita' verrebbe gonfiata. Si segna, e la media lo lascia fuori.
      resumed = job.attempts > 0,
      finishedAt = now,
    )
    stats.insert(run)
    run
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (error: Exception) {
    null
  }

  companion object {
    const val DEVICE_GROQ = "groq"
    const val DEVICE_CPU = "cpu"
  }
}
