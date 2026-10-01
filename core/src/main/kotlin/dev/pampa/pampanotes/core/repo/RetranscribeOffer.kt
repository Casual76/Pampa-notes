package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.db.JobType
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import java.time.LocalDate
import java.time.ZoneId

/** Una grezza senza il testo: quanto basta per sapere chi l'ha fatta e quando. */
data class RawStamp(
  val id: String,
  val sessionId: String,
  val provider: String,
  val createdAt: Long,
)

/**
 * Le lezioni da rifare dopo la correzione dei buchi.
 *
 * Fino al companion 1.0.3 il computer di casa poteva perdere lunghi tratti di parlato in mezzo a una
 * lezione, senza dirlo. Il companion che non li perde piu' dichiara `holes` in `/health`; la prima
 * volta che l'app lo vede lo segna (`holesSince`), e da li' le lezioni trascritte dal computer prima
 * di quel momento — ma non prima del 24/09, quando il filtro che le bucava e' arrivato — si possono
 * rifare in un colpo, sempre sul computer, mai con Groq. Puro, per provarlo in JVM: chi chiama da'
 * le grezze, i lavori e le sessioni che un altro dispositivo sta trascrivendo.
 */
object RetranscribeOffer {

  /** Il giorno in cui il companion ha cominciato a bucare le lezioni: prima non c'e' niente da rifare. */
  val FILTER_ARRIVED: LocalDate = LocalDate.of(2026, 9, 24)

  /** La mezzanotte di [FILTER_ARRIVED] nel fuso del telefono. */
  fun fromMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    FILTER_ARRIVED.atStartOfDay(zone).toInstant().toEpochMilli()

  /**
   * Le sessioni da offrire, in ordine di trascrizione.
   *
   * Una sessione entra se la sua grezza di adesso (la piu' recente, come per la schermata) l'ha fatta
   * il computer di casa fra [fromMs] e [holesSince]; ne resta fuori se l'ultimo lavoro di
   * trascrizione ha risposto «nessuna parola» (rifarla da' la stessa risposta), se ha un lavoro in fila
   * o al lavoro qui, o se un altro dispositivo la sta trascrivendo ([busyElsewhere]). Una sessione
   * ritrascritta dopo [holesSince] ha una grezza piu' recente, e quindi non entra da se'.
   *
   * @param holesSince 0 finche' il computer non ha mai detto `holes`: allora non c'e' niente da offrire.
   */
  fun candidates(
    raws: List<RawStamp>,
    jobs: List<JobEntity>,
    busyElsewhere: Set<String>,
    fromMs: Long,
    holesSince: Long,
  ): List<String> {
    if (holesSince <= 0L || holesSince <= fromMs) return emptyList()
    val current = raws.groupBy { it.sessionId }
      .mapValues { (_, list) -> list.maxWith(compareBy<RawStamp>({ it.createdAt }, { it.id })) }
    val jobsBySession = jobs.groupBy { it.sessionId }
    return current.values
      .filter { it.provider == OpenAiCompatProvider.ID && it.createdAt >= fromMs && it.createdAt < holesSince }
      .filter { it.sessionId !in busyElsewhere }
      .filter { raw ->
        val sessionJobs = jobsBySession[raw.sessionId].orEmpty()
        val busy = sessionJobs.any { it.state.isActive }
        val lastTranscription = sessionJobs.filter { it.type == JobType.TRANSCRIBE }.maxByOrNull { it.createdAt }
        val silent = lastTranscription?.state == JobState.FAILED && lastTranscription.errorCode == FailedJobs.NO_SPEECH
        !busy && !silent
      }
      .sortedBy { it.createdAt }
      .map { it.sessionId }
  }
}
