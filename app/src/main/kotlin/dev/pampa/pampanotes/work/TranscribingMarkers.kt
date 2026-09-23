package dev.pampa.pampanotes.work

import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.sync.deviceLabel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Tiene il segno «in trascrizione su» di questo dispositivo in passo con la sua coda (vedi
 * `TranscribingMarker`), e lo fa salire subito.
 *
 * Guarda la coda invece di farsi chiamare dal worker, apposta: un lavoro smette di lavorare in
 * almeno sei modi (finito, fallito, annullato, fermato dal sistema, tornato ad aspettare il computer
 * o il limite di Groq), e ognuno che si dimenticasse di togliere il segno lascerebbe la lezione
 * bloccata sugli altri dispositivi per tre ore. Qui c'e' una regola sola: al lavoro qui ⇔ segno di
 * qui. Vive nel processo, che e' lo stesso del worker: se il worker gira, questo guarda.
 *
 * Si riguarda anche a orologio ([TICK_MS]), per rinnovare il segno di un lavoro lungo prima che
 * scada, e quando cambia il nome del dispositivo: il segno col nome vecchio sembrerebbe di un
 * altro, anche a questo telefono.
 */
@Singleton
class TranscribingMarkers @Inject constructor(
  private val repository: TranscriptionRepository,
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
) {
  fun start(scope: CoroutineScope) {
    scope.launch {
      val ticks = flow {
        while (true) {
          emit(Unit)
          delay(TICK_MS)
        }
      }
      var previousLabel: String? = null
      combine(
        repository.observeRunningTranscriptions(),
        settingsStore.settings.map { it.deviceLabel() }.distinctUntilChanged(),
        ticks,
      ) { _, label, _ -> label }.collect { label ->
        try {
          var changed = false
          val old = previousLabel
          if (old != null && old != label) changed = repository.releaseMarkersOf(old)
          previousLabel = label
          changed = repository.reconcileMarkers() || changed
          if (changed && settingsStore.current().syncEnabled) scheduler.syncSoon()
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          // Al prossimo cambio della coda ci si riprova: il segno e' un avviso, non un dato.
          android.util.Log.w("TranscribingMarkers", "segno non aggiornato: ${error.message}")
        }
      }
    }
  }

  private companion object {
    /** Un quarto d'ora: il rinnovo arriva molto prima che il segno scada (vedi TranscribingMarker). */
    const val TICK_MS = 15L * 60 * 1000
  }
}
