package dev.pampa.pampanotes.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chi sveglia le code.
 *
 * Una coda per provider, con `KEEP`: chiedere due volte non fa partire due worker, fa trovare il
 * lavoro nuovo a quello che sta gia' girando. E' il modo in cui la concorrenza resta uno per
 * provider senza un semaforo scritto a mano.
 */
@Singleton
class WorkScheduler @Inject constructor(
  @ApplicationContext private val context: Context,
) {

  fun kick(providerId: String) {
    WorkManager.getInstance(context).enqueueUniqueWork(workName(providerId), ExistingWorkPolicy.KEEP, queueRequest(providerId))
  }

  /**
   * Sveglia la coda di un provider quando c'e' motivo di credere che il suo servizio risponda.
   *
   * Un worker che ha trovato il computer di casa muto si e' chiuso e ha lasciato in coda il suo
   * tentativo successivo ([retryForEndpoint]: un minuto, o cinque dopo mezz'ora). Se intanto
   * qualcuno il computer l'ha visto (l'archivio che ha appena caricato, la sonda, «Prova», l'app
   * che si apre), aspettare quel tentativo e' tempo perso: si **sostituisce**. Mai pero' un worker
   * che sta lavorando: sostituirlo vorrebbe dire interrompere una trascrizione a meta'.
   */
  suspend fun wake(providerId: String) {
    val manager = WorkManager.getInstance(context)
    val running = manager.getWorkInfosForUniqueWorkFlow(workName(providerId)).first().any { it.state == WorkInfo.State.RUNNING }
    manager.enqueueUniqueWork(workName(providerId), if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, queueRequest(providerId))
  }

  /**
   * Riprende la coda di un provider fra [delayMillis]: il limite di Groq ha chiesto di aspettare ore.
   *
   * `APPEND_OR_REPLACE` perche' lo chiede il worker che sta ancora girando: si accoda a lui e parte
   * quando lui ha chiuso, dopo l'attesa. Un `REPLACE` lo annullerebbe mentre scrive; un `KEEP` non
   * accoderebbe niente. Chi nel frattempo chiede con [kick] trova questo in attesa e lo lascia: il
   * limite e' dell'account, e un lavoro nuovo lo troverebbe uguale.
   */
  fun kickAfter(providerId: String, delayMillis: Long) {
    val request = OneTimeWorkRequestBuilder<TranscriptionQueueWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setInputData(workDataOf(TranscriptionQueueWorker.KEY_PROVIDER to providerId))
      .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
      .addTag(TAG)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(workName(providerId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }

  /**
   * Il prossimo sguardo al computer di casa, fra [delayMillis]: lo chiede il worker che l'ha
   * trovato muto, subito prima di chiudersi con `success`.
   *
   * Non il `retry` di WorkManager, perche' la sua attesa cresce (esponenziale o lineare, fino a
   * cinque ore) e non si puo' fermare a un tetto: un PC riavviato faceva aspettare minuti una coda
   * che poteva ripartire subito (vedi `EndpointWait`). E nemmeno un lavoro della coda accodato
   * dietro quello che sta chiudendo (`APPEND_OR_REPLACE`), che era la prima versione: ogni
   * tentativo allungava la catena della coda, una voce al minuto per tutta l'attesa.
   *
   * Un timer ([EndpointRetryWorker]) con un nome suo per provider e `REPLACE`: ce n'e' al massimo
   * uno, rimetterlo sostituisce il precedente, e non tocca mai la coda — che il timer, scadendo,
   * sveglia con [wake], con le sue regole (mai un worker che sta lavorando).
   */
  fun retryForEndpoint(providerId: String, delayMillis: Long) {
    val request = OneTimeWorkRequestBuilder<EndpointRetryWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setInputData(workDataOf(TranscriptionQueueWorker.KEY_PROVIDER to providerId))
      .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
      .addTag(TAG)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(retryName(providerId), ExistingWorkPolicy.REPLACE, request)
  }

  /** Il timer non serve piu': la coda e' partita per conto suo, o e' stata fermata. */
  fun cancelEndpointRetry(providerId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(retryName(providerId))
  }

  private fun queueRequest(providerId: String) = OneTimeWorkRequestBuilder<TranscriptionQueueWorker>()
    .setConstraints(
      Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build(),
    )
    .setInputData(workDataOf(TranscriptionQueueWorker.KEY_PROVIDER to providerId))
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
    .addTag(TAG)
    .build()

  /**
   * La sonda: ogni quarto d'ora, finche' c'e' un lavoro in fila per il computer di casa, si guarda
   * se il computer risponde e in quel caso si sveglia la coda. E' la rete di sicurezza sotto i
   * tentativi di [retryForEndpoint]: se la loro catena si interrompe (un worker ucciso prima di
   * chiedere il successivo), una lezione non aspetta piu' di un quarto d'ora un PC gia' acceso.
   * Si spegne da sola quando la fila e' vuota.
   */
  fun watchEndpoint(enabled: Boolean) {
    val manager = WorkManager.getInstance(context)
    if (!enabled) {
      manager.cancelUniqueWork(ENDPOINT_WATCH)
      return
    }
    val request = PeriodicWorkRequestBuilder<EndpointWatchWorker>(15, TimeUnit.MINUTES)
      .setInitialDelay(15, TimeUnit.MINUTES)
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .addTag(TAG)
      .build()
    manager.enqueueUniquePeriodicWork(ENDPOINT_WATCH, ExistingPeriodicWorkPolicy.KEEP, request)
  }

  /** Ferma la coda di un provider. I lavori restano dove sono: li rimette in fila l'avvio successivo. */
  fun stop(providerId: String) {
    WorkManager.getInstance(context).cancelUniqueWork(workName(providerId))
    cancelEndpointRetry(providerId)
  }

  /**
   * Ferma tutte le code.
   *
   * Serve al ripristino: un worker che sta scrivendo il risultato di una trascrizione dentro il
   * database che si sta per sostituire lo riscriverebbe un secondo dopo, sopra quello ripristinato.
   */
  fun stopAll() {
    WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    WorkManager.getInstance(context).cancelAllWorkByTag(TAG_ARCHIVE)
    WorkManager.getInstance(context).cancelAllWorkByTag(TAG_SYNC)
    WorkManager.getInstance(context).cancelAllWorkByTag(TAG_FETCH)
  }

  // --- tieni tutto anche qui ---

  /**
   * Un giro di scarico: quello che il computer ha e questo dispositivo no. `KEEP`, come l'archivio.
   * [force] e' il tasto «Scarica adesso»: vale anche con l'interruttore spento, una volta sola.
   */
  fun fetchNow(unmeteredOnly: Boolean, force: Boolean = false) {
    val request = OneTimeWorkRequestBuilder<FetchWorker>()
      .setConstraints(archiveConstraints(unmeteredOnly))
      .setInputData(workDataOf(FetchWorker.KEY_FORCE to force))
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
      .addTag(TAG_FETCH)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(FETCH_NOW, if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
  }

  fun observeFetch(): Flow<List<WorkInfo>> = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG_FETCH)

  // --- l'indice in cloud ---

  /**
   * Un giro adesso.
   *
   * Di solito `KEEP`: se uno sta gia' girando, chiedere due volte non ne fa partire due. Ma un giro
   * fallito resta in coda ad aspettare il suo tentativo successivo (un minuto, poi due, poi
   * quattro), e con `KEEP` il tocco su «Sincronizza adesso» — o l'accesso appena fatto — verrebbe
   * ignorato in silenzio finche' quel tentativo non scade: e' successo. Chi chiede con [force] lo
   * sostituisce e parte subito.
   *
   * Sostituisce solo un giro **in attesa**, pero'. Uno che sta girando non si annulla: tirare giu'
   * una pagina due volte, o toccare «Sincronizza adesso» durante il giro dell'apertura, lo
   * interrompeva a meta' push — il server aveva scritto il lotto, il telefono non aveva letto la
   * risposta, e la riga restava nell'outbox con una base vecchia. Con un giro al lavoro se ne accoda
   * uno dopo di lui, come fa [syncSoon]: chi ha chiesto vede quello che c'e' dopo la sua richiesta.
   */
  fun syncNow(force: Boolean = false): UUID {
    val manager = WorkManager.getInstance(context)
    val request = OneTimeWorkRequestBuilder<SyncWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
      .addTag(TAG_SYNC)
      .build()
    val policy = when {
      !force -> ExistingWorkPolicy.KEEP
      syncNowRunning(manager) -> ExistingWorkPolicy.APPEND_OR_REPLACE
      else -> ExistingWorkPolicy.REPLACE
    }
    manager.enqueueUniqueWork(SYNC_NOW, policy, request)
    // Con [force] e' il giro che partira' davvero; senza, un `KEEP` puo' aver tenuto quello di prima.
    return request.id
  }

  /**
   * Il giro di [syncNow] sta lavorando adesso. Una lettura bloccante, ma breve e con un tetto: chi
   * chiama non e' sospendibile (il tasto e il primo accesso), e sbagliare per difetto vuol dire solo
   * il `REPLACE` di prima.
   */
  private fun syncNowRunning(manager: WorkManager): Boolean = runCatching {
    manager.getWorkInfosForUniqueWork(SYNC_NOW).get(1, TimeUnit.SECONDS).any { it.state == WorkInfo.State.RUNNING }
  }.getOrDefault(false)

  /**
   * Un giro fra qualche secondo, per una cosa che gli altri dispositivi devono sapere presto: il
   * segno «in trascrizione su» di una sessione ([TranscribingMarkers]).
   *
   * Non [syncNow]: il suo `KEEP` lascerebbe cadere la richiesta se un giro sta gia' girando — ed e'
   * proprio il caso tipico, l'import che chiede un giro e fa partire la trascrizione un attimo dopo
   * — e quel giro puo' aver letto l'outbox prima del segno. Qui, con un giro al lavoro, se ne
   * accoda uno dopo di lui; altrimenti si sostituisce quello in attesa, e i cambi ravvicinati (un
   * lavoro che finisce, il successivo che parte) salgono in un giro solo.
   */
  suspend fun syncSoon() {
    val manager = WorkManager.getInstance(context)
    val running = manager.getWorkInfosForUniqueWorkFlow(SYNC_SOON).first().any { it.state == WorkInfo.State.RUNNING }
    val request = OneTimeWorkRequestBuilder<SyncWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setInitialDelay(SYNC_SOON_DELAY_SECONDS, TimeUnit.SECONDS)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
      .addTag(TAG_SYNC)
      .build()
    manager.enqueueUniqueWork(SYNC_SOON, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
  }

  /** Un lavoro per id: chi ha chiesto un giro con [syncNow] guarda quando finisce. */
  fun observeWork(id: UUID): Flow<WorkInfo?> = WorkManager.getInstance(context).getWorkInfoByIdFlow(id)

  /** Ogni sei ore, con sei ore di ritardo iniziale: chi accende chiede gia' un giro con [syncNow]. */
  fun setPeriodicSync(enabled: Boolean) {
    val manager = WorkManager.getInstance(context)
    if (!enabled) {
      manager.cancelUniqueWork(SYNC_PERIODIC)
      return
    }
    val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
      .setInitialDelay(6, TimeUnit.HOURS)
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
      .addTag(TAG_SYNC)
      .build()
    manager.enqueueUniquePeriodicWork(SYNC_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
  }

  fun observeSync(): Flow<List<WorkInfo>> = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG_SYNC)

  // --- l'archivio sul computer di casa ---

  /** Un giro adesso. `KEEP`: se uno sta gia' girando, quello che chiede si accoda a lui. */
  fun archiveNow(unmeteredOnly: Boolean) {
    val request = OneTimeWorkRequestBuilder<ArchiveWorker>()
      .setConstraints(archiveConstraints(unmeteredOnly))
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
      .addTag(TAG_ARCHIVE)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(ARCHIVE_NOW, ExistingWorkPolicy.KEEP, request)
  }

  /**
   * Il giro periodico, ogni sei ore, oppure niente.
   *
   * `UPDATE` e non `KEEP`: cambiare «solo su Wi-Fi» deve cambiare il vincolo del lavoro gia' in
   * coda, e con `KEEP` resterebbe quello vecchio finche' non scade.
   */
  fun setPeriodicArchive(enabled: Boolean, unmeteredOnly: Boolean) {
    val manager = WorkManager.getInstance(context)
    if (!enabled) {
      manager.cancelUniqueWork(ARCHIVE_PERIODIC)
      return
    }
    val request = PeriodicWorkRequestBuilder<ArchiveWorker>(6, TimeUnit.HOURS)
      // Un periodico appena creato parte subito, e chi accende l'interruttore chiede gia' un giro
      // con [archiveNow]: senza il ritardo partivano in due sullo stesso elenco.
      .setInitialDelay(6, TimeUnit.HOURS)
      .setConstraints(archiveConstraints(unmeteredOnly))
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
      .addTag(TAG_ARCHIVE)
      .build()
    manager.enqueueUniquePeriodicWork(ARCHIVE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
  }

  /** I lavori di archiviazione, per la pagina che ne mostra il progresso. */
  fun observeArchive(): Flow<List<WorkInfo>> = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG_ARCHIVE)

  private fun archiveConstraints(unmeteredOnly: Boolean) = Constraints.Builder()
    .setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
    .build()

  private fun workName(providerId: String) = "$WORK_PREFIX$providerId"

  private fun retryName(providerId: String) = "$RETRY_PREFIX$providerId"

  companion object {
    const val TAG = "transcription"
    const val TAG_ARCHIVE = "archive"
    const val TAG_SYNC = "sync"
    const val TAG_FETCH = "fetch"
    private const val FETCH_NOW = "fetch-now"
    private const val ENDPOINT_WATCH = "endpoint-watch"
    private const val SYNC_NOW = "sync-now"
    private const val SYNC_SOON = "sync-soon"
    private const val SYNC_SOON_DELAY_SECONDS = 3L
    private const val SYNC_PERIODIC = "sync-periodic"
    private const val WORK_PREFIX = "transcription-queue-"
    private const val RETRY_PREFIX = "endpoint-retry-"
    private const val ARCHIVE_NOW = "archive-now"
    private const val ARCHIVE_PERIODIC = "archive-periodic"
  }
}
