package dev.pampa.pampanotes.work

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.EndpointResolver
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.core.transcription.TailscaleHint
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * L'app di Tailscale vista da qui: c'e', e' accesa, accendila.
 *
 * «Accesa» si legge dalle reti del telefono, non chiedendo a Tailscale (che non lo dice a nessuno):
 * una rete VPN con un indirizzo di Tailscale ([TailscaleHint.isTailscaleAddress]). Un'altra VPN non
 * conta — Android ne tiene una alla volta, quindi con quella accesa Tailscale e' spento — e nemmeno
 * un Tailscale che esclude questa app dal tunnel: per lei e' come spento, ed e' giusto cosi'.
 *
 * Accenderla e' un broadcast documentato da Tailscale per Tasker (`CONNECT_VPN` a `IPNReceiver`),
 * mandato due volte a due secondi: su Android 16 il primo sveglia il servizio e solo il secondo lo
 * accende. Funziona se Tailscale ha gia' avuto il permesso della VPN una volta; altrimenti si apre
 * l'app, che lo chiede.
 */
object Tailscale {
  private const val ACTION_CONNECT = "com.tailscale.ipn.CONNECT_VPN"
  private const val RECEIVER = "com.tailscale.ipn.IPNReceiver"

  fun isInstalled(context: Context): Boolean = launchIntent(context) != null

  fun launchIntent(context: Context): Intent? =
    context.packageManager.getLaunchIntentForPackage(TailscaleHint.PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  /** La pagina dello store, per chi non ce l'ha. */
  fun storeIntent(): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${TailscaleHint.PACKAGE}"))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  @Suppress("DEPRECATION") // allNetworks: l'alternativa e' un callback, e qui serve una risposta adesso.
  fun isUp(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    return runCatching {
      manager.allNetworks.any { network ->
        val vpn = manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        vpn && manager.getLinkProperties(network)?.linkAddresses.orEmpty().any { TailscaleHint.isTailscaleAddress(it.address.address) }
      }
    }.getOrDefault(false)
  }

  /** Un segnale a ogni rete che arriva, se ne va o cambia indirizzi: Tailscale acceso o spento, il Wi-Fi di casa. */
  fun changes(context: Context): Flow<Unit> = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    if (manager == null) {
      awaitClose {}
      return@callbackFlow
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) { trySend(Unit) }
      override fun onLost(network: Network) { trySend(Unit) }
      override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) { trySend(Unit) }
    }
    // Senza NOT_VPN fra le capacita' richieste: la richiesta di serie la mette, e le VPN non si vedrebbero.
    val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
    runCatching { manager.registerNetworkCallback(request, callback) }
    awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
  }

  /** Chiede a Tailscale di accendersi; vero se ha potuto chiederlo (l'app c'e'). */
  suspend fun requestConnect(context: Context): Boolean {
    if (!isInstalled(context)) return false
    val intent = Intent(ACTION_CONNECT).setClassName(TailscaleHint.PACKAGE, RECEIVER)
    runCatching { context.sendBroadcast(intent) }.onFailure { return false }
    delay(2_000)
    if (!isUp(context)) runCatching { context.sendBroadcast(intent) }
    return true
  }

  /** Apre Tailscale, o lo store se non c'e'. Falso se non si e' aperto niente. */
  fun open(context: Context): Boolean = try {
    context.startActivity(launchIntent(context) ?: storeIntent())
    true
  } catch (missing: ActivityNotFoundException) {
    false
  }
}

/**
 * Il promemoria «accendi Tailscale», per la coda (la notifica) e per le schermate (la scheda): la
 * stessa domanda nei due posti, vedi [TailscaleHint].
 */
@Singleton
class TailscaleReminder @Inject constructor(
  @ApplicationContext private val context: Context,
  private val settingsStore: PampaSettingsStore,
  private val repository: TranscriptionRepository,
  private val resolver: EndpointResolver,
  private val scheduler: WorkScheduler,
) {

  /**
   * Serve adesso? Prima le domande gratuite (c'e' Tailscale di mezzo, e' gia' acceso), poi quella che
   * costa: `/health`, casa e poi fuori, quattro secondi al peggio. La cache del risolutore si butta,
   * perche' chi chiede ha appena visto cambiare una rete.
   */
  suspend fun needed(): Boolean {
    val settings = runCatching { settingsStore.current() }.getOrNull() ?: return false
    if (!settings.hasEndpoint || !TailscaleHint.usesTailscale(settings.endpointUrl, settings.endpointRemoteUrl)) return false
    if (Tailscale.isUp(context)) return false
    resolver.invalidate()
    val reachable = runCatching { repository.endpointState() == TranscriptionRepository.EndpointState.REACHABLE }.getOrDefault(true)
    return TailscaleHint.shouldRemind(settings.endpointUrl, settings.endpointRemoteUrl, tailscaleUp = false, computerReachable = reachable)
  }

  /**
   * Accende Tailscale e aspetta che la sua rete arrivi (dieci secondi al massimo): poi la coda del
   * computer si sveglia subito, invece di aspettare il suo prossimo tentativo.
   *
   * @return vero se Tailscale si e' acceso; falso se non c'e' o non ha risposto, e allora chi chiama
   *   apre l'app (che chiede il permesso della VPN, se manca).
   */
  suspend fun turnOn(): Boolean {
    if (!Tailscale.requestConnect(context)) return false
    val up = withTimeoutOrNull(10_000) {
      while (!Tailscale.isUp(context)) delay(500)
      true
    } ?: false
    if (up) {
      AppNotifications.cancelTailscale(context)
      resolver.invalidate()
      runCatching {
        if (repository.queuedCount(OpenAiCompatProvider.ID) > 0) scheduler.wake(OpenAiCompatProvider.ID)
      }
    }
    return up
  }

  /**
   * Dalla coda che si mette ad aspettare il computer (che quindi non ha risposto, ne' da casa ne' da
   * fuori): una notifica per attesa, e solo con Tailscale spento. Con Tailscale acceso nel frattempo
   * la notifica di prima non e' piu' vera, e se ne va.
   */
  suspend fun onWaiting(waitingSince: Long) {
    val settings = runCatching { settingsStore.current() }.getOrNull() ?: return
    val remind = TailscaleHint.shouldRemind(
      settings.endpointUrl,
      settings.endpointRemoteUrl,
      tailscaleUp = Tailscale.isUp(context),
      computerReachable = false,
    )
    if (!remind) {
      AppNotifications.cancelTailscale(context)
      return
    }
    if (runCatching { settingsStore.tailscaleRemindedFor() }.getOrDefault(0L) == waitingSince) return
    val waiting = runCatching { repository.queuedCount(OpenAiCompatProvider.ID) }.getOrDefault(0)
    AppNotifications.notifyTailscale(context, waiting, installed = Tailscale.isInstalled(context))
    runCatching { settingsStore.setTailscaleRemindedFor(waitingSince) }
  }

  /** Il computer ha risposto: il promemoria non serve piu'. */
  fun onReachable() = AppNotifications.cancelTailscale(context)
}

/**
 * «Accendi» nella notifica: chiede a Tailscale di accendersi e, se si accende, sveglia la coda. Da
 * una notifica non si puo' aprire un'app dal ricevitore (Android 12 vieta i «trampolini»), quindi
 * se Tailscale non risponde resta la notifica, il cui tocco apre Tailscale.
 *
 * Non esportato: lo manda solo la notifica dell'app.
 */
@AndroidEntryPoint
class TailscaleReceiver : BroadcastReceiver() {

  @Inject lateinit var reminder: TailscaleReminder

  override fun onReceive(context: Context, intent: Intent) {
    // Due secondi fra i due broadcast piu' dieci di attesa andrebbero oltre i dieci concessi a un
    // ricevitore: ci si ferma a otto e mezzo. Se Tailscale arriva dopo, la coda lo trova al suo
    // prossimo tentativo (un minuto, nella prima mezz'ora di attesa).
    val pending = goAsync()
    scope.launch {
      try {
        withTimeoutOrNull(8_500) { reminder.turnOn() }
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    private const val ACTION_TURN_ON = "dev.pampa.pampanotes.action.TAILSCALE_ON"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun intent(context: Context): Intent = Intent(context, TailscaleReceiver::class.java).setAction(ACTION_TURN_ON)
  }
}
