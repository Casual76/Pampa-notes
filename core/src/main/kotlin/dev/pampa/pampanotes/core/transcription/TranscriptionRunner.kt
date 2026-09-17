package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.audio.ChunkEncoder
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

/** A che punto siamo, nelle parole che la notifica e la schermata Lavori mostrano. */
sealed interface TranscriptionProgress {
  data class Preparing(val partIndex: Int, val partCount: Int, val fraction: Float) : TranscriptionProgress
  data class Uploading(val chunkIndex: Int, val chunkCount: Int, val fraction: Float) : TranscriptionProgress
  data class Transcribing(val chunkIndex: Int, val chunkCount: Int) : TranscriptionProgress
  data class Waiting(val seconds: Int, val reason: String) : TranscriptionProgress
  data object Stitching : TranscriptionProgress
}

/** Una parte trascritta, con i suoi segmenti gia' collocati nel tempo della parte. */
data class PartTranscript(
  val part: AudioPartEntity,
  val text: String,
  val segments: List<StitchedSegment>,
  val language: String?,
)

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
)

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
) {

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  /**
   * @param jobId la cartella di lavoro, e la chiave con cui si riprende.
   * @param onProgress chiamato spesso: la UI ci disegna sopra una barra.
   */
  suspend fun transcribeSession(
    jobId: String,
    parts: List<AudioPartEntity>,
    provider: TranscriptionProvider,
    request: TranscribeRequest,
    chunkMinutes: Int,
    onProgress: (TranscriptionProgress) -> Unit = {},
  ): SessionTranscript {
    require(parts.isNotEmpty()) { "una sessione senza parti non si trascrive" }
    val workDir = files.jobDir(jobId)
    val transcripts = mutableListOf<PartTranscript>()

    parts.sortedBy { it.position }.forEachIndexed { index, part ->
      currentCoroutineContext().ensureActive()
      val partDir = File(workDir, "part-${part.id}").apply { mkdirs() }
      transcripts += transcribePart(
        part = part,
        partIndex = index,
        partCount = parts.size,
        workDir = partDir,
        provider = provider,
        request = request,
        chunkMinutes = chunkMinutes,
        onProgress = onProgress,
      )
    }

    onProgress(TranscriptionProgress.Stitching)
    return SessionAssembler.assemble(transcripts, provider.id, request.model)
  }

  private suspend fun transcribePart(
    part: AudioPartEntity,
    partIndex: Int,
    partCount: Int,
    workDir: File,
    provider: TranscriptionProvider,
    request: TranscribeRequest,
    chunkMinutes: Int,
    onProgress: (TranscriptionProgress) -> Unit,
  ): PartTranscript {
    val source = files.audioFile(part.fileName)
    if (!source.exists()) throw TranscriptionError.Decode("il file ${part.originalName} non c'e' piu'")

    val limit = provider.capabilities.maxUploadBytes
    val targetMs = chunkMinutes * 60_000L
    val fitsWhole = !provider.capabilities.needsChunking ||
      (limit != null && source.length() <= limit && part.durationMs <= targetMs)

    // La via breve: il file ci sta intero. Niente decodifica, niente ricodifica, niente cuciture —
    // ed e' anche l'unica che conserva la qualita' originale dell'audio.
    if (fitsWhole) {
      val result = sendWithRetry(provider, source, part.mime, request) { progress ->
        onProgress(TranscriptionProgress.Uploading(0, 1, progress.fraction))
      }
      onProgress(TranscriptionProgress.Transcribing(0, 1))
      val stitched = TranscriptStitcher.stitch(
        listOf(ChunkTranscript(ChunkSpec(0, 0, part.durationMs), result.segments)),
      )
      return PartTranscript(part, stitched.text, stitched.segments, result.language)
    }

    val pcm = File(workDir, "audio.pcm")
    val plan = preparePlan(source, pcm, workDir, targetMs) { fraction ->
      onProgress(TranscriptionProgress.Preparing(partIndex, partCount, fraction))
    }

    val chunkTranscripts = mutableListOf<ChunkTranscript>()
    plan.chunks.forEach { spec ->
      currentCoroutineContext().ensureActive()

      // Gia' fatto in un giro precedente: si rilegge e si va avanti.
      readStoredChunk(workDir, spec)?.let {
        chunkTranscripts += it
        onProgress(TranscriptionProgress.Transcribing(spec.index + 1, plan.chunks.size))
        return@forEach
      }

      val encoded = ChunkEncoder.encode(pcm, PcmDecoder.TARGET_SAMPLE_RATE, spec, workDir)
      try {
        val result = sendWithRetry(provider, encoded.file, encoded.mime, request) { progress ->
          onProgress(TranscriptionProgress.Uploading(spec.index + 1, plan.chunks.size, progress.fraction))
        }
        onProgress(TranscriptionProgress.Transcribing(spec.index + 1, plan.chunks.size))
        val chunk = ChunkTranscript(spec, result.segments)
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

    val stitched = TranscriptStitcher.stitch(chunkTranscripts)
    return PartTranscript(part, stitched.text, stitched.segments, request.language)
  }

  /** Decodifica e pianifica, oppure rilegge il piano di un tentativo precedente. */
  private fun preparePlan(
    source: File,
    pcm: File,
    workDir: File,
    targetMs: Long,
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
    val plan = ChunkPlanner.plan(
      frameEnergies = decoded.frameEnergies,
      frameMs = decoded.frameMs,
      targetMs = targetMs,
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
    onProgress: (UploadProgress) -> Unit,
  ): TranscriptResult {
    var attempt = 0
    while (true) {
      currentCoroutineContext().ensureActive()
      try {
        return provider.transcribe(file, mime, request, onProgress)
      } catch (error: Throwable) {
        val mapped = TranscriptionError.from(error)
        if (mapped is TranscriptionError.Cancelled) throw mapped
        attempt++
        if (!mapped.retryable || attempt > MAX_ATTEMPTS) throw mapped
        delay(backoffMillis(mapped, attempt))
      }
    }
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

  private fun readStoredChunk(workDir: File, spec: ChunkSpec): ChunkTranscript? {
    val file = chunkFile(workDir, spec.index)
    if (!file.exists()) return null
    return runCatching {
      val stored = json.decodeFromString<StoredChunk>(file.readText())
      ChunkTranscript(
        spec = ChunkSpec(stored.index, stored.startMs, stored.endMs),
        segments = stored.segments.map {
          RawSegment(it.startMs, it.endMs, it.text, it.noSpeechProb, it.avgLogProb)
        },
      )
    }.getOrNull()
  }

  private fun writeStoredChunk(workDir: File, chunk: ChunkTranscript) {
    val stored = StoredChunk(
      index = chunk.spec.index,
      startMs = chunk.spec.startMs,
      endMs = chunk.spec.endMs,
      segments = chunk.segments.map {
        StoredSegment(it.startMs, it.endMs, it.text, it.noSpeechProb, it.avgLogProb)
      },
    )
    runCatching { chunkFile(workDir, chunk.spec.index).writeText(json.encodeToString(stored)) }
  }

  /** Butta tutto quello che il lavoro aveva lasciato in giro: si chiama quando finisce, in bene o in male. */
  fun cleanUp(jobId: String) {
    runCatching { files.jobDir(jobId).deleteRecursively() }
  }

  companion object {
    const val MAX_ATTEMPTS = 5
    const val BASE_BACKOFF_MS = 2_000L
    const val MAX_BACKOFF_MS = 32_000L

    /** Un'attesa piu' lunga di questa la si mostra come errore, non come pausa. */
    const val MAX_RATE_LIMIT_WAIT_MS = 90_000L
  }
}
