package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.core.transcription.CompanionTranscription
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest

/** L'offerta com'e' adesso: quali lezioni, quanto audio, e il nome del computer che le rifara'. */
data class HolesOffer(
  val sessionIds: List<String>,
  val durationMs: Long,
  val computerName: String,
)

/**
 * «N lezioni trascritte prima della correzione dei buchi»: le trova ([RetranscribeOffer]), le rimette
 * in fila sul computer di casa, e si ricorda di un «Non ora». La mostrano Lavori e la home.
 */
@Singleton
class RetranscribeOffers @Inject constructor(
  private val transcripts: TranscriptDao,
  private val jobs: JobDao,
  private val parts: AudioPartDao,
  private val settingsStore: PampaSettingsStore,
  private val transcription: TranscriptionRepository,
) {

  /** Null quando non c'e' niente da offrire, o quando l'utente ha detto «Non ora». */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun observe(): Flow<HolesOffer?> = combine(
    transcripts.observeRawStamps(),
    jobs.observeAll(),
    transcription.observeElsewhere(),
    combine(settingsStore.holesSince, settingsStore.holesOfferDismissed, settingsStore.settings) { since, dismissed, settings ->
      Triple(since, dismissed, settings)
    },
  ) { raws, allJobs, elsewhere, (since, dismissed, settings) ->
    if (dismissed || !settings.hasEndpoint) return@combine null
    val ids = RetranscribeOffer.candidates(raws, allJobs, elsewhere.keys, RetranscribeOffer.fromMillis(), since)
    if (ids.isEmpty()) null else ids to settings.endpointName
  }.distinctUntilChanged().mapLatest { found ->
    found?.let { (ids, name) -> HolesOffer(ids, ids.sumOf { id -> parts.bySession(id).sumOf { it.durationMs } }, name) }
  }

  /**
   * Chi apre Lavori o la home prima di qualunque trascrizione deve vedere l'offerta lo stesso: se il
   * computer non ha ancora detto `holes` a nessuno, glielo si chiede una volta per processo.
   */
  suspend fun probe() {
    if (!probed.compareAndSet(false, true)) return
    if (!settingsStore.current().hasEndpoint || settingsStore.holesSince.first() > 0L) return
    try {
      val companion = transcription.providerFor(OpenAiCompatProvider.ID) as? CompanionTranscription ?: return
      transcription.noteCompanionFeatures(companion.features())
    } catch (cancelled: CancellationException) {
      probed.set(false)
      throw cancelled
    } catch (ignored: Exception) {
      Unit
    }
  }

  /**
   * In fila, tutte sul computer di casa: mai Groq, qualunque sia il servizio delle impostazioni —
   * sono lezioni che il computer ha gia' avuto, e l'offerta parla di lui.
   *
   * @return quante sono entrate in fila.
   */
  suspend fun retranscribe(sessionIds: List<String>): Int =
    sessionIds.count { transcription.enqueue(it, TranscriptionProviderId.CUSTOM) != null }

  suspend fun dismiss() = settingsStore.setHolesOfferDismissed()

  private companion object {
    val probed = AtomicBoolean(false)
  }
}
