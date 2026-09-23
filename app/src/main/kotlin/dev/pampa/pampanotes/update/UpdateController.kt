package dev.pampa.pampanotes.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.antigravity.fluidengine.foundation.AppUpdateInstallState
import dev.antigravity.fluidengine.foundation.AppUpdater
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel
import dev.pampa.pampanotes.BuildConfig
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Quello che la home e Informazioni mostrano degli aggiornamenti. */
data class UpdateUiState(
  val currentVersion: String = BuildConfig.VERSION_NAME,
  /** Una build di prova ha un altro pacchetto: la release si installerebbe accanto, non sopra. */
  val supported: Boolean = !BuildConfig.DEBUG,
  val available: AvailableAppUpdate? = null,
  val checking: Boolean = false,
  /** L'ultimo controllo e' fallito (rete, GitHub): si dice, ma senza allarmi. */
  val checkFailed: Boolean = false,
  val lastCheckedAt: Long = 0L,
  val install: AppUpdateInstallState? = null,
  val ignoredVersion: String = "",
  val beta: Boolean = false,
  /** Android non lascia ancora installare da quest'app: si e' aperta l'impostazione, si aspetta il ritorno. */
  val needsPermission: Boolean = false,
) {
  /** La home la propone solo se non ci si e' gia' detto «non ora» per questa versione. */
  val offerOnHome: Boolean get() = available != null && available.version != ignoredVersion || install != null || needsPermission
  val installing: Boolean get() = install != null && install !is AppUpdateInstallState.Error && install !is AppUpdateInstallState.Installed
}

/**
 * Gli aggiornamenti dentro l'app: l'app non passa da un negozio, e senza questo una versione nuova
 * la trova solo chi apre il Pampa Store.
 *
 * Il lavoro vero lo fa l'engine ([AppUpdater]: legge `manifest.json`, confronta, scarica l'APK dalla
 * release di GitHub, lo installa con il PackageInstaller e il permesso «app sconosciute»). Qui c'e'
 * solo quando chiedere e cosa ricordarsi: un controllo a ogni apertura, al massimo uno all'ora, la
 * versione a cui si e' detto «non ora», e il canale. Un oggetto solo per il processo, perche' un
 * download avviato dalla home deve continuare a vedersi in Informazioni.
 */
@Singleton
class UpdateController @Inject constructor(
  @ApplicationContext private val context: Context,
  private val updater: AppUpdater,
  private val settings: PampaSettingsStore,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _state = MutableStateFlow(UpdateUiState())
  val state: StateFlow<UpdateUiState> = _state.asStateFlow()
  private var installJob: Job? = null

  init {
    scope.launch { settings.updateIgnored.collect { v -> _state.update { it.copy(ignoredVersion = v) } } }
    scope.launch { settings.updateBeta.collect { v -> _state.update { it.copy(beta = v) } } }
  }

  /**
   * All'avvio, in silenzio. Una volta all'ora al massimo, non due al giorno: la versione trovata non si
   * ricorda fra un'apertura e l'altra, e con un limite lungo un'app riaperta non la proponeva piu'.
   * Il manifesto e' un file di pochi kB.
   */
  fun checkIfDue() {
    if (!_state.value.supported) return
    scope.launch {
      val last = settings.updateLastCheck()
      if (System.currentTimeMillis() - last >= CHECK_EVERY_MS) check()
    }
  }

  /** «Cerca aggiornamenti»: adesso, anche se l'ultimo controllo e' di un minuto fa. */
  fun checkNow() {
    if (!_state.value.supported) return
    scope.launch { check() }
  }

  private suspend fun check() {
    if (_state.value.checking) return
    _state.update { it.copy(checking = true, checkFailed = false) }
    val channel = if (settings.updateBeta.first()) UpdateChannel.BETA else UpdateChannel.STABLE
    val result = updater.check(currentVersionName = BuildConfig.VERSION_NAME, channel = channel)
    val now = System.currentTimeMillis()
    result.onSuccess { settings.setUpdateLastCheck(now) }
    _state.update {
      it.copy(
        checking = false,
        checkFailed = result.isFailure,
        lastCheckedAt = if (result.isSuccess) now else it.lastCheckedAt,
        // Un controllo fallito non cancella quello che si sapeva: la versione nuova resta offerta.
        available = result.getOrElse { _ -> it.available },
      )
    }
  }

  fun install() {
    val update = _state.value.available ?: return
    if (installJob?.isActive == true) return
    // Il permesso «installa app sconosciute» lo chiediamo noi, con parole nostre: l'engine
    // aprirebbe l'impostazione e chiuderebbe con un errore, e al ritorno toccherebbe a chi usa l'app
    // capire che deve premere di nuovo. Qui si apre l'impostazione e, tornati, si riparte da soli.
    if (!context.packageManager.canRequestPackageInstalls()) {
      _state.update { it.copy(needsPermission = true, install = null) }
      runCatching {
        context.startActivity(
          Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
      }
      return
    }
    _state.update { it.copy(needsPermission = false) }
    installJob = scope.launch {
      updater.install(update).collect { step -> _state.update { it.copy(install = step) } }
    }
  }

  /** Di ritorno nell'app: se il permesso adesso c'e', l'installazione chiesta prima riparte. */
  fun onResume() {
    if (_state.value.needsPermission && context.packageManager.canRequestPackageInstalls()) install()
  }

  /** «Non ora»: questa versione non torna nella home; la prossima si'. */
  fun ignore() {
    val version = _state.value.available?.version ?: return
    scope.launch { settings.setUpdateIgnored(version) }
  }

  /** Chiude un errore o un «installato» rimasti a schermo. */
  fun dismissInstall() {
    if (_state.value.installing) return
    _state.update { it.copy(install = null, needsPermission = false) }
  }

  fun setBeta(on: Boolean) {
    scope.launch {
      settings.setUpdateBeta(on)
      check()
    }
  }

  private companion object {
    const val CHECK_EVERY_MS = 60L * 60 * 1000
  }
}
