package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.audio.ChunkDecision
import dev.pampa.pampanotes.core.audio.ChunkEncoder
import dev.pampa.pampanotes.core.audio.ChunkPolicy
import dev.pampa.pampanotes.core.audio.ChunkPlan
import dev.pampa.pampanotes.core.audio.ChunkPlanner
import dev.pampa.pampanotes.core.audio.ChunkSpec
import dev.pampa.pampanotes.core.audio.PcmDecoder
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.files.AppFiles
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A che punto siamo, nelle parole che la notifica e la schermata Lavori mostrano.
 *
 * Gli indici sono da zero per le parti ([partIndex], come `forEachIndexed`) e da uno per i pezzi
 * ([chunkIndex], «pezzo 1 di 3»), come sono sempre stati. [overall] e' la barra dell'intera
 * sessione, gia' pesata ([ProgressScale]); null quando l'evento non la sposta.
 */
sealed interface TranscriptionProgress {
  val overall: Float?

  data class Preparing(
    val partIndex: Int,
    val partCount: Int,
    val fraction: Float,
    override val overall: Float? = null,
  ) : TranscriptionProgress

  data class Uploading(
    val chunkIndex: Int,
    val chunkCount: Int,
    val fraction: Float,
    val partIndex: Int = 0,
    val partCount: Int = 1,
    override val overall: Float? = null,
  ) : TranscriptionProgress

  /** Un pezzo finito: [chunkIndex] e' l'ultimo arrivato. */
  data class Transcribing(
    val chunkIndex: Int,
    val chunkCount: Int,
    val partIndex: Int = 0,
    val partCount: Int = 1,
    override val overall: Float? = null,
  ) : TranscriptionProgress

  /** Il computer di casa racconta cosa sta facendo col pezzo [chunkIndex] della parte [partIndex]. */
  data class Remote(
    val remote: RemoteProgress,
    val chunkIndex: Int,
    val chunkCount: Int,
    val partIndex: Int,
    val partCount: Int,
    override val overall: Float? = null,
  ) : TranscriptionProgress

  data class Waiting(val seconds: Int, val reason: String) : TranscriptionProgress {
    override val overall: Float? get() = null
  }

  data object Stitching : TranscriptionProgress {
    override val overall: Float get() = ProgressScale.MAX
  }
}

/**
 * La barra dell'intera sessione.
 *
 * Le parti pesano per durata: con una registrazione da un'ora e una da cinque minuti, finire la
 * seconda non e' «meta' fatto». Dentro una parte, se va tagliata, un decimo e' la decodifica e il
 * resto si divide fra i pezzi in parti uguali; dentro un pezzo una quota e' il caricamento
 * ([uploadShare]) e il resto il lavoro del servizio, che il computer di casa racconta in due
 * stadi: la trascrizione, che e' la parte lunga, e l'allineamento delle parole.
 *
 * Il massimo e' [MAX] e non 1: l'ultimo tratto e' salvare, e una barra piena con l'app che ancora
 * lavora e' una barra che mente.
 */
class ProgressScale(durationsMs: List<Long>, private val uploadShare: Float) {

  private val weights: List<Float> = run {
    val total = durationsMs.sumOf { it.coerceAtLeast(0) }
    if (durationsMs.isEmpty()) {
      emptyList()
    } else if (total <= 0) {
      // Durate sconosciute (righe arrivate dal sync senza metadati): tutte uguali.
      List(durationsMs.size) { 1f / durationsMs.size }
    } else {
      durationsMs.map { it.coerceAtLeast(0).toFloat() / total }
    }
  }

  /** La sessione intera, da una parte e da quanto di quella parte e' fatto. */
  fun overall(partIndex: Int, withinPart: Float): Float {
    if (weights.isEmpty()) return 0f
    val index = partIndex.coerceIn(0, weights.lastIndex)
    val before = weights.take(index).sum()
    return ((before + weights[index] * withinPart.coerceIn(0f, 1f)) * MAX).coerceIn(0f, MAX)
  }

  /** La decodifica di una parte che va tagliata. */
  fun preparing(fraction: Float): Float = PREPARE_SHARE * fraction.coerceIn(0f, 1f)

  /**
   * Un pezzo a meta': [chunkIndex] da uno, [withinChunk] quanto del pezzo e' fatto.
   * [chunked] dice se la parte e' stata tagliata, e quindi se c'e' la decodifica prima.
   */
  fun chunk(chunkIndex: Int, chunkCount: Int, withinChunk: Float, chunked: Boolean): Float {
    val count = chunkCount.coerceAtLeast(1)
    val done = (chunkIndex - 1).coerceIn(0, count)
    val share = (done + withinChunk.coerceIn(0f, 1f)) / count
    return if (chunked) PREPARE_SHARE + (1f - PREPARE_SHARE) * share else share
  }

  fun uploading(fraction: Float): Float = uploadShare * fraction.coerceIn(0f, 1f)

  /** Il lavoro del servizio, dopo il caricamento. */
  fun remote(progress: RemoteProgress): Float = uploadShare + (1f - uploadShare) * remoteFraction(progress)

  /**
   * Una parte che il computer di casa fa da se': i pezzi, se ce ne sono, li conta lui
   * ([RemoteProgress.chunk]); [byRef] toglie la quota del caricamento, che per un file preso
   * dall'archivio non c'e' — la barra parte dal lavoro del computer.
   */
  fun onComputer(progress: RemoteProgress, byRef: Boolean): Float {
    val upload = if (byRef) 0f else uploadShare
    val count = (progress.chunks ?: 1).coerceAtLeast(1)
    val done = ((progress.chunk ?: 1) - 1).coerceIn(0, count - 1)
    return upload + (1f - upload) * ((done + remoteFraction(progress)) / count)
  }

  companion object {
    const val MAX = 0.98f
    const val PREPARE_SHARE = 0.1f

    /** Groq risponde in secondi: il caricamento e' meta' dell'attesa. */
    const val GROQ_UPLOAD_SHARE = 0.5f

    /** Il computer di casa lavora minuti: il caricamento in casa ne e' una frazione piccola. */
    const val COMPUTER_UPLOAD_SHARE = 0.15f

    /**
     * Quanto del lavoro del computer e' la trascrizione, e quanto l'allineamento. Sulla scheda con
     * large-v3 la trascrizione e' quasi tutto; sul processore con un modello piccolo le due cose si
     * avvicinano (un minuto di lezione: sette secondi contro cinque). Tre quarti sta in mezzo.
     */
    const val TRANSCRIBE_SHARE = 0.75f

    fun remoteFraction(progress: RemoteProgress): Float = when (progress.stage) {
      RemoteStage.RECEIVED, RemoteStage.QUEUED, RemoteStage.DECODING, RemoteStage.LOADING_MODEL, RemoteStage.FAILED -> 0f
      RemoteStage.TRANSCRIBING -> TRANSCRIBE_SHARE * progress.fraction
      RemoteStage.ALIGNING -> TRANSCRIBE_SHARE + (1f - TRANSCRIBE_SHARE) * progress.fraction
      // Le voci vengono dopo tutti i pezzi, sull'audio intero: la parte e' trascritta, e il passo di
      // adesso ha la sua barra. Riportare indietro quella della lezione sembrerebbe un passo indietro.
      RemoteStage.DIARIZING, RemoteStage.DONE -> 1f
    }
  }
}

/** Una parte trascritta, con i suoi segmenti gia' collocati nel tempo della parte. */
data class PartTranscript(
  val part: AudioPartEntity,
  val text: String,
  val segments: List<StitchedSegment>,
  val language: String?,
) {
  /** Niente parole: una parte muta, o tutti i suoi pezzi muti. */
  val isEmpty: Boolean get() = segments.isEmpty() && text.isBlank()
}

/** Il risultato di una sessione intera: le parti in fila, con i tempi assoluti gia' fatti. */
data class SessionTranscript(
  val text: String,
  val segments: List<SessionSegment>,
  val language: String?,
  val model: String,
  val provider: String,
)

data class SessionSegment(
  val partId: String,
  val indexInPart: Int,
  val partStartMs: Long,
  val partEndMs: Long,
  val sessionStartMs: Long,
  val sessionEndMs: Long,
  val text: String,
  val noSpeechProb: Float?,
  val avgLogProb: Float?,
  /**
   * Le parole, con i tempi **relativi all'inizio del segmento dentro la parte**.
   *
   * Relativi perche' e' quello che non cambia mai: riordinare le parti sposta i tempi di sessione,
   * e una parola salvata con il tempo assoluto andrebbe riscritta a ogni riordino. Vedi
   * [WordTimings.encode].
   */
  val wordsEncoded: String? = null,
  /** Vero quando le parole sono una stima e non un allineamento. La schermata lo dice. */
  val wordsEstimated: Boolean = false,
  /** La voce che lo dice, quando il computer le ha separate («chi parla»). */
  val speaker: String? = null,
)

/** Il risultato di un pezzo, salvato su disco appena arriva. */
@Serializable
private data class StoredChunk(
  val index: Int,
  val startMs: Long,
  val endMs: Long,
  val segments: List<StoredSegment>,
)

@Serializable
private data class StoredSegment(
  val startMs: Long,
  val endMs: Long,
  val text: String,
  val noSpeechProb: Float? = null,
  val avgLogProb: Float? = null,
  /** Con il default vuoto, un lavoro a meta' della versione precedente si rilegge ancora. */
  val words: List<StoredWord> = emptyList(),
  val speaker: String? = null,
)

@Serializable
private data class StoredWord(val startMs: Long, val endMs: Long, val text: String)

/**
 * Da un elenco di file audio a una trascrizione sola.
 *
 * Il pezzo di codice che tiene insieme tutto il resto. Fa, in ordine: guarda se il file ci sta
 * intero, altrimenti lo decodifica e lo taglia nei silenzi; manda ogni pezzo; ricuce; incolla le
 * parti una dopo l'altra con i tempi assoluti.
 *
 * **Riprende da dove era rimasto.** Il risultato di ogni pezzo finisce su disco appena arriva, in
 * `filesDir/jobs/<id>/`. Una lezione da un'ora sono sei richieste e qualche minuto: se il sistema
 * ferma il processo a meta', ricominciare da capo vuol dire rifare richieste gia' pagate e far
 * aspettare di nuovo. Al riavvio i pezzi gia' fatti si rileggono dal disco e si saltano.
 */
@Singleton
class TranscriptionRunner @Inject constructor(
  private val files: AppFiles,
  private val fetcher: dev.pampa.pampanotes.core.archive.ArchiveFetcher,
) {

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  /**
   * @param jobId la cartella di lavoro, e la chiave con cui si riprende.
   * @param archiveUploads col computer di casa che lavora da se': una registrazione caricata per
   *   trascriverla resta anche nel suo archivio (l'archivio acceso nelle impostazioni). Cosi' non
   *   sale due volte, una per la trascrizione e una per l'archivio.
   * @param onArchived il computer ha tenuto la parte: chi chiama la segna archiviata.
   * @param onProgress chiamato spesso: la UI ci disegna sopra una barra.
   */
  suspend fun transcribeSession(
    jobId: String,
    parts: List<AudioPartEntity>,
    provider: TranscriptionProvider,
    request: TranscribeRequest,
    chunkMinutes: Int,
    archiveUploads: Boolean = false,
    onArchived: suspend (partId: String, at: Long) -> Unit = { _, _ -> },
    onProgress: (TranscriptionProgress) -> Unit = {},
  ): SessionTranscript {
    require(parts.isNotEmpty()) { "una sessione senza parti non si trascrive" }
    val workDir = files.jobDir(jobId)
    val transcripts = mutableListOf<PartTranscript>()
    val sorted = parts.sortedBy { it.position }
    val scale = ProgressScale(
      durationsMs = sorted.map { it.durationMs },
      uploadShare = if (provider.id == GroqWhisperProvider.ID) ProgressScale.GROQ_UPLOAD_SHARE else ProgressScale.COMPUTER_UPLOAD_SHARE,
    )
    // Una domanda per lavoro: il computer sa lavorare da se'? Se si', il telefono non decodifica e
    // non taglia niente, e una registrazione che il computer ha gia' non la scarica ne' la rimanda.
    val computer = computerMode(provider, archiveUploads)
    // «Chi parla» si chiede solo a un computer che dichiara di saperlo fare, e solo sulla strada in
    // cui la parte va intera: sulla strada di sempre (Groq, un companion vecchio, un ospite) il campo
    // non parte, e i pezzi tagliati qui avrebbero comunque voci che non si riconoscono fra loro.
    val plain = request.copy(diarize = false)
    val onComputer = request.copy(diarize = request.diarize && computer?.diarize == true)

    sorted.forEachIndexed { index, part ->
      currentCoroutineContext().ensureActive()
      val partDir = File(workDir, "part-${part.id}").apply { mkdirs() }
      computer?.let { mode ->
        transcribeOnComputer(part, index, parts.size, partDir, mode, onComputer, scale, onArchived, onProgress)
          ?.let {
            transcripts += it
            return@forEachIndexed
          }
      }
      transcripts += transcribePart(
        part = part,
        partIndex = index,
        partCount = parts.size,
        workDir = partDir,
        provider = provider,
        request = plain,
        chunkMinutes = chunkMinutes,
        scale = scale,
        onProgress = onProgress,
      )
    }

    // Una parte muta in mezzo a una lezione e' una registrazione partita per sbaglio, non una
    // lezione fallita: si salta. Muta tutta, invece, non c'e' niente da salvare, e lo si dice.
    if (transcripts.all { it.isEmpty }) throw TranscriptionError.NoSpeech("il servizio non ha riconosciuto parole")

    onProgress(TranscriptionProgress.Stitching)
    return SessionAssembler.assemble(transcripts, provider.id, request.model)
  }

  /** Il computer di casa che lavora da se', per un lavoro: con cosa, e fino a quando. */
  private class ComputerMode(
    val companion: CompanionTranscription,
    /** Il tetto dei pezzi, che il computer applica da se' (`customMaxMinutes`). */
    val maxMinutes: Int?,
    /** Tenere nell'archivio del computer quello che si carica. */
    val archive: Boolean,
    /** Il computer sa separare le voci ([CompanionFeatures.DIARIZE]). */
    val diarize: Boolean = false,
  ) {
    /**
     * Il computer ha detto che chi chiede e' un ospite: da li' in poi, per questo lavoro, la strada
     * di sempre. Chiederlo a ogni parte vorrebbe dire caricare ogni registrazione due volte.
     */
    @Volatile var ownerOnly = false
  }

  /**
   * Null quando il computer non sa lavorare da se' — un companion vecchio, un altro server
   * compatibile OpenAI, Groq — e allora si fa tutto come prima. Con un tetto ai pezzi serve anche
   * che il computer sappia dividere: se non lo sa, il tetto lo applica il telefono, come prima.
   */
  private suspend fun computerMode(provider: TranscriptionProvider, archiveUploads: Boolean): ComputerMode? {
    if (provider.id != OpenAiCompatProvider.ID) return null
    val companion = provider as? CompanionTranscription ?: return null
    val features = companion.features()
    if (CompanionFeatures.BY_REF !in features) return null
    val maxMinutes = provider.capabilities.maxChunkMinutes
    if (maxMinutes != null && CompanionFeatures.SERVER_CHUNKS !in features) return null
    return ComputerMode(
      companion, maxMinutes, archiveUploads && CompanionFeatures.ARCHIVE_UPLOAD in features,
      diarize = CompanionFeatures.DIARIZE in features,
    )
  }

  /**
   * Una parte fatta dal computer di casa da se': il telefono non decodifica e non taglia niente.
   *
   * 1. Se il computer ce l'ha gia' (`archivedAt > 0`) gli si dice quale, per impronta, e non si
   *    scarica niente: prima il telefono la prendeva dal PC per rimandargliela.
   * 2. Se non ce l'ha (o dice di non averla piu') e il file e' qui, si carica, e il computer la
   *    tiene nel suo archivio se l'archivio e' acceso: non salira' una seconda volta.
   * 3. Se non e' ne' qui ne' sul computer, non c'e' niente da trascrivere, e lo si dice.
   *
   * @return null quando la parte va per la strada di sempre: chi chiede e' un ospite, oppure non c'e'
   *   un'impronta con cui chiederla, oppure il file non e' qui e il computer non l'ha mai avuto —
   *   e allora l'errore lo da' la strada di sempre, con le sue parole.
   */
  private suspend fun transcribeOnComputer(
    part: AudioPartEntity,
    partIndex: Int,
    partCount: Int,
    workDir: File,
    mode: ComputerMode,
    request: TranscribeRequest,
    scale: ProgressScale,
    onArchived: suspend (partId: String, at: Long) -> Unit,
    onProgress: (TranscriptionProgress) -> Unit,
  ): PartTranscript? {
    if (mode.ownerOnly) return null
    val spec = ChunkSpec(0, 0, part.durationMs)
    val stored = computerFile(workDir)

    fun finished(chunks: Int) = onProgress(
      TranscriptionProgress.Transcribing(chunks, chunks, partIndex, partCount, scale.overall(partIndex, 1f)),
    )

    fun transcript(chunk: ChunkTranscript, language: String?): PartTranscript {
      // Un pezzo solo, ma le stesse difese della strada a pezzi: il computer di casa sente gli
      // stessi silenzi e ci inventa le stesse cose.
      val stitched = TranscriptStitcher.stitch(listOf(chunk), request.prompt)
      return PartTranscript(part, stitched.text, stitched.segments, language)
    }

    // Gia' fatta in un giro precedente: si rilegge e si va avanti.
    readStored(stored)?.let {
      finished(1)
      return transcript(it, request.language)
    }

    // Il computer racconta i suoi pezzi da se': sono quelli che la frase deve dire.
    fun remote(byRef: Boolean): (RemoteProgress) -> Unit = { remote ->
      onProgress(
        TranscriptionProgress.Remote(
          remote, remote.chunk ?: 1, remote.chunks ?: 1, partIndex, partCount,
          scale.overall(partIndex, scale.onComputer(remote, byRef)),
        ),
      )
    }
    val uploading: (UploadProgress) -> Unit = { progress ->
      onProgress(
        TranscriptionProgress.Uploading(
          1, 1, progress.fraction, partIndex, partCount,
          scale.overall(partIndex, scale.uploading(progress.fraction)),
        ),
      )
    }

    val source = files.audioFile(part.fileName)
    val sha = part.sha256.trim().lowercase().takeIf { it.isNotEmpty() }
    val result = try {
      var answer: TranscriptResult? = null
      if (part.archivedAt > 0 && sha != null) {
        // Niente da caricare: la barra parte dal lavoro del computer, e la fase lo dice subito.
        onProgress(
          TranscriptionProgress.Remote(RemoteProgress(RemoteStage.RECEIVED), 1, 1, partIndex, partCount, scale.overall(partIndex, 0f)),
        )
        answer = try {
          withRetry(waitingReporter(onProgress)) {
            mode.companion.transcribeByRef(sha, request, mode.maxMinutes, remote(byRef = true))
          }
        } catch (missing: TranscriptionError.BlobMissing) {
          // La riga dice archiviata, il computer dice di no (un archivio rifatto, un altro PC): se il
          // file e' qui si carica; se non e' neanche qui, non e' da nessuna parte.
          if (!source.exists()) {
            throw TranscriptionError.Decode("«${part.originalName}» non e' su questo dispositivo, e il computer di casa non ce l'ha piu'")
          }
          null
        }
      }
      answer ?: run {
        if (!source.exists()) return null
        withRetry(waitingReporter(onProgress)) {
          mode.companion.transcribeUpload(
            file = source,
            mime = part.mime,
            request = request,
            upload = CompanionUpload(sha, part.originalName, mode.archive && sha != null, mode.maxMinutes),
            onProgress = uploading,
            onRemote = remote(byRef = false),
          )
        }
      }
    } catch (guest: TranscriptionError.OwnerOnly) {
      mode.ownerOnly = true
      return null
    } catch (silent: TranscriptionError.NoSpeech) {
      // Come sulla strada di sempre: una parte muta e' una parte vuota, non una lezione fallita.
      writeStored(stored, ChunkTranscript(spec, emptyList()))
      finished(1)
      return PartTranscript(part, "", emptyList(), null)
    }

    // Il computer l'ha tenuta: e' archiviata adesso, e l'archivio non la rimandera'. Un errore qui
    // non tocca la trascrizione, che e' gia' arrivata: al peggio l'archivio la manda un'altra volta,
    // e il computer risponde che ce l'ha gia'.
    if (result.archived && part.archivedAt <= 0) {
      try {
        onArchived(part.id, System.currentTimeMillis())
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (ignored: Exception) {
        Unit
      }
    }

    val chunk = ChunkTranscript(spec, result.segments)
    writeStored(stored, chunk)
    finished(result.serverChunks ?: 1)
    // Un server che risponde col solo testo, senza segmenti: mezzo risultato vale piu' di niente.
    // Solo senza segmenti, pero': se c'erano e le difese li hanno tolti tutti, il testo del server
    // e' quello stesso «Grazie.» inventato, e rimetterlo vorrebbe dire rimettere l'allucinazione.
    return transcript(chunk, result.language).let {
      if (it.text.isBlank() && result.segments.isEmpty()) it.copy(text = result.text.trim()) else it
    }
  }

  private suspend fun transcribePart(
    part: AudioPartEntity,
    partIndex: Int,
    partCount: Int,
    workDir: File,
    provider: TranscriptionProvider,
    request: TranscribeRequest,
    chunkMinutes: Int,
    scale: ProgressScale,
    onProgress: (TranscriptionProgress) -> Unit,
  ): PartTranscript {
    val source = files.audioFile(part.fileName)
    if (!source.exists()) {
      // Registrata su un altro dispositivo: se il computer di casa ce l'ha, si prende da li' e la
      // coda va avanti da sola. Altrimenti e' rimasta dov'e' nata, e qui non c'e' niente da trascrivere.
      if (part.archivedAt <= 0) throw TranscriptionError.Decode("«${part.originalName}» non e' su questo dispositivo")
      onProgress(TranscriptionProgress.Preparing(partIndex, partCount, 0f, scale.overall(partIndex, 0f)))
      runCatching { fetcher.fetchPart(part) }.getOrElse {
        if (it is kotlinx.coroutines.CancellationException) throw it
        throw TranscriptionError.Decode("«${part.originalName}» sta sul computer di casa e non sono riuscito a prenderlo: ${it.message}")
      }
    }

    val decision = chunkDecision(provider.capabilities, source.name, source.length(), part.durationMs, chunkMinutes)
    val chunked = decision != ChunkDecision.Whole

    // Gli eventi di un pezzo, con la barra della sessione gia' fatta: il pezzo sa solo quanto di se'
    // e' fatto, la scala sa quanto pesa lui dentro la parte e la parte dentro la sessione.
    fun overall(chunkIndex: Int, chunkCount: Int, withinChunk: Float) =
      scale.overall(partIndex, scale.chunk(chunkIndex, chunkCount, withinChunk, chunked))

    fun uploading(chunkIndex: Int, chunkCount: Int): (UploadProgress) -> Unit = { progress ->
      onProgress(
        TranscriptionProgress.Uploading(
          chunkIndex, chunkCount, progress.fraction, partIndex, partCount,
          overall(chunkIndex, chunkCount, scale.uploading(progress.fraction)),
        ),
      )
    }

    fun remote(chunkIndex: Int, chunkCount: Int): (RemoteProgress) -> Unit = { remote ->
      onProgress(
        TranscriptionProgress.Remote(
          remote, chunkIndex, chunkCount, partIndex, partCount,
          overall(chunkIndex, chunkCount, scale.remote(remote)),
        ),
      )
    }

    fun finished(chunkIndex: Int, chunkCount: Int) = onProgress(
      TranscriptionProgress.Transcribing(chunkIndex, chunkCount, partIndex, partCount, overall(chunkIndex, chunkCount, 1f)),
    )

    // La via breve: il file ci sta intero. Niente decodifica, niente ricodifica, niente cuciture —
    // ed e' anche l'unica che conserva la qualita' originale dell'audio.
    if (!chunked) {
      val result = try {
        sendWithRetry(provider, source, part.mime, request, waitingReporter(onProgress), remote(1, 1), uploading(1, 1))
      } catch (silent: TranscriptionError.NoSpeech) {
        return PartTranscript(part, "", emptyList(), null)
      }
      finished(1, 1)
      val stitched = TranscriptStitcher.stitch(
        listOf(ChunkTranscript(ChunkSpec(0, 0, part.durationMs), result.segments)),
        request.prompt,
      )
      // Un server che risponde col solo testo, senza segmenti: mezzo risultato vale piu' di niente.
      // Con segmenti tutti tolti dalle difese, invece, il testo del server e' l'allucinazione stessa.
      val text = if (stitched.text.isBlank() && result.segments.isEmpty()) result.text.trim() else stitched.text
      return PartTranscript(part, text, stitched.segments, result.language)
    }

    val pcm = File(workDir, "audio.pcm")
    // I pezzi si ricontano sulla durata vera, quella del PCM decodificato: la durata della riga puo'
    // mancare (0) o essere quella stimata dal contenitore, e un conto sbagliato qui e' un pezzo che
    // sfora il limite di Groq.
    val plan = preparePlan(source, pcm, workDir, { totalMs -> piecesFor(provider.capabilities, totalMs, chunkMinutes) }) { fraction ->
      onProgress(
        TranscriptionProgress.Preparing(partIndex, partCount, fraction, scale.overall(partIndex, scale.preparing(fraction))),
      )
    }

    val chunkTranscripts = mutableListOf<ChunkTranscript>()
    val chunkCount = plan.chunks.size
    plan.chunks.forEach { spec ->
      currentCoroutineContext().ensureActive()
      val chunkIndex = spec.index + 1

      // Gia' fatto in un giro precedente: si rilegge e si va avanti.
      readStoredChunk(workDir, spec)?.let {
        chunkTranscripts += it
        finished(chunkIndex, chunkCount)
        return@forEach
      }

      val encoded = ChunkEncoder.encode(pcm, PcmDecoder.TARGET_SAMPLE_RATE, spec, workDir)
      try {
        val segments = try {
          sendWithRetry(
            provider, encoded.file, encoded.mime, request, waitingReporter(onProgress),
            remote(chunkIndex, chunkCount), uploading(chunkIndex, chunkCount),
          ).segments
        } catch (silent: TranscriptionError.NoSpeech) {
          // Dieci minuti di intervallo, o la classe che esce: un pezzo muto e' un pezzo vuoto, non
          // una lezione fallita. Si salva vuoto, cosi' una ripresa non lo rimanda.
          emptyList()
        }
        finished(chunkIndex, chunkCount)
        val chunk = ChunkTranscript(spec, segments)
        // Prima su disco, poi in memoria: un processo ucciso fra le due cose deve poter ripartire
        // da qui, non dal pezzo precedente.
        writeStoredChunk(workDir, chunk)
        chunkTranscripts += chunk
      } finally {
        encoded.file.delete()
      }
    }

    // Il PCM non serve piu': su un'ora sono centoquindici megabyte che non hanno motivo di restare.
    pcm.delete()

    val stitched = TranscriptStitcher.stitch(chunkTranscripts, request.prompt)
    return PartTranscript(part, stitched.text, stitched.segments, request.language)
  }

  /** Decodifica e pianifica, oppure rilegge il piano di un tentativo precedente. */
  private fun preparePlan(
    source: File,
    pcm: File,
    workDir: File,
    piecesFor: (totalMs: Long) -> Int,
    onProgress: (Float) -> Unit,
  ): ChunkPlan {
    val planFile = File(workDir, "plan.json")
    if (pcm.exists() && pcm.length() > 0 && planFile.exists()) {
      runCatching { json.decodeFromString<List<StoredChunk>>(planFile.readText()) }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?.let { stored ->
          onProgress(1f)
          return ChunkPlan(
            chunks = stored.map { ChunkSpec(it.index, it.startMs, it.endMs) },
            overlapMs = ChunkPlanner.DEFAULT_OVERLAP_MS,
            totalDurationMs = stored.last().endMs,
          )
        }
    }

    val decoded = PcmDecoder.decodeToPcm(source, pcm, onProgress)
    val totalMs = decoded.frameEnergies.size * decoded.frameMs
    val plan = ChunkPlanner.planEqual(
      frameEnergies = decoded.frameEnergies,
      frameMs = decoded.frameMs,
      pieces = piecesFor(totalMs),
    )
    planFile.writeText(
      json.encodeToString(plan.chunks.map { StoredChunk(it.index, it.startMs, it.endMs, emptyList()) }),
    )
    return plan
  }

  /**
   * Manda un pezzo, e riprova quando ha senso.
   *
   * Il limite di richieste si aspetta per il tempo che il server chiede; un guasto passeggero si
   * riprova con attese che raddoppiano. Una chiave sbagliata o un file troppo grande non si
   * riprovano: insistere brucia quota e ritarda il momento in cui l'utente legge cosa non va.
   */
  private suspend fun sendWithRetry(
    provider: TranscriptionProvider,
    file: File,
    mime: String,
    request: TranscribeRequest,
    onWaiting: (seconds: Int) -> Unit,
    onRemote: (RemoteProgress) -> Unit,
    onProgress: (UploadProgress) -> Unit,
  ): TranscriptResult = withRetry(onWaiting) { provider.transcribe(file, mime, request, onProgress, onRemote) }

  private suspend fun withRetry(onWaiting: (seconds: Int) -> Unit, send: suspend () -> TranscriptResult): TranscriptResult {
    var attempt = 0
    while (true) {
      currentCoroutineContext().ensureActive()
      try {
        return send()
      } catch (error: Throwable) {
        // Una cancellazione resta una cancellazione: tradotta in un errore, il worker la scambiava
        // per un guasto e segnava fallito un lavoro che l'utente — o il sistema — aveva fermato.
        if (error is kotlinx.coroutines.CancellationException) throw error
        val mapped = TranscriptionError.from(error)
        attempt++
        if (!mapped.retryable || attempt > MAX_ATTEMPTS) throw mapped
        // Un limite che chiede ore non si aspetta qui dentro, con il worker in primo piano e la
        // notifica accesa: esce, e la coda rimette il lavoro in fila per quell'ora.
        if (mapped is TranscriptionError.RateLimited && exceedsWaitCap(mapped)) throw mapped
        val wait = backoffMillis(mapped, attempt)
        if (mapped is TranscriptionError.RateLimited) onWaiting(((wait + 999) / 1000).toInt())
        delay(wait)
      }
    }
  }

  private fun waitingReporter(onProgress: (TranscriptionProgress) -> Unit): (Int) -> Unit = { seconds ->
    onProgress(TranscriptionProgress.Waiting(seconds, "rate_limit"))
  }

  /** Quanto aspettare prima del tentativo numero [attempt]. */
  fun backoffMillis(error: TranscriptionError, attempt: Int): Long {
    if (error is TranscriptionError.RateLimited) {
      val asked = error.retryAfterSec?.times(1000)?.toLong()
      // Quando il server dice quanto aspettare si obbedisce, ma con un tetto: certi limiti
      // giornalieri rispondono "riprova fra sei ore", e un lavoro che dorme sei ore e' un lavoro
      // che l'utente crede rotto.
      if (asked != null) return asked.coerceIn(1_000L, MAX_RATE_LIMIT_WAIT_MS)
    }
    return (BASE_BACKOFF_MS shl (attempt - 1)).coerceAtMost(MAX_BACKOFF_MS)
  }

  private fun chunkFile(workDir: File, index: Int) = File(workDir, "chunk-$index.json")

  /**
   * Il risultato di una parte fatta dal computer da se'. Un nome suo e non `chunk-0.json`: un
   * tentativo precedente sulla strada di sempre puo' aver lasciato li' il primo pezzo, che non e'
   * la parte intera.
   */
  private fun computerFile(workDir: File) = File(workDir, "computer.json")

  private fun readStoredChunk(workDir: File, spec: ChunkSpec): ChunkTranscript? =
    readStored(chunkFile(workDir, spec.index))

  private fun readStored(file: File): ChunkTranscript? {
    if (!file.exists()) return null
    return runCatching {
      val stored = json.decodeFromString<StoredChunk>(file.readText())
      ChunkTranscript(
        spec = ChunkSpec(stored.index, stored.startMs, stored.endMs),
        segments = stored.segments.map { segment ->
          RawSegment(
            startMs = segment.startMs,
            endMs = segment.endMs,
            text = segment.text,
            noSpeechProb = segment.noSpeechProb,
            avgLogProb = segment.avgLogProb,
            words = segment.words.map { RawWord(it.startMs, it.endMs, it.text) },
            speaker = segment.speaker,
          )
        },
      )
    }.getOrNull()
  }

  private fun writeStoredChunk(workDir: File, chunk: ChunkTranscript) = writeStored(chunkFile(workDir, chunk.spec.index), chunk)

  private fun writeStored(file: File, chunk: ChunkTranscript) {
    val stored = StoredChunk(
      index = chunk.spec.index,
      startMs = chunk.spec.startMs,
      endMs = chunk.spec.endMs,
      segments = chunk.segments.map { segment ->
        StoredSegment(
          startMs = segment.startMs,
          endMs = segment.endMs,
          text = segment.text,
          noSpeechProb = segment.noSpeechProb,
          avgLogProb = segment.avgLogProb,
          words = segment.words.map { StoredWord(it.startMs, it.endMs, it.text) },
          speaker = segment.speaker,
        )
      },
    )
    runCatching { file.writeText(json.encodeToString(stored)) }
  }

  /** Butta tutto quello che il lavoro aveva lasciato in giro: si chiama quando finisce, in bene o in male. */
  fun cleanUp(jobId: String) {
    runCatching { files.jobDir(jobId).deleteRecursively() }
  }

  companion object {
    const val MAX_ATTEMPTS = 5
    const val BASE_BACKOFF_MS = 2_000L
    const val MAX_BACKOFF_MS = 32_000L

    /** Un'attesa piu' lunga di questa non si fa qui: il lavoro torna in coda per l'ora giusta. */
    const val MAX_RATE_LIMIT_WAIT_MS = 90_000L

    /** Il limite chiede di aspettare piu' di quanto si aspetti dentro un lavoro. */
    fun exceedsWaitCap(error: TranscriptionError.RateLimited): Boolean =
      (error.retryAfterSec ?: 0.0) * 1000 > MAX_RATE_LIMIT_WAIT_MS

    /**
     * Intero, o in quanti pezzi uguali: la regola sta in [ChunkPolicy], qui si sceglie coi numeri
     * di quale servizio.
     *
     * Intero sempre quando il servizio non vuole pezzi (il computer di casa senza tetto). Il tetto
     * lo dice il servizio se ne ha uno suo ([TranscriptionCapabilities.maxChunkMinutes], il computer
     * di casa con un tetto: tolleranza di dieci minuti, perche' li' sforare costa solo tempo),
     * altrimenti le impostazioni di Groq ([groqChunkMinutes], tolleranza di due e il limite di byte).
     */
    fun chunkDecision(
      capabilities: TranscriptionCapabilities,
      fileName: String,
      sizeBytes: Long,
      durationMs: Long,
      groqChunkMinutes: Int,
    ): ChunkDecision {
      if (!capabilities.needsChunking) return ChunkDecision.Whole
      val computer = capabilities.maxChunkMinutes != null
      val capMs = (capabilities.maxChunkMinutes ?: groqChunkMinutes).coerceAtLeast(1) * 60_000L
      return ChunkPolicy.decide(
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        capMs = capMs,
        toleranceMs = if (computer) ChunkPolicy.COMPUTER_TOLERANCE_MS else ChunkPolicy.GROQ_TOLERANCE_MS,
        maxUploadBytes = capabilities.maxUploadBytes,
        acceptedAsIs = capabilities.acceptsAsIs(fileName),
      )
    }

    /**
     * In quanti pezzi va un audio gia' decodificato, lungo [totalMs]. E' sempre una ricodifica: il
     * peso che conta e' quello dei pezzi ricodificati, che [ChunkPolicy] stima dalla durata.
     */
    fun piecesFor(capabilities: TranscriptionCapabilities, totalMs: Long, groqChunkMinutes: Int): Int {
      val computer = capabilities.maxChunkMinutes != null
      val capMs = (capabilities.maxChunkMinutes ?: groqChunkMinutes).coerceAtLeast(1) * 60_000L
      val decision = ChunkPolicy.decide(
        durationMs = totalMs,
        sizeBytes = 0,
        capMs = capMs,
        toleranceMs = if (computer) ChunkPolicy.COMPUTER_TOLERANCE_MS else ChunkPolicy.GROQ_TOLERANCE_MS,
        maxUploadBytes = capabilities.maxUploadBytes,
        acceptedAsIs = false,
      )
      return (decision as? ChunkDecision.Split)?.pieces ?: 1
    }
  }
}
