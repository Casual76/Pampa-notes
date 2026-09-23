package dev.pampa.pampanotes.core.transcription

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whisper su Groq, con la chiave dell'utente.
 *
 * Il tetto per richiesta e' quello che decide tutto il resto dell'architettura: venticinque megabyte
 * sul piano gratuito sono circa venti minuti di m4a a qualita' telefono, quindi una lezione va
 * decodificata, tagliata nei silenzi, ricodificata piccola e mandata a pezzi. Il numero e'
 * configurabile perche' il piano a pagamento lo alza a cento.
 */
class GroqWhisperProvider(
  private val http: TranscriptionHttp,
  private val apiKey: String,
  maxUploadBytes: Long = FREE_TIER_LIMIT_BYTES,
) : TranscriptionProvider {

  override val id: String = ID

  override val capabilities: TranscriptionCapabilities = TranscriptionCapabilities(
    maxUploadBytes = maxUploadBytes,
    supportsSegments = true,
    needsChunking = true,
    acceptedExtensions = ACCEPTED_EXTENSIONS,
  )

  override suspend fun listModels(): List<String> {
    val body = http.getJson("$BASE_URL/models", headers())
    // Fra tutti i modelli del catalogo interessano quelli che trascrivono, e si riconoscono dal nome:
    // Groq non marca la modalita' in nessun campo.
    return VerboseJson.parseModels(body).filter { it.contains("whisper", ignoreCase = true) }
  }

  override suspend fun health(): EndpointHealth {
    val started = System.currentTimeMillis()
    return runCatching { listModels() }
      .map { models ->
        EndpointHealth(
          reachable = true,
          latencyMs = System.currentTimeMillis() - started,
          models = models,
          serverName = "Groq",
        )
      }
      .getOrElse { error ->
        EndpointHealth(
          reachable = false,
          latencyMs = System.currentTimeMillis() - started,
          detail = TranscriptionError.from(error).message,
        )
      }
  }

  override suspend fun transcribe(
    file: File,
    mime: String,
    request: TranscribeRequest,
    onProgress: (UploadProgress) -> Unit,
    onRemote: (RemoteProgress) -> Unit,
  ): TranscriptResult {
    val limit = capabilities.maxUploadBytes
    if (limit != null && file.length() > limit) {
      throw TranscriptionError.FileTooLarge(limit, "il pezzo supera il limite di Groq")
    }

    val fields = linkedMapOf(
      "model" to request.model,
      "response_format" to "verbose_json",
      "temperature" to request.temperature.toString(),
    )
    request.language?.takeIf { it.isNotBlank() }?.let { fields["language"] = it }
    request.prompt?.takeIf { it.isNotBlank() }?.let { fields["prompt"] = it.take(PROMPT_MAX_CHARS) }

    val body = http.postAudio(
      url = "$BASE_URL/audio/transcriptions",
      headers = headers(),
      fields = fields,
      file = file,
      fileMime = mime,
      readTimeoutMillis = READ_TIMEOUT_MS,
      onProgress = onProgress,
    )
    return VerboseJson.parse(body)
  }

  private fun headers(): Map<String, String> = mapOf("Authorization" to "Bearer $apiKey")

  companion object {
    const val ID = "groq"
    const val BASE_URL = "https://api.groq.com/openai/v1"

    /** Il tetto del piano gratuito. Quello a pagamento arriva a cento megabyte. */
    const val FREE_TIER_LIMIT_BYTES = 25L * 1024 * 1024
    const val DEV_TIER_LIMIT_BYTES = 100L * 1024 * 1024

    /** Il prompt di Whisper vale al massimo 224 token: ottocento caratteri di nomi propri ci stanno. */
    const val PROMPT_MAX_CHARS = 800

    /**
     * I formati che Groq prende cosi' come sono, riconosciuti dall'estensione del nome che si manda.
     * Tutto il resto — un `.amr` del registratore, un `.3gp`, un `.aac` nudo — va decodificato e
     * ricodificato: mandato com'e' tornava un 400 che nessuno riprovando poteva guarire.
     */
    val ACCEPTED_EXTENSIONS = setOf("flac", "mp3", "mp4", "mpeg", "mpga", "m4a", "ogg", "opus", "wav", "webm")

    /**
     * Groq risponde in pochi secondi anche per venti minuti di audio, ma il caricamento sulla rete
     * mobile puo' essere lento e il tempo di lettura parte da quando l'ultimo byte e' uscito.
     */
    const val READ_TIMEOUT_MS = 5 * 60 * 1000

    /** Il modello che vogliamo, quando c'e'; altrimenti il primo whisper del catalogo. */
    val PREFERRED_MODELS = listOf("whisper-large-v3-turbo", "whisper-large-v3")

    fun pickModel(available: List<String>): String? =
      PREFERRED_MODELS.firstOrNull { it in available } ?: available.firstOrNull { it.contains("whisper", true) }
  }
}

/**
 * Un endpoint compatibile con l'API OpenAI: di regola il computer di casa con WhisperX.
 *
 * Di serie nessun tetto e nessun taglio: la macchina che sta dall'altra parte macina un'ora di audio
 * senza battere ciglio, e mandargliela intera evita sia le cuciture sia gli errori che le cuciture
 * possono introdurre. Il prezzo e' che bisogna aspettarla, e l'attesa puo' durare mezz'ora. Chi ha
 * un PC con poca memoria video puo' chiedere pezzi ([maxChunkMinutes]): stessa pianificazione e
 * stessa cucitura di Groq.
 *
 * Le credenziali le da' [auth] a ogni chiamata — il biglietto dell'account, o il codice scritto a
 * mano — e un 401 col biglietto lo rinnova e riprova una volta ([call]).
 */
class OpenAiCompatProvider(
  private val http: TranscriptionHttp,
  baseUrl: String,
  private val auth: CompanionAuth = CompanionAuth.fixed(null),
  private val readTimeoutMillis: Int = READ_TIMEOUT_MS,
  /** Null: il file va intero. Altrimenti la durata massima di un pezzo, in minuti. */
  maxChunkMinutes: Int? = null,
  /** Ogni quanto chiedere a che punto e' il lavoro (vedi [RemoteJobPoller]); i test lo accorciano. */
  private val pollIntervalMs: Long = RemoteJobPoller.DEFAULT_INTERVAL_MS,
  /** Dopo quanto silenzio il computer si da' per perso (vedi [RemoteJobPoller]); i test lo accorciano. */
  private val lostAfterSilenceMs: Long = RemoteJobPoller.LOST_AFTER_SILENCE_MS,
  /** Ogni quanto la sola sonda chiede `/health` (vedi [RemoteJobPoller]); i test lo accorciano. */
  private val blindIntervalMs: Long = RemoteJobPoller.BLIND_INTERVAL_MS,
  /**
   * I lavori lasciati indietro sul companion (vedi [AbandonedCompanionJobs]). Di serie uno suo, che
   * vale per i tentativi di questo provider; la coda gli passa quello del processo, cosi' vale anche
   * da un lavoro al successivo. La prova di «Prova» no: non trascrive niente.
   */
  private val abandoned: AbandonedCompanionJobs = AbandonedCompanionJobs(),
  /**
   * «Automatico»: la durata dei pezzi la sceglie il computer prima di ogni lezione (`max_minutes=auto`),
   * e [onChunksChosen] riceve quello che ha scelto, per lo slider delle impostazioni. Un companion che
   * non conosce «auto» lo legge come «nessun tetto»: la lezione va intera, come prima.
   */
  private val autoChunks: Boolean = false,
  private val onChunksChosen: suspend (Int) -> Unit = {},
) : TranscriptionProvider, CompanionTranscription {

  /** Normalizzato una volta: chi digita l'indirizzo mette o non mette la barra e il `/v1`. */
  private val base: String = normalize(baseUrl)

  override val id: String = ID

  override val capabilities: TranscriptionCapabilities = TranscriptionCapabilities(
    maxUploadBytes = null,
    supportsSegments = true,
    needsChunking = maxChunkMinutes != null,
    maxChunkMinutes = maxChunkMinutes,
  )

  override suspend fun listModels(): List<String> =
    authorized { headers -> VerboseJson.parseModels(http.getJson("$base/models", headers)) }

  override suspend fun health(): EndpointHealth {
    val started = System.currentTimeMillis()
    // Prima `/health`, che i server pensati per questo espongono e risponde subito; se non c'e' si
    // ripiega su `/models`, che qualsiasi endpoint compatibile ha.
    // `/health` risponde anche senza credenziali: e' la sonda. Le si mandano lo stesso, perche' un
    // companion vecchio le chiedeva.
    val health = runCatching { fetchHealth() }.getOrNull()
    val models = runCatching { listModels() }.getOrElse { error ->
      return EndpointHealth(
        reachable = false,
        latencyMs = System.currentTimeMillis() - started,
        detail = TranscriptionError.from(error).message,
      )
    }
    val serverName = (health as? JsonObject)
      ?.get("model")?.let { (it as? JsonPrimitive)?.content }
    return EndpointHealth(
      reachable = true,
      latencyMs = System.currentTimeMillis() - started,
      models = models,
      serverName = serverName,
      features = parseFeatures(health),
    )
  }

  /** Le caratteristiche lette l'ultima volta: il provider vive un lavoro, e un lavoro chiede una volta. */
  @Volatile
  private var knownFeatures: Set<String>? = null

  override suspend fun features(): Set<String> {
    knownFeatures?.let { return it }
    // Un companion che non risponde qui non viene ricordato: la prossima parte richiede. Nel
    // frattempo vale «non sa niente», cioe' la strada di sempre.
    val health = runCatching { fetchHealth() }.getOrElse {
      if (it is CancellationException) throw it
      return emptySet()
    }
    return parseFeatures(health).also { knownFeatures = it }
  }

  private suspend fun fetchHealth(): JsonElement? =
    http.getJson("${base.removeSuffix("/v1")}/health", headers(auth.bearer()))

  override suspend fun transcribe(
    file: File,
    mime: String,
    request: TranscribeRequest,
    onProgress: (UploadProgress) -> Unit,
    onRemote: (RemoteProgress) -> Unit,
  ): TranscriptResult = VerboseJson.parse(
    watching(onRemote, uploadedAlready = false) { headers, uploaded ->
      http.postAudio(
        url = "$base/audio/transcriptions",
        headers = headers,
        fields = baseFields(request),
        file = file,
        fileMime = mime,
        readTimeoutMillis = readTimeoutMillis,
        onProgress = uploadTracker(uploaded, onProgress),
      )
    },
  )

  override suspend fun transcribeByRef(
    sha256: String,
    request: TranscribeRequest,
    maxMinutes: Int?,
    onRemote: (RemoteProgress) -> Unit,
  ): TranscriptResult {
    val fields = baseFields(request).apply {
      put(FIELD_SHA, sha256.lowercase())
      putMaxMinutes(maxMinutes)
    }
    // Niente da caricare: le domande sullo stato possono partire subito.
    return VerboseJson.parse(
      watching(onRemote, uploadedAlready = true) { headers, _ ->
        companionRefusals {
          http.postForm("$base/audio/transcriptions", headers, fields, readTimeoutMillis)
        }
      },
    ).also { reportChunks(it) }
  }

  private fun MutableMap<String, String>.putMaxMinutes(maxMinutes: Int?) {
    if (autoChunks) put(FIELD_MAX_MINUTES, MAX_MINUTES_AUTO) else maxMinutes?.let { put(FIELD_MAX_MINUTES, it.toString()) }
  }

  /** La durata scelta dal computer va allo slider; un suo guasto non tocca la trascrizione. */
  private suspend fun reportChunks(result: TranscriptResult) {
    val used = result.maxMinutesUsed ?: return
    if (!autoChunks) return
    runCatching { onChunksChosen(used) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
  }

  override suspend fun transcribeUpload(
    file: File,
    mime: String,
    request: TranscribeRequest,
    upload: CompanionUpload,
    onProgress: (UploadProgress) -> Unit,
    onRemote: (RemoteProgress) -> Unit,
  ): TranscriptResult {
    val fields = baseFields(request).apply {
      upload.sha256?.takeIf { it.isNotBlank() }?.let { sha ->
        put(FIELD_SHA, sha.lowercase())
        // L'archivio tiene un file sotto la sua impronta: senza impronta non c'e' niente da tenere.
        if (upload.archive) put(FIELD_ARCHIVE, "1")
      }
      upload.name?.takeIf { it.isNotBlank() }?.let { put(FIELD_NAME, it) }
      putMaxMinutes(upload.maxMinutes)
    }
    return VerboseJson.parse(
      watching(onRemote, uploadedAlready = false) { headers, uploaded ->
        companionRefusals {
          http.postAudio(
            url = "$base/audio/transcriptions",
            headers = headers,
            fields = fields,
            file = file,
            fileMime = mime,
            readTimeoutMillis = readTimeoutMillis,
            onProgress = uploadTracker(uploaded, onProgress),
          )
        }
      },
    ).also { reportChunks(it) }
  }

  private fun baseFields(request: TranscribeRequest): LinkedHashMap<String, String> {
    val fields = linkedMapOf(
      "model" to request.model,
      "response_format" to "verbose_json",
      "temperature" to request.temperature.toString(),
      // Certi server danno i tempi solo se glieli si chiede; quelli che non capiscono il campo lo ignorano.
      "timestamp_granularities[]" to "segment",
    )
    request.language?.takeIf { it.isNotBlank() }?.let { fields["language"] = it }
    request.prompt?.takeIf { it.isNotBlank() }?.let { fields["prompt"] = it }
    return fields
  }

  /**
   * Il caricamento e' finito all'ultimo byte, e solo da li' si chiede a che punto e': prima il
   * telefono sa gia' tutto da solo, e le domande si metterebbero in fila sulla stessa rete lenta che
   * sta portando l'audio.
   */
  private fun uploadTracker(uploaded: AtomicBoolean, onProgress: (UploadProgress) -> Unit): (UploadProgress) -> Unit = { progress ->
    if (progress.totalBytes > 0 && progress.sentBytes >= progress.totalBytes) uploaded.set(true)
    onProgress(progress)
  }

  /**
   * La POST, con accanto le domande su a che punto e' ([RemoteJobPoller]).
   *
   * Un companion che si sta riavviando risponde `503 restarting` con un `Retry-After`: non e' un
   * guasto ma un «un attimo», e si aspetta qui ([restartWait], da 5 a 120 secondi) e si rimanda —
   * senza consumare i tentativi del runner, che sono per i guasti veri. Al massimo
   * [MAX_RESTART_WAITS] volte per lavoro: un companion che dice di riavviarsi per sempre non si
   * sta riavviando.
   */
  private suspend fun watching(
    onRemote: (RemoteProgress) -> Unit,
    uploadedAlready: Boolean,
    post: suspend (headers: Map<String, String>, uploaded: AtomicBoolean) -> JsonElement?,
  ): JsonElement? {
    while (true) {
      try {
        return watchOnce(onRemote, uploadedAlready, post)
      } catch (error: TranscriptionError.Server) {
        val wait = restartWait(error) ?: throw error
        if (++restartWaits > MAX_RESTART_WAITS) throw error
        delay(wait)
      }
    }
  }

  /** Quante volte questo lavoro ha aspettato un companion che si riavviava (vedi [watching]). */
  @Volatile
  private var restartWaits = 0

  /**
   * Una POST, con accanto le domande su a che punto e'.
   *
   * Un id per richiesta, scelto qui: e' con questo che si chiede al companion a che punto e'. Lo
   * stesso anche se [authorized] rimanda la POST con un biglietto nuovo — il primo tentativo e'
   * stato rifiutato prima di arrivare al lavoro, e il companion lo registra solo se passa. Tutto —
   * POST, domande, sonda, `DELETE` — va allo stesso [base]: il provider nasce con un indirizzo
   * risolto e non lo cambia, anche se nel frattempo il resolver passasse da casa a Tailscale.
   *
   * Quello che la POST da sola non sa fare:
   *  - **accorgersi che il computer ha perso il lavoro** (riavviato, spento a meta'): lo dice il
   *    [RemoteJobPoller] con [RemoteJobLost], confermato da `/health` ([pulse]), e qui diventa un
   *    [TranscriptionError.Network] che annulla la POST e si riprova — invece di novanta minuti di
   *    timeout con la coda ferma dietro;
   *  - **fermare il computer quando si annulla qui**: una coroutine annullata (l'utente, il tetto di
   *    tempo, il sistema che ferma il worker) manda `DELETE /v1/jobs/<id>` ([cancelRemote]). Anche
   *    a meta' caricamento: il companion registra il lavoro alle intestazioni;
   *  - **non far lavorare il PC due volte**: una POST abbandonata (persa, caduta) finisce in
   *    [abandoned], e la POST dopo — un altro tentativo della stessa parte, o la parte successiva —
   *    le manda la `DELETE`. **Dopo** essersi agganciata al companion (la prima scheda del lavoro
   *    nuovo, o al massimo [STALE_ATTACH_WAIT_MS]), non prima: il companion unisce le richieste
   *    identiche per impronta, e una `DELETE` stacca solo quella a cui e' mandata. Mandata prima,
   *    fermerebbe il lavoro vecchio proprio mentre quello nuovo poteva riprenderlo da dove era.
   *
   * @param uploadedAlready vero quando non c'e' niente da caricare: si chiede da subito.
   */
  private suspend fun watchOnce(
    onRemote: (RemoteProgress) -> Unit,
    uploadedAlready: Boolean,
    post: suspend (headers: Map<String, String>, uploaded: AtomicBoolean) -> JsonElement?,
  ): JsonElement? {
    val jobId = UUID.randomUUID().toString()
    val uploaded = AtomicBoolean(uploadedAlready)
    // Le credenziali con cui e' partita l'ultima POST: sono quelle che il companion accetta per
    // annullare il lavoro che hanno creato. Null finche' non ne e' partita nessuna.
    var sentWith: Map<String, String>? = null
    val lost = AtomicBoolean(false)
    val stale = abandoned.drain()
    // Il companion ha il lavoro nuovo (la prima scheda), o la POST e' finita comunque.
    val attached = CompletableDeferred<Unit>()
    return coroutineScope {
      val sweeper = if (stale.isEmpty()) null else launch {
        try {
          withTimeoutOrNull(STALE_ATTACH_WAIT_MS) { attached.await() }
        } finally {
          withContext(NonCancellable) { sweep(stale) }
        }
      }
      val poller = launch {
        try {
          RemoteJobPoller(
            fetch = { http.getJson("$base/jobs/$jobId", headers(auth.bearer()), readTimeoutMillis = POLL_TIMEOUT_MS) },
            intervalMs = pollIntervalMs,
            silenceLimitMs = lostAfterSilenceMs,
            probe = { pulse() },
            blindIntervalMs = blindIntervalMs,
            onUpdate = { progress ->
              attached.complete(Unit)
              onRemote(progress)
            },
          ).run(ready = { uploaded.get() })
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (gone: RemoteJobLost) {
          // Un figlio che fallisce annulla lo scope, cioe' la POST, e `coroutineScope` rilancia
          // questo errore al posto della cancellazione: chi chiama vede una rete caduta, e riprova.
          lost.set(true)
          throw TranscriptionError.Network(gone.message.orEmpty(), gone)
        } catch (ignored: Throwable) {
          // Qualunque altra cosa nelle domande sullo stato non tocca la trascrizione.
          Unit
        }
      }
      try {
        authorized { headers ->
          sentWith = headers
          // Un secondo giro (biglietto rinnovato) ricarica da capo: le domande aspettano di nuovo
          // l'ultimo byte, o un caricamento lento conterebbe come silenzio.
          if (!uploadedAlready) uploaded.set(false)
          post(headers + (JOB_HEADER to jobId), uploaded)
        }
      } catch (cancelled: CancellationException) {
        val headers = sentWith
        if (headers != null) {
          if (lost.get()) {
            // Perso: niente da fermare adesso, e col PC muto la DELETE costerebbe solo attesa. Se
            // era solo la rete, la prossima POST lo ferma.
            abandoned.record(jobId, headers)
          } else if (!withContext(NonCancellable) { cancelRemote(jobId, headers) }) {
            abandoned.record(jobId, headers)
          }
        }
        throw cancelled
      } catch (error: Throwable) {
        // La POST e' caduta (rete, 5xx, un rifiuto): il companion potrebbe avere ancora il lavoro.
        // Un «mi sto riavviando» no: il lavoro non l'ha nemmeno registrato.
        val headers = sentWith
        if (headers != null && restartWait(error) == null) abandoned.record(jobId, headers)
        throw error
      } finally {
        attached.complete(Unit)
        // Aspettato, non solo annullato: una risposta di stato arrivata dopo la fine riscriverebbe
        // «trascrivo 90%» sopra il pezzo gia' finito.
        withContext(NonCancellable) {
          poller.cancelAndJoin()
          sweeper?.join()
        }
      }
    }
  }

  /**
   * `GET /health` con un tempo lungo, per confermare quello che le domande sul lavoro fanno temere
   * (vedi [RemoteJobPoller]). Una risposta d'errore e' pur sempre una risposta: il PC c'e'.
   */
  private suspend fun pulse(): CompanionPulse? = try {
    val bearer = try {
      auth.bearer()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (ignored: Throwable) {
      null
    }
    val body = http.getJson("${base.removeSuffix("/v1")}/health", headers(bearer), readTimeoutMillis = HEALTH_CONFIRM_TIMEOUT_MS)
    CompanionPulse(RemoteJobPoller.instanceOf(body))
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (error: Throwable) {
    if (RemoteJobPoller.httpCode(error) != null) CompanionPulse(null) else null
  }

  /** Le `DELETE` dei lavori lasciati indietro, insieme; chi non risponde torna nell'elenco. */
  private suspend fun sweep(stale: List<AbandonedCompanionJobs.Entry>) = coroutineScope {
    stale.forEach { entry ->
      launch { if (!cancelRemote(entry.jobId, entry.headers)) abandoned.putBack(entry) }
    }
  }

  /**
   * Chiede al companion di fermare il lavoro [jobId]: `DELETE /v1/jobs/<id>`, con le stesse
   * credenziali della POST che l'ha creato. Al meglio e mai piu' di [CANCEL_TIMEOUT_MS]: un
   * annullamento non deve aspettare un PC che non risponde, e un companion che non conosce la rotta
   * (404, 405) o il lavoro gia' finito non sono errori di nessuno. La connessione della POST intanto
   * e' gia' chiusa: un companion che se ne accorge si ferma da se', questo e' il modo sicuro.
   *
   * @return vero se il companion ha risposto, qualunque cosa: falso se non l'ha sentita.
   */
  private suspend fun cancelRemote(jobId: String, headers: Map<String, String>): Boolean = try {
    withTimeoutOrNull(CANCEL_TIMEOUT_MS) {
      try {
        http.delete("$base/jobs/$jobId", headers, timeoutMillis = CANCEL_SOCKET_TIMEOUT_MS)
        true
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        RemoteJobPoller.httpCode(error) != null
      }
    } ?: false
  } catch (ignored: Throwable) {
    false
  }


  /**
   * I due rifiuti che non sono guasti ma indicazioni: il file non e' nell'archivio, o chi chiede e'
   * un ospite. Tradotti qui, dentro [authorized], perche' un 403 non venga preso per un biglietto
   * scaduto e rinnovato per niente.
   */
  private suspend fun <T> companionRefusals(block: suspend () -> T): T = try {
    block()
  } catch (error: Throwable) {
    throw refusal(error) ?: error
  }

  private suspend fun <T> authorized(block: suspend (Map<String, String>) -> T): T = auth.call(
    isUnauthorized = { TranscriptionError.from(it) is TranscriptionError.Unauthorized },
    rejected = { TranscriptionError.Unauthorized(CompanionAuth.ACCOUNT_REJECTED) },
  ) { bearer -> block(headers(bearer)) }

  private fun headers(bearer: String?): Map<String, String> =
    bearer?.takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

  companion object {
    const val ID = "custom"

    /**
     * Quanto aspettare la risposta dopo l'ultimo byte mandato: novanta minuti. Lungo, perche' il PC
     * trascrive un'ora di lezione prima di dire qualcosa; ma finito, perche' con zero una socket
     * restava aperta per sempre verso un computer spento a meta' lavoro.
     */
    const val READ_TIMEOUT_MS = 90 * 60_000

    /** L'intestazione con cui il companion riconosce il lavoro (vedi [RemoteJobPoller]). */
    const val JOB_HEADER = "X-Pampa-Job"

    /** Una domanda sullo stato risponde subito, o non serve: la prossima parte fra un secondo. */
    const val POLL_TIMEOUT_MS = 5_000

    /**
     * Quanto puo' durare la `DELETE` che ferma il lavoro sul computer quando qui si annulla: tre
     * secondi per connettersi e tre per la risposta, e comunque mai piu' di quattro in tutto.
     */
    const val CANCEL_SOCKET_TIMEOUT_MS = 3_000
    const val CANCEL_TIMEOUT_MS = 4_000L

    /**
     * La `/health` che conferma un sospetto (vedi [RemoteJobPoller]): quindici secondi, tre volte
     * la pazienza di una domanda sullo stato — un PC sotto sforzo risponde tardi, ma risponde.
     */
    const val HEALTH_CONFIRM_TIMEOUT_MS = 15_000

    /** Quanto la POST nuova aspetta di agganciarsi prima di fermare quelle lasciate indietro. */
    const val STALE_ATTACH_WAIT_MS = 30_000L

    /** Quante volte per lavoro si aspetta un companion che dice di riavviarsi. */
    const val MAX_RESTART_WAITS = 10
    const val RESTART_WAIT_MIN_SEC = 5.0
    const val RESTART_WAIT_MAX_SEC = 120.0
    const val RESTART_WAIT_DEFAULT_SEC = 10.0

    /**
     * Quanto aspettare un companion che si sta riavviando (`503 {"detail":"restarting"}`), in
     * millisecondi: il suo `Retry-After`, fra 5 e 120 secondi. Null per ogni altro errore — un 503
     * qualunque resta un guasto da riprovare coi tentativi di sempre.
     */
    fun restartWait(error: Throwable): Long? {
      val server = error as? TranscriptionError.Server ?: return null
      if (server.httpCode != 503 || "restarting" !in server.message.orEmpty()) return null
      val seconds = (server.retryAfterSec ?: RESTART_WAIT_DEFAULT_SEC).coerceIn(RESTART_WAIT_MIN_SEC, RESTART_WAIT_MAX_SEC)
      return (seconds * 1000).toLong()
    }

    /** I campi del companion che lavora da se' (vedi [CompanionTranscription]). */
    const val FIELD_SHA = "source_sha256"
    const val FIELD_ARCHIVE = "archive"
    const val FIELD_NAME = "name"
    const val FIELD_MAX_MINUTES = "max_minutes"
    const val MAX_MINUTES_AUTO = "auto"

    /** `features` di `/health`: un elenco di stringhe. Qualunque altra forma vale «niente». */
    fun parseFeatures(health: JsonElement?): Set<String> {
      val list = (health as? JsonObject)?.get("features") as? JsonArray ?: return emptySet()
      return list.mapNotNull { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
      }.toSet()
    }

    /**
     * Da un errore della POST a [TranscriptionError.BlobMissing] o [TranscriptionError.OwnerOnly],
     * se e' uno dei due; null altrimenti. Il companion li dice nel corpo (`{"detail":"blob_missing"}`),
     * che arriva nel messaggio dell'errore.
     */
    fun refusal(error: Throwable): TranscriptionError? {
      if (error is CancellationException) return null
      val text = generateSequence(error) { current -> current.cause?.takeIf { it !== current } }
        .mapNotNull { it.message }
        .joinToString(" ")
      val code = RemoteJobPoller.httpCode(error)
      return when {
        "blob_missing" in text && (code == null || code == 404) -> TranscriptionError.BlobMissing(text)
        "owner_only" in text && (code == null || code == 401 || code == 403) -> TranscriptionError.OwnerOnly(text)
        else -> null
      }
    }

    /**
     * Da quello che si scrive nel campo a un indirizzo che funziona.
     *
     * Chi incolla un indirizzo mette `http://192.168.1.10:8765`, oppure lo stesso con la barra, o
     * gia' con `/v1`. Tutti e tre devono funzionare: chiedere la forma esatta e' chiedere all'utente
     * di indovinare la nostra convenzione.
     */
    fun normalize(raw: String): String {
      val trimmed = raw.trim().trimEnd('/')
      val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
      return if (withScheme.endsWith("/v1")) withScheme else "$withScheme/v1"
    }
  }
}
