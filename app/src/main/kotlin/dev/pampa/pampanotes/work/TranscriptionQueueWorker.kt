package dev.pampa.pampanotes.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.db.JobType
import dev.pampa.pampanotes.core.refinement.RefinementError
import dev.pampa.pampanotes.core.repo.RefinementRepository
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.GroqWhisperProvider
import dev.pampa.pampanotes.core.transcription.JobPhase
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import dev.pampa.pampanotes.core.transcription.TranscriptionProgress
import dev.pampa.pampanotes.core.transcription.TranscriptionRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * La coda delle trascrizioni, un provider alla volta.
 *
 * Un worker per provider e non uno per lavoro: Groq ha un limite di richieste al minuto, e due
 * lavori che partono insieme se lo prendono a vicenda. Le due code — il cloud e il computer di casa
 * — restano pero' indipendenti, perche' non condividono niente.
 *
 * Gira **in primo piano** con una notifica: una lezione da un'ora sono diversi minuti di lavoro, e
 * un worker normale il sistema lo ferma dopo dieci. Che sia sincronizzazione dati e' esatto: sta
 * caricando file e scaricando testo.
 *
 * Tre modi in cui un lavoro si ferma a meta', e ognuno ha la sua uscita:
 *  - **l'utente annulla** (la riga diventa `CANCEL_REQUESTED`): il worker la guarda mentre lavora e
 *    interrompe tutto, connessione compresa, e il lavoro si chiude annullato;
 *  - **il sistema ferma il worker** (vincoli, quota, un aggiornamento): il lavoro torna in fila,
 *    non fallisce, e riparte dai pezzi gia' su disco;
 *  - **il servizio chiede di aspettare ore** (il limite giornaliero di Groq): torna in fila con
 *    l'ora a cui riprovare, e la coda si risveglia da sola a quell'ora.
 */
@HiltWorker
class TranscriptionQueueWorker @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted params: WorkerParameters,
  private val repository: TranscriptionRepository,
  private val runner: TranscriptionRunner,
  private val refinement: RefinementRepository,
  private val settingsStore: PampaSettingsStore,
  private val scheduler: WorkScheduler,
) : CoroutineWorker(context, params) {

  /** Cosa fare dopo un lavoro: il prossimo, aspettare il computer di casa, o riprendere a un'ora. */
  private sealed interface Step {
    data object Next : Step
    data object WaitForEndpoint : Step
    data class ResumeAt(val atMillis: Long) : Step
  }

  /** Il lavoro l'ha annullato chi guardava la riga: l'utente, o la riga che non c'e' piu'. */
  private class JobCancelled : CancellationException("lavoro annullato")

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(
    title = applicationContext.getString(dev.pampa.pampanotes.R.string.notification_transcribing),
    text = null,
    progress = null,
  )

  override suspend fun doWork(): Result {
    val providerId = inputData.getString(KEY_PROVIDER) ?: return Result.failure()

    // Prima di prendere qualunque lavoro: quelli rimasti «in corso» da un processo morto tornano in
    // fila. Una volta per processo, chiunque arrivi prima fra l'avvio dell'app e un worker.
    if (runCatching { repository.requeueInterruptedOnce() }.getOrDefault(false)) wakeQueuesWithWork()

    if (repository.nextQueued(providerId) == null) return finish(providerId)

    // Il computer di casa e' configurato ma non risponde: non si va nemmeno in primo piano. I
    // lavori restano in fila e lo dicono, e si riprova (vedi [waitForEndpoint]).
    if (providerId == OpenAiCompatProvider.ID &&
      repository.endpointState() == TranscriptionRepository.EndpointState.UNREACHABLE
    ) {
      return waitForEndpoint()
    }

    // Da Android 12 un servizio in primo piano non parte se l'app e' in background (un tentativo
    // rimandato che scade mentre il telefono e' in tasca): e' un «non adesso», non un guasto. Il
    // lavoro resta in fila e si riprova col ritardo che cresce.
    if (!startForeground()) return Result.retry()

    while (true) {
      if (isStopped) return Result.retry()
      val job = repository.nextQueued(providerId) ?: break
      // Prima di ogni lavoro, non solo del primo: il computer puo' spegnersi fra una lezione e la
      // successiva, e la seconda deve aspettare, non fallire.
      if (job.provider == OpenAiCompatProvider.ID && job.type == JobType.TRANSCRIBE &&
        repository.endpointState() == TranscriptionRepository.EndpointState.UNREACHABLE
      ) {
        return waitForEndpoint()
      }
      val step = try {
        process(job)
      } catch (cancelled: CancellationException) {
        // Il worker e' stato fermato: il lavoro e' gia' tornato in fila (o annullato) dentro
        // [process]; qui si esce e basta, WorkManager sa cosa fare di un worker fermato.
        throw cancelled
      } catch (error: Throwable) {
        // Un guasto imprevisto non deve fermare la coda: il lavoro si segna fallito e si passa
        // al successivo, altrimenti un file rotto blocca tutti quelli dietro di lui.
        val translated = TranscriptionError.from(error)
        // Un errore di rete verso il computer di casa, e il computer non risponde: non e' il
        // lavoro che e' andato male, e' il computer che se n'e' andato a meta'. Si torna in fila.
        val vanished = job.provider == OpenAiCompatProvider.ID &&
          (translated is TranscriptionError.Network || translated is TranscriptionError.Timeout) &&
          repository.endpointState() == TranscriptionRepository.EndpointState.UNREACHABLE
        if (vanished) {
          repository.requeueForEndpoint(job.id)
          Step.WaitForEndpoint
        } else {
          fail(job, translated)
          Step.Next
        }
      }
      when (step) {
        Step.Next -> Unit
        Step.WaitForEndpoint -> return waitForEndpoint()
        is Step.ResumeAt -> {
          scheduler.kickAfter(providerId, step.atMillis - System.currentTimeMillis())
          return Result.success()
        }
      }
    }
    return finish(providerId)
  }

  private suspend fun finish(providerId: String): Result {
    if (providerId == OpenAiCompatProvider.ID) repository.markWaitingForEndpoint(false)
    return Result.success()
  }

  /** In primo piano, se il sistema lo concede adesso. */
  private suspend fun startForeground(): Boolean = try {
    setForeground(getForegroundInfo())
    true
  } catch (refused: IllegalStateException) {
    // `ForegroundServiceStartNotAllowedException` (API 31+) e' una IllegalStateException: catturare
    // questa copre anche i telefoni in cui la classe non esiste.
    false
  }

  /** La pulizia ha rimesso in fila dei lavori: le code che ne hanno si svegliano. */
  private suspend fun wakeQueuesWithWork() {
    if (repository.queuedCount(GroqWhisperProvider.ID) > 0) scheduler.kick(GroqWhisperProvider.ID)
    if (repository.queuedCount(OpenAiCompatProvider.ID) > 0) scheduler.kick(OpenAiCompatProvider.ID)
  }

  /**
   * Il computer di casa non risponde: i lavori restano in coda, non falliscono.
   *
   * E' quello che rende possibile «solo il computer di casa» con la trascrizione automatica accesa:
   * una lezione importata a scuola aspetta di essere a casa, invece di fallire con un errore da
   * ritentare a mano — o, peggio, di finire su Groq. Si riprova con l'attesa che cresce, e una
   * sonda ogni quarto d'ora ([EndpointWatchWorker]) mette un tetto all'attesa; chi il computer lo
   * vede rispondere — l'archivio, l'apertura dell'app — sveglia la coda prima.
   */
  private suspend fun waitForEndpoint(): Result {
    repository.markWaitingForEndpoint(true)
    scheduler.watchEndpoint(true)
    return Result.retry()
  }

  private suspend fun process(job: JobEntity): Step {
    // Due lavori, una coda sola: il limite di richieste al minuto di Groq e' uno, e due code
    // parallele se lo prenderebbero a vicenda mostrando due barre invece di una.
    if (job.type == JobType.REFINE) {
      refine(job)
      return Step.Next
    }
    return transcribe(job)
  }

  private suspend fun transcribe(job: JobEntity): Step {
    val settings = settingsStore.current()
    val provider = repository.providerFor(job.provider) ?: run {
      // Configurato ma muto: si aspetta. Mai configurato: e' un errore, e lo si dice.
      if (job.provider == OpenAiCompatProvider.ID && settings.hasEndpoint) return Step.WaitForEndpoint
      fail(
        job,
        TranscriptionError.Unauthorized(
          applicationContext.getString(dev.pampa.pampanotes.R.string.error_provider_not_configured),
        ),
      )
      return Step.Next
    }

    val parts = repository.partsOf(job.sessionId)
    if (parts.isEmpty()) {
      fail(job, TranscriptionError.Decode(applicationContext.getString(dev.pampa.pampanotes.R.string.error_no_audio)))
      return Step.Next
    }

    val model = repository.resolveModel(provider, settings)
    val request = repository.requestFor(job.sessionId, model, settings)
    // Annullato o tolto mentre si sceglieva il modello: non si parte.
    if (repository.get(job.id)?.state != JobState.QUEUED) return Step.Next
    repository.update(job.copy(state = JobState.PREPARING, model = model, attempts = job.attempts + 1, phase = null, errorCode = null, errorMessage = null))
    // Da qui si misura la velocita' della trascrizione: l'attesa in fila non e' lentezza di nessuno.
    val startedAt = System.currentTimeMillis()

    // Lo stato piu' recente, aggiornato dal motore; a scriverlo ci pensa un'altra coroutine.
    //
    // Separati apposta: il progresso arriva ogni centoventotto kilobyte caricati, cioe' decine di
    // volte al secondo, e una scrittura sul database dentro il ciclo di upload rallenterebbe
    // l'upload per muovere una barra che l'occhio non riesce comunque a seguire.
    val latest = MutableStateFlow(job.copy(state = JobState.PREPARING, model = model))

    try {
      val result = watched(job, latest) {
        // Il tetto di tempo sta qui: un `withTimeout` si annulla, e annullandosi stacca anche la
        // connessione (`TranscriptionHttp`), che da sola aspetterebbe il suo timeout di lettura.
        withTimeout(settings.endpointTimeoutMinutes.toLong() * 60_000L) {
          runner.transcribeSession(
            jobId = job.id,
            parts = parts,
            provider = provider,
            request = request,
            chunkMinutes = settings.chunkMinutes,
          ) { progress ->
            latest.update { it.applyProgress(progress) }
          }
        }
      }

      if (repository.get(job.id)?.state == JobState.CANCEL_REQUESTED) {
        cancel(job)
        return Step.Next
      }

      repository.publishProgress(latest.value.copy(state = JobState.STITCHING, progress = 0.98f, phase = null))
      val transcript = repository.saveTranscript(job.sessionId, result)
      repository.update(
        latest.value.copy(
          state = JobState.DONE,
          progress = 1f,
          phase = null,
          errorCode = null,
          errorMessage = null,
          finishedAt = System.currentTimeMillis(),
        ),
      )
      runner.cleanUp(job.id)
      AppNotifications.notifyDone(applicationContext, job.id, transcript.wordCount, repository.recordRun(job, startedAt, transcript))
    } catch (timeout: TimeoutCancellationException) {
      if (job.provider == OpenAiCompatProvider.ID && repository.endpointState() == TranscriptionRepository.EndpointState.UNREACHABLE) {
        repository.requeueForEndpoint(job.id)
        return Step.WaitForEndpoint
      }
      fail(job, TranscriptionError.Timeout(applicationContext.getString(dev.pampa.pampanotes.R.string.error_timeout)))
    } catch (limited: TranscriptionError.RateLimited) {
      // Il limite chiede piu' di quanto valga la pena aspettare con la notifica accesa: il lavoro
      // torna in fila con l'ora in cui riprovare, e la coda si risveglia da sola a quell'ora.
      val waitMs = ((limited.retryAfterSec ?: DEFAULT_RATE_LIMIT_WAIT_SEC) * 1000).toLong()
        .coerceIn(MIN_RESUME_DELAY_MS, MAX_RESUME_DELAY_MS)
      val at = System.currentTimeMillis() + waitMs
      repository.requeueUntil(job.id, at)
      return Step.ResumeAt(at)
    } catch (cancellation: CancellationException) {
      return cancelled(job, cancellation)
    }
    return Step.Next
  }

  /**
   * Il lavoro sotto sorveglianza: il progresso si scrive ogni mezzo secondo, e se la riga diventa
   * `CANCEL_REQUESTED` (o sparisce) il lavoro si interrompe subito — anche a meta' upload, anche
   * mentre il computer di casa trascrive — invece di accorgersene al pezzo successivo.
   */
  private suspend fun <T> watched(
    job: JobEntity,
    latest: MutableStateFlow<JobEntity>,
    titleRes: Int = dev.pampa.pampanotes.R.string.notification_transcribing,
    block: suspend () -> T,
  ): T = coroutineScope {
    val work: Deferred<T> = async { block() }
    val watcher = launch {
      repository.observeJob(job.id).first { it == null || it.state == JobState.CANCEL_REQUESTED }
      work.cancel(JobCancelled())
    }
    val publisher = launch {
      while (isActive) {
        delay(PUBLISH_EVERY_MS)
        runCatching { publish(latest.value, titleRes) }
      }
    }
    try {
      work.await()
    } finally {
      watcher.cancel()
      publisher.cancel()
    }
  }

  /**
   * Una cancellazione arrivata dentro un lavoro: di chi e'?
   *
   * - Il worker e' stato fermato: dall'app (il tasto «Annulla» della notifica, API 31+) il lavoro si
   *   chiude annullato; dal sistema torna in fila. In `NonCancellable`, perche' il worker fermato ha
   *   gia' il suo contesto annullato, e senza la scrittura non partirebbe.
   * - La riga e' `CANCEL_REQUESTED`: l'utente l'ha annullato dall'app.
   * - La riga non c'e' piu': la sessione e' stata cancellata mentre si trascriveva.
   */
  private suspend fun cancelled(job: JobEntity, cancellation: CancellationException): Step {
    if (isStopped) {
      withContext(NonCancellable) {
        if (stoppedByApp()) {
          repository.markCancelled(job.id)
          runner.cleanUp(job.id)
        } else {
          repository.requeueStopped(job.id)
        }
      }
      throw cancellation
    }
    val current = withContext(NonCancellable) { repository.get(job.id) }
    return when {
      current == null -> {
        runner.cleanUp(job.id)
        Step.Next
      }
      current.state == JobState.CANCEL_REQUESTED -> {
        withContext(NonCancellable) { cancel(job) }
        Step.Next
      }
      else -> throw cancellation
    }
  }

  private fun stoppedByApp(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP

  /**
   * Ripulisce la trascrizione grezza di una sessione.
   *
   * Molto piu' semplice di una trascrizione: niente file, niente pezzi da ricucire, niente riprese.
   * Se si interrompe si rifa' da capo, perche' rifare costa qualche secondo e qualche centesimo, e
   * tenere mezzo testo ripulito accanto a mezzo testo grezzo sarebbe peggio.
   */
  private suspend fun refine(job: JobEntity) {
    val raw = repository.rawFor(job.sessionId) ?: run {
      fail(job, TranscriptionError.Decode(applicationContext.getString(dev.pampa.pampanotes.R.string.error_no_transcript)))
      return
    }
    val provider = refinement.provider() ?: run {
      fail(job, TranscriptionError.Unauthorized(applicationContext.getString(dev.pampa.pampanotes.R.string.error_provider_not_configured)))
      return
    }
    val settings = settingsStore.current()
    val model = refinement.resolveModel(provider, settings) ?: run {
      fail(job, TranscriptionError.UnknownModel("", applicationContext.getString(dev.pampa.pampanotes.R.string.error_no_model)))
      return
    }
    val options = refinement.decode(job.optionsJson)

    if (repository.get(job.id)?.state != JobState.QUEUED) return
    repository.update(job.copy(state = JobState.TRANSCRIBING, model = model, attempts = job.attempts + 1, errorCode = null, errorMessage = null))
    val latest = MutableStateFlow(job.copy(state = JobState.TRANSCRIBING, model = model))

    try {
      val result = watched(job, latest, dev.pampa.pampanotes.R.string.notification_refining) {
        refinement.service.refine(
          rawText = raw.text,
          provider = provider,
          model = model,
          preset = options.presetOrDefault,
          customPrompt = options.customPrompt,
        ) { progress ->
          latest.update {
            it.copy(
              chunkTotal = progress.chunkCount,
              chunkDone = progress.chunkIndex,
              progress = if (progress.chunkCount <= 0) 0f else progress.chunkIndex.toFloat() / progress.chunkCount,
              phase = if (progress.waitingSeconds > 0) {
                JobPhase.Waiting(progress.waitingSeconds).encode()
              } else {
                JobPhase.Refining(progress.chunkIndex + 1, progress.chunkCount).encode()
              },
            )
          }
        }
      }

      if (repository.get(job.id)?.state == JobState.CANCEL_REQUESTED) {
        cancel(job)
        return
      }

      val transcript = repository.saveRefinement(
        sessionId = job.sessionId,
        parentId = raw.id,
        result = result,
        promptHash = dev.pampa.pampanotes.core.files.Hashing.sha256(
          dev.pampa.pampanotes.core.refinement.RefinementPrompts.system(options.presetOrDefault, options.customPrompt),
        ).take(16),
      )
      repository.update(
        latest.value.copy(
          state = JobState.DONE,
          progress = 1f,
          phase = null,
          errorCode = null,
          errorMessage = null,
          finishedAt = System.currentTimeMillis(),
        ),
      )
      AppNotifications.notifyRefined(applicationContext, job.id, transcript.wordCount, result.suspicious)
    } catch (cancellation: CancellationException) {
      cancelled(job, cancellation)
    } catch (error: RefinementError) {
      fail(job, TranscriptionError.from(error.cause ?: error))
    }
  }

  private suspend fun publish(job: JobEntity, titleRes: Int = dev.pampa.pampanotes.R.string.notification_transcribing) {
    // Solo le colonne del progresso, e solo se nessuno ha chiesto di annullare: vedi `JobDao.publishProgress`.
    if (!repository.publishProgress(job)) return
    setForeground(
      foregroundInfo(
        title = applicationContext.getString(titleRes),
        text = JobPhaseText.describe(applicationContext, job),
        progress = job.progress,
      ),
    )
  }

  private suspend fun cancel(job: JobEntity) {
    repository.markCancelled(job.id)
    runner.cleanUp(job.id)
  }

  private suspend fun fail(job: JobEntity, error: TranscriptionError) {
    repository.update(
      job.copy(
        state = JobState.FAILED,
        errorCode = error.code,
        errorMessage = error.message,
        phase = null,
        finishedAt = System.currentTimeMillis(),
      ),
    )
    AppNotifications.notifyFailed(applicationContext, job.id, error.code)
  }

  private fun foregroundInfo(title: String, text: String?, progress: Float?): ForegroundInfo {
    val notification = AppNotifications.buildProgress(applicationContext, title, text, progress, id)
    return if (Build.VERSION.SDK_INT >= 29) {
      ForegroundInfo(AppNotifications.ID_TRANSCRIPTION_FOREGROUND, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(AppNotifications.ID_TRANSCRIPTION_FOREGROUND, notification)
    }
  }

  companion object {
    const val KEY_PROVIDER = "provider"

    /** Ogni mezzo secondo: piu' spesso di cosi' la barra non si muove comunque. */
    private const val PUBLISH_EVERY_MS = 500L

    /** Un limite che non dice quanto aspettare: dieci minuti, poi si riprova. */
    private const val DEFAULT_RATE_LIMIT_WAIT_SEC = 600.0
    private const val MIN_RESUME_DELAY_MS = 60_000L
    private const val MAX_RESUME_DELAY_MS = 24 * 60 * 60_000L
  }
}

/**
 * Dal progresso del motore allo stato che la riga del lavoro mostra.
 *
 * `progress` e' la barra dell'intera sessione, gia' pesata dal motore (`ProgressScale`: le parti per
 * durata, dentro una parte il caricamento e il lavoro del computer); la fase porta il passo in
 * corso col suo percento, per la seconda barra e per la frase. Qui si scrive in memoria: sul
 * database ci va il publisher, ogni mezzo secondo, con l'UPDATE che rispetta un «annulla».
 */
private fun JobEntity.applyProgress(event: TranscriptionProgress): JobEntity {
  val overall = event.overall ?: progress
  return when (event) {
    is TranscriptionProgress.Preparing -> copy(
      state = JobState.PREPARING,
      progress = overall,
      phase = JobPhase.Preparing(event.partIndex + 1, event.partCount, JobPhase.percent(event.fraction)).encode(),
    )

    is TranscriptionProgress.Uploading -> copy(
      state = JobState.UPLOADING,
      chunkTotal = event.chunkCount,
      chunkDone = (event.chunkIndex - 1).coerceAtLeast(0),
      progress = overall,
      phase = JobPhase.Uploading(
        event.chunkIndex, event.chunkCount, JobPhase.percent(event.fraction), event.partIndex + 1, event.partCount,
      ).encode(),
    )

    is TranscriptionProgress.Remote -> copy(
      state = JobState.TRANSCRIBING,
      chunkTotal = event.chunkCount,
      chunkDone = (event.chunkIndex - 1).coerceAtLeast(0),
      progress = overall,
      phase = JobPhase.Remote(
        stage = event.remote.stage,
        part = event.partIndex + 1,
        parts = event.partCount,
        percent = JobPhase.percent(event.remote.fraction),
        position = event.remote.position,
        chunk = event.chunkIndex,
        chunks = event.chunkCount,
        etaSeconds = event.remote.etaSeconds?.let { kotlin.math.ceil(it).toInt() },
        device = event.remote.device,
      ).encode(),
    )

    is TranscriptionProgress.Transcribing -> copy(
      state = JobState.TRANSCRIBING,
      chunkTotal = event.chunkCount,
      chunkDone = event.chunkIndex,
      progress = overall,
      phase = JobPhase.Transcribing(event.chunkIndex, event.chunkCount, event.partIndex + 1, event.partCount).encode(),
    )

    is TranscriptionProgress.Waiting -> copy(phase = JobPhase.Waiting(event.seconds).encode())

    TranscriptionProgress.Stitching -> copy(state = JobState.STITCHING, progress = overall, phase = JobPhase.Stitching.encode())
  }
}
