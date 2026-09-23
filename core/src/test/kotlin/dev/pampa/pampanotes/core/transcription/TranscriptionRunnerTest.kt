package dev.pampa.pampanotes.core.transcription

import android.content.Context
import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.audio.ChunkDecision
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.files.AppFiles
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Le decisioni del motore che non hanno bisogno di un decoder: quando dividere, e cosa fare dei silenzi. */
class TranscriptionRunnerTest {

  @get:Rule val temp = TemporaryFolder()

  private lateinit var files: AppFiles
  private lateinit var runner: TranscriptionRunner

  @Before
  fun setUp() {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder("files")
    every { context.cacheDir } returns temp.newFolder("cache")
    files = AppFiles(context)
    runner = TranscriptionRunner(files, mockk<ArchiveFetcher>(relaxed = true))
  }

  // --- quando si divide ---

  private val groq = TranscriptionCapabilities(
    maxUploadBytes = 25L * 1024 * 1024,
    supportsSegments = true,
    needsChunking = true,
    acceptedExtensions = GroqWhisperProvider.ACCEPTED_EXTENSIONS,
  )
  private val home = TranscriptionCapabilities(maxUploadBytes = null, supportsSegments = true, needsChunking = false)
  private fun homeWithCap(minutes: Int) = home.copy(needsChunking = true, maxChunkMinutes = minutes)

  private val minute = 60_000L

  private fun decide(caps: TranscriptionCapabilities, name: String, bytes: Long, durationMs: Long, groqMinutes: Int = 10) =
    TranscriptionRunner.chunkDecision(caps, name, bytes, durationMs, groqChunkMinutes = groqMinutes)

  @Test
  fun `un m4a piccolo e corto va a Groq intero`() {
    assertEquals(ChunkDecision.Whole, decide(groq, "p.m4a", 3_000_000, 8 * minute))
    // Due minuti oltre il tetto restano interi, se il peso ci sta.
    assertEquals(ChunkDecision.Whole, decide(groq, "p.m4a", 4_000_000, 12 * minute))
  }

  @Test
  fun `un formato che Groq non prende si ricodifica intero anche se ci starebbe`() {
    assertEquals(ChunkDecision.Split(1, 2 * minute), decide(groq, "p.amr", 300_000, 2 * minute))
    assertEquals(ChunkDecision.Split(1, 2 * minute), decide(groq, "p.3gp", 300_000, 2 * minute))
  }

  @Test
  fun `troppo lungo per Groq si divide in pezzi uguali coi minuti delle impostazioni`() {
    assertEquals(ChunkDecision.Split(12, 5 * minute), decide(groq, "p.m4a", 3_000_000, 60 * minute, groqMinutes = 5))
    // Tredici minuti con un tetto di dieci: due da sei e mezzo, non dieci piu' tre.
    assertEquals(ChunkDecision.Split(2, 390_000), decide(groq, "p.m4a", 5_000_000, 13 * minute))
  }

  @Test
  fun `troppo pesante per Groq si ricodifica anche se la durata ci starebbe`() {
    assertEquals(ChunkDecision.Split(1, 8 * minute), decide(groq, "p.m4a", 30L * 1024 * 1024, 8 * minute))
  }

  @Test
  fun `il computer di casa senza tetto prende tutto intero`() {
    assertEquals(ChunkDecision.Whole, decide(home, "p.amr", 900_000_000, 180 * minute))
  }

  @Test
  fun `il computer di casa con un tetto divide coi suoi minuti, non con quelli di Groq`() {
    assertEquals(ChunkDecision.Split(4, 30 * minute), decide(homeWithCap(30), "p.m4a", 90_000_000, 120 * minute))
    assertEquals(ChunkDecision.Whole, decide(homeWithCap(60), "p.m4a", 90_000_000, 45 * minute))
  }

  @Test
  fun `il computer di casa tiene intero fino a dieci minuti oltre il tetto`() {
    assertEquals(ChunkDecision.Whole, decide(homeWithCap(30), "p.m4a", 40_000_000, 40 * minute))
    assertEquals(ChunkDecision.Split(2, 1_230_000), decide(homeWithCap(30), "p.m4a", 41_000_000, 41 * minute))
  }

  @Test
  fun `i pezzi di un audio decodificato si contano sulla durata vera`() {
    assertEquals(4, TranscriptionRunner.piecesFor(homeWithCap(30), 95 * minute, groqChunkMinutes = 10))
    assertEquals(1, TranscriptionRunner.piecesFor(homeWithCap(30), 38 * minute, groqChunkMinutes = 10))
    assertEquals(6, TranscriptionRunner.piecesFor(groq, 60 * minute, groqChunkMinutes = 10))
  }

  @Test
  fun `il provider del computer di casa dichiara il tetto scelto`() {
    val http = TranscriptionHttp("test")
    assertEquals(false, OpenAiCompatProvider(http, "http://pc:8765").capabilities.needsChunking)
    val capped = OpenAiCompatProvider(http, "http://pc:8765", maxChunkMinutes = 60).capabilities
    assertEquals(true, capped.needsChunking)
    assertEquals(60, capped.maxChunkMinutes)
  }

  // --- silenzi, cancellazioni, limiti ---

  private fun part(id: String, position: Int): AudioPartEntity {
    val name = "$id.m4a"
    File(files.audio, name).writeBytes(ByteArray(16))
    return AudioPartEntity(
      id = id, sessionId = "s", position = position, fileName = name, originalName = name, mime = "audio/mp4",
      sizeBytes = 16, durationMs = 60_000, sha256 = id, createdAt = 0,
    )
  }

  private class FakeProvider(val answer: (File) -> TranscriptResult) : TranscriptionProvider {
    var calls = 0
    override val id = "custom"
    override val capabilities = TranscriptionCapabilities(maxUploadBytes = null, supportsSegments = true, needsChunking = false)
    override suspend fun listModels() = emptyList<String>()
    override suspend fun health() = EndpointHealth(reachable = true, latencyMs = 0)
    override suspend fun transcribe(
      file: File,
      mime: String,
      request: TranscribeRequest,
      onProgress: (UploadProgress) -> Unit,
      onRemote: (RemoteProgress) -> Unit,
    ): TranscriptResult {
      calls++
      return answer(file)
    }
  }

  private fun spoken(text: String) = TranscriptResult(text, listOf(RawSegment(0, 5_000, text)), "it", 60_000)

  @Test
  fun `una parte muta in mezzo alla lezione si salta`() = runBlocking {
    val provider = FakeProvider { file ->
      if (file.name.startsWith("a")) throw TranscriptionError.NoSpeech("niente") else spoken("La ragion pratica viene prima.")
    }

    val result = runner.transcribeSession("job", listOf(part("a", 0), part("b", 1)), provider, TranscribeRequest("m"), chunkMinutes = 10)

    assertEquals("La ragion pratica viene prima.", result.text)
    assertEquals(listOf("b"), result.segments.map { it.partId })
  }

  @Test
  fun `se tutte le parti sono mute la lezione non ha parole, e lo si dice`() {
    val provider = FakeProvider { throw TranscriptionError.NoSpeech("niente") }
    val error = runCatching {
      runBlocking { runner.transcribeSession("job", listOf(part("a", 0), part("b", 1)), provider, TranscribeRequest("m"), chunkMinutes = 10) }
    }.exceptionOrNull()
    assertTrue(error is TranscriptionError.NoSpeech)
  }

  @Test
  fun `una cancellazione esce come cancellazione, non come errore`() {
    val provider = FakeProvider { throw CancellationException("fermato") }
    val error = runCatching {
      runBlocking { runner.transcribeSession("job", listOf(part("a", 0)), provider, TranscribeRequest("m"), chunkMinutes = 10) }
    }.exceptionOrNull()
    assertTrue("era ${error?.javaClass}", error is CancellationException && error !is TranscriptionError)
    assertEquals(1, provider.calls)
  }

  @Test
  fun `un limite che chiede ore non si aspetta, esce subito e lo decide la coda`() {
    val provider = FakeProvider { throw TranscriptionError.RateLimited(retryAfterSec = 3 * 3600.0, message = "domani") }
    val error = runCatching {
      runBlocking { runner.transcribeSession("job", listOf(part("a", 0)), provider, TranscribeRequest("m"), chunkMinutes = 10) }
    }.exceptionOrNull()
    assertTrue(error is TranscriptionError.RateLimited)
    assertEquals(1, provider.calls)
  }

  @Test
  fun `il tetto dell'attesa si riconosce`() {
    assertTrue(TranscriptionRunner.exceedsWaitCap(TranscriptionError.RateLimited(3600.0, "")))
    assertEquals(false, TranscriptionRunner.exceedsWaitCap(TranscriptionError.RateLimited(30.0, "")))
    assertEquals(false, TranscriptionRunner.exceedsWaitCap(TranscriptionError.RateLimited(null, "")))
  }
}
