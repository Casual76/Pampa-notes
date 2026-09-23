package dev.pampa.pampanotes.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pampa.pampanotes.ui.settings.AccountSignIn
import dev.pampa.pampanotes.ui.settings.GoogleIdentity
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Il passo «Hai gia' usato Pampa Notes?»: com'e' andato l'accesso. */
data class AccountStepState(
  val busy: Boolean = false,
  val error: String? = null,
  /** Le note che ci sono dopo il primo giro; `null` se il giro non e' finito bene e le porta il worker. */
  val notes: Int? = null,
)

/**
 * Il primo avvio, per la parte che non e' una preferenza: l'accesso con l'account. Le preferenze
 * le scrive [dev.pampa.pampanotes.ui.settings.SettingsViewModel], come dalla pagina Impostazioni.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
  private val accountSignIn: AccountSignIn,
) : ViewModel() {

  val accountAvailable: Boolean get() = accountSignIn.available

  private val _account = MutableStateFlow(AccountStepState())
  val account: StateFlow<AccountStepState> = _account.asStateFlow()

  /** Il `context` e' l'Activity: il Credential Manager apre un foglio. */
  fun signIn(context: Context) {
    if (_account.value.busy) return
    viewModelScope.launch {
      _account.update { it.copy(busy = true, error = null) }
      try {
        // Qui si aspetta il primo giro: e' quello che porta il computer di casa prima del passo
        // «Chi trascrive», e che fa dire quante note sono arrivate invece di un «fatto».
        val result = accountSignIn.signIn(context, waitForFirstSync = true)
        _account.update { it.copy(busy = false, notes = result.notes.takeIf { result.report != null }) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (cancelled: GoogleIdentity.Cancelled) {
        _account.update { it.copy(busy = false) }
      } catch (error: Exception) {
        _account.update { it.copy(busy = false, error = error.message ?: error::class.java.simpleName) }
      }
    }
  }
}
