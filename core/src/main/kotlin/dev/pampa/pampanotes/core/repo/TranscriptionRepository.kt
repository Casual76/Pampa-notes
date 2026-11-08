package dev.pampa.pampanotes.core.repo

import androidx.room.withTransaction
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.db.JobType
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SegmentDao
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.db.TranscriptStatus
import dev.pampa.pampanotes.core.db.TranscriptionRunEntity
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.model.Ids
import dev.pampa.pampanotes.core.model.wordCount
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.settings.SpeakerSeparation
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.core.transcription.ComputerAuth
import dev.pampa.pampanotes.core.transcription.CloudTranscriptionGuard
import dev.pampa.pampanotes.core.transcription.OpenRouterTranscriptionProvider
import dev.pampa.pampanotes.core.transcription.GroqWhisperProvider
import dev.pampa.pampanotes.core.transcription.CloudCredentials
import dev.pampa.pampanotes.core.transcription.CloudCooldownStore
import dev.pampa.pampanotes.core.transcription.CloudRetry
import dev.pampa.pampanotes.core.transcription.MistralTranscriptionProvider
import dev.pampa.pampanotes.core.transcription.EndpointResolver
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.core.transcription.Pieces
import dev.pampa.pampanotes.core.transcription.SessionTranscript
import dev.pampa.pampanotes.core.transcription.TranscribeOverrides
import dev.pampa.pampanotes.core.transcription.TranscribeRequest
import dev.pampa.pampanotes.core.transcription.TranscriptionPrompt
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import dev.pampa.pampanotes.core.transcription.TranscriptionHttp
import dev.pampa.pampanotes.core.transcription.TranscriptionProvider
import dev.pampa.pampanotes.core.transcription.RemoteTranscribing
import dev.pampa.pampanotes.core.transcription.TranscribingMarker
import dev.pampa.pampanotes.core.transcription.VoiceNames
import dev.pampa.pampanotes.core.transcription.JobPhase
import dev.pampa.pampanotes.core.sync.deviceLabel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive

/**
 * La coda delle trascrizioni, vista da chi la usa.
 *
 * Il worker chiede il prossimo lavoro e aggiorna lo stato; la UI guarda le stesse righe. Nessuno dei
 * due tiene stato in memoria: il database e' l'unica verita', ed e' l'unico modo perche' una coda
 * sopravviva alla morte del processo senza raccontare bugie.
 */
@Singleton
class TranscriptionRepository @Inject constructor(
  private val jobs: JobDao,
  private val sessions: SessionDao,
  private val notes: NoteDao,
  private val parts: AudioPartDao,
  private val transcripts: TranscriptDao,
  private val segments: SegmentDao,
  private val settingsStore: PampaSettingsStore,
  private val keys: AiKeyStore,
  private val http: TranscriptionHttp,
  private val resolver: EndpointResolver,
  private val db: PampaDatabase,
  private val computerAuth: ComputerAuth,
  private val stats: StatsRepository,
  private val sessionRepository: SessionRepository,
  private val files: AppFiles,
  private val spending: dev.pampa.pampanotes.core.spending.CloudSpending,
  private val credentials: CloudCredentials,
  private val cooldowns: CloudCooldownStore,
) {

  fun observeAll(): Flow<List<JobEntity>> = jobs.observeAll()
  fun observeActive(): Flow<List<JobEntity>> = jobs.observeActive()
  fun observeActiveCount(): Flow<Int> = jobs.observeActiveCount()
  fun observeBySession(sessionId: String): Flow<List<JobEntity>> = jobs.observeBySession(sessionId)

  suspend fun get(jobId: String): JobEntity? = jobs.get(jobId)

  suspend fun hasRetainedCloudWork(job: JobEntity): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    job.type == JobType.TRANSCRIBE && job.provider != OpenAiCompatProvider.ID &&
      dev.pampa.pampanotes.core.transcription.TranscriptionWorkStorage().hasRetainedWork(java.io.File(files.jobs, job.id))
  }

  suspend fun uncertainCloudWork(job: JobEntity): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    job.type == JobType.TRANSCRIBE && job.provider != OpenAiCompatProvider.ID &&
      runCatching { dev.pampa.pampanotes.core.transcription.TranscriptionWorkStorage().hasUncertainUpload(java.io.File(files.jobs, job.id)) }.getOrDefault(true)
  }

  /** Account/session/audio changes revoke an open reader too. No provider, fetch, or DB writes. */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  fun observeCloudRecovery(jobId: String): Flow<dev.pampa.pampanotes.core.transcription.RecoveredCloudWork?> =
    jobs.observe(jobId).flatMapLatest { job ->
      if (job == null || !job.state.isTerminal || job.type != JobType.TRANSCRIBE || job.provider == OpenAiCompatProvider.ID)
        kotlinx.coroutines.flow.flowOf(null)
      else combine(settingsStore.settings, sessions.observe(job.sessionId)) { settings, session -> settings to session }
        .transformLatest { (settings, session) ->
          emit(null)
          if (session == null) return@transformLatest
          val account = CloudRetry.fingerprint(settings.syncAccount)
          val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
              if (cooldowns.contexts.get(job.id)?.accountFingerprint != account) null
              else dev.pampa.pampanotes.core.transcription.CloudWorkRecovery.read(
                java.io.File(files.jobs, job.id), job, session.partsSorted, files.audio,
              )
            }.getOrNull()
          }
          if (CloudRetry.fingerprint(settingsStore.current().syncAccount) == account) emit(result)
        }
    }

  /** La riga di un lavoro, viva: il worker la guarda per accorgersi di un «Annulla» mentre lavora. */
  fun observeJob(jobId: String): Flow<JobEntity?> = jobs.observe(jobId)

  /**
   * Mette in coda la trascrizione di una sessione.
   *
   * @return il lavoro creato, oppure quello gia' in corso: chiedere due volte la stessa sessione e'
   *   un doppio tocco, non una richiesta di trascriverla due volte. Null se un altro dispositivo la
   *   sta gia' trascrivendo ([busyElsewhere]): la trascrizione arrivera' col sync, e rifarla qui
   *   vorrebbe dire la stessa lezione due volte dal computer o da Groq. Vale per tutti quelli che
   *   accodano — il tasto, «Trascrivi tutte», la selezione, l'import — anche se la schermata non
   *   l'ha ancora saputo.
   *
   * @param overrides le impostazioni di questo lavoro soltanto ([TranscribeOverrides]): finiscono in
   *   `optionsJson` e valgono solo per lui. Vuote o null: il lavoro usa le impostazioni di sempre.
   */
  suspend fun enqueue(
    sessionId: String,
    providerId: TranscriptionProviderId,
    overrides: TranscribeOverrides? = null,
  ): JobEntity? {
    jobs.activeForSession(sessionId)?.let { return it }
    if (busyElsewhere(sessionId) != null) return null

    val settings = settingsStore.current()
    val requested = overrides?.provider?.let { id -> TranscriptionProviderId.entries.firstOrNull { it.id == id }
      ?: throw TranscriptionError.UnknownModel(id, "Unknown transcription provider") } ?: providerId
    val explicitCloud = overrides?.explicitlyChoosesCloud(requested.id) == true
    val selectedProvider = if (explicitCloud) requested.id else settings.providerForJob(requested.id, transcription = true)
    var folderModel: String? = null
    val blocked = if (selectedProvider == OpenAiCompatProvider.ID) null else try {
      val policy = spending.policyForSession(sessionId, allowExplicitCloud = explicitCloud)
      folderModel = policy.model
      policy.takeUnless { it.allowed }?.reason
    } catch (error: TranscriptionError.CloudControl) {
      error.reason
    }
    val now = System.currentTimeMillis()
    val job = JobEntity(
      id = Ids.newId(),
      sessionId = sessionId,
      type = JobType.TRANSCRIBE,
      provider = selectedProvider,
      model = modelAtEnqueue(selectedProvider, settings, overrides, folderModel),
      state = if (blocked != null) JobState.FAILED else JobState.QUEUED,
      errorCode = blocked,
      chunkTotal = 0,
      chunkDone = 0,
      optionsJson = json.encodeToString(TranscribeOptions.serializer(), TranscribeOptions(
        overrides = overrides?.takeUnless { it.isEmpty },
        cloudChunkMinutes = settings.chunkMinutes,
        groqMaxUploadMb = settings.groqMaxUploadMb,
      )),
      createdAt = now,
      updatedAt = now,
    )
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      cooldowns.contexts.bind(job.id, CloudRetry.fingerprint(settings.syncAccount))
    }
    jobs.upsert(job)
    return job
  }

  /**
   * Mette in coda il raffinamento di una sessione.
   *
   * Stessa coda delle trascrizioni e stesso provider, perche' il limite di richieste al minuto di
   * Groq e' uno solo: due code parallele se lo prenderebbero a vicenda e si aspetterebbero lo stesso,
   * ma con due barre invece di una.
   */
  suspend fun enqueueRefinement(sessionId: String, optionsJson: String): JobEntity? {
    jobs.activeForSession(sessionId)?.let { return it }
    // Senza una grezza non c'e' niente da ripulire, e un lavoro che fallisce subito e' rumore.
    rawFor(sessionId) ?: return null

    val now = System.currentTimeMillis()
    val job = JobEntity(
      id = Ids.newId(),
      sessionId = sessionId,
      type = JobType.REFINE,
      provider = GroqWhisperProvider.ID,
      state = JobState.QUEUED,
      optionsJson = optionsJson,
      createdAt = now,
      updatedAt = now,
    )
    jobs.upsert(job)
    return job
  }

  /**
   * Il prossimo in fila per un servizio. [skip] sono i lavori che il worker ha gia' lasciato in fila
   * in questo giro (vedi [busyElsewhere]): restano `QUEUED`, e senza saltarli li riprenderebbe.
   */
  suspend fun nextQueued(providerId: String, skip: Set<String> = emptySet()): JobEntity? =
    if (skip.isEmpty()) jobs.nextQueued(providerId) else jobs.nextQueuedExcept(providerId, skip.toList())

  /**
   * Vale la pena svegliare la coda di un servizio adesso: c'e' qualcosa in fila e nessuno aspetta il
   * limite di Groq. Un lavoro fermo con `until:` nel futuro ha gia' il suo risveglio a quell'ora;
   * svegliarlo prima vorrebbe dire rimandarlo contro lo stesso limite.
   */
  suspend fun readyToWake(providerId: String): Boolean {
    val queued = jobs.queued(providerId)
    if (queued.isEmpty()) return false
    val now = System.currentTimeMillis()
    return queued.any { job -> waitUntil(job)?.let { it <= now } != false }
  }

  /** Shared key/account quota plus the job's own saved wait. Called before foreground/probes. */
  suspend fun waitUntil(job: JobEntity): Long? {
    var own = (JobPhase.parse(job.phase) as? JobPhase.Until)?.atMillis
    if (job.provider == OpenAiCompatProvider.ID) return own
    val settings = settingsStore.current()
    val context = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cooldowns.contexts.get(job.id) }
    val account = CloudRetry.fingerprint(settings.syncAccount)
    // Let the worker surface the account fence now instead of blocking a new account behind it.
    if (context != null && context.accountFingerprint != account) return null
    val key = credentials.key(job.provider) ?: return null
    val fingerprint = CloudRetry.fingerprint(key)
    if (context?.cooldownScope != null && context.cooldownScope != CloudRetry.scope(job.provider, fingerprint, account)) own = null
    val shared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      cooldowns.durable.deadline(job.provider, fingerprint, account)
    }
    return listOfNotNull(own, shared).maxOrNull()
  }

  private fun modelAtEnqueue(provider: String, settings: PampaSettings, overrides: TranscribeOverrides?, folderModel: String? = null): String? = when (provider) {
    GroqWhisperProvider.ID -> overrides?.model?.takeIf(String::isNotBlank) ?: settings.groqTranscriptionModel
    OpenRouterTranscriptionProvider.ID -> overrides?.model?.takeIf(String::isNotBlank) ?: folderModel ?: settings.openRouterTranscriptionModel
    MistralTranscriptionProvider.ID -> overrides?.model?.takeIf(String::isNotBlank) ?: settings.mistralTranscriptionModel
    else -> settings.endpointModel.takeIf(String::isNotBlank)
  }

  /** Legacy rows are pinned once locally; new cloud jobs already carry a model at enqueue. */
  suspend fun pinForRun(job: JobEntity): JobEntity? {
    if (uncertainCloudWork(job)) throw TranscriptionError.UncertainOutcome()
    // Privacy migration can replace a queued cloud identity while this caller is suspended.
    // Never derive or persist a model for a stale provider, including already-pinned rows.
    val current = jobs.get(job.id) ?: return null
    if (current.provider != job.provider || current.state != JobState.QUEUED) return current
    val job = current
    val settings = settingsStore.current()
    val options = transcribeOptions(job)
    val context = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      cooldowns.contexts.bind(job.id, CloudRetry.fingerprint(settings.syncAccount))
    }
    if (job.provider != OpenAiCompatProvider.ID && context.accountFingerprint != CloudRetry.fingerprint(settings.syncAccount)) throw TranscriptionError.CloudControl("cloud_account_changed")
    if (job.model != null && options.cloudChunkMinutes != null && options.groqMaxUploadMb != null) return job
    val legacyPlan = if ((job.provider == GroqWhisperProvider.ID || job.provider == OpenRouterTranscriptionProvider.ID) && job.attempts > 0)
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { java.io.File(files.jobs, "${job.id}/${dev.pampa.pampanotes.core.transcription.StoredChunks.WORK_FILE}").readText().split('|') }
          .getOrNull()?.takeIf { it.size in 3..4 && it.first() == job.provider }
      } else null
    val pinned = options.copy(cloudChunkMinutes = options.cloudChunkMinutes ?: legacyPlan?.get(1)?.toIntOrNull()?.takeIf { it in 3..20 } ?: settings.chunkMinutes,
      groqMaxUploadMb = options.groqMaxUploadMb ?: legacyPlan?.get(2)?.toLongOrNull()?.div(1024 * 1024)?.toInt()?.takeIf { it in 5..100 } ?: settings.groqMaxUploadMb)
    val legacyModel = legacyPlan?.getOrNull(3)?.takeIf { job.provider == OpenRouterTranscriptionProvider.ID && it.isNotBlank() }
    // Pre-upgrade queued rows did not save a model. Recover the same folder preference that
    // governed their first dispatch, without catalog/probe requests. Established choices win.
    val folderModel = if (job.model == null && legacyModel == null && options.overrides?.model.isNullOrBlank() && job.provider == OpenRouterTranscriptionProvider.ID)
      spending.policyForSession(job.sessionId, allowExplicitCloud = options.overrides?.explicitlyChoosesCloud(job.provider) == true).model
    else null
    jobs.pinTranscription(job.id, job.model ?: legacyModel ?: modelAtEnqueue(job.provider, settings, options.overrides, folderModel),
      json.encodeToString(TranscribeOptions.serializer(), pinned), System.currentTimeMillis(), job.provider, job.model, job.optionsJson)
    return jobs.get(job.id)
  }

  suspend fun queuedCount(providerId: String): Int = jobs.queuedCount(providerId)

  /**
   * I falliti che vale la pena riprovare tornano in fila, e si dice quali code svegliare. Non quelli
   * superati da una trascrizione piu' recente, non le registrazioni mute, non i lavori di una
   * sessione che non c'e' piu' ([FailedJobs]).
   */
  suspend fun retryAllFailed(): Set<String> {
    val standings = failureStandings(jobs.all())
    return standings.filterValues { it == FailureStanding.RETRYABLE }.keys
      .mapNotNullTo(mutableSetOf()) { retry(it)?.provider }
  }

  /** Per ogni lavoro fallito fra [all], che cosa vale ancora (vedi [FailedJobs.standing]). */
  suspend fun failureStandings(all: List<JobEntity>): Map<String, FailureStanding> {
    val failed = all.filter { it.state == JobState.FAILED }
    if (failed.isEmpty()) return emptyMap()
    val bySession = all.groupBy { it.sessionId }
    return failed.groupBy { it.sessionId }.flatMap { (sessionId, sessionFailed) ->
      val exists = sessions.get(sessionId) != null
      val sessionTranscripts = if (exists) transcripts.bySession(sessionId) else emptyList()
      sessionFailed.map { job -> job.id to FailedJobs.standing(
        if (uncertainCloudWork(job)) job.copy(errorCode = "cloud_request_uncertain") else job,
        exists, sessionTranscripts, bySession[sessionId].orEmpty(),
      ) }
    }.toMap()
  }

  /**
   * I lavori in fila del computer di casa dicono che lo stanno aspettando, o smettono di dirlo.
   * E' una fase come le altre (`JobEntity.phase`): la riga del lavoro la legge e la scrive.
   */
  suspend fun markWaitingForEndpoint(waiting: Boolean) {
    val now = System.currentTimeMillis()
    // Smettere di aspettare toglie solo «in attesa del computer»: le altre fasi di chi e' in fila
    // («in trascrizione su Tab S9») dicono cose ancora vere.
    if (waiting) {
      jobs.setQueuedPhase(OpenAiCompatProvider.ID, PHASE_WAITING_ENDPOINT, now)
    } else {
      jobs.clearQueuedPhase(OpenAiCompatProvider.ID, PHASE_WAITING_ENDPOINT, now)
    }
  }

  /**
   * I lavori in fila di un servizio sono pronti, ma Android non ha lasciato partire il worker in
   * primo piano (un worker svegliato in background, da Android 12): lo dicono, al posto di un «in
   * attesa del computer» o di un «fino alle 14:32» che non sono piu' veri.
   */
  suspend fun markNeedsApp(providerId: String) =
    jobs.setQueuedPhase(providerId, JobPhase.NeedsApp.encode(), System.currentTimeMillis())

  /** Il worker e' partito: chi e' in fila dietro al primo non ha piu' bisogno che si apra l'app. */
  suspend fun clearNeedsApp(providerId: String) =
    jobs.clearQueuedPhase(providerId, JobPhase.NeedsApp.encode(), System.currentTimeMillis())

  /** Il lavoro resta in fila perche' un altro dispositivo sta trascrivendo la stessa sessione. */
  suspend fun markElsewhere(jobId: String, device: String) {
    jobs.setPhaseIfQueued(jobId, JobPhase.Elsewhere(device).encode(), System.currentTimeMillis())
  }

  /**
   * «Solo il computer di casa» e' acceso: le trascrizioni per Groq in fila o fallite passano al
   * computer. La regola vale anche per i lavori accodati prima che la si accendesse, o una lezione
   * finiva nel cloud per sbaglio lo stesso — ed e' la promessa dell'interruttore.
   *
   * @return quante ne ha spostate: chi chiama sveglia la coda del computer.
   */
  suspend fun moveCloudTranscriptionsToComputer(): Int {
    var moved = 0
    for (provider in TranscriptionProviderId.entries.filter { it != TranscriptionProviderId.CUSTOM }) {
      for (job in jobs.movableTranscriptions(provider.id)) {
        if (!explicitCloud(job) && !hasRetainedCloudWork(job))
          moved += jobs.moveTranscription(job.id, provider.id, OpenAiCompatProvider.ID, job.optionsJson, System.currentTimeMillis())
      }
    }
    return moved
  }

  /**
   * Il servizio che una trascrizione deve usare adesso: con «solo il computer di casa» acceso, mai
   * Groq, qualunque cosa ci fosse scritto quando e' stata accodata.
   */
  fun explicitCloud(job: JobEntity): Boolean = overridesOf(job)?.explicitlyChoosesCloud(job.provider) == true

  suspend fun effectiveProvider(job: JobEntity): String =
    if (explicitCloud(job)) job.provider else settingsStore.current().providerForJob(job.provider, job.type == JobType.TRANSCRIBE)

  /**
   * Il computer di casa: mai configurato, configurato ma muto, o pronto.
   *
   * Il resolver sceglie la **strada** (casa o Tailscale) e con due indirizzi ne restituisce sempre
   * una, anche se nessuna delle due risponde: e' fatto per un errore di rete chiaro, non per dire
   * se il computer c'e'. Qui serve l'altra domanda, e la si fa battendo `/health` sull'indirizzo
   * scelto: due secondi, senza token.
   */
  suspend fun endpointState(): EndpointState {
    val settings = settingsStore.current()
    if (!settings.hasEndpoint) return EndpointState.UNCONFIGURED
    val endpoint = resolver.resolve(settings.endpointUrl, settings.endpointRemoteUrl) ?: return EndpointState.UNCONFIGURED
    return if (EndpointResolver.reachable(endpoint.url)) EndpointState.REACHABLE else EndpointState.UNREACHABLE
  }

  /**
   * Il lavoro torna in fila ad aspettare il computer, invece di fallire: e' quello che succede a
   * un lavoro del computer di casa caduto su un errore di rete mentre il computer non risponde.
   *
   * Non per sempre: una registrazione che fa cadere il computer (un file che manda in crash il
   * companion, o la scheda) tornava in testa alla fila, lo rifaceva cadere al riavvio, e cosi' via,
   * con tutte le lezioni dietro ferme. Alla [MAX_ENDPOINT_LOSSES]-esima volta si arrende, e chi
   * chiama la segna fallita dicendo perche'. Il conto sta nelle opzioni del lavoro, e «Riprova» lo
   * azzera.
   *
   * @return falso se il lavoro ha perso il computer troppe volte: va chiuso fallito.
   */
  suspend fun requeueForEndpoint(job: JobEntity): Boolean = requeueCountingLoss(job, PHASE_WAITING_ENDPOINT)

  /**
   * Il computer c'e', ma a un altro indirizzo da quello con cui il lavoro era partito (da casa a
   * Tailscale, uscendo): il lavoro torna in fila e ripartira' con l'indirizzo di adesso, invece di
   * fallire dopo i tentativi su una strada che non porta piu' da nessuna parte. Contato come
   * [requeueForEndpoint], perche' un indirizzo che cambia a ogni tentativo e' un giro senza fine.
   */
  suspend fun requeueForNewAddress(job: JobEntity): Boolean = requeueCountingLoss(job, null)

  private suspend fun requeueCountingLoss(job: JobEntity, phase: String?): Boolean {
    // Una riga che non c'e' piu' non ha niente da chiudere: chi chiama non deve segnarla fallita.
    val current = jobs.get(job.id) ?: return true
    if (!ownsDispatch(job, current)) return true
    val options = transcribeOptions(job)
    val losses = options.endpointLosses + 1
    if (losses >= MAX_ENDPOINT_LOSSES) return false
    val encoded = json.encodeToString(TranscribeOptions.serializer(), options.copy(endpointLosses = losses))
    jobs.requeueOwned(job.id, phase, System.currentTimeMillis(), job.provider, job.model, job.optionsJson,
      job.attempts, job.state == JobState.QUEUED, encoded)
    return true
  }

  private fun transcribeOptions(job: JobEntity): TranscribeOptions = TranscribeOptions.decode(job.optionsJson)

  /** Le impostazioni di questo solo lavoro, se chi ha ritrascritto ne ha scelte ([enqueue]). */
  fun overridesOf(job: JobEntity): TranscribeOverrides? =
    if (job.type == JobType.TRANSCRIBE) transcribeOptions(job).overrides else null

  /**
   * Il sistema ha fermato il worker a meta' lavoro (vincoli, quota, un aggiornamento): il lavoro non
   * e' fallito, torna in fila e riparte dai pezzi gia' su disco. Un «Annulla» arrivato nel frattempo
   * vince: la query chiude atomicamente la richiesta di annullamento.
   */
  suspend fun requeueStopped(jobId: String) {
    jobs.requeue(jobId, null, System.currentTimeMillis())
  }

  suspend fun requeueStopped(job: JobEntity): Boolean = requeueOwned(job, null)

  private suspend fun requeueOwned(job: JobEntity, phase: String?): Boolean = jobs.requeueOwned(
    job.id, phase, System.currentTimeMillis(), job.provider, job.model, job.optionsJson, job.attempts,
    job.state == JobState.QUEUED,
    job.optionsJson,
  ) > 0

  /**
   * Il limite di Groq chiede di aspettare ore: il lavoro torna in fila e dice fino a quando
   * ([PHASE_RETRY_AT]), invece di fallire o di tenere una notifica accesa per tutto quel tempo.
   */
  suspend fun requeueUntil(jobId: String, atMillis: Long, scope: String? = null): Boolean {
    if (scope != null) {
      val account = CloudRetry.fingerprint(settingsStore.current().syncAccount)
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cooldowns.contexts.setCooldown(jobId, scope, account) }
    }
    val phase = if (scope != null) JobPhase.ProviderCooldown.encode() else "$PHASE_RETRY_AT:$atMillis"
    jobs.requeue(jobId, phase, System.currentTimeMillis())
    return jobs.get(jobId)?.state == JobState.QUEUED
  }

  suspend fun requeueUntil(job: JobEntity, atMillis: Long, scope: String? = null): Boolean {
    val phase = if (scope != null) JobPhase.ProviderCooldown.encode() else "$PHASE_RETRY_AT:$atMillis"
    if (!requeueOwned(job, phase)) return false
    if (scope != null) {
      val account = CloudRetry.fingerprint(settingsStore.current().syncAccount)
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cooldowns.contexts.setCooldown(job.id, scope, account) }
    }
    return jobs.get(job.id)?.let { ownsDispatch(job, it, queuedAfterRun = true) } == true
  }

  /** Progress may change state; provider/model/options and the attempt counter cannot change owner. */
  fun ownsDispatch(expected: JobEntity, current: JobEntity, queuedAfterRun: Boolean = false): Boolean =
    expected.provider == current.provider && expected.model == current.model && expected.optionsJson == current.optionsJson &&
      expected.attempts == current.attempts && (if (queuedAfterRun) current.state == JobState.QUEUED
      else if (expected.state == JobState.QUEUED) current.state == JobState.QUEUED
      else current.state in setOf(JobState.PREPARING, JobState.UPLOADING, JobState.TRANSCRIBING, JobState.STITCHING))

  suspend fun markCancelled(jobId: String) = jobs.markCancelled(jobId, System.currentTimeMillis())

  suspend fun markCancelled(job: JobEntity): Boolean = jobs.cancelOwned(job.id, System.currentTimeMillis(),
    job.provider, job.model, job.optionsJson, job.attempts, job.state == JobState.QUEUED) > 0

  /**
   * Scrive il progresso senza toccare il resto della riga.
   *
   * @return falso se il lavoro non c'e' piu' o se nel frattempo e' stato chiesto di annullarlo: chi
   *   lavora deve fermarsi, non scrivere sopra.
   */
  suspend fun publishProgress(job: JobEntity): Boolean = jobs.publishProgress(
    id = job.id,
    state = job.state,
    progress = job.progress,
    phase = job.phase,
    chunkTotal = job.chunkTotal,
    chunkDone = job.chunkDone,
    updatedAt = System.currentTimeMillis(),
  ) > 0

  suspend fun update(job: JobEntity) = jobs.update(job.copy(updatedAt = System.currentTimeMillis()))

  /**
   * Il lavoro parte: da in fila a [state]. Falso se nel frattempo non era piu' in fila — annullato
   * un attimo prima, o preso da qualcun altro — e allora non e' piu' di chi chiama.
   */
  suspend fun start(jobId: String, state: JobState, model: String?, expectedProvider: String): Boolean =
    jobs.start(jobId, state, model, System.currentTimeMillis(), expectedProvider) > 0

  suspend fun start(job: JobEntity, state: JobState, model: String?): Boolean =
    jobs.startOwned(job.id, state, model, System.currentTimeMillis(), job.provider, job.model, job.optionsJson, job.attempts) > 0

  suspend fun markDone(jobId: String): Boolean {
    val now = System.currentTimeMillis()
    if (jobs.markDone(jobId, now) > 0) return true
    jobs.finishCancellation(jobId, now)
    return false
  }

  suspend fun markDone(job: JobEntity): Boolean = db.withTransaction {
    val current = jobs.get(job.id) ?: return@withTransaction false
    if (ownsDispatch(job, current)) return@withTransaction jobs.markDone(job.id, System.currentTimeMillis()) > 0
    if (current.state == JobState.CANCEL_REQUESTED) markCancelled(job)
    false
  }

  /** Com'e' finito un lavoro che si voleva segnare fallito. */
  enum class FailOutcome {
    /** Segnato fallito: si dice all'utente. */
    FAILED,

    /** Qualcuno aveva chiesto di annullarlo: vince la sua richiesta, e non c'e' niente da notificare. */
    CANCELLED,

    /** Il lavoro non c'e' piu', o era gia' chiuso: niente da scrivere e niente da dire. */
    GONE,

    /** Another queued/running dispatch owns this row. Keep its work and wake its queue. */
    SUPERSEDED,
  }

  /** Segna fallito un lavoro, toccando solo le colonne dell'esito (vedi `JobDao.fail`). */
  suspend fun fail(job: JobEntity, error: TranscriptionError): FailOutcome {
    val jobId = job.id
    val now = System.currentTimeMillis()
    if (jobs.fail(jobId, error.code, error.message, now, job.provider, job.model, job.optionsJson, job.attempts,
        job.state == JobState.QUEUED) > 0) return FailOutcome.FAILED
    val current = jobs.get(jobId) ?: return FailOutcome.GONE
    if (current.provider != job.provider || current.model != job.model || current.optionsJson != job.optionsJson ||
        current.attempts != job.attempts) return FailOutcome.SUPERSEDED
    if (current.state == JobState.CANCEL_REQUESTED) {
      return if (requeueOwned(job, null)) FailOutcome.CANCELLED else FailOutcome.SUPERSEDED
    }
    if (current.state == JobState.QUEUED || current.state in setOf(JobState.PREPARING, JobState.UPLOADING,
        JobState.TRANSCRIBING, JobState.STITCHING)) return FailOutcome.SUPERSEDED
    return FailOutcome.GONE
  }

  suspend fun markState(jobId: String, state: JobState) = jobs.setState(jobId, state, System.currentTimeMillis())

  /**
   * Un lavoro che l'utente vuole fermare: il worker se ne accorge subito (guarda la riga).
   *
   * Un solo UPDATE: anche se il worker lo rimette in fila per quota dopo l'uscita del watcher,
   * l'annullamento trova o la riga in corso o quella in fila, senza perdere la richiesta.
   */
  suspend fun requestCancel(jobId: String) {
    jobs.requestCancel(jobId, System.currentTimeMillis())
  }

  /**
   * Di nuovo in fila. Con «solo il computer di casa» acceso una trascrizione per Groq riparte sul
   * computer: riprovare non deve essere la strada per cui una lezione finisce nel cloud.
   *
   * @return il lavoro com'e' adesso, col servizio che lo portera' avanti; null se non c'e'.
   */
  suspend fun retry(jobId: String): JobEntity? {
    val job = jobs.get(jobId) ?: return null
    if (job.state != JobState.FAILED || job.errorCode == "cloud_request_uncertain" || uncertainCloudWork(job)) return null
    if (FailedJobs.standing(job, sessions.get(job.sessionId) != null, transcripts.bySession(job.sessionId),
        jobs.all().filter { it.sessionId == job.sessionId }) != FailureStanding.RETRYABLE) return null
    val provider = effectiveProvider(job)
    if (provider != job.provider && hasRetainedCloudWork(job)) return null
    // Con lo stesso id «Riprova» rileggerebbe i pezzi del tentativo fallito — vuoti, o di un altro
    // piano — invece di chiedere di nuovo ([FailedJobs.discardsWorkOnRetry]).
    // Il conto delle volte che il computer e' sparito riparte: chi riprova ha di solito rimesso a
    // posto il computer. Le opzioni di un raffinamento (il preset) invece restano, e le impostazioni
    // che chi ha ritrascritto aveva scelto per questo lavoro: «Riprova» e' la stessa richiesta.
    val options = if (job.type == JobType.TRANSCRIBE) {
      json.encodeToString(TranscribeOptions.serializer(), transcribeOptions(job).copy(endpointLosses = 0, emptyParts = emptyList()))
    } else {
      job.optionsJson
    }
    return db.withTransaction {
      if (jobs.get(jobId) != job) return@withTransaction null
      if (FailedJobs.discardsWorkOnRetry(job)) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        java.io.File(files.jobs, job.id).deleteRecursively()
      }
      jobs.retry(jobId, provider, options, System.currentTimeMillis())
      jobs.get(jobId)
    }
  }

  suspend fun delete(jobId: String) = jobs.delete(jobId)

  suspend fun clearFinished() {
    // Keep recovery reachable. Individual Delete explicitly dismisses this result.
    for (job in jobs.all().filter { it.state.isTerminal }) {
      if (!hasRetainedCloudWork(job)) db.withTransaction { if (jobs.get(job.id) == job) jobs.delete(job.id) }
    }
  }

  /**
   * I lavori che il processo precedente ha lasciato «in corso» tornano in coda: erano in corso, non
   * falliti. Una volta per processo, da chiunque arrivi prima.
   *
   * Lo chiamano l'avvio dell'app (in una coroutine: un `runBlocking` su `onCreate` e' un ANR che
   * aspetta un disco lento) e ogni worker della coda prima di prendere un lavoro. Il lucchetto fa
   * si' che nessun worker di questo processo prenda un lavoro prima che la pulizia sia finita, cosi'
   * non si rischia di rimettere in fila un lavoro vivo: a quel punto tutti quelli «in corso» sono
   * del processo morto. Se la query fallisce, il prossimo che chiama riprova.
   *
   * @return vero se questa chiamata ha fatto la pulizia: chi la fa sveglia le code che ne hanno bisogno.
   */
  suspend fun requeueInterruptedOnce(): Boolean {
    if (requeueDone.get()) return false
    return requeueLock.withLock {
      if (requeueDone.get()) return@withLock false
      jobs.requeueInterrupted(System.currentTimeMillis())
      classifyUncertainCloudWork()
      requeueDone.set(true)
      true
    }
  }

  /** Runs before any startup queue wake, independently of credentials/provider availability. */
  internal suspend fun classifyUncertainCloudWork() {
    for (job in jobs.all().filter { it.state == JobState.QUEUED || it.state == JobState.FAILED }) {
      if (uncertainCloudWork(job)) {
        if (job.state == JobState.QUEUED) fail(job, TranscriptionError.UncertainOutcome())
        else db.withTransaction {
          if (jobs.get(job.id) == job) jobs.update(job.copy(errorCode = "cloud_request_uncertain",
            errorMessage = "Cloud request outcome uncertain; received results retained"))
        }
      }
    }
  }

  suspend fun partsOf(sessionId: String) = parts.bySession(sessionId)

  /**
   * Il computer di casa ha tenuto nel suo archivio la registrazione caricata per trascriverla: e'
   * archiviata come se l'avesse mandata l'archivio, che cosi' non la rimanda.
   */
  suspend fun markPartArchived(partId: String, at: Long) = parts.markArchived(partId, at)

  suspend fun requestFor(
    sessionId: String,
    model: String,
    settings: PampaSettings,
    overrides: TranscribeOverrides? = null,
  ): TranscribeRequest {
    val session = sessions.get(sessionId)
    val note = session?.let { notes.get(it.noteId) }
    val request = TranscribeRequest(
      model = model,
      cloudSessionId = sessionId,
      // La lingua della nota vince su quella generale: un quaderno di inglese in mezzo a note
      // italiane non deve essere trascritto come italiano storpiato.
      language = note?.language ?: settings.languageOrNull,
      // Solo il vocabolario: il titolo della nota, messo qui, Whisper lo ripeteva nei silenzi.
      prompt = TranscriptionPrompt.of(settings.vocabulary),
      // «Chi parla»: di serie per le Registrazioni. Chiederlo non basta: il runner lo manda solo a
      // un computer che dichiara di saperlo fare.
      diarize = when (settings.speakerSeparation) {
        SpeakerSeparation.NEVER -> false
        SpeakerSeparation.ALWAYS -> true
        SpeakerSeparation.PERSONAL -> note != null && PersonalScope.isPersonal(note.folderId, db.folders().all())
      },
    )
    // Quello che chi ritrascrive ha scelto per questo lavoro sta sopra a tutto il resto.
    return overrides?.applyTo(request) ?: request
  }

  /**
   * Il servizio da usare per questo lavoro, gia' configurato.
   *
   * Null quando manca quello che serve — la chiave di Groq, o l'indirizzo del server — e in quel
   * caso il lavoro fallisce con un errore che lo dice invece di provarci e prendere un 401.
   */
  enum class EndpointState { UNCONFIGURED, UNREACHABLE, REACHABLE }

  suspend fun providerFor(providerId: String): TranscriptionProvider? = bind(providerId)?.provider

  /** Un servizio pronto e, per il computer di casa, l'indirizzo a cui e' legato. */
  data class BoundProvider(val provider: TranscriptionProvider, val baseUrl: String?)

  /**
   * Come [providerFor], dicendo anche a quale indirizzo e' legato il provider del computer di casa:
   * se a meta' lavoro il computer si raggiunge da un'altra strada (vedi [endpointMovedFrom]), il
   * lavoro si rimette in fila invece di fallire.
   */
  suspend fun accountForJob(jobId: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cooldowns.contexts.get(jobId)?.accountFingerprint }

  suspend fun bind(providerId: String, pieces: Pieces? = null, explicitCloud: Boolean = false, groqMaxUploadMb: Int? = null, accountFingerprint: String? = null): BoundProvider? {
    val settings = settingsStore.current()
    // I pezzi scelti per questo lavoro vincono su quelli delle impostazioni.
    val chunkAuto = pieces?.auto ?: settings.customChunkAuto
    val chunkMinutes = if (pieces != null) pieces.minutes else settings.customMaxMinutes
    return when (providerId) {
      GroqWhisperProvider.ID -> {
        val key = keys.key(ProviderId.GROQ)?.takeIf { it.isNotBlank() } ?: return null
        val provider = GroqWhisperProvider(
          http = http,
          apiKey = key,
          maxUploadBytes = (groqMaxUploadMb ?: settings.groqMaxUploadMb) * 1024L * 1024L,
        )
        BoundProvider(guardCloud(provider, key, settings, explicitCloud, accountFingerprint), baseUrl = null)
      }

      OpenRouterTranscriptionProvider.ID -> {
        val key = keys.key(ProviderId.OPENROUTER)?.takeIf { it.isNotBlank() } ?: return null
        BoundProvider(guardCloud(OpenRouterTranscriptionProvider(http, key), key, settings, explicitCloud, accountFingerprint), baseUrl = null)
      }

      MistralTranscriptionProvider.ID -> {
        val key = credentials.key(providerId) ?: return null
        BoundProvider(guardCloud(MistralTranscriptionProvider(http, key), key, settings, explicitCloud, accountFingerprint), baseUrl = null)
      }

      OpenAiCompatProvider.ID -> {
        // Casa o Tailscale: lo decide la sonda, adesso, per questo lavoro. Vedi [EndpointResolver].
        val endpoint = resolver.resolve(settings.endpointUrl, settings.endpointRemoteUrl) ?: return null
        val provider = OpenAiCompatProvider(
          http = http,
          baseUrl = endpoint.url,
          // Il biglietto dell'account, se c'e'; altrimenti il codice scritto a mano. Vedi [ComputerAuth].
          auth = computerAuth,
          // Mai meno di quanto l'utente ha detto di voler aspettare il server: il limite vero lo
          // mette il worker con un withTimeout, che adesso stacca anche la socket; questo e' il
          // paracadute, finito, per quando nessuno annulla.
          readTimeoutMillis = maxOf(
            OpenAiCompatProvider.READ_TIMEOUT_MS.toLong(),
            settings.endpointTimeoutMinutes * 60_000L,
          ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
          // Con «Automatico» nessun tetto dal telefono: lo sceglie il computer, e lo racconta.
          maxChunkMinutes = if (chunkAuto) null else chunkMinutes,
          autoChunks = chunkAuto,
          // Quello che il computer sceglie in «Automatico» e' la sua ultima scelta, per lo slider: un
          // lavoro con i pezzi decisi da chi ha ritrascritto non e' la scelta delle impostazioni.
          onChunksChosen = { minutes -> if (pieces == null) settingsStore.setCustomLastMaxMinutes(minutes) },
          // Un elenco per processo: il lavoro lasciato indietro da questo provider lo ferma il
          // provider del lavoro dopo. Vedi [AbandonedCompanionJobs].
          abandoned = dev.pampa.pampanotes.core.transcription.AbandonedCompanionJobs.shared,
        )
        BoundProvider(provider, baseUrl = endpoint.url)
      }

      else -> null
    }
  }

  private fun guardCloud(provider: TranscriptionProvider, key: String, settings: PampaSettings, explicitCloud: Boolean, accountFingerprint: String?): TranscriptionProvider {
    val fingerprint = CloudRetry.fingerprint(key)
    val account = accountFingerprint ?: CloudRetry.fingerprint(settings.syncAccount)
    return CloudTranscriptionGuard(provider, beforeUpload = { request ->
      spending.beforeUpload(provider.id, fingerprint, request, explicitCloud)
    }, beforeRequest = {
      kotlinx.coroutines.currentCoroutineContext().ensureActive()
      if (CloudRetry.fingerprint(settingsStore.current().syncAccount) != account) throw TranscriptionError.CloudControl("cloud_account_changed")
      if (credentials.key(provider.id)?.let(CloudRetry::fingerprint) != fingerprint) throw TranscriptionError.CloudControl("cloud_key_changed")
      val until = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cooldowns.durable.deadline(provider.id, fingerprint, account) }
      val now = System.currentTimeMillis()
      if (until != null && until > now) throw TranscriptionError.RateLimited((until - now) / 1000.0, "Provider cooldown", until, CloudRetry.scope(provider.id, fingerprint, account))
    }, limited = { error ->
      val now = System.currentTimeMillis()
      val until = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
        if (error.scopeFingerprint == CloudRetry.scope(provider.id, fingerprint, account)) {
          cooldowns.durable.deadline(provider.id, fingerprint, account)
            ?: throw TranscriptionError.CloudControl("cloud_cost_storage")
        } else {
          cooldowns.durable.extend(provider.id, fingerprint, account, CloudRetry.deadline(error, now))
        }
      }
      TranscriptionError.RateLimited((until - now) / 1000.0, "Provider cooldown", until, CloudRetry.scope(provider.id, fingerprint, account))
    }) { !explicitCloud && settingsStore.current().customOnly }
  }

  /**
   * Il computer di casa risponde adesso, ma a un indirizzo diverso da [baseUrl]: si e' usciti di
   * casa (o si e' tornati) a meta' lavoro. Con la risposta del resolver tenuta mezzo minuto la
   * strada di prima sembrerebbe ancora buona: qui si chiede da capo.
   */
  suspend fun endpointMovedFrom(baseUrl: String): Boolean {
    val settings = settingsStore.current()
    if (!settings.hasEndpoint) return false
    resolver.invalidate()
    val endpoint = resolver.resolve(settings.endpointUrl, settings.endpointRemoteUrl) ?: return false
    if (OpenAiCompatProvider.normalize(endpoint.url) == OpenAiCompatProvider.normalize(baseUrl)) return false
    return EndpointResolver.reachable(endpoint.url)
  }

  /** Il modello da chiedere: quello salvato, altrimenti il migliore che il servizio dichiara. */
  suspend fun resolveModel(provider: TranscriptionProvider, settings: PampaSettings): String {
    val saved = when (provider.id) {
      OpenAiCompatProvider.ID -> settings.endpointModel
      OpenRouterTranscriptionProvider.ID -> settings.openRouterTranscriptionModel
      else -> null
    }?.takeIf { it.isNotBlank() }
    if (saved != null && provider.id != OpenRouterTranscriptionProvider.ID) return saved

    val available = try {
      provider.listModels()
    } catch (error: Exception) {
      if (error is kotlinx.coroutines.CancellationException || provider.id == OpenRouterTranscriptionProvider.ID) throw error
      // Keep compatibility with custom endpoints which do not expose a model catalogue.
      emptyList()
    }
    if (saved != null) {
      if (saved !in available) throw TranscriptionError.UnknownModel(saved, "Selected transcription model unavailable")
      return saved
    }
    return when (provider.id) {
      OpenRouterTranscriptionProvider.ID -> available.firstOrNull()
        ?: throw TranscriptionError.UnknownModel("", "OpenRouter has no transcription models")
      GroqWhisperProvider.ID -> GroqWhisperProvider.pickModel(available)
        ?: throw TranscriptionError.UnknownModel("", "Groq non offre nessun modello Whisper")
      else -> available.firstOrNull() ?: DEFAULT_LOCAL_MODEL
    }
  }

  /** Validate capability without changing the queued choice or making an inference probe. */
  suspend fun validatePinnedModel(provider: TranscriptionProvider, model: String) {
    val supported = when (provider.id) {
      MistralTranscriptionProvider.ID -> model in MistralTranscriptionProvider.MODELS
      GroqWhisperProvider.ID -> model in setOf("whisper-large-v3-turbo", "whisper-large-v3")
      OpenRouterTranscriptionProvider.ID -> model in provider.listModels()
      else -> true
    }
    if (!supported) throw TranscriptionError.UnknownModel(model, "Selected transcription model unavailable")
  }

  /**
   * Salva il risultato e lo rende quello mostrato.
   *
   * Una trascrizione grezza per sessione: rifarla sostituisce la precedente e porta via le raffinate
   * che ne discendevano, perche' un testo raffinato che cita una grezza che non esiste piu' e' un
   * testo di cui nessuno sa piu' da dove viene.
   *
   * Si salva **per parte**, nelle sessioni in cui le parti stanno adesso: mentre il computer
   * trascriveva la sessione puo' essere stata riordinata, separata, unita o sfoltita (vedi
   * `SessionRepository.saveTranscription`). [partIds] sono le parti che il lavoro ha trascritto.
   *
   * @return null se nessuna di quelle parti esiste piu': non c'e' niente da salvare.
   */
  suspend fun saveTranscript(
    sessionId: String,
    partIds: List<String>,
    result: SessionTranscript,
    originalParts: List<AudioPartEntity> = emptyList(),
  ): SavedTranscription? = sessionRepository.saveTranscription(sessionId, partIds, result, originalParts)

  data class CloudSaveSnapshot(val activeId: String?, val transcripts: List<TranscriptEntity>)

  suspend fun cloudSaveSnapshot(sessionId: String): CloudSaveSnapshot? = db.withTransaction {
    val session = sessions.get(sessionId) ?: return@withTransaction null
    CloudSaveSnapshot(session.activeTranscriptId, transcripts.bySession(sessionId))
  }

  /** Rechecked by the actual HTTP boundary after metadata and spending authorization suspend. */
  suspend fun validateCloudDispatch(job: JobEntity, originalParts: List<AudioPartEntity>) {
    if (!dev.pampa.pampanotes.core.transcription.CloudRecoveryFence.sameParts(originalParts, parts.bySession(job.sessionId)))
      throw TranscriptionError.CloudControl("cloud_recovery_changed")
    if (accountForJob(job.id) != CloudRetry.fingerprint(settingsStore.current().syncAccount))
      throw TranscriptionError.CloudControl("cloud_account_changed")
    val current = jobs.get(job.id)
    if (current == null || !ownsDispatch(job, current)) throw kotlinx.coroutines.CancellationException("Cloud dispatch no longer owned")
  }

  /** Commit only into the same dispatch/input/history the worker actually started with. */
  suspend fun saveOwnedCloudTranscript(job: JobEntity, originalParts: List<AudioPartEntity>,
    snapshot: CloudSaveSnapshot, result: SessionTranscript): SavedTranscription? = db.withTransaction {
    val current = jobs.get(job.id) ?: return@withTransaction null
    if (!ownsDispatch(job, current)) return@withTransaction null
    val account = CloudRetry.fingerprint(settingsStore.current().syncAccount)
    if (accountForJob(job.id) != account) return@withTransaction null
    val session = sessions.get(job.sessionId) ?: return@withTransaction null
    if (session.activeTranscriptId != snapshot.activeId || transcripts.bySession(job.sessionId) != snapshot.transcripts ||
        snapshot.transcripts.any { it.createdAt > job.createdAt }) return@withTransaction null
    val currentParts = parts.bySession(job.sessionId)
    if (!dev.pampa.pampanotes.core.transcription.CloudRecoveryFence.sameParts(originalParts, currentParts)) return@withTransaction null
    val audioMatches = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      originalParts.all { part ->
        val source = result.sourceParts.singleOrNull { it.partId == part.id }
        val hash = source?.audioSha256
        val audio = files.audioFile(part.fileName)
        hash != null && dev.pampa.pampanotes.core.alignment.AlignmentProtocol.validHash(hash) &&
          (!dev.pampa.pampanotes.core.alignment.AlignmentProtocol.validHash(part.sha256) || part.sha256 == hash) &&
          (!audio.exists() || dev.pampa.pampanotes.core.alignment.AlignmentProtocol.sha256(audio) == hash)
      }
    }
    if (!audioMatches) return@withTransaction null
    sessionRepository.saveTranscription(job.sessionId, originalParts.map { it.id }, result, originalParts)
  }

  /**
   * La sessione del lavoro non c'e' piu', ma qualcuna delle sue parti si': e' stata unita a
   * un'altra mentre si trascriveva. La riga del lavoro se n'e' andata con lei (cascata), ma il
   * lavoro vale ancora, e il risultato va nella sessione che ha preso le parti.
   */
  suspend fun partsOutliveSession(sessionId: String, partIds: List<String>): Boolean =
    sessions.get(sessionId) == null && partIds.any { parts.get(it) != null }

  /**
   * La trascrizione di questa sessione e' gia' arrivata da un altro dispositivo dopo che il lavoro
   * era stato accodato: una grezza nata dopo il lavoro, che copre tutte le parti di adesso. Rifarla
   * vorrebbe dire la stessa lezione due volte dal computer o da Groq, e cancellare il risultato
   * dell'altro con le raffinate che gli sono gia' state fatte sopra.
   *
   * Tutte le parti, e non solo «una grezza piu' nuova»: una grezza nasce anche qui, ricomponendo,
   * quando nella sessione arriva una parte gia' trascritta altrove — e allora le parti di prima
   * sono ancora da fare.
   */
  suspend fun arrivedFromElsewhere(job: JobEntity): Boolean {
    val raw = rawFor(job.sessionId) ?: return false
    if (raw.createdAt <= job.createdAt) return false
    val ids = parts.bySession(job.sessionId).map { it.id }
    if (ids.isEmpty()) return false
    val covered = segments.byTranscript(raw.id).mapTo(mutableSetOf()) { it.partId }
    return covered.containsAll(ids)
  }

  /**
   * Salva una versione raffinata e la rende quella mostrata.
   *
   * Nasce figlia della grezza e non la sostituisce: la grezza resta nel database con i suoi segmenti
   * e i suoi tempi, e un tocco la riporta a schermo. E' l'unica cosa che rende accettabile far
   * riscrivere una fonte a una macchina.
   */
  suspend fun saveRefinement(
    sessionId: String,
    parentId: String,
    result: dev.pampa.pampanotes.core.refinement.RefinementResult,
    promptHash: String,
  ): TranscriptEntity = db.withTransaction {
    val now = System.currentTimeMillis()
    val parent = transcripts.get(parentId)
    val transcript = TranscriptEntity(
      id = Ids.newId(),
      sessionId = sessionId,
      kind = TranscriptKind.REFINED,
      provider = "groq",
      model = result.model,
      language = parent?.language,
      text = result.text,
      preset = result.preset.name,
      promptHash = promptHash,
      parentId = parentId,
      wordCount = result.text.wordCount(),
      status = if (result.suspicious) TranscriptStatus.SUSPICIOUS else TranscriptStatus.OK,
      createdAt = now,
    )
    // Una raffinata per preset: rifarla con lo stesso preset sostituisce, con un altro affianca.
    transcripts.bySession(sessionId)
      .filter { it.kind == TranscriptKind.REFINED && it.parentId == parentId && it.preset == transcript.preset }
      .forEach { transcripts.delete(it.id) }
    transcripts.upsert(transcript)
    sessions.get(sessionId)?.let { session ->
      val captured = VoiceNames.capture(session.voiceNames, parts.bySession(sessionId).map { it.id }, session.updatedAt)
      if (captured != session.voiceNames) sessions.setVoiceNames(sessionId, captured, session.updatedAt)
    }
    sessions.setActiveTranscript(sessionId, transcript.id, now)
    transcript
  }

  /**
   * I numeri di una trascrizione appena salvata: quanto audio, in quanto tempo, su cosa. Li scrive
   * [StatsRepository] e li restituisce per la notifica; null se non si e' riusciti, e non lancia —
   * il lavoro a questo punto e' gia' riuscito.
   */
  suspend fun recordRun(job: JobEntity, startedAt: Long, transcript: TranscriptEntity): TranscriptionRunEntity? =
    stats.recordTranscription(job, startedAt, transcript)

  suspend fun rawFor(sessionId: String): TranscriptEntity? =
    RawTranscripts.forSelection(transcripts.bySession(sessionId), sessions.get(sessionId)?.activeTranscriptId)

  // ---------------------------------------------------------------------------------------------
  // In trascrizione su un altro dispositivo (vedi TranscribingMarker)
  // ---------------------------------------------------------------------------------------------

  /**
   * Le sessioni che un altro dispositivo sta trascrivendo adesso, per id. Quelle di questo
   * dispositivo non ci sono mai: qui si guarda il lavoro vero, con le sue barre.
   *
   * La scadenza si valuta quando qualcosa cambia, non a orologio: un segno che scade mentre la
   * pagina e' aperta resta a schermo finche' la pagina non si riapre o arriva un giro di sync. Tre
   * ore sono abbastanza larghe perche' non valga un timer.
   */
  fun observeElsewhere(): Flow<Map<String, RemoteTranscribing>> = combine(
    sessions.observeMarkers(),
    settingsStore.settings.map { it.deviceLabel() }.distinctUntilChanged(),
  ) { rows, me -> TranscribingMarker.elsewhere(rows, me, System.currentTimeMillis()) }

  /** Un altro dispositivo sta trascrivendo questa sessione adesso: chi, e da quando. */
  suspend fun busyElsewhere(sessionId: String): RemoteTranscribing? {
    val session = sessions.get(sessionId) ?: return null
    val me = settingsStore.current().deviceLabel()
    if (!TranscribingMarker.isElsewhere(session.transcribingOn, session.transcribingSince, me, System.currentTimeMillis())) return null
    return RemoteTranscribing(session.id, session.noteId, session.transcribingOn.orEmpty(), session.transcribingSince ?: 0L, session.activeTranscriptId != null)
  }

  /** Le sessioni con una trascrizione al lavoro qui, vive: chi tiene il segno in passo le guarda. */
  fun observeRunningTranscriptions(): Flow<List<String>> = jobs.observeRunningTranscriptions().distinctUntilChanged()

  /**
   * Mette il segno di questo dispositivo in passo con i suoi lavori: su ogni sessione che si sta
   * trascrivendo qui, via da ogni sessione che qui non si trascrive piu' — finita, fallita,
   * annullata, tornata in fila, o lasciata cosi' da un processo ucciso. Lo chiama chi guarda la coda
   * (`TranscribingMarkers` nell'app) a ogni cambio e all'avvio; i lavori non ne sanno niente, ed e'
   * apposta: nessuna strada d'uscita di un lavoro puo' dimenticarsi di togliere il segno.
   *
   * @return vero se ha scritto qualcosa: c'e' un segno da far salire.
   */
  suspend fun reconcileMarkers(): Boolean = markersLock.withLock {
    val me = settingsStore.current().deviceLabel()
    val now = System.currentTimeMillis()
    val running = jobs.runningTranscriptions().toSet()
    val mine = sessions.markedBy(me).associate { it.id to it.transcribingSince }
    val plan = TranscribingMarker.plan(running, mine, now)
    if (plan.isEmpty) return@withLock false
    db.withTransaction {
      plan.clear.forEach { sessions.clearMarker(it, me) }
      plan.mark.forEach { sessions.setMarker(it, me, now) }
    }
    true
  }

  /**
   * Il dispositivo ha cambiato nome: i segni col nome di prima sono suoi, ma a tutti — anche a lui —
   * sembrerebbero di un altro. Si tolgono; [reconcileMarkers] li rimette col nome nuovo.
   */
  suspend fun releaseMarkersOf(device: String): Boolean = markersLock.withLock {
    val stale = sessions.markedBy(device)
    stale.forEach { sessions.clearMarker(it.id, device) }
    stale.isNotEmpty()
  }

  /** Un giro alla volta: il cambio di un lavoro e il rinnovo a orologio possono arrivare insieme. */
  private val markersLock = Mutex()

  /**
   * La trascrizione e' finita, ma qualche registrazione e' tornata senza parole mentre le altre ce
   * l'hanno ([SessionTranscript.emptyPartIds]): lo si scrive nel lavoro, e la sessione lo dice finche'
   * quelle parti restano senza testo. Nelle opzioni e non in una colonna: e' un fatto di questo
   * lavoro, non della trascrizione, e non deve viaggiare col sync.
   */
  suspend fun recordEmptyParts(jobId: String, partIds: List<String>) {
    if (partIds.isEmpty()) return
    val job = jobs.get(jobId) ?: return
    val options = TranscribeOptions.decode(job.optionsJson).copy(emptyParts = partIds.distinct())
    jobs.setOptions(jobId, json.encodeToString(TranscribeOptions.serializer(), options), System.currentTimeMillis())
  }

  /**
   * Il computer di casa ha detto cosa sa fare: se e' la prima volta che dichiara «holes» (il
   * companion che non perde piu' pezzi di parlato, 1.0.4), da adesso le lezioni trascritte prima si
   * possono offrire da rifare (vedi [RetranscribeOffer]). Solo la prima volta: e' il confine fra prima
   * e dopo, e non deve spostarsi a ogni `/health`.
   */
  suspend fun noteCompanionFeatures(features: Set<String>) {
    if (dev.pampa.pampanotes.core.transcription.CompanionFeatures.HOLES in features) {
      settingsStore.markHolesSince(System.currentTimeMillis())
    }
  }

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  companion object {
    /**
     * Alla terza volta che un lavoro perde il computer di casa a meta', si arrende: due sono un
     * computer spento per sbaglio, la terza e' una registrazione che lo fa cadere.
     */
    const val MAX_ENDPOINT_LOSSES = 3

    /** La fase di un lavoro in fila che aspetta il computer di casa. */
    const val PHASE_WAITING_ENDPOINT = "endpoint"

    /** La fase di un lavoro in fila che aspetta il limite di Groq: `until:<millisecondi epoch>`. */
    const val PHASE_RETRY_AT = "until"

    /** Per processo, non per istanza: la pulizia riguarda il processo morto prima di questo. */
    private val requeueDone = AtomicBoolean(false)
    private val requeueLock = Mutex()
    /** Quello che WhisperX usa quando nessuno dice altro. */
    const val DEFAULT_LOCAL_MODEL = "large-v3"
  }
}

/** Le opzioni di una trascrizione, in `optionsJson` del lavoro. «Riprova» le azzera. */
@Serializable
data class TranscribeOptions(
  /** Quante volte il computer di casa e' sparito a meta' (vedi `TranscriptionRepository.requeueForEndpoint`). */
  val endpointLosses: Int = 0,
  /** Le registrazioni tornate senza parole in una sessione che le parole le ha (vedi `recordEmptyParts`). */
  val emptyParts: List<String> = emptyList(),
  /** Le impostazioni scelte da chi ha ritrascritto, per questo lavoro soltanto (vedi [TranscribeOverrides]). */
  val overrides: TranscribeOverrides? = null,
  val cloudChunkMinutes: Int? = null,
  val groqMaxUploadMb: Int? = null,
) {
  companion object {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Tollerante: un raffinamento ha altre opzioni (il preset), e un testo rotto vale «niente». */
    fun decode(optionsJson: String?): TranscribeOptions =
      optionsJson?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: TranscribeOptions()
  }
}
