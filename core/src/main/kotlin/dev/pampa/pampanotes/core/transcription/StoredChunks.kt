package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.audio.ChunkSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * I pezzi gia' trascritti messi da parte in `filesDir/jobs/<id>/`, e quando si possono rileggere.
 *
 * Prima un `chunk-N.json` si rileggeva per posizione e basta: il pezzo 0 era il pezzo 0, qualunque
 * piano l'avesse prodotto. Ma il piano cambia — un tentativo con Groq a pezzi da dieci minuti, poi
 * «Riprova» sul computer di casa con un tetto di trenta, stesso id e stessa cartella — e il vecchio
 * 0–10 min riempiva il posto del nuovo 0–30: il pezzo 1 cominciava a 30, e venti minuti di lezione
 * sparivano senza un errore. Adesso ogni pezzo porta dove comincia e dove finisce e con quale piano
 * e' nato ([signature]); se non combacia con quello che si sta per chiedere, non vale niente.
 */
object StoredChunks {

  /** Il file in cima alla cartella del lavoro che dice con quale piano sono nati i pezzi li' dentro. */
  const val WORK_FILE = "work.json"

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  /**
   * Il piano di un lavoro, in una riga: il servizio e il tetto dei pezzi (o il peso massimo). Cambia
   * uno dei due e i pezzi di prima non sono piu' pezzi di questo lavoro. Non c'entra se il computer
   * lavora da se' o no: quella strada scrive un file suo per parte (`computer.json`), che vale per la
   * parte intera qualunque sia il tetto.
   */
  fun signature(providerId: String, capabilities: TranscriptionCapabilities, groqChunkMinutes: Int): String {
    val cap = if (capabilities.needsChunking) (capabilities.maxChunkMinutes ?: groqChunkMinutes).toString() else "whole"
    return "$providerId|$cap|${capabilities.maxUploadBytes ?: 0}"
  }

  fun encode(chunk: ChunkTranscript, signature: String): String = json.encodeToString(
    StoredChunk(
      index = chunk.spec.index,
      startMs = chunk.spec.startMs,
      endMs = chunk.spec.endMs,
      signature = signature,
      fallbackText = chunk.fallbackText,
      sourceText = chunk.sourceText,
      language = chunk.language,
      audioSha256 = chunk.audioSha256,
      reportedLanguageKnown = chunk.reportedLanguageKnown,
      sourceProvider = chunk.sourceProvider,
      sourceModel = chunk.sourceModel,
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
    ),
  )

  /**
   * Il pezzo salvato, se e' proprio quello che [spec] chiede: stesso posto, stesso inizio, stessa
   * fine, stesso piano. Un file senza piano viene da una versione di prima, che non lo scriveva: non
   * si sa da dove venga: il runner lo conserva e rifiuta il riuso, senza pagare una nuova richiesta.
   */
  fun decode(text: String, spec: ChunkSpec, signature: String): ChunkTranscript? {
    val stored = runCatching { json.decodeFromString<StoredChunk>(text) }.getOrNull() ?: return null
    if (stored.signature != signature) return null
    if (stored.index != spec.index || stored.startMs != spec.startMs || stored.endMs != spec.endMs) return null
    return ChunkTranscript(
      spec = spec,
      fallbackText = stored.fallbackText,
      sourceText = stored.sourceText,
      language = stored.language,
      audioSha256 = stored.audioSha256,
      reportedLanguageKnown = stored.reportedLanguageKnown,
      sourceProvider = stored.sourceProvider,
      sourceModel = stored.sourceModel,
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
  }

  /** Il piano dei tagli (`plan.json`): solo dove cominciano e finiscono i pezzi. */
  fun encodePlan(chunks: List<ChunkSpec>): String =
    json.encodeToString(chunks.map { StoredChunk(it.index, it.startMs, it.endMs, segments = emptyList()) })

  fun decodePlan(text: String): List<ChunkSpec>? =
    runCatching { json.decodeFromString<List<StoredChunk>>(text) }.getOrNull()
      ?.takeIf { it.isNotEmpty() }
      ?.takeIf { chunks -> chunks.all { it.index >= 0 && it.startMs >= 0 && it.endMs > it.startMs } &&
        chunks.map { it.index }.distinct().size == chunks.size &&
        chunks.zipWithNext().all { (a, b) -> a.index < b.index && a.startMs < b.startMs && a.endMs < b.endMs } }
      ?.map { ChunkSpec(it.index, it.startMs, it.endMs) }

  @Serializable
  private data class StoredChunk(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val segments: List<StoredSegment>,
    /** Con quale piano e' nato il pezzo ([signature]). Null nei file delle versioni di prima. */
    val signature: String? = null,
    val fallbackText: String = "",
    val sourceText: String? = null,
    val language: String? = null,
    val audioSha256: String? = null,
    val reportedLanguageKnown: Boolean = false,
    val sourceProvider: String? = null,
    val sourceModel: String? = null,
  )

  @Serializable
  private data class StoredSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val noSpeechProb: Float? = null,
    val avgLogProb: Float? = null,
    val words: List<StoredWord> = emptyList(),
    val speaker: String? = null,
  )

  @Serializable
  private data class StoredWord(val startMs: Long, val endMs: Long, val text: String)
}
