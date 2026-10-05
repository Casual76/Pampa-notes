package dev.pampa.pampanotes.core.repo

import dev.pampa.pampanotes.core.audio.PcmDecoder
import dev.pampa.pampanotes.core.db.NoteDao
import dev.pampa.pampanotes.core.db.SegmentDao
import dev.pampa.pampanotes.core.db.SessionDao
import dev.pampa.pampanotes.core.db.TranscriptDao
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.stats.Loudness
import dev.pampa.pampanotes.core.stats.LoudnessAccumulator
import dev.pampa.pampanotes.core.stats.NoteLoudness
import dev.pampa.pampanotes.core.stats.PartLoudness
import dev.pampa.pampanotes.core.stats.PlacedLoudness
import dev.pampa.pampanotes.core.stats.Spoken
import dev.pampa.pampanotes.core.stats.SpokenStats
import dev.pampa.pampanotes.core.transcription.SessionAssembler
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A che punto e' l'ascolto: la parte [part] di [parts], e quanto di lei. */
data class LoudnessProgress(val part: Int, val parts: Int, val fraction: Float)

/** Il volume di una nota, e quante registrazioni non si sono potute ascoltare perche' non sono qui. */
data class NoteLoudnessResult(val loudness: NoteLoudness?, val missingParts: Int, val failedParts: Int)

/**
 * Le statistiche di una nota (il pannello «Statistiche»): le parole, subito, dal database; il volume
 * ascoltando i file, che costa — un'ora di audio si decodifica in mezzo minuto — e quindi si fa solo
 * quando qualcuno apre il pannello e si ricorda per impronta del file in `filesDir/stats/`.
 *
 * Per impronta e non per parte: lo stesso file ha lo stesso volume, anche spostato in un'altra
 * sessione o rientrato con un `.sdocx` piu' nuovo. Le misure non viaggiano col sync ne' coi backup:
 * sono un gioco, e rifarle costa solo tempo.
 */
@Singleton
class NoteStatsRepository @Inject constructor(
  private val notes: NoteDao,
  private val sessions: SessionDao,
  private val transcripts: TranscriptDao,
  private val segments: SegmentDao,
  private val files: AppFiles,
  private val json: Json,
) {
  private val cacheLock = Mutex()
  private val cacheFile: File get() = File(files.root, "stats/loudness.json")
  private val cacheSerializer = MapSerializer(String.serializer(), PartLoudness.serializer())

  /** Le parole: quelle dette (le grezze) e quelle scritte (gli appunti). Null se la nota non c'e'. */
  suspend fun spoken(noteId: String): Spoken? = withContext(Dispatchers.IO) {
    val note = notes.get(noteId) ?: return@withContext null
    val texts = mutableListOf<String>()
    var speechMs = 0L
    sessions.byNote(noteId).forEach { session ->
      val raw = RawTranscripts.newest(transcripts.bySession(session.session.id)) ?: return@forEach
      texts += raw.text
      speechMs += segments.byTranscriptWithoutWords(raw.id).sumOf { (it.partEndMs - it.partStartMs).coerceAtLeast(0L) }
    }
    SpokenStats.of(texts, speechMs, note.body)
  }

  /**
   * Il volume della nota: misura le registrazioni che sono qui e non sono ancora state misurate,
   * una alla volta, e mette insieme. Quelle che stanno solo sul computer si contano a parte, senza
   * scaricarle: per un numero che fa ridere non si tirano giu' gigabyte.
   */
  suspend fun loudness(noteId: String, onProgress: (LoudnessProgress) -> Unit = {}): NoteLoudnessResult = withContext(Dispatchers.IO) {
    val list = sessions.byNote(noteId)
    val cache = readCache().toMutableMap()
    val placed = mutableListOf<PlacedLoudness>()
    var missing = 0
    var failed = 0
    val all = list.flatMap { s -> s.partsSorted.map { s to it } }
    val toMeasure = all.filter { (_, part) -> cache[part.sha256]?.version != Loudness.VERSION && files.audioFile(part.fileName).exists() }
    var measured = 0
    list.forEach { session ->
      val offsets = SessionAssembler.offsets(session.partsSorted.map { SessionAssembler.Part(it.id, it.durationMs) })
      session.partsSorted.forEach { part ->
        val known = cache[part.sha256]?.takeIf { it.version == Loudness.VERSION }
        val result = known ?: run {
          val file = files.audioFile(part.fileName)
          if (!file.exists()) {
            missing++
            return@forEach
          }
          val index = ++measured
          coroutineContext.ensureActive()
          val measuredPart = runCatching { measure(file) { onProgress(LoudnessProgress(index, toMeasure.size, it)) } }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()
          if (measuredPart == null) {
            failed++
            return@forEach
          }
          cache[part.sha256] = measuredPart
          // Una parte alla volta: chi chiude il pannello a meta' non perde quelle gia' ascoltate.
          writeCache(cache)
          measuredPart
        }
        placed += PlacedLoudness(session.session.id, offsets[part.id] ?: 0L, result)
      }
    }
    NoteLoudnessResult(Loudness.aggregate(placed), missingParts = missing, failedParts = failed)
  }

  private suspend fun measure(file: File, onProgress: (Float) -> Unit): PartLoudness {
    val accumulator = LoudnessAccumulator()
    val context = coroutineContext
    PcmDecoder.stream(file, onProgress) { mono, rate ->
      // Chiudere il pannello ferma la decodifica al blocco dopo, non alla fine di un'ora.
      context.ensureActive()
      accumulator.add(mono, rate)
    }
    return accumulator.finish()
  }

  private suspend fun readCache(): Map<String, PartLoudness> = cacheLock.withLock {
    runCatching { json.decodeFromString(cacheSerializer, cacheFile.readText()) }.getOrDefault(emptyMap())
  }

  private suspend fun writeCache(cache: Map<String, PartLoudness>) = cacheLock.withLock {
    runCatching {
      val target = cacheFile
      target.parentFile?.mkdirs()
      // Tutto o niente: un file scritto a meta' si leggerebbe come vuoto, e si rifarebbe tutto.
      val tmp = File(target.parentFile, "${target.name}.tmp")
      tmp.writeText(json.encodeToString(cacheSerializer, cache))
      if (!tmp.renameTo(target)) {
        target.delete()
        tmp.renameTo(target)
      }
    }
  }
}
