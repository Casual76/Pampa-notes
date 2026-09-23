package dev.pampa.pampanotes.work

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Il permesso delle notifiche, chiesto quando serve: al primo lavoro messo in coda.
 *
 * Da Android 13 senza permesso la coda gira lo stesso, ma in silenzio: niente barra, niente «fatto»,
 * niente tasto per annullare. Nessuno lo chiedeva, e il primo segno che una lezione era trascritta
 * era andare a guardare. Chiederlo all'avvio sarebbe chiederlo a chi non ha ancora capito a cosa
 * serve; al primo lavoro in coda invece la risposta e' ovvia. Una volta sola: chi dice di no non se
 * lo sente richiedere a ogni lezione, e lo puo' cambiare dalle impostazioni di sistema.
 */
@HiltViewModel
class NotificationPermissionViewModel @Inject constructor(
  repository: TranscriptionRepository,
  private val settingsStore: PampaSettingsStore,
) : ViewModel() {

  val activeJobs: Flow<Int> = repository.observeActiveCount()

  /** Vero una volta sola nella vita dell'app: chi lo riceve chiede il permesso. */
  suspend fun claimAsk(): Boolean {
    if (settingsStore.notificationPermissionAsked()) return false
    settingsStore.setNotificationPermissionAsked()
    return true
  }
}

/**
 * Da mettere una volta sola nella shell (`MainApp`): guarda la coda, e al primo lavoro in coda
 * chiede il permesso delle notifiche se manca. Sotto Android 13 non fa niente, perche' li' il
 * permesso non esiste.
 */
@Composable
fun NotificationPermissionGate(viewModel: NotificationPermissionViewModel = hiltViewModel()) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
  val context = LocalContext.current
  val active by viewModel.activeJobs.collectAsStateWithLifecycle(initialValue = 0)
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
  LaunchedEffect(active > 0) {
    if (active <= 0) return@LaunchedEffect
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!granted && viewModel.claimAsk()) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
  }
}
