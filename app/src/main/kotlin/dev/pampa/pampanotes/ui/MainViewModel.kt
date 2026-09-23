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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
   * Un link di configurazione arrivato e non ancora confermato.
   *
   * Un link `pampanotes://sync` o `pampanotes://endpoint` dice all'app dove mandare le note o le
   * registrazioni. Qualunque pagina web, messaggio o QR puo' aprirne uno, e applicarlo senza
   * chiedere vorrebbe dire che un tocco su un link ricevuto da uno sconosciuto manda i propri
   * appunti al suo server. Quindi si ferma qui, la shell mostra l'indirizzo, e si applica solo con
   * «Collega». Per il QR del proprio computer e' un tocco in piu', ed e' il tocco giusto.
   */
  private val _pendingLink = MutableStateFlow<PendingLink?>(null)
  val pendingLink: StateFlow<PendingLink?> = _pendingLink.asStateFlow()

  /** I link confermati e applicati: la shell apre la pagina giusta e lo dice. */
  private val _linkApplied = MutableSharedFlow<IntentOutcome>(extraBufferCapacity = 4)
  val linkApplied: SharedFlow<IntentOutcome> = _linkApplied.asSharedFlow()

  /**
   * Solo i due link di configurazione, l'indice e il computer di casa. Il primo avvio passa da qui
   * e non da [onIntent]: chi inquadra il QR del companion mentre e' ancora al passo «Chi
   * trascrive» deve poterlo collegare subito, non dopo aver finito. Una condivisione invece aspetta
   * la shell: il wizard dell'import non ha dove aprirsi sopra il benvenuto.
   *
   * Il link non si applica: si mette da parte in [pendingLink] per la conferma.
   *
   * `null` se l'intent non e' un link nostro.
   */
  fun onLinkIntent(intent: Intent): IntentOutcome? {
    val pending = SyncLink.parse(intent.dataString)?.let { PendingLink.Sync(it) }
      ?: EndpointLink.parse(intent.dataString)?.let { PendingLink.Endpoint(it) }
      ?: return null
    // Il link vale una volta. Una rotazione ricrea l'Activity con lo stesso intent, e senza questo
    // ogni giro richiederebbe la stessa conferma sopra le impostazioni che nel frattempo sono cambiate.
    intent.data = null
    _pendingLink.value = pending
    return IntentOutcome.LinkPending
  }

  /** «Annulla»: il link se ne va senza aver toccato niente. */
  fun dismissPendingLink() {
    _pendingLink.value = null
  }

  /** «Collega»: adesso, e solo adesso, le impostazioni cambiano. */
  fun confirmPendingLink() {
    val pending = _pendingLink.value ?: return
    _pendingLink.value = null
    viewModelScope.launch {
      when (pending) {
        is PendingLink.Sync -> {
          val link = pending.link
          settingsStore.setSyncServerUrl(link.url)
          link.token?.let { settingsStore.setSyncToken(it) }
          link.name?.let { settingsStore.setSyncDeviceName(it) }
          // Con un codice il link dice tutto quello che serve: confermarlo e poi dover trovare
          // l'interruttore da soli era un collegamento che non collegava niente.
          if (!link.token.isNullOrBlank()) {
            settingsStore.setSyncEnabled(true)
            scheduler.setPeriodicSync(true)
            scheduler.syncNow(force = true)
          }
          _linkApplied.emit(IntentOutcome.SyncLinked(link.url))
        }
        is PendingLink.Endpoint -> {
          val link = pending.link
          // Il modello resta quello che c'era: il QR dice dove sta il server, non quale modello usare.
          settingsStore.setEndpoint(link.url, name = "", model = settings.value.endpointModel)
          settingsStore.setEndpointRemoteUrl(link.remoteUrl.orEmpty())
          // Anche quando e' nullo: il QR e' la verita' su quel server, e un token vecchio rimasto
          // dentro farebbe rispondere 401 a un server che non ne vuole.
          settingsStore.setEndpointToken(link.token)
          settingsStore.setPreferredProvider(TranscriptionProviderId.CUSTOM)
          // Sono i setter di chi scrive a mano: il computer e' sporco, e sale all'account adesso,
          // cosi' gli altri dispositivi lo trovano ricollegato senza rifare il QR.
          if (settings.value.syncEnabled) scheduler.syncNow()
          _linkApplied.emit(IntentOutcome.EndpointLinked(link.url))
        }
      }
    }
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

  /**
   * Il selettore e' tornato senza file. Se era partito da «importa in questa nota», la nota
   * ricordata se ne va: altrimenti il prossimo import dal tasto generale finirebbe in quella nota,
   * senza che nessuno l'abbia chiesto.
   */
  fun onPickerCancelled() {
    pendingNoteId = null
  }

  private var pendingNoteId: String? = null
}

/** Cosa ha trovato [MainViewModel.onIntent], e quindi dove deve andare la navigazione. */
sealed interface IntentOutcome {
  /** Roba da importare: si apre il wizard. */
  data object Import : IntentOutcome

  /** Un link di configurazione, messo da parte in attesa della conferma. */
  data object LinkPending : IntentOutcome

  /** Il QR del server companion, confermato: le impostazioni sono salvate, si apre la pagina dei servizi. */
  data class EndpointLinked(val url: String) : IntentOutcome

  /** Il link dell'indice in cloud, confermato: si apre la pagina Sincronizzazione. */
  data class SyncLinked(val url: String) : IntentOutcome

  /** Niente di nostro: si lascia alla navigazione, che magari ci riconosce una rotta. */
  data object None : IntentOutcome
}

/** Un link di configurazione in attesa di «Collega». */
sealed interface PendingLink {
  /** L'indirizzo che si mostra: e' quello che l'utente deve riconoscere prima di dire si'. */
  val url: String

  data class Sync(val link: SyncLink) : PendingLink {
    override val url: String get() = link.url
  }

  data class Endpoint(val link: EndpointLink) : PendingLink {
    override val url: String get() = link.url
  }
}
