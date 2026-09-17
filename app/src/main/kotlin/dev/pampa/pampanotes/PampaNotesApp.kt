package dev.pampa.pampanotes

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.antigravity.fluidengine.config.EngineRemoteConfig
import dev.antigravity.fluidengine.foundation.EngineFlag
import dev.pampa.pampanotes.work.AppNotifications
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

  /** Vive quanto il processo: niente di quello che parte qui ha qualcosa da cui essere cancellato. */
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onCreate() {
    super.onCreate()
    AppNotifications.createChannels(this)
    // Il file di controllo, se la copia in cache e' vecchia. Non blocca niente: finche' non arriva,
    // l'app usa l'ultima risposta valida (o i default compilati).
    applicationScope.launch { runCatching { remoteConfig.refreshIfStale() } }
  }

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
      .setWorkerFactory(workerFactory)
      .build()
}
