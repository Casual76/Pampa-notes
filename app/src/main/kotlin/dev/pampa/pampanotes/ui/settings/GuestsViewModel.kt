package dev.pampa.pampanotes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.sync.GuestInfo
import dev.pampa.pampanotes.core.sync.SyncApi
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** L'invito appena creato: il token si vede una volta sola, come una password. */
data class GuestInvite(
  val name: String,
  val token: String,
  /** L'indirizzo del computer che l'amico usera': quello da fuori casa, se c'e'. */
  val computerUrl: String,
)

data class GuestsUiState(
  val loading: Boolean = true,
  /** L'indice in cloud e' configurato: senza, gli ospiti non hanno dove vivere. */
  val configured: Boolean = true,
  /** Il server personale e' configurato: senza, non c'e' un computer da prestare. */
  val hasComputer: Boolean = false,
  val computerUrl: String = "",
  /** Come il companion deve riconoscere il proprietario: l'account Google, o l'id. */
  val owner: String = "",
  val indexUrl: String = "",
  val guests: List<GuestInfo> = emptyList(),
  val invite: GuestInvite? = null,
  val busy: Boolean = false,
  val error: String? = null,
)

/**
 * Gli ospiti del computer: chi puo' trascrivere col tuo PC oltre a te.
 *
 * Un ospite e' un nome e un token che il Worker emette e il companion verifica. Qui si crea, si
 * vede quanto ha usato, si revoca. Il token compare una volta sola, nel testo dell'invito.
 */
@HiltViewModel
class GuestsViewModel @Inject constructor(
  private val settingsStore: PampaSettingsStore,
  private val api: SyncApi,
) : ViewModel() {

  private val _uiState = MutableStateFlow(GuestsUiState())
  val uiState: StateFlow<GuestsUiState> = _uiState.asStateFlow()

  init {
    load()
  }

  private suspend fun server(): Pair<String, String>? {
    val url = settingsStore.current().syncServerUrl.takeIf { it.isNotBlank() } ?: return null
    val token = settingsStore.syncToken() ?: return null
    return url to token
  }

  fun load() {
    viewModelScope.launch {
      val settings = settingsStore.current()
      val computer = settings.endpointRemoteUrl.ifBlank { settings.endpointUrl }
      _uiState.update {
        it.copy(
          loading = true, error = null,
          hasComputer = computer.isNotBlank(), computerUrl = computer,
          owner = settings.syncAccount, indexUrl = settings.syncServerUrl,
        )
      }
      val server = server()
      if (server == null) {
        _uiState.update { it.copy(loading = false, configured = false, guests = emptyList()) }
        return@launch
      }
      try {
        val guests = api.listGuests(server.first, server.second)
        _uiState.update { it.copy(loading = false, configured = true, guests = guests) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        _uiState.update { it.copy(loading = false, configured = true, error = error.message ?: error::class.java.simpleName) }
      }
    }
  }

  fun invite(name: String) {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || _uiState.value.busy) return
    viewModelScope.launch {
      val (url, token) = server() ?: return@launch
      _uiState.update { it.copy(busy = true, error = null) }
      try {
        val guest = api.createGuest(url, token, trimmed)
        val invite = GuestInvite(name = guest.name, token = guest.token.orEmpty(), computerUrl = _uiState.value.computerUrl)
        _uiState.update { s -> s.copy(busy = false, invite = invite, guests = listOf(guest.copy(token = null)) + s.guests) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        _uiState.update { it.copy(busy = false, error = error.message ?: error::class.java.simpleName) }
      }
    }
  }

  fun dismissInvite() = _uiState.update { it.copy(invite = null) }

  fun revoke(guestId: String) {
    viewModelScope.launch {
      val (url, token) = server() ?: return@launch
      try {
        api.revokeGuest(url, token, guestId)
        _uiState.update { s -> s.copy(guests = s.guests.filterNot { it.guestId == guestId }, error = null) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        _uiState.update { it.copy(error = error.message ?: error::class.java.simpleName) }
      }
    }
  }
}
