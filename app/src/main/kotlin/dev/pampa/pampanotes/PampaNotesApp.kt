package dev.pampa.pampanotes

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.antigravity.fluidengine.config.EngineRemoteConfig
import dev.antigravity.fluidengine.foundation.EngineFlag
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.importing.HandwritingPages
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
import kotlinx.coroutines.runBlocking

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

  /** Vive quanto il processo: niente di quello che parte qui ha qualcosa da cui essere cancellato. */
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onCreate() {
    super.onCreate()
    AppNotifications.createChannels(this)
    // Un lavoro «in corso» all'avvio del processo e' un lavoro il cui processo e' morto: Android
    // ha ucciso l'app, o un aggiornamento l'ha sostituita a meta' trascrizione. Qui nessun worker
    // sta ancora girando, quindi torna in coda; senza, restava «in caricamento» per sempre e la
    // fila si fermava dietro di lui. Si fa prima che WorkManager possa far partire un worker —
    // dopo, si rischierebbe di rimettere in coda un lavoro vivo — ed e' un UPDATE solo.
    runBlocking(Dispatchers.IO) { runCatching { transcription.requeueInterrupted() } }
    // Il file di controllo, se la copia in cache e' vecchia. Non blocca niente: finche' non arriva,
    // l'app usa l'ultima risposta valida (o i default compilati).
    applicationScope.launch { runCatching { remoteConfig.refreshIfStale() } }
    // Il giro periodico dell'archivio segue l'impostazione: dirlo a ogni avvio e' idempotente, ed
    // e' l'unico modo per cui un'app aggiornata con l'archivio gia' acceso lo ritrovi in coda.
    applicationScope.launch {
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
  }

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
      .setWorkerFactory(workerFactory)
      .build()
}
