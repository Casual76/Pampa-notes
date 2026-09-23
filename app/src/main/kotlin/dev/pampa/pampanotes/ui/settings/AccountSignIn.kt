package dev.pampa.pampanotes.ui.settings

import android.content.Context
import dev.pampa.pampanotes.BuildConfig
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.sync.SyncApi
import dev.pampa.pampanotes.core.sync.SyncReport
import dev.pampa.pampanotes.core.sync.SyncRepository
import dev.pampa.pampanotes.work.WorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Accedi con Google», dal primo avvio o dalla pagina Sincronizzazione: una strada sola.
 *
 * Chi entra con l'account vuole ritrovare le sue cose, non configurare un servizio. Per questo
 * l'accesso fa tutto il resto da solo: l'indice compilato se non se n'e' scritto un altro
 * ([BuildConfig.DEFAULT_SYNC_URL]), la sessione, la sincronizzazione **accesa** — con il giro
 * periodico, come l'interruttore — e un primo giro, che porta anche il computer di casa
 * (`ComputerSync`). Senza, chi entra dal primo avvio avrebbe un account e nessuna nota, e dovrebbe
 * scoprire da solo l'interruttore in fondo a una pagina che non ha mai visto.
 */
@Singleton
class AccountSignIn @Inject constructor(
  private val settingsStore: PampaSettingsStore,
  private val api: SyncApi,
  private val repository: SyncRepository,
  private val scheduler: WorkScheduler,
  private val notes: NoteDao,
) {

  /** Com'e' andata: chi e' entrato, e — se si e' aspettato il primo giro — cosa ha portato. */
  data class Result(
    val account: String,
    /** `null` se il giro non si e' aspettato, o se non e' andato: lo rifara' il worker. */
    val report: SyncReport?,
    val notes: Int,
  )

  val available: Boolean get() = BuildConfig.GOOGLE_CLIENT_ID.isNotBlank()

  /**
   * @param waitForFirstSync il primo avvio aspetta il giro, per dire «sono arrivate 42 note» e
   *   per mostrare il computer gia' collegato nel passo dopo; la pagina Sincronizzazione lo lascia
   *   al worker e guarda quello.
   * @throws GoogleIdentity.Cancelled se l'utente chiude il foglio: non e' un errore da mostrare.
   */
  suspend fun signIn(context: Context, waitForFirstSync: Boolean): Result {
    val clientId = BuildConfig.GOOGLE_CLIENT_ID
    check(clientId.isNotBlank()) { "accesso Google non configurato in questa build" }
    // Prima dell'accesso, e solo se vuoto: cambiare indirizzo butta l'identita' del dispositivo
    // (`setSyncServerUrl`), e un indirizzo scritto a mano e' una scelta da non scavalcare.
    if (settingsStore.current().syncServerUrl.isBlank()) settingsStore.setSyncServerUrl(BuildConfig.DEFAULT_SYNC_URL)
    val settings = settingsStore.current()
    val idToken = GoogleIdentity.idToken(context, clientId)
    val login = api.loginWithGoogle(
      settings.syncServerUrl,
      idToken,
      settingsStore.syncDeviceId(),
      settings.syncDeviceName.ifBlank { android.os.Build.MODEL.orEmpty() },
    )
    val account = login.email ?: login.name ?: login.ownerId
    settingsStore.setSyncToken(login.token)
    settingsStore.setSyncAccount(account)
    settingsStore.setSyncEnabled(true)
    scheduler.setPeriodicSync(true)

    if (!waitForFirstSync) {
      scheduler.syncNow(force = true)
      return Result(account, report = null, notes = notes.count())
    }
    val report = repository.syncNow()
    // Un giro andato male (rete, un archivio enorme interrotto) non fa fallire l'accesso: la
    // sessione c'e', e il worker ci riprova con i suoi tempi.
    if (!report.ok) scheduler.syncNow(force = true)
    return Result(account, report = report.takeIf { it.ok }, notes = notes.count())
  }
}
