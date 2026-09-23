package dev.pampa.pampanotes.core.transcription

import android.content.Context
import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.audio.ChunkDecision
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.files.AppFiles
import io.mockk.coVerify
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
  private lateinit var fetcher: ArchiveFetcher
  private lateinit var runner: TranscriptionRunner

  @Before
  fun setUp() {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder("files")
    every { context.cacheDir } returns temp.newFolder("cache")
    files = AppFiles(context)
    fetcher = mockk(relaxed = true)
    runner = TranscriptionRunner(files, fetcher)
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

  // --- il computer di casa che lavora da se' ---

  /** Un companion finto: dice cosa sa fare, e si ricorda come e' stato chiamato. */
  private class FakeCompanion(
    private val features: Set<String>,
    maxChunkMinutes: Int? = null,
    val byRef: (String) -> TranscriptResult = { spokenResult("dall'archivio") },
    val upload: (CompanionUpload) -> TranscriptResult = { spokenResult("caricata") },
    val plain: (File) -> TranscriptResult = { spokenResult("strada di sempre") },
  ) : TranscriptionProvider, CompanionTranscription {
    val byRefCalls = mutableListOf<Pair<String, Int?>>()
    val uploadCalls = mutableListOf<CompanionUpload>()
    val plainCalls = mutableListOf<File>()
    var remoteToSend: List<RemoteProgress> = emptyList()

    override val id = OpenAiCompatProvider.ID
    override val capabilities = TranscriptionCapabilities(
      maxUploadBytes = null, supportsSegments = true, needsChunking = maxChunkMinutes != null, maxChunkMinutes = maxChunkMinutes,
    )
    override suspend fun listModels() = emptyList<String>()
    override suspend fun health() = EndpointHealth(reachable = true, latencyMs = 0, features = features)
    override suspend fun features() = features

    override suspend fun transcribe(
      file: File,
      mime: String,
      request: TranscribeRequest,
      onProgress: (UploadProgress) -> Unit,
      onRemote: (RemoteProgress) -> Unit,
    ): TranscriptResult {
      plainCalls += file
      return plain(file)
    }

    override suspend fun transcribeByRef(
      sha256: String,
      request: TranscribeRequest,
      maxMinutes: Int?,
      onRemote: (RemoteProgress) -> Unit,
    ): TranscriptResult {
      byRefCalls += sha256 to maxMinutes
      remoteToSend.forEach(onRemote)
      return byRef(sha256)
    }

    override suspend fun transcribeUpload(
      file: File,
      mime: String,
      request: TranscribeRequest,
      upload: CompanionUpload,
      onProgress: (UploadProgress) -> Unit,
      onRemote: (RemoteProgress) -> Unit,
    ): TranscriptResult {
      uploadCalls += upload
      onProgress(UploadProgress(16, 16))
      remoteToSend.forEach(onRemote)
      return upload(upload)
    }

    companion object {
      fun spokenResult(text: String, archived: Boolean = false, chunks: Int? = null) =
        TranscriptResult(text, listOf(RawSegment(0, 5_000, text)), "it", 60_000, archived = archived, serverChunks = chunks)
    }
  }

  private val allFeatures = setOf(CompanionFeatures.BY_REF, CompanionFeatures.ARCHIVE_UPLOAD, CompanionFeatures.SERVER_CHUNKS)

  /** Una parte che il computer ha gia'; [local] dice se il file e' anche qui. */
  private fun archivedPart(id: String, position: Int = 0, local: Boolean = false): AudioPartEntity {
    val base = part(id, position)
    if (!local) File(files.audio, base.fileName).delete()
    return base.copy(sha256 = "ABC$id", archivedAt = 1_000, originalName = "Voce $id.m4a")
  }

  @Test
  fun `una parte archiviata si trascrive per impronta, senza scaricarla e senza pezzi sul telefono`() = runBlocking {
    val companion = FakeCompanion(allFeatures, maxChunkMinutes = 30)
    companion.remoteToSend = listOf(
      RemoteProgress(RemoteStage.TRANSCRIBING, 0.5f, chunk = 2, chunks = 2),
      RemoteProgress(RemoteStage.DONE, 1f, chunk = 2, chunks = 2),
    )
    val events = mutableListOf<TranscriptionProgress>()

    val result = runner.transcribeSession("job", listOf(archivedPart("a")), companion, TranscribeRequest("m"), chunkMinutes = 10) {
      events += it
    }

    assertEquals("dall'archivio", result.text)
    assertEquals(listOf("abca" to 30), companion.byRefCalls)
    assertTrue(companion.uploadCalls.isEmpty() && companion.plainCalls.isEmpty())
    coVerify(exactly = 0) { fetcher.fetchPart(any(), any()) }
    // Niente da caricare: la prima cosa che si dice e' che il computer l'ha ricevuta, con la barra a zero.
    val first = events.first() as TranscriptionProgress.Remote
    assertEquals(RemoteStage.RECEIVED, first.remote.stage)
    assertEquals(0f, first.overall!!, 0.001f)
    assertTrue(events.none { it is TranscriptionProgress.Uploading || it is TranscriptionProgress.Preparing })
    // I pezzi li conta il computer, e arrivano nella fase: «pezzo 2 di 2».
    val working = events.filterIsInstance<TranscriptionProgress.Remote>().first { it.remote.stage == RemoteStage.TRANSCRIBING }
    assertEquals(2, working.chunkIndex)
    assertEquals(2, working.chunkCount)
    val phase = JobPhase.Remote(working.remote.stage, 1, 1, 50, chunk = working.chunkIndex, chunks = working.chunkCount)
    assertEquals(phase, JobPhase.parse(phase.encode()))
    // Il secondo pezzo a meta' sta a tre quarti della parte: meta' per il primo, un quarto dentro il secondo.
    val expected = ProgressScale.MAX * (1f + ProgressScale.TRANSCRIBE_SHARE * 0.5f) / 2f
    assertEquals(expected, working.overall!!, 0.001f)
  }

  @Test
  fun `se il computer non ha piu' il file e il file e' qui, si carica e lo si fa tenere`() = runBlocking {
    val companion = FakeCompanion(
      allFeatures,
      maxChunkMinutes = 60,
      byRef = { throw TranscriptionError.BlobMissing("blob_missing") },
    )

    val result = runner.transcribeSession(
      "job", listOf(archivedPart("a", local = true)), companion, TranscribeRequest("m"), chunkMinutes = 10, archiveUploads = true,
    )

    assertEquals("caricata", result.text)
    assertEquals(1, companion.byRefCalls.size)
    assertEquals(listOf(CompanionUpload("abca", "Voce a.m4a", archive = true, maxMinutes = 60)), companion.uploadCalls)
    assertTrue(companion.plainCalls.isEmpty())
  }

  @Test
  fun `se il computer non ha il file e non e' neanche qui, non e' da nessuna parte`() {
    val companion = FakeCompanion(allFeatures, byRef = { throw TranscriptionError.BlobMissing("blob_missing") })
    val error = runCatching {
      runBlocking { runner.transcribeSession("job", listOf(archivedPart("a")), companion, TranscribeRequest("m"), chunkMinutes = 10) }
    }.exceptionOrNull()
    assertTrue("era $error", error is TranscriptionError.Decode)
    assertTrue(companion.uploadCalls.isEmpty())
    coVerify(exactly = 0) { fetcher.fetchPart(any(), any()) }
  }

  @Test
  fun `una parte che il computer tiene si segna archiviata`() = runBlocking {
    val companion = FakeCompanion(allFeatures, upload = { FakeCompanion.spokenResult("tenuta", archived = true) })
    val archived = mutableListOf<String>()

    runner.transcribeSession(
      "job", listOf(part("a", 0)), companion, TranscribeRequest("m"), chunkMinutes = 10,
      archiveUploads = true, onArchived = { id, _ -> archived += id },
    )

    assertEquals(listOf("a"), archived)
    val upload = companion.uploadCalls.single()
    assertEquals("a", upload.sha256)
    assertTrue(upload.archive)
    assertEquals(null, upload.maxMinutes)
    assertTrue(companion.byRefCalls.isEmpty())
  }

  @Test
  fun `con l'archivio spento il computer non tiene niente`() = runBlocking {
    val companion = FakeCompanion(allFeatures)
    runner.transcribeSession("job", listOf(part("a", 0)), companion, TranscribeRequest("m"), chunkMinutes = 10, archiveUploads = false)
    assertEquals(false, companion.uploadCalls.single().archive)
  }

  @Test
  fun `un companion vecchio segue la strada di sempre`() = runBlocking {
    val companion = FakeCompanion(features = emptySet())

    val result = runner.transcribeSession("job", listOf(archivedPart("a", local = true)), companion, TranscribeRequest("m"), chunkMinutes = 10)

    assertEquals("strada di sempre", result.text)
    assertEquals(1, companion.plainCalls.size)
    assertTrue(companion.byRefCalls.isEmpty() && companion.uploadCalls.isEmpty())
  }

  @Test
  fun `un companion vecchio scarica dal computer una parte che qui non c'e'`() = runBlocking {
    val companion = FakeCompanion(features = emptySet())
    runner.transcribeSession("job", listOf(archivedPart("a")), companion, TranscribeRequest("m"), chunkMinutes = 10)
    coVerify(exactly = 1) { fetcher.fetchPart(any(), any()) }
    assertEquals(1, companion.plainCalls.size)
  }

  @Test
  fun `un ospite torna alla strada di sempre, e non ci riprova a ogni parte`() = runBlocking {
    val companion = FakeCompanion(allFeatures, upload = { throw TranscriptionError.OwnerOnly("owner_only") })

    val result = runner.transcribeSession("job", listOf(part("a", 0), part("b", 1)), companion, TranscribeRequest("m"), chunkMinutes = 10)

    assertEquals(1, companion.uploadCalls.size)
    assertEquals(2, companion.plainCalls.size)
    assertEquals(listOf("a", "b"), result.segments.map { it.partId }.distinct())
  }

  @Test
  fun `un ospite con una parte archiviata passa dallo scaricamento di sempre`() = runBlocking {
    val companion = FakeCompanion(allFeatures, byRef = { throw TranscriptionError.OwnerOnly("owner_only") })
    runner.transcribeSession("job", listOf(archivedPart("a")), companion, TranscribeRequest("m"), chunkMinutes = 10)
    coVerify(exactly = 1) { fetcher.fetchPart(any(), any()) }
    assertEquals(1, companion.plainCalls.size)
  }

  @Test
  fun `Groq non passa mai dal computer`() = runBlocking {
    val groqLike = object : TranscriptionProvider by FakeCompanion(allFeatures) {
      override val id = GroqWhisperProvider.ID
    }
    // Il delegato risponde ai metodi, ma l'id e' quello di Groq: la strada e' quella di sempre.
    val result = runner.transcribeSession("job", listOf(part("a", 0)), groqLike, TranscribeRequest("m"), chunkMinutes = 10)
    assertEquals("strada di sempre", result.text)
  }
}
