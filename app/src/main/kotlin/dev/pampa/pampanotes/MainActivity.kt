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
    incomingIntents.tryEmit(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    incomingIntents.tryEmit(intent)
  }
}
