package dev.pampa.pampanotes

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.antigravity.fluidengine.config.EngineRemoteConfig
import dev.antigravity.fluidengine.foundation.EngineFlag
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.importing.HandwritingPages
import dev.pampa.pampanotes.core.importing.RealDatesBackfill
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.GroqWhisperProvider
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.work.AppNotifications
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** I flag remoti, dichiarati con il valore con cui la build e' stata provata. */
object Flags {
  /** Il raffinamento via LLM, spegnibile da remoto se un modello sparisce o cambia comportamento. */
  val Refinement = EngineFlag(key = "refinement", default = true)
}

@HiltAndroidApp
class PampaNotesApp : Application(), Configuration.Provider {
  @Inject lateinit var workerFactory: HiltWorkerFactory
  @Inject lateinit var remoteConfig: EngineRemoteConfig
  @Inject lateinit var settingsStore: PampaSettingsStore
  @Inject lateinit var scheduler: WorkScheduler
  @Inject lateinit var transcription: TranscriptionRepository
  @Inject lateinit var handwriting: HandwritingPages
  @Inject lateinit var notes: NoteDao
  @Inject lateinit var files: AppFiles
  @Inject lateinit var realDates: RealDatesBackfill

  /** Vive quanto il processo: niente di quello che parte qui ha qualcosa da cui essere cancellato. */
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onCreate() {
    super.onCreate()
    AppNotifications.createChannels(this)
    // Il file di controllo, se la copia in cache e' vecchia. Non blocca niente: finche' non arriva,
    // l'app usa l'ultima risposta valida (o i default compilati).
    applicationScope.launch { runCatching { remoteConfig.refreshIfStale() } }
    // Il giro periodico dell'archivio segue l'impostazione: dirlo a ogni avvio e' idempotente, ed
    // e' l'unico modo per cui un'app aggiornata con l'archivio gia' acceso lo ritrovi in coda.
    applicationScope.launch {
      runCatching {
        // Un lavoro «in corso» all'avvio del processo e' un lavoro il cui processo e' morto: Android
        // ha ucciso l'app, o un aggiornamento l'ha sostituita a meta' trascrizione. Torna in coda;
        // senza, restava «in caricamento» per sempre e la fila si fermava dietro di lui. Non piu'
        // con un `runBlocking` qui sopra — un disco lento all'avvio e' un ANR — ma una volta per
        // processo, e prima che qualunque worker prenda un lavoro: ogni worker la chiama a sua
        // volta, e chi arriva secondo aspetta che il primo abbia finito (vedi il repository).
        transcription.requeueInterruptedOnce()
      }
      runCatching {
        val settings = settingsStore.current()
        scheduler.setPeriodicArchive(settings.archiveEnabled, settings.archiveOnlyUnmetered)
        scheduler.setPeriodicSync(settings.syncEnabled)
        // Un giro all'apertura: e' il momento in cui si vuole vedere quello che si e' scritto altrove.
        if (settings.syncEnabled) scheduler.syncNow()
        // Una fila che aspetta il computer di casa: aprire l'app e' un buon momento per riprovare,
        // prima del tentativo rimandato. Il worker guarda se risponde, e se no torna ad aspettare.
        if (transcription.queuedCount(OpenAiCompatProvider.ID) > 0) scheduler.wake(OpenAiCompatProvider.ID)
        if (transcription.queuedCount(GroqWhisperProvider.ID) > 0) scheduler.kick(GroqWhisperProvider.ID)
      }
    }
    // Quello che un prelievo dal computer o un import interrotti hanno lasciato in `cacheDir/tmp`.
    applicationScope.launch(Dispatchers.IO) { runCatching { files.sweepTemp() } }
    // Le pagine scritte a mano delle note importate prima che l'app le sapesse disegnare: una volta
    // sola, e solo dai `.sdocx` che stanno qui. Se il giro si interrompe si rifa' al prossimo avvio,
    // e salta i file che hanno gia' le loro pagine.
    applicationScope.launch {
      runCatching {
        if (!settingsStore.handwritingBackfillDone()) {
          handwriting.backfill { noteId -> notes.get(noteId)?.title }
          settingsStore.setHandwritingBackfillDone()
          if (settingsStore.current().syncEnabled) scheduler.syncNow()
        }
      }
    }
    // Le date vere delle note importate prima che l'app le sapesse leggere: quella del `.sdocx` e
    // delle registrazioni invece del giorno dell'import. Si ripete ai prossimi avvii solo per
    // quello che aspettava il computer di casa spento; un errore su una nota non ferma le altre.
    applicationScope.launch {
      runCatching {
        val summary = realDates.run()
        if (summary.changed && settingsStore.current().syncEnabled) scheduler.syncNow()
      }.onFailure { android.util.Log.w("PampaNotes", "date vere: giro fallito", it) }
    }
  }

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
      .setWorkerFactory(workerFactory)
      .build()
}
