package dev.pampa.pampanotes

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.ui.MainApp
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  @Inject lateinit var scheduler: WorkScheduler
  @Inject lateinit var transcription: TranscriptionRepository

  /**
   * Gli intent che arrivano da fuori: condivisione, deep link del server. Con replay 1 perche' il
   * primo arriva prima che la composizione sia in piedi a raccoglierlo.
   */
  private val incomingIntents = MutableSharedFlow<Intent>(replay = 1, extraBufferCapacity = 4)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MainApp(incomingIntents = incomingIntents)
    }
    // L'intent di partenza vale una volta sola. Dopo una rotazione (o dopo che il sistema ha ucciso
    // il processo e l'Activity rinasce da uno stato salvato) e' ancora lo stesso intent, e
    // riemetterlo vorrebbe dire rifare l'import di una condivisione gia' importata. Aperta dai
    // recenti, Android ripresenta l'ultimo intent con cui era partita: la condivisione di ieri.
    val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
    if (savedInstanceState == null && !fromHistory) incomingIntents.tryEmit(intent)
  }

  /**
   * Tornare nell'app sveglia la fila del computer di casa, se ne ha una. L'avvio del processo lo
   * faceva gia' (`PampaNotesApp`), ma a processo vivo — il worker che l'aveva appena tenuto in piedi
   * — riaprire l'app non cambiava niente, e la fila aspettava il suo tentativo. Al massimo una volta
   * ogni mezzo minuto: una rotazione non e' un motivo per bussare di nuovo al PC.
   */
  override fun onStart() {
    super.onStart()
    val now = SystemClock.elapsedRealtime()
    if (lastWake != 0L && now - lastWake < WAKE_EVERY_MS) return
    lastWake = now
    lifecycleScope.launch {
      runCatching {
        if (transcription.queuedCount(OpenAiCompatProvider.ID) > 0) scheduler.wake(OpenAiCompatProvider.ID)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    incomingIntents.tryEmit(intent)
  }

  private companion object {
    const val WAKE_EVERY_MS = 30_000L

    /** Per processo, non per Activity: una rotazione ne crea una nuova. */
    var lastWake = 0L
  }
}
