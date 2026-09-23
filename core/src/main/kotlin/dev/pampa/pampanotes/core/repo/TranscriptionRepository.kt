package dev.pampa.pampanotes.core.repo

import androidx.room.withTransaction
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.db.JobType
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SegmentDao
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.db.TranscriptStatus
import dev.pampa.pampanotes.core.db.TranscriptionRunEntity
import dev.pampa.pampanotes.core.model.Ids
import dev.pampa.pampanotes.core.model.wordCount
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.core.transcription.ComputerAuth
import dev.pampa.pampanotes.core.transcription.GroqWhisperProvider
import dev.pampa.pampanotes.core.transcription.EndpointResolver
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.core.transcription.SessionTranscript
import dev.pampa.pampanotes.core.transcription.TranscribeRequest
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import dev.pampa.pampanotes.core.transcription.TranscriptionHttp
import dev.pampa.pampanotes.core.transcription.TranscriptionProvider
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) {

  fun observeAll(): Flow<List<JobEntity>> = jobs.observeAll()
  fun observeActive(): Flow<List<JobEntity>> = jobs.observeActive()
  fun observeActiveCount(): Flow<Int> = jobs.observeActiveCount()
  fun observeBySession(sessionId: String): Flow<List<JobEntity>> = jobs.observeBySession(sessionId)

  suspend fun get(jobId: String): JobEntity? = jobs.get(jobId)

  /** La riga di un lavoro, viva: il worker la guarda per accorgersi di un «Annulla» mentre lavora. */
  fun observeJob(jobId: String): Flow<JobEntity?> = jobs.observe(jobId)

  /**
   * Mette in coda la trascrizione di una sessione.
   *
   * @return il lavoro creato, oppure quello gia' in corso: chiedere due volte la stessa sessione e'
   *   un doppio tocco, non una richiesta di trascriverla due volte.
   */
  suspend fun enqueue(sessionId: String, providerId: TranscriptionProviderId): JobEntity {
    jobs.activeForSession(sessionId)?.let { return it }

    val now = System.currentTimeMillis()
    val settings = settingsStore.current()
    val job = JobEntity(
      id = Ids.newId(),
      sessionId = sessionId,
      type = JobType.TRANSCRIBE,
      provider = providerId.id,
      model = null,
      state = JobState.QUEUED,
      chunkTotal = 0,
      chunkDone = 0,
      createdAt = now,
      updatedAt = now,
    )
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
    transcripts.rawForSession(sessionId) ?: return null

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

  suspend fun nextQueued(providerId: String): JobEntity? = jobs.nextQueued(providerId)

  suspend fun queuedCount(providerId: String): Int = jobs.queuedCount(providerId)

  /** Tutti i falliti tornano in fila, e si dice quali code svegliare. */
  suspend fun retryAllFailed(): Set<String> {
    val failed = jobs.failed()
    failed.forEach { retry(it.id) }
    return failed.mapTo(mutableSetOf()) { it.provider }
  }

  /**
   * I lavori in fila del computer di casa dicono che lo stanno aspettando, o smettono di dirlo.
   * E' una fase come le altre (`JobEntity.phase`): la riga del lavoro la legge e la scrive.
   */
  suspend fun markWaitingForEndpoint(waiting: Boolean) =
    jobs.setQueuedPhase(OpenAiCompatProvider.ID, if (waiting) PHASE_WAITING_ENDPOINT else null, System.currentTimeMillis())

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
   */
  suspend fun requeueForEndpoint(jobId: String) {
    jobs.requeue(jobId, PHASE_WAITING_ENDPOINT, System.currentTimeMillis())
  }

  /**
   * Il sistema ha fermato il worker a meta' lavoro (vincoli, quota, un aggiornamento): il lavoro non
   * e' fallito, torna in fila e riparte dai pezzi gia' su disco. Un «Annulla» arrivato nel frattempo
   * vince: la condizione della query lo lascia com'e'.
   */
  suspend fun requeueStopped(jobId: String) {
    jobs.requeue(jobId, null, System.currentTimeMillis())
  }

  /**
   * Il limite di Groq chiede di aspettare ore: il lavoro torna in fila e dice fino a quando
   * ([PHASE_RETRY_AT]), invece di fallire o di tenere una notifica accesa per tutto quel tempo.
   */
  suspend fun requeueUntil(jobId: String, atMillis: Long) {
    jobs.requeue(jobId, "$PHASE_RETRY_AT:$atMillis", System.currentTimeMillis())
  }

  suspend fun markCancelled(jobId: String) = jobs.markCancelled(jobId, System.currentTimeMillis())

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

  suspend fun markState(jobId: String, state: JobState) = jobs.setState(jobId, state, System.currentTimeMillis())

  /** Un lavoro che l'utente vuole fermare: il worker se ne accorge fra un pezzo e l'altro. */
  suspend fun requestCancel(jobId: String) {
    val job = jobs.get(jobId) ?: return
    if (job.state == JobState.QUEUED) {
      // Non e' ancora partito: si puo' togliere subito, senza aspettare che qualcuno lo guardi.
      jobs.update(job.copy(state = JobState.CANCELLED, finishedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
    } else if (job.state.isRunning) {
      jobs.setState(jobId, JobState.CANCEL_REQUESTED, System.currentTimeMillis())
    }
  }

  suspend fun retry(jobId: String) {
    val job = jobs.get(jobId) ?: return
    jobs.update(
      job.copy(
        state = JobState.QUEUED,
        errorCode = null,
        errorMessage = null,
        phase = null,
        progress = 0f,
        updatedAt = System.currentTimeMillis(),
        finishedAt = null,
      ),
    )
  }

  suspend fun delete(jobId: String) = jobs.delete(jobId)

  suspend fun clearFinished() = jobs.deleteTerminal()

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
      requeueDone.set(true)
      true
    }
  }

  suspend fun partsOf(sessionId: String) = parts.bySession(sessionId)

  /**
   * Il computer di casa ha tenuto nel suo archivio la registrazione caricata per trascriverla: e'
   * archiviata come se l'avesse mandata l'archivio, che cosi' non la rimanda.
   */
  suspend fun markPartArchived(partId: String, at: Long) = parts.markArchived(partId, at)

  /**
   * Il vocabolario da passare al modello.
   *
   * Il titolo della nota e le sue parole entrano nel prompt di Whisper insieme al vocabolario delle
   * impostazioni: su una lezione di storia la differenza fra "Termidoro" e "term e d'oro" la fa
   * l'aver visto la parola prima.
   */
  suspend fun promptFor(sessionId: String, settings: PampaSettings): String? {
    val session = sessions.get(sessionId) ?: return settings.vocabulary.takeIf { it.isNotBlank() }
    val note = notes.get(session.noteId)
    val pieces = buildList {
      note?.title?.takeIf { it.isNotBlank() }?.let(::add)
      session.title.takeIf { it.isNotBlank() }?.let(::add)
      settings.vocabulary.takeIf { it.isNotBlank() }?.let(::add)
    }
    return pieces.joinToString(". ").takeIf { it.isNotBlank() }?.take(GroqWhisperProvider.PROMPT_MAX_CHARS)
  }

  suspend fun requestFor(sessionId: String, model: String, settings: PampaSettings): TranscribeRequest {
    val session = sessions.get(sessionId)
    val note = session?.let { notes.get(it.noteId) }
    return TranscribeRequest(
      model = model,
      // La lingua della nota vince su quella generale: un quaderno di inglese in mezzo a note
      // italiane non deve essere trascritto come italiano storpiato.
      language = note?.language ?: settings.languageOrNull,
      prompt = promptFor(sessionId, settings),
    )
  }

  /**
   * Il servizio da usare per questo lavoro, gia' configurato.
   *
   * Null quando manca quello che serve — la chiave di Groq, o l'indirizzo del server — e in quel
   * caso il lavoro fallisce con un errore che lo dice invece di provarci e prendere un 401.
   */
  enum class EndpointState { UNCONFIGURED, UNREACHABLE, REACHABLE }

  suspend fun providerFor(providerId: String): TranscriptionProvider? {
    val settings = settingsStore.current()
    return when (providerId) {
      GroqWhisperProvider.ID -> {
        val key = keys.key(ProviderId.GROQ)?.takeIf { it.isNotBlank() } ?: return null
        GroqWhisperProvider(
          http = http,
          apiKey = key,
          maxUploadBytes = settings.groqMaxUploadMb * 1024L * 1024L,
        )
      }

      OpenAiCompatProvider.ID -> {
        // Casa o Tailscale: lo decide la sonda, adesso, per questo lavoro. Vedi [EndpointResolver].
        val endpoint = resolver.resolve(settings.endpointUrl, settings.endpointRemoteUrl) ?: return null
        OpenAiCompatProvider(
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
          maxChunkMinutes = settings.customMaxMinutes,
        )
      }

      else -> null
    }
  }

  /** Il modello da chiedere: quello salvato, altrimenti il migliore che il servizio dichiara. */
  suspend fun resolveModel(provider: TranscriptionProvider, settings: PampaSettings): String {
    val saved = when (provider.id) {
      OpenAiCompatProvider.ID -> settings.endpointModel
      else -> null
    }?.takeIf { it.isNotBlank() }
    if (saved != null) return saved

    val available = runCatching { provider.listModels() }.getOrDefault(emptyList())
    return when (provider.id) {
      GroqWhisperProvider.ID -> GroqWhisperProvider.pickModel(available)
        ?: throw TranscriptionError.UnknownModel("", "Groq non offre nessun modello Whisper")
      else -> available.firstOrNull() ?: DEFAULT_LOCAL_MODEL
    }
  }

  /**
   * Salva il risultato e lo rende quello mostrato.
   *
   * Una trascrizione grezza per sessione: rifarla sostituisce la precedente e porta via i suoi
   * segmenti e le raffinate che ne discendevano, perche' un testo raffinato che cita una grezza che
   * non esiste piu' e' un testo di cui nessuno sa piu' da dove viene.
   */
  suspend fun saveTranscript(sessionId: String, result: SessionTranscript): TranscriptEntity = db.withTransaction {
    // Tutto o niente: fra la cancellazione della grezza vecchia e l'ultimo segmento della nuova, un
    // processo ucciso lasciava una sessione senza trascrizione, o una trascrizione senza segmenti
    // — e il sync la portava cosi' anche sugli altri dispositivi.
    transcripts.rawForSession(sessionId)?.let { previous ->
      transcripts.deleteChildren(previous.id)
      transcripts.delete(previous.id)
    }

    val now = System.currentTimeMillis()
    val transcript = TranscriptEntity(
      id = Ids.newId(),
      sessionId = sessionId,
      kind = TranscriptKind.RAW,
      provider = result.provider,
      model = result.model,
      language = result.language,
      text = result.text,
      wordCount = result.text.wordCount(),
      createdAt = now,
    )
    transcripts.upsert(transcript)
    segments.insertAll(
      result.segments.map { segment ->
        SegmentEntity(
          transcriptId = transcript.id,
          partId = segment.partId,
          indexInPart = segment.indexInPart,
          partStartMs = segment.partStartMs,
          partEndMs = segment.partEndMs,
          sessionStartMs = segment.sessionStartMs,
          sessionEndMs = segment.sessionEndMs,
          text = segment.text,
          noSpeechProb = segment.noSpeechProb,
          avgLogProb = segment.avgLogProb,
          wordsJson = segment.wordsEncoded,
          wordsEstimated = segment.wordsEstimated,
        )
      },
    )
    sessions.setActiveTranscript(sessionId, transcript.id, now)
    // La nota non si tocca: una trascrizione finita non e' una modifica di chi l'ha scritta, e la
    // data della nota e' quella vera (vedi `NoteDates`), non l'ora in cui il computer ha finito.
    transcript
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
  ): TranscriptEntity {
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
    sessions.setActiveTranscript(sessionId, transcript.id, now)
    return transcript
  }

  /**
   * I numeri di una trascrizione appena salvata: quanto audio, in quanto tempo, su cosa. Li scrive
   * [StatsRepository] e li restituisce per la notifica; null se non si e' riusciti, e non lancia —
   * il lavoro a questo punto e' gia' riuscito.
   */
  suspend fun recordRun(job: JobEntity, startedAt: Long, transcript: TranscriptEntity): TranscriptionRunEntity? =
    stats.recordTranscription(job, startedAt, transcript)

  suspend fun rawFor(sessionId: String): TranscriptEntity? = transcripts.rawForSession(sessionId)

  companion object {
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
