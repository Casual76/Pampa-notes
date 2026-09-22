package dev.pampa.pampanotes.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
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
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import dev.pampa.pampanotes.core.transcription.TranscriptionProgress
import dev.pampa.pampanotes.core.transcription.TranscriptionRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

  /** Cosa fare dopo un lavoro: il prossimo, oppure fermarsi e aspettare il computer di casa. */
  private enum class Step { NEXT, WAIT }

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(
    title = applicationContext.getString(dev.pampa.pampanotes.R.string.notification_transcribing),
    text = null,
    progress = null,
  )

  override suspend fun doWork(): Result {
    val providerId = inputData.getString(KEY_PROVIDER) ?: return Result.failure()
    // Il computer di casa e' configurato ma non risponde: non si va nemmeno in primo piano. I
    // lavori restano in fila e lo dicono, e si riprova (vedi [waitForEndpoint]).
    if (providerId == OpenAiCompatProvider.ID && repository.nextQueued(providerId) != null &&
      repository.endpointState() == TranscriptionRepository.EndpointState.UNREACHABLE
    ) {
      return waitForEndpoint()
    }
    setForeground(getForegroundInfo())

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
      val step = runCatching { process(job) }
        .getOrElse { error ->
          // Un guasto imprevisto non deve fermare la coda: il lavoro si segna fallito e si passa
          // al successivo, altrimenti un file rotto blocca tutti quelli dietro di lui.
          if (error is CancellationException && !isStopped) throw error
          val translated = TranscriptionError.from(error)
          // Un errore di rete verso il computer di casa, e il computer non risponde: non e' il
          // lavoro che e' andato male, e' il computer che se n'e' andato a meta'. Si torna in fila.
          val vanished = job.provider == OpenAiCompatProvider.ID &&
            (translated is TranscriptionError.Network || translated is TranscriptionError.Timeout) &&
            repository.endpointState() == TranscriptionRepository.EndpointState.UNREACHABLE
          if (vanished) {
            repository.requeueForEndpoint(job.id)
            Step.WAIT
          } else {
            fail(job, translated)
            Step.NEXT
          }
        }
      if (step == Step.WAIT) return waitForEndpoint()
    }
    if (providerId == OpenAiCompatProvider.ID) repository.markWaitingForEndpoint(false)
    return Result.success()
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
      return Step.NEXT
    }
    return transcribe(job)
  }

  private suspend fun transcribe(job: JobEntity): Step {
    val settings = settingsStore.current()
    val provider = repository.providerFor(job.provider) ?: run {
      // Configurato ma muto: si aspetta. Mai configurato: e' un errore, e lo si dice.
      if (job.provider == OpenAiCompatProvider.ID && settings.hasEndpoint) return Step.WAIT
      fail(
        job,
        TranscriptionError.Unauthorized(
          applicationContext.getString(dev.pampa.pampanotes.R.string.error_provider_not_configured),
        ),
      )
      return Step.NEXT
    }

    val parts = repository.partsOf(job.sessionId)
    if (parts.isEmpty()) {
      fail(job, TranscriptionError.Decode(applicationContext.getString(dev.pampa.pampanotes.R.string.error_no_audio)))
      return Step.NEXT
    }

    val model = repository.resolveModel(provider, settings)
    val request = repository.requestFor(job.sessionId, model, settings)
    repository.update(job.copy(state = JobState.PREPARING, model = model, attempts = job.attempts + 1, phase = null, errorCode = null, errorMessage = null))

    // Lo stato piu' recente, aggiornato dal motore; a scriverlo ci pensa un'altra coroutine.
    //
    // Separati apposta: il progresso arriva ogni centoventotto kilobyte caricati, cioe' decine di
    // volte al secondo, e una scrittura sul database dentro il ciclo di upload rallenterebbe
    // l'upload per muovere una barra che l'occhio non riesce comunque a seguire.
    val latest = MutableStateFlow(job.copy(state = JobState.PREPARING, model = model))

    try {
      val result = coroutineScope {
        val publisher = launch {
          while (isActive) {
            delay(PUBLISH_EVERY_MS)
            runCatching { publish(latest.value) }
          }
        }
        try {
          // Il tetto di tempo sta qui e non sulla socket: un `withTimeout` si puo' annullare, una
          // lettura bloccata su una socket no.
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
        } finally {
          publisher.cancel()
        }
      }

      if (repository.get(job.id)?.state == JobState.CANCEL_REQUESTED) {
        cancel(job)
        return Step.NEXT
      }

      repository.update(latest.value.copy(state = JobState.STITCHING, progress = 0.98f, phase = null))
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
      AppNotifications.notifyDone(applicationContext, job.id, transcript.wordCount)
    } catch (timeout: TimeoutCancellationException) {
      if (job.provider == OpenAiCompatProvider.ID && repository.endpointState() == TranscriptionRepository.EndpointState.UNREACHABLE) {
        repository.requeueForEndpoint(job.id)
        return Step.WAIT
      }
      fail(job, TranscriptionError.Timeout(applicationContext.getString(dev.pampa.pampanotes.R.string.error_timeout)))
    } catch (cancellation: CancellationException) {
      // Annullato dall'utente: il lavoro si chiude, i pezzi gia' fatti restano per una ripresa.
      if (repository.get(job.id)?.state == JobState.CANCEL_REQUESTED) cancel(job) else throw cancellation
    }
    return Step.NEXT
  }

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

    repository.update(job.copy(state = JobState.TRANSCRIBING, model = model, attempts = job.attempts + 1, errorCode = null, errorMessage = null))
    val latest = MutableStateFlow(job.copy(state = JobState.TRANSCRIBING, model = model))

    try {
      val result = coroutineScope {
        val publisher = launch {
          while (isActive) {
            delay(PUBLISH_EVERY_MS)
            runCatching { publish(latest.value, dev.pampa.pampanotes.R.string.notification_refining) }
          }
        }
        try {
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
                  "waiting:${progress.waitingSeconds}"
                } else {
                  "refining:${progress.chunkIndex + 1}/${progress.chunkCount}"
                },
              )
            }
          }
        } finally {
          publisher.cancel()
        }
      }

      if (repository.get(job.id)?.state == JobState.CANCEL_REQUESTED) {
        repository.update(latest.value.copy(state = JobState.CANCELLED, phase = null, finishedAt = System.currentTimeMillis()))
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
      if (repository.get(job.id)?.state == JobState.CANCEL_REQUESTED) {
        repository.update(latest.value.copy(state = JobState.CANCELLED, phase = null, finishedAt = System.currentTimeMillis()))
      } else {
        throw cancellation
      }
    } catch (error: RefinementError) {
      fail(job, TranscriptionError.from(error.cause ?: error))
    }
  }

  private suspend fun publish(job: JobEntity, titleRes: Int = dev.pampa.pampanotes.R.string.notification_transcribing) {
    repository.update(job)
    setForeground(
      foregroundInfo(
        title = applicationContext.getString(titleRes),
        text = job.phase,
        progress = job.progress,
      ),
    )
  }

  private suspend fun cancel(job: JobEntity) {
    repository.update(job.copy(state = JobState.CANCELLED, phase = null, finishedAt = System.currentTimeMillis()))
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
    return if (android.os.Build.VERSION.SDK_INT >= 29) {
      ForegroundInfo(AppNotifications.ID_TRANSCRIPTION_FOREGROUND, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(AppNotifications.ID_TRANSCRIPTION_FOREGROUND, notification)
    }
  }

  companion object {
    const val KEY_PROVIDER = "provider"

    /** Ogni mezzo secondo: piu' spesso di cosi' la barra non si muove comunque. */
    private const val PUBLISH_EVERY_MS = 500L
  }
}

/** Dal progresso del motore allo stato che la riga del lavoro mostra. */
private fun JobEntity.applyProgress(progress: TranscriptionProgress): JobEntity = when (progress) {
  is TranscriptionProgress.Preparing -> copy(
    state = JobState.PREPARING,
    progress = progress.fraction * 0.25f,
    phase = "preparing:${progress.partIndex + 1}/${progress.partCount}:${(progress.fraction * 100).toInt()}",
  )

  is TranscriptionProgress.Uploading -> copy(
    state = JobState.UPLOADING,
    chunkTotal = progress.chunkCount,
    chunkDone = (progress.chunkIndex - 1).coerceAtLeast(0),
    progress = chunkFraction(progress.chunkIndex, progress.chunkCount, progress.fraction),
    phase = "uploading:${progress.chunkIndex}/${progress.chunkCount}:${(progress.fraction * 100).toInt()}",
  )

  is TranscriptionProgress.Transcribing -> copy(
    state = JobState.TRANSCRIBING,
    chunkTotal = progress.chunkCount,
    chunkDone = progress.chunkIndex,
    progress = chunkFraction(progress.chunkIndex, progress.chunkCount, 1f),
    phase = "transcribing:${progress.chunkIndex}/${progress.chunkCount}",
  )

  is TranscriptionProgress.Waiting -> copy(
    phase = "waiting:${progress.seconds}",
  )

  TranscriptionProgress.Stitching -> copy(state = JobState.STITCHING, progress = 0.98f, phase = "stitching")
}

/**
 * Il quarto iniziale e' la preparazione, il resto sono i pezzi.
 *
 * Una barra che sta ferma sul venticinque per cento mentre decodifica e poi salta a cento e' una
 * barra che non dice niente: la decodifica di un'ora dura quanto un paio di richieste.
 */
private fun chunkFraction(chunkIndex: Int, chunkCount: Int, within: Float): Float {
  if (chunkCount <= 0) return 0.25f
  val done = (chunkIndex - 1).coerceAtLeast(0)
  return (0.25f + 0.73f * ((done + within) / chunkCount)).coerceIn(0f, 0.98f)
}
