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
import dev.pampa.pampanotes.core.transcription.EndpointWait
import dev.pampa.pampanotes.core.transcription.GroqWhisperProvider
import dev.pampa.pampanotes.core.transcription.IdleTimeoutException
import dev.pampa.pampanotes.core.transcription.JobPhase
import dev.pampa.pampanotes.core.transcription.OpenAiCompatProvider
import dev.pampa.pampanotes.core.transcription.TranscriptionError
import dev.pampa.pampanotes.core.transcription.TranscriptionProgress
import dev.pampa.pampanotes.core.transcription.TranscriptionRunner
import dev.pampa.pampanotes.core.transcription.withIdleTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *    interrompe tutto, connessione compresa — e il computer di casa riceve la `DELETE` che lo ferma
 *    (`OpenAiCompatProvider.cancelRemote`) — e il lavoro si chiude annullato;
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
  private val computerOnly: dev.pampa.pampanotes.core.archive.ComputerOnlyScope,
  /** «Il testo che arriva a pezzi»: il provvisorio che la sessione mostra mentre il computer lavora. */
  private val partials: dev.pampa.pampanotes.core.transcription.PartialTranscripts,
) : CoroutineWorker(context, params) {

  /** Cosa fare dopo un lavoro: il prossimo, aspettare il computer di casa, o riprendere a un'ora. */
  private sealed interface Step {
    data object Next : Step
    data object WaitForEndpoint : Step
    data class ResumeAt(val atMillis: Long) : Step
  }

  /** Il lavoro l'ha annullato chi guardava la riga: l'utente, o la riga che non c'e' piu'. */
  private class JobCancelled : CancellationException("lavoro annullato")

  /**
   * I lavori che questo giro ha lasciato in fila apposta: la loro sessione la sta trascrivendo un
   * altro dispositivo (vedi [transcribe]). Senza saltarli il ciclo li riprenderebbe all'infinito.
   */
  private val skipped = mutableSetOf<String>()

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(
    title = applicationContext.getString(dev.pampa.pampanotes.R.string.notification_transcribing),
    text = null,
    progress = null,
    jobId = null,
  )

  override suspend fun doWork(): Result {
    val providerId = inputData.getString(KEY_PROVIDER) ?: return Result.failure()

    // Prima di prendere qualunque lavoro: quelli rimasti «in corso» da un processo morto tornano in
    // fila. Una volta per processo, chiunque arrivi prima fra l'avvio dell'app e un worker.
    if (runCatching { repository.requeueInterruptedOnce() }.getOrDefault(false)) wakeQueuesWithWork()

    // «Solo il computer di casa»: le trascrizioni accodate per Groq prima che la si accendesse (o
    // arrivate con un backup) passano al computer, e questa coda non le tocca.
    if (providerId == GroqWhisperProvider.ID && runCatching { settingsStore.current().customOnly }.getOrDefault(false)) {
      if (runCatching { repository.moveGroqTranscriptionsToComputer() }.getOrDefault(0) > 0) scheduler.kick(OpenAiCompatProvider.ID)
    }

    if (repository.nextQueued(providerId) == null) return finish(providerId)

    // Il computer di casa e' configurato ma non risponde: non si va nemmeno in primo piano. I
    // lavori restano in fila e lo dicono, e si riprova (vedi [waitForEndpoint]).
    if (providerId == OpenAiCompatProvider.ID &&
      repository.endpointState() == TranscriptionRepository.EndpointState.UNREACHABLE
    ) {
      return waitForEndpoint(providerId)
    }
    // Il computer ha risposto: l'attesa e' finita, e se si torna ad aspettarlo e' un'attesa nuova,
    // dal passo corto. Il timer che l'avrebbe svegliata non serve piu'.
    if (providerId == OpenAiCompatProvider.ID) endWait(providerId)

    // Da Android 12 un servizio in primo piano non parte se l'app e' in background (un tentativo
    // rimandato che scade mentre il telefono e' in tasca): e' un «non adesso», non un guasto. Vedi
    // [needsApp].
    if (!startForeground()) return needsApp(providerId)
    AppNotifications.cancelNeedsApp(applicationContext)
    runCatching { repository.clearNeedsApp(providerId) }

    while (true) {
      if (isStopped) return Result.retry()
      val job = repository.nextQueued(providerId, skipped) ?: break
      // Prima di ogni lavoro, non solo del primo: il computer puo' spegnersi fra una lezione e la
      // successiva, e la seconda deve aspettare, non fallire.
      if (job.provider == OpenAiCompatProvider.ID && job.type == JobType.TRANSCRIBE &&
        repository.endpointState() == TranscriptionRepository.EndpointState.UNREACHABLE
      ) {
        return waitForEndpoint(providerId)
      }
      val step = try {
        process(job)
      } catch (cancelled: CancellationException) {
        // Il worker e' stato fermato: il lavoro e' gia' tornato in fila (o annullato) dentro
        // [process]; qui si esce e basta, WorkManager sa cosa fare di un worker fermato.
        throw cancelled
      } catch (error: Throwable) {
        // Un guasto imprevisto non deve fermare la coda: il lavoro si segna fallito e si passa
        // al successivo, altrimenti un file rotto blocca tutti quelli dietro di lui. Salvo che sia
        // il computer di casa a essersene andato: allora il lavoro torna in fila (vedi [lostComputer]).
        val translated = TranscriptionError.from(error)
        lostComputer(job, translated, baseUrl = null) ?: run {
          fail(job, translated)
          Step.Next
        }
      }
      when (step) {
        Step.Next -> Unit
        Step.WaitForEndpoint -> return waitForEndpoint(providerId)
        is Step.ResumeAt -> {
          scheduler.kickAfter(providerId, step.atMillis - System.currentTimeMillis())
          return Result.success()
        }
      }
    }
    return finish(providerId)
  }

  private suspend fun finish(providerId: String): Result {
    if (providerId == OpenAiCompatProvider.ID) {
      repository.markWaitingForEndpoint(false)
      endWait(providerId)
    }
    // Restano in fila le sessioni che un altro dispositivo sta trascrivendo: fra un po' si riguarda.
    // O il segno se n'e' andato (l'altro ha finito, e la trascrizione e' arrivata o arrivera' col
    // sync: il lavoro si chiude da se'), o e' scaduto e tocca a questo dispositivo.
    if (skipped.isNotEmpty()) scheduler.kickAfter(providerId, ELSEWHERE_RECHECK_MS)
    return Result.success()
  }

  /**
   * Android non ha lasciato partire il servizio in primo piano: il worker e' stato svegliato mentre
   * l'app era in background (da Android 12), da un timer o da una sonda. Prima si rispondeva `retry`,
   * e restavano scritte fasi non piu' vere — «in attesa del computer di casa» con il PC che
   * rispondeva, «fino alle 14:32» con le 14:32 passate — mentre WorkManager allungava l'attesa a ogni
   * tentativo, tutti rifiutati allo stesso modo.
   *
   * Adesso i lavori in fila dicono com'e': pronti, manca solo l'app aperta. Una notifica lo dice con
   * un tocco che la apre, e aprirla sveglia le code (`MainActivity.onStart`), che a quel punto
   * possono andare in primo piano. Il worker si chiude con `success`: riprovare da qui prenderebbe lo
   * stesso rifiuto.
   */
  private suspend fun needsApp(providerId: String): Result {
    runCatching { repository.markNeedsApp(providerId) }
    AppNotifications.notifyNeedsApp(applicationContext)
    return Result.success()
  }

  /**
   * Un errore di rete o un tempo scaduto verso il computer di casa: e' il lavoro che e' andato male,
   * o il computer che se n'e' andato?
   *
   * - Il computer non risponde: il lavoro torna in fila ad aspettarlo ([waitForEndpoint]).
   * - Risponde, ma a un altro indirizzo da quello con cui il lavoro era partito ([baseUrl]: da casa a
   *   Tailscale uscendo, o al ritorno): il lavoro torna in fila e riparte subito dalla strada nuova.
   *   Prima falliva, con il computer raggiungibile.
   * - Tutte e due le volte, non all'infinito: alla terza il lavoro si chiude fallito con un errore
   *   che lo dice (vedi `TranscriptionRepository.requeueForEndpoint`).
   *
   * @return null se non c'entra il computer: chi chiama segna il lavoro fallito come sempre.
   */
  private suspend fun lostComputer(job: JobEntity, error: TranscriptionError, baseUrl: String?): Step? {
    if (job.provider != OpenAiCompatProvider.ID || job.type != JobType.TRANSCRIBE) return null
    if (error !is TranscriptionError.Network && error !is TranscriptionError.Timeout) return null
    val requeued: Boolean
    val next: Step
    when {
      repository.endpointState() == TranscriptionRepository.EndpointState.UNREACHABLE -> {
        requeued = repository.requeueForEndpoint(job.id)
        next = Step.WaitForEndpoint
      }
      baseUrl != null && repository.endpointMovedFrom(baseUrl) -> {
        requeued = repository.requeueForNewAddress(job.id)
        next = Step.Next
      }
      else -> return null
    }
    if (requeued) return next
    fail(
      job,
      TranscriptionError.ComputerLost(applicationContext.getString(dev.pampa.pampanotes.R.string.error_computer_lost)),
    )
    return Step.Next
  }

  private suspend fun endWait(providerId: String) {
    runCatching { settingsStore.setEndpointWaitingSince(providerId, null) }
    scheduler.cancelEndpointRetry(providerId)
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
   * ritentare a mano — o, peggio, di finire su Groq.
   *
   * Si riprova a passo fisso ([EndpointWait]: un minuto per la prima mezz'ora, poi cinque) e non
   * col `retry` di WorkManager, la cui attesa raddoppia: dopo un riavvio del PC la coda restava
   * ferma minuti con il companion che rispondeva gia'. Per questo il worker chiude con `success`
   * dopo aver messo il timer ([WorkScheduler.retryForEndpoint], uno solo per provider) che sveglia
   * la coda. Da quando si aspetta sta in DataStore ([PampaSettingsStore.endpointWaitingSince]):
   * sopravvive al processo e al riavvio, e si azzera quando il computer risponde. E' ora del
   * telefono e non un orologio monotono, perche' deve valere anche dopo un riavvio; se l'ora salta,
   * al peggio si sceglie il passo corto o quello lungo un giro prima (vedi [EndpointWait]). Una
   * sonda ogni quarto d'ora ([EndpointWatchWorker]) resta come rete di sicurezza; chi il computer
   * lo vede rispondere — l'archivio, «Prova», l'apertura dell'app — sveglia la coda prima.
   */
  private suspend fun waitForEndpoint(providerId: String): Result {
    repository.markWaitingForEndpoint(true)
    scheduler.watchEndpoint(true)
    val now = System.currentTimeMillis()
    val since = EndpointWait.waitingSince(runCatching { settingsStore.endpointWaitingSince(providerId) }.getOrDefault(0L), now)
    runCatching { settingsStore.setEndpointWaitingSince(providerId, since) }
    scheduler.retryForEndpoint(providerId, EndpointWait.nextDelayMs(now - since))
    return Result.success()
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
    // «Solo il computer di casa» acceso dopo che il lavoro era stato accodato per Groq: non parte da
    // qui. Passa al computer (con tutti quelli nella stessa situazione) e si sveglia la sua coda.
    if (repository.effectiveProvider(job) != job.provider) {
      if (repository.moveGroqTranscriptionsToComputer() > 0) scheduler.kick(OpenAiCompatProvider.ID)
      return Step.Next
    }

    // Il segno «in trascrizione su» copre solo i lavori al lavoro, e un lavoro accodato qui prima che
    // l'altro dispositivo partisse lo trovava solo adesso. Partire vorrebbe dire la stessa lezione
    // due volte: si resta in fila, e il giro dopo riguarda (vedi [finish]).
    repository.busyElsewhere(job.sessionId)?.let { busy ->
      repository.markElsewhere(job.id, busy.device)
      skipped += job.id
      return Step.Next
    }
    // L'altro ha gia' finito, e la sua trascrizione e' arrivata col sync dopo che questo lavoro era
    // in fila: il lavoro e' fatto. Rifarlo cancellerebbe il risultato dell'altro e le sue raffinate.
    if (repository.arrivedFromElsewhere(job)) {
      repository.markDone(job.id)
      runner.cleanUp(job.id)
      return Step.Next
    }

    val settings = settingsStore.current()
    val bound = repository.bind(job.provider) ?: run {
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
    val provider = bound.provider

    val parts = repository.partsOf(job.sessionId)
    if (parts.isEmpty()) {
      fail(job, TranscriptionError.Decode(applicationContext.getString(dev.pampa.pampanotes.R.string.error_no_audio)))
      return Step.Next
    }
    // La fotografia delle parti: il risultato si salva per parte, dove ognuna sta quando arriva.
    val partIds = parts.map { it.id }

    val model = repository.resolveModel(provider, settings)
    val request = repository.requestFor(job.sessionId, model, settings)
    // Annullato o tolto mentre si sceglieva il modello: non si parte. Il passaggio e' condizionato,
    // cosi' un «Annulla» che arriva proprio adesso non viene riscritto da una copia vecchia.
    if (!repository.start(job.id, JobState.PREPARING, model)) return Step.Next
    // Da qui si misura la velocita' della trascrizione: l'attesa in fila non e' lentezza di nessuno.
    val startedAt = System.currentTimeMillis()

    // Lo stato piu' recente, aggiornato dal motore; a scriverlo ci pensa un'altra coroutine.
    //
    // Separati apposta: il progresso arriva ogni centoventotto kilobyte caricati, cioe' decine di
    // volte al secondo, e una scrittura sul database dentro il ciclo di upload rallenterebbe
    // l'upload per muovere una barra che l'occhio non riesce comunque a seguire.
    val latest = MutableStateFlow(job.copy(state = JobState.PREPARING, model = model, attempts = job.attempts + 1))

    // Il tetto di tempo e' un tetto di silenzio: riparte a ogni evento di progresso (vedi
    // [withIdleTimeout]). Un tetto sull'intera sessione uccideva lezioni lunghe che andavano
    // benissimo; quello complessivo resta, largo, per chi parla ma non finisce mai.
    val idleMs = settings.endpointTimeoutMinutes.toLong().coerceAtLeast(1L) * 60_000L
    val capMs = idleMs * (parts.size + 1)

    try {
      val result = watched(job, latest, keepAlive = { repository.partsOutliveSession(job.sessionId, partIds) }) {
        // Il watchdog annulla il lavoro, e annullandosi stacca anche la connessione
        // (`TranscriptionHttp`), che da sola aspetterebbe il suo timeout di lettura.
        withIdleTimeout(idleMs, capMs) { touch ->
          runner.transcribeSession(
            jobId = job.id,
            parts = parts,
            provider = provider,
            request = request,
            chunkMinutes = settings.chunkMinutes,
            // Col computer che lavora da se', quello che sale per la trascrizione resta nel suo
            // archivio (se l'archivio e' acceso): una lezione non viaggia due volte.
            archiveUploads = settings.archiveEnabled,
            onArchived = repository::markPartArchived,
            // «Il testo che arriva a pezzi»: la sessione lo mostra «in arrivo» finche' il lavoro va.
            onPartial = { partial -> partials.publish(job.sessionId, partial) },
          ) { progress ->
            touch()
            latest.update { it.applyProgress(progress) }
          }
        }
      }

      if (repository.get(job.id)?.state == JobState.CANCEL_REQUESTED) {
        cancel(job)
        return Step.Next
      }

      repository.publishProgress(latest.value.copy(state = JobState.STITCHING, progress = 0.98f, phase = null))
      val saved = repository.saveTranscript(job.sessionId, partIds, result)
      if (saved == null) {
        // Nessuna delle parti esiste piu': la sessione e' stata cancellata mentre il computer
        // trascriveva. Non c'e' niente da salvare, e non e' un fallimento da notificare — prima la
        // chiave esterna faceva fallire il salvataggio, con una notifica d'errore e la cartella del
        // lavoro lasciata su disco.
        repository.markCancelled(job.id)
        runner.cleanUp(job.id)
        return Step.Next
      }
      // La riga puo' non esserci piu' (la sessione e' stata unita a un'altra, e la riga se n'e'
      // andata con lei): il risultato e' salvato lo stesso, e lo si dice.
      repository.markDone(job.id)
      runner.cleanUp(job.id)
      val transcript = saved.transcript
      val run = transcript?.let { repository.recordRun(job.copy(sessionId = saved.sessionId, model = model), startedAt, it) }
      AppNotifications.notifyDone(applicationContext, job.id, transcript?.wordCount ?: 0, run)
      archiveIfComputerOnly(saved.sessionId)
    } catch (timeout: IdleTimeoutException) {
      val error = TranscriptionError.Timeout(applicationContext.getString(dev.pampa.pampanotes.R.string.error_timeout), timeout)
      return lostComputer(job, error, bound.baseUrl) ?: run {
        fail(job, error)
        Step.Next
      }
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
    } catch (error: Exception) {
      // Un errore di rete verso il computer di casa: qui si sa a quale indirizzo era legato il
      // lavoro, e se il computer adesso risponde da un altro si riparte da li' (vedi [lostComputer]).
      val translated = TranscriptionError.from(error)
      return lostComputer(job, translated, bound.baseUrl) ?: run {
        fail(job, translated)
        Step.Next
      }
    } finally {
      // Il testo provvisorio vale finche' il lavoro e' questo: salvato, fallito, annullato o
      // rimesso in fila, se ne va — il testo vero, se c'e', e' gia' nel database.
      partials.clear(job.sessionId)
    }
    return Step.Next
  }

  /**
   * Una lezione che una regola «solo sul computer» copre — le Registrazioni lo sono di serie — non
   * aspetta il giro periodico dell'archivio per andarsene: diciannove ore di audio restavano sul
   * telefono fino a sei ore dopo la trascrizione. Un giro adesso la porta sul PC se non c'e' ancora,
   * e alla fine `evictComputerOnly` la toglie da qui, con le sue guardie (il `HEAD` per file, la
   * lezione che si sta ascoltando). Mai un errore del lavoro: e' gia' `DONE`.
   */
  private suspend fun archiveIfComputerOnly(sessionId: String) {
    runCatching {
      val settings = settingsStore.current()
      if (!settings.archiveEnabled) return
      if (sessionId !in computerOnly.current().sessionIds) return
      // Non `archiveNow`: un giro gia' in corso ha letto l'elenco prima che la lezione finisse.
      scheduler.archiveSoon(settings.archiveOnlyUnmetered)
    }
  }

  /**
   * Il lavoro sotto sorveglianza: il progresso si scrive ogni mezzo secondo, e se la riga diventa
   * `CANCEL_REQUESTED` (o sparisce) il lavoro si interrompe subito — anche a meta' upload, anche
   * mentre il computer di casa trascrive — invece di accorgersene al pezzo successivo.
   *
   * [keepAlive] dice se una riga sparita vale lo stesso la pena: la riga se ne va in cascata con la
   * sua sessione, e una sessione unita a un'altra mentre si trascriveva ha passato le sue parti a
   * quella che resta — il risultato ha ancora un posto dove andare.
   */
  private suspend fun <T> watched(
    job: JobEntity,
    latest: MutableStateFlow<JobEntity>,
    titleRes: Int = dev.pampa.pampanotes.R.string.notification_transcribing,
    keepAlive: suspend () -> Boolean = { false },
    block: suspend () -> T,
  ): T = coroutineScope {
    val work: Deferred<T> = async { block() }
    val watcher = launch {
      repository.observeJob(job.id).first { current ->
        if (current == null) !keepAlive() else current.state == JobState.CANCEL_REQUESTED
      }
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
   * - Il worker e' stato fermato: dall'app (`WorkScheduler.stop`/`stopAll`, il ripristino di un
   *   backup; API 31+) il lavoro si chiude annullato; dal sistema torna in fila. In `NonCancellable`,
   *   perche' il worker fermato ha gia' il suo contesto annullato, e senza la scrittura non
   *   partirebbe. Il tasto «Annulla» della notifica non passa piu' di qui: chiede di annullare il
   *   lavoro, non di fermare il worker (vedi [JobCancelReceiver]).
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

    // Annullato mentre si sceglieva il modello: non si parte (il passaggio e' condizionato).
    if (!repository.start(job.id, JobState.TRANSCRIBING, model)) return
    val latest = MutableStateFlow(job.copy(state = JobState.TRANSCRIBING, model = model, attempts = job.attempts + 1))

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
      repository.markDone(job.id)
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
        jobId = job.id,
      ),
    )
  }

  private suspend fun cancel(job: JobEntity) {
    repository.markCancelled(job.id)
    runner.cleanUp(job.id)
  }

  /**
   * Il lavoro e' fallito: lo si scrive con le sole colonne dell'esito (la copia che si ha in mano e'
   * di prima della partenza) e lo si notifica. Un «Annulla» arrivato nel frattempo vince, e un
   * lavoro che non c'e' piu' non ha niente da dire: niente notifica d'errore per nessuno dei due.
   */
  private suspend fun fail(job: JobEntity, error: TranscriptionError) {
    when (withContext(NonCancellable) { repository.fail(job.id, error) }) {
      TranscriptionRepository.FailOutcome.FAILED -> {
        // Muta: i pezzi messi da parte sono tutti vuoti, e tenerli farebbe fallire «Riprova» senza
        // chiedere niente a nessuno. Gli altri fallimenti li tengono, per riprendere da li'.
        if (error is TranscriptionError.NoSpeech) runner.cleanUp(job.id)
        AppNotifications.notifyFailed(applicationContext, job.id, error.code, job.provider)
      }
      TranscriptionRepository.FailOutcome.CANCELLED,
      TranscriptionRepository.FailOutcome.GONE,
      -> runner.cleanUp(job.id)
    }
  }

  /**
   * La notifica in primo piano. Con un lavoro, «Annulla» annulla **quel lavoro** ([JobCancelReceiver]):
   * il worker resta vivo e passa al successivo. Senza (il primo istante, prima di prendere un
   * lavoro) il tasto non c'e': fermare il worker da li' rimetteva il lavoro in fila su Android 11 e
   * precedenti, e la coda non ripartiva.
   */
  private fun foregroundInfo(title: String, text: String?, progress: Float?, jobId: String?): ForegroundInfo {
    val cancel = jobId?.let { JobCancelReceiver.pendingIntent(applicationContext, it) }
    val notification = AppNotifications.buildProgress(applicationContext, title, text, progress, cancel)
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

    /**
     * Ogni quanto si riguarda una sessione che un altro dispositivo sta trascrivendo: abbastanza
     * spesso da non far aspettare troppo se l'altro si e' fermato, abbastanza di rado da non
     * svegliare il telefono per niente.
     */
    private const val ELSEWHERE_RECHECK_MS = 10 * 60_000L
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
