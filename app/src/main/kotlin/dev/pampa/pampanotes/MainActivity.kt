package dev.pampa.pampanotes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.pampa.pampanotes.ui.MainApp
import kotlinx.coroutines.flow.MutableSharedFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    incomingIntents.tryEmit(intent)
  }
}
