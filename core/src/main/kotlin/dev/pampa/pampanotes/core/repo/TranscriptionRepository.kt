package dev.pampa.pampanotes.core.repo

import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.db.JobType
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.SegmentDao
import dev.pampa.pampanotes.core.db.SegmentEntity
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import dev.pampa.pampanotes.core.model.Ids
import dev.pampa.pampanotes.core.model.wordCount
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import dev.pampa.pampanotes.core.transcription.GroqWhisperProvider
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.core.transcription.SessionTranscript
import dev.pampa.pampanotes.core.transcription.TranscribeRequest
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import dev.pampa.pampanotes.core.transcription.TranscriptionHttp
import dev.pampa.pampanotes.core.transcription.TranscriptionProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

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
) {

  fun observeAll(): Flow<List<JobEntity>> = jobs.observeAll()
  fun observeActive(): Flow<List<JobEntity>> = jobs.observeActive()
  fun observeActiveCount(): Flow<Int> = jobs.observeActiveCount()
  fun observeBySession(sessionId: String): Flow<List<JobEntity>> = jobs.observeBySession(sessionId)

  suspend fun get(jobId: String): JobEntity? = jobs.get(jobId)

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

  suspend fun nextQueued(providerId: String): JobEntity? = jobs.nextQueued(providerId)

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

  /** I lavori che il sistema ha interrotto tornano in coda all'avvio: erano in corso, non falliti. */
  suspend fun requeueInterrupted() = jobs.requeueInterrupted(System.currentTimeMillis())

  suspend fun partsOf(sessionId: String) = parts.bySession(sessionId)

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
        val url = settings.endpointUrl.takeIf { it.isNotBlank() } ?: return null
        OpenAiCompatProvider(
          http = http,
          baseUrl = url,
          token = settingsStore.endpointToken(),
          // Zero vuol dire "aspetta": il limite vero lo mette il worker con un withTimeout, che si
          // puo' annullare, invece della socket, che non si annulla.
          readTimeoutMillis = 0,
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
  suspend fun saveTranscript(sessionId: String, result: SessionTranscript): TranscriptEntity {
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
        )
      },
    )
    sessions.setActiveTranscript(sessionId, transcript.id, now)
    sessions.get(sessionId)?.let { notes.touch(it.noteId, now) }
    return transcript
  }

  companion object {
    /** Quello che WhisperX usa quando nessuno dice altro. */
    const val DEFAULT_LOCAL_MODEL = "large-v3"
  }
}
