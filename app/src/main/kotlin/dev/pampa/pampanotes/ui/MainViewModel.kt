package dev.pampa.pampanotes.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.storage.EngineSettingsStore
import dev.pampa.pampanotes.core.settings.EndpointLink
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.settings.SyncLink
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.ui.importing.ImportRequest
import dev.pampa.pampanotes.ui.importing.ImportRequestHolder
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
  engineSettingsStore: EngineSettingsStore,
  private val settingsStore: PampaSettingsStore,
  private val importRequests: ImportRequestHolder,
  private val scheduler: dev.pampa.pampanotes.work.WorkScheduler,
) : ViewModel() {

  /** Il tema. Parte dai default compilati: un fotogramma con l'accento giusto vale piu' di uno vuoto. */
  val engineSettings: StateFlow<EngineSettings> = engineSettingsStore.settings
    .stateIn(viewModelScope, SharingStarted.Eagerly, EngineSettings())

  val settings: StateFlow<PampaSettings> = settingsStore.settings
    .stateIn(viewModelScope, SharingStarted.Eagerly, PampaSettings())

  /**
   * Se il primo avvio e' gia' stato fatto. `null` finche' non si sa.
   *
   * Tre stati e non due: il default compilato di [PampaSettings] dice "non fatto", e partire da
   * quello vorrebbe dire un lampo di benvenuto a ogni apertura per chi l'app ce l'ha da mesi.
   */
  val onboardingDone: StateFlow<Boolean?> = settingsStore.settings
    .map { it.onboardingDone }
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  fun completeOnboarding() {
    viewModelScope.launch { settingsStore.setOnboardingDone(true) }
  }

  /**
   * Cosa portava un intent arrivato da fuori, e cosa deve fare la navigazione di conseguenza.
   *
   * Prima la condivisione, poi il QR del server: un intent di SEND non e' un link, e un link
   * `pampanotes://endpoint` non e' un file. [ImportRequest.fromIntent] sa gia' distinguere.
   */
  fun onIntent(intent: Intent): IntentOutcome {
    ImportRequest.fromIntent(intent)?.let { request ->
      importRequests.offer(request)
      return IntentOutcome.Import
    }
    return onLinkIntent(intent) ?: IntentOutcome.None
  }

  /**
   * Solo i due link di configurazione, l'indice e il computer di casa. Il primo avvio passa da qui
   * e non da [onIntent]: chi inquadra il QR del companion mentre e' ancora al passo «Chi
   * trascrive» deve vedere il computer collegato subito, non dopo aver finito. Una condivisione
   * invece aspetta la shell: il wizard dell'import non ha dove aprirsi sopra il benvenuto.
   *
   * `null` se l'intent non e' un link nostro.
   */
  fun onLinkIntent(intent: Intent): IntentOutcome? {
    SyncLink.parse(intent.dataString)?.let { link ->
      intent.data = null
      viewModelScope.launch {
        settingsStore.setSyncServerUrl(link.url)
        link.token?.let { settingsStore.setSyncToken(it) }
        link.name?.let { settingsStore.setSyncDeviceName(it) }
      }
      return IntentOutcome.SyncLinked(link.url)
    }
    val link = EndpointLink.parse(intent.dataString) ?: return null
    // Il link vale una volta. Una rotazione ricrea l'Activity con lo stesso intent, e senza questo
    // ogni giro risalverebbe le stesse impostazioni sopra quelle che nel frattempo si sono cambiate.
    intent.data = null
    viewModelScope.launch {
      // Il modello resta quello che c'era: il QR dice dove sta il server, non quale modello usare.
      settingsStore.setEndpoint(link.url, name = "", model = settings.value.endpointModel)
      settingsStore.setEndpointRemoteUrl(link.remoteUrl.orEmpty())
      // Anche quando e' nullo: il QR e' la verita' su quel server, e un token vecchio rimasto
      // dentro farebbe rispondere 401 a un server che non ne vuole.
      settingsStore.setEndpointToken(link.token)
      settingsStore.setPreferredProvider(TranscriptionProviderId.CUSTOM)
      // Sono i setter di chi scrive a mano: il computer e' sporco, e sale all'account adesso, cosi'
      // gli altri dispositivi lo trovano ricollegato senza rifare il QR.
      if (settings.value.syncEnabled) scheduler.syncNow()
    }
    return IntentOutcome.EndpointLinked(link.url)
  }

  /** Dal selettore file, o da "importa in questa nota" (dove gli URI arrivano subito dopo). */
  fun onFilesPicked(uris: List<Uri>, intoNoteId: String?) {
    if (uris.isEmpty() && intoNoteId != null) {
      // La nota di destinazione arriva prima del selettore: si ricorda per la chiamata successiva.
      pendingNoteId = intoNoteId
      return
    }
    importRequests.offer(ImportRequest(uris = uris, intoNoteId = intoNoteId ?: pendingNoteId))
    pendingNoteId = null
  }

  private var pendingNoteId: String? = null
}

/** Cosa ha trovato [MainViewModel.onIntent], e quindi dove deve andare la navigazione. */
sealed interface IntentOutcome {
  /** Roba da importare: si apre il wizard. */
  data object Import : IntentOutcome

  /** Il QR del server companion. Le impostazioni sono gia' salvate: si apre la pagina dei servizi. */
  data class EndpointLinked(val url: String) : IntentOutcome

  /** Il link dell'indice in cloud: indirizzo e codice salvati, si apre la pagina Sincronizzazione. */
  data class SyncLinked(val url: String) : IntentOutcome

  /** Niente di nostro: si lascia alla navigazione, che magari ci riconosce una rotta. */
  data object None : IntentOutcome
}
