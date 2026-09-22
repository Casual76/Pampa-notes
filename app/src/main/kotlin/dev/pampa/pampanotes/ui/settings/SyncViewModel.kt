package dev.pampa.pampanotes.ui.settings

import kotlinx.coroutines.CancellationException
import dev.pampa.pampanotes.core.sync.SyncApi
import dev.pampa.pampanotes.BuildConfig
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.db.SyncDao
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.work.SyncWorker
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** L'esito dell'ultimo giro finito, come l'ha lasciato il worker. */
data class SyncLast(val pushed: Int, val pulled: Int, val deleted: Int, val forked: Int, val rejected: Int, val error: String?)

data class SyncUiState(
  val enabled: Boolean = false,
  val serverUrl: String = "",
  val hasToken: Boolean = false,
  val deviceName: String = "",
  val deviceId: String = "",
  /** L'account Google della sessione, se si e' entrati cosi'. */
  val account: String = "",
  /** Il client ID compilato: vuoto vuol dire «niente Google, si entra con un codice». */
  val googleClientId: String = BuildConfig.GOOGLE_CLIENT_ID,
  val authBusy: Boolean = false,
  val authError: String? = null,
  val lastSyncAt: Long = 0L,
  val lastSyncError: String = "",
  /** Le modifiche locali che aspettano di salire. */
  val pending: Int = 0,
  val running: Boolean = false,
  val last: SyncLast? = null,
) {
  val configured: Boolean get() = serverUrl.isNotBlank() && hasToken
}

/**
 * La pagina Sincronizzazione: dove sta l'indice, con che accesso, e come sta andando.
 *
 * Il giro lo fa un worker e la pagina lo guarda da fuori (`WorkInfo`): un giro partito da qui
 * deve finire anche se la pagina si chiude a meta'.
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
  private val api: SyncApi,
  sync: SyncDao,
) : ViewModel() {

  private val _uiState = MutableStateFlow(SyncUiState())
  val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      settingsStore.settings.collect { s ->
        _uiState.update {
          it.copy(
            enabled = s.syncEnabled, serverUrl = s.syncServerUrl, hasToken = s.syncHasToken,
            deviceName = s.syncDeviceName.ifBlank { android.os.Build.MODEL.orEmpty() }, deviceId = s.syncDeviceId, account = s.syncAccount,
            lastSyncAt = s.lastSyncAt, lastSyncError = s.lastSyncError,
          )
        }
      }
    }
    viewModelScope.launch { sync.observeOutboxCount().collect { n -> _uiState.update { it.copy(pending = n) } } }
    viewModelScope.launch {
      scheduler.observeSync().collect { infos ->
        val running = infos.any { it.state == WorkInfo.State.RUNNING }
        val last = infos.filter { it.state == WorkInfo.State.SUCCEEDED && it.outputData.keyValueMap.isNotEmpty() }
          .maxByOrNull { it.outputData.getInt(SyncWorker.KEY_PUSHED, 0) + it.outputData.getInt(SyncWorker.KEY_PULLED, 0) }
          ?.outputData?.let {
            SyncLast(
              pushed = it.getInt(SyncWorker.KEY_PUSHED, 0), pulled = it.getInt(SyncWorker.KEY_PULLED, 0),
              deleted = it.getInt(SyncWorker.KEY_DELETED, 0), forked = it.getInt(SyncWorker.KEY_FORKED, 0),
              rejected = it.getInt(SyncWorker.KEY_REJECTED, 0), error = it.getString(SyncWorker.KEY_ERROR),
            )
          }
        _uiState.update { it.copy(running = running, last = last) }
      }
    }
  }

  fun setServerUrl(url: String) = viewModelScope.launch { settingsStore.setSyncServerUrl(url) }
  fun setToken(token: String) = viewModelScope.launch { settingsStore.setSyncToken(token) }
  fun setDeviceName(name: String) = viewModelScope.launch { settingsStore.setSyncDeviceName(name) }

  fun setEnabled(enabled: Boolean) {
    viewModelScope.launch {
      settingsStore.setSyncEnabled(enabled)
      scheduler.setPeriodicSync(enabled)
      if (enabled) scheduler.syncNow(force = true)
    }
  }

  fun syncNow() = scheduler.syncNow(force = true)

  /**
   * Entra con Google: l'ID token dal Credential Manager, la sessione dal Worker, il token di
   * sessione nel Keystore al posto del codice. Il `context` e' l'Activity: si apre un foglio.
   */
  fun signInWithGoogle(context: Context) {
    val clientId = BuildConfig.GOOGLE_CLIENT_ID
    if (clientId.isBlank() || _uiState.value.authBusy) return
    viewModelScope.launch {
      _uiState.update { it.copy(authBusy = true, authError = null) }
      try {
        val settings = settingsStore.current()
        val url = settings.syncServerUrl
        val idToken = GoogleIdentity.idToken(context, clientId)
        val login = api.loginWithGoogle(url, idToken, settingsStore.syncDeviceId(), settings.syncDeviceName.ifBlank { android.os.Build.MODEL.orEmpty() })
        settingsStore.setSyncToken(login.token)
        settingsStore.setSyncAccount(login.email ?: login.name ?: login.ownerId)
        // Appena dentro, un giro: se l'interruttore e' gia' acceso non c'e' motivo di aspettare.
        if (settings.syncEnabled) scheduler.syncNow(force = true)
        _uiState.update { it.copy(authBusy = false) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (cancelled: GoogleIdentity.Cancelled) {
        _uiState.update { it.copy(authBusy = false) }
      } catch (error: Exception) {
        _uiState.update { it.copy(authBusy = false, authError = error.message ?: error::class.java.simpleName) }
      }
    }
  }

  /** Chiude la sessione sul server (se ci arriva) e dimentica il token: da qui la sincronizzazione si ferma. */
  fun signOut() {
    viewModelScope.launch {
      val url = settingsStore.current().syncServerUrl
      val token = settingsStore.syncToken()
      if (url.isNotBlank() && token != null) runCatching { api.logout(url, token) }
      settingsStore.setSyncToken(null)
      settingsStore.setSyncAccount("")
      settingsStore.setSyncEnabled(false)
      scheduler.setPeriodicSync(false)
      _uiState.update { it.copy(authError = null) }
    }
  }
}
