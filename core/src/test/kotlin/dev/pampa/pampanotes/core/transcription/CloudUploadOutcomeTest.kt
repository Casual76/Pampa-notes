package dev.pampa.pampanotes.core.transcription

import android.content.Context
import dev.pampa.pampanotes.core.alignment.AlignmentProtocol
import dev.pampa.pampanotes.core.audio.*
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.files.AppFiles
import io.mockk.*
import java.io.*
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic bytes and in-memory HTTP only. Codecs are replaced; no sockets or audio processing. */
class CloudUploadOutcomeTest {
  @get:Rule val temp = TemporaryFolder()
  private val providers = System.getProperty("outcome.provider")?.let { listOf(it) } ?: listOf("groq", "openrouter", "mistral")
  private val shapes = System.getProperty("outcome.chunked")?.let { listOf(it.toBooleanStrict()) } ?: listOf(false, true)
  private val authCodes = System.getProperty("outcome.status")?.let { listOf(it.toInt()) } ?: listOf(401, 402)
  private val paidStates = System.getProperty("outcome.paidFirst")?.let { listOf(it.toBooleanStrict()) } ?: listOf(false, true)

  private inner class Fixture(val id: String, val chunked: Boolean = false, val paidFirst: Boolean = false) {
    val root = temp.newFolder()
    val files: AppFiles
    val parts: List<AudioPartEntity>
    val quota = File(root, "quota.json")
    var key = "fixture-key"
    var account = "fixture-account"
    var now = System.currentTimeMillis()
    var status = 200
    var rejectAt = if (paidFirst) 2 else 1
    var metadataStatus = 200
    var beforeCode: (Int) -> Unit = {}
    var beforeOutput: () -> Unit = {}
    var outputFailure: Throwable? = null
    var bodyFailure: Throwable? = null
    var headersFailure = false
    var posts = 0
    var gets = 0
    val model = when (id) { "mistral" -> MistralTranscriptionProvider.DEFAULT_MODEL; "openrouter" -> "openai/whisper-1"; else -> "whisper-large-v3-turbo" }
    val request get() = TranscribeRequest(model)
    val url = when (id) { "mistral" -> MistralTranscriptionProvider.BASE_URL; "openrouter" -> OpenRouterTranscriptionProvider.BASE_URL; else -> GroqWhisperProvider.BASE_URL }
    init {
      val context = mockk<Context>()
      every { context.filesDir } returns File(root, "files")
      every { context.cacheDir } returns File(root, "cache")
      files = AppFiles(context)
      parts = (if (!chunked && paidFirst) listOf("a", "b") else listOf("a")).mapIndexed { i, name ->
        val file = files.audioFile("$name.wav").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        AudioPartEntity(name, "session", i, file.name, file.name, "audio/wav", 3,
          if (chunked) 1_200_000 else 1000, AlignmentProtocol.sha256(file), 1)
      }
    }
    fun http() = TranscriptionHttp("offline-outcome", cloudGate = CloudRequestGate(DurableCloudCooldowns(quota),
      { CloudRetry.fingerprint(account) }, { CloudRetry.fingerprint(key) }, { now }), connectionFactory = { target ->
      object : HttpURLConnection(target) {
        private var response: Int? = null
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream {
          beforeOutput()
          outputFailure?.let { throw it }
          return ByteArrayOutputStream()
        }
        override fun getResponseCode(): Int {
          response?.let { return it }
          val code = if (requestMethod == "POST") { posts++; if (posts == rejectAt) status else 200 }
            else { gets++; metadataStatus }
          response = code
          beforeCode(code)
          return code
        }
        override fun getHeaderFields(): Map<String?, List<String>> {
          if (headersFailure) throw IOException("fixture headers lost")
          return mapOf("Retry-After" to listOf("172800.125"))
        }
        override fun getInputStream(): InputStream {
          bodyFailure?.let { throw it }
          return ByteArrayInputStream("""{"text":"Paid text.","language":"it","segments":[{"id":0,"start":0,"end":1,"text":"Paid text."}]}""".toByteArray())
        }
        override fun getErrorStream(): InputStream {
          bodyFailure?.let { throw it }
          return ByteArrayInputStream("{}".toByteArray())
        }
      }
    })
    fun provider(http: TranscriptionHttp = http(), credential: String = key): TranscriptionProvider = when (id) {
      "groq" -> GroqWhisperProvider(http, credential)
      "openrouter" -> OpenRouterTranscriptionProvider(http, credential)
      else -> MistralTranscriptionProvider(http, credential)
    }
    suspend fun run(job: String = "job", storage: TranscriptionWorkStorage = TranscriptionWorkStorage(), provider: TranscriptionProvider = provider()) =
      TranscriptionRunner(files, mockk(), storage).transcribeSession(job, parts, provider, request, 10)
    fun deadline() = DurableCloudCooldowns(quota).deadline(id, CloudRetry.fingerprint(key), CloudRetry.fingerprint(account))!!
    fun result(first: Boolean = false) = files.jobs.resolve("job/part-${if (!chunked && paidFirst && !first) "b" else "a"}/${if (chunked) "chunk-${if (paidFirst && !first) 1 else 0}.json" else "whole.json"}")
    fun marker() = File(result().parentFile, result().name + ".sending")
    fun paidBytes() = if (paidFirst) result(true).readBytes() else null
    fun assertPaid(bytes: ByteArray?) { if (bytes != null) assertArrayEquals(bytes, result(true).readBytes()) }
  }

  private suspend fun withCodecs(chunked: Boolean, block: suspend () -> Unit) {
    if (!chunked) { block(); return }
    mockkObject(PcmDecoder, ChunkEncoder)
    try {
      every { PcmDecoder.decodeToPcm(any(), any(), any()) } answers {
        val pcm = secondArg<File>().also { it.writeBytes(byteArrayOf(4, 5)) }
        DecodedAudio(pcm, 16000, FloatArray(1200), 1000, 1_200_000)
      }
      every { ChunkEncoder.encode(any(), any(), any(), any(), any()) } answers {
        val spec = thirdArg<ChunkSpec>()
        EncodedChunk(spec, File(arg<File>(3), "encoded-${spec.index}.fixture").also { it.writeBytes(byteArrayOf(6)) }, "audio/fixture")
      }
      block()
    } finally { unmockkObject(PcmDecoder, ChunkEncoder) }
  }

  @Test fun `definitive auth and credit errors allow explicit same job retry retaining paid cache`() = runBlocking {
    for (id in providers) for (code in authCodes) for (chunked in shapes) for (paid in paidStates) withCodecs(chunked) {
      val f = Fixture(id, chunked, paid).also { it.status = code }
      val error = runCatching { f.run() }.exceptionOrNull()
      assertTrue("$id/$code/$chunked/$paid: $error", if (code == 401) error is TranscriptionError.Unauthorized else error is TranscriptionError.CreditRequired)
      val bytes = f.paidBytes(); val before = f.posts
      f.status = 200
      if (code == 401) f.key = "corrected-fixture-key"
      val resumed = f.run()
      assertTrue(resumed.text.contains("Paid text."))
      assertEquals(before + if (chunked && !paid) 2 else 1, f.posts)
      f.assertPaid(bytes)
      val after = f.posts; f.run(); assertEquals(after, f.posts)
      println("AUTH_RECOVERY $id status=$code chunks=$chunked paidFirst=$paid PASS")
    }
  }

  @Test fun `system cancellation at observed 429 resumes only after actual durable deadline`() = runBlocking {
    for (id in providers) for (chunked in shapes) for (paid in paidStates) withCodecs(chunked) {
      val f = Fixture(id, chunked, paid).also { it.status = 429 }
      var first: Throwable? = null
      val task = launch {
        val own = currentCoroutineContext()[Job]!!
        f.beforeCode = { if (it == 429) own.cancel(CancellationException("fixture system stop at observed status")) }
        first = runCatching { f.run() }.exceptionOrNull()
      }
      task.join()
      assertTrue(task.isCancelled); assertTrue(first is CancellationException)
      val at = f.deadline()
      assertTrue(at >= f.now + 172_800_000)
      val bytes = f.paidBytes(); val before = f.posts
      f.beforeCode = {}; f.status = 200
      val waiting = runCatching { f.run() }.exceptionOrNull()
      assertEquals(before, f.posts)
      // Another job and reopened gate share the entire wait; there is no request loop.
      val otherWaiting = runCatching { f.run("other-job") }.exceptionOrNull()
      assertEquals(before, f.posts)
      f.now = at - 1
      val beforeDeadline = runCatching { f.run() }.exceptionOrNull()
      assertEquals(before, f.posts)
      f.now = at + 1
      f.run(); f.assertPaid(bytes)
      assertTrue(waiting is TranscriptionError.RateLimited)
      assertTrue(otherWaiting is TranscriptionError.RateLimited)
      assertTrue(beforeDeadline is TranscriptionError.RateLimited)
      assertEquals(before + if (chunked && !paid) 2 else 1, f.posts)
      val after = f.posts; f.run(); assertEquals(after, f.posts)
      println("CANCEL_429_RECOVERY $id chunks=$chunked paidFirst=$paid deadline=$at PASS")
    }
  }

  @Test fun `auth or credit observation survives cancellation and interrupted error body`() = runBlocking {
    for (code in listOf(401, 402)) for (cancel in listOf(false, true)) {
      val f = Fixture("mistral").also { it.status = code; it.bodyFailure = IOException("fixture error body interrupted") }
      var failure: Throwable? = null
      val task = launch {
        val own = currentCoroutineContext()[Job]!!
        if (cancel) f.beforeCode = { own.cancel() }
        failure = runCatching { f.run() }.exceptionOrNull()
      }
      task.join()
      if (cancel) assertTrue(failure is CancellationException)
      else assertTrue(if (code == 401) failure is TranscriptionError.Unauthorized else failure is TranscriptionError.CreditRequired)
      f.status = 200; f.beforeCode = {}; f.bodyFailure = null
      f.run(); assertEquals(2, f.posts)
    }
  }

  @Test fun `early rejection observed after output failure persists even on system cancellation`() = runBlocking {
    for (code in listOf(401, 402, 429)) for (cancel in listOf(false, true)) {
      val f = Fixture("mistral").also { it.status = code; it.outputFailure = IOException("fixture output interrupted by early response") }
      var failure: Throwable? = null
      val task = launch {
        val own = currentCoroutineContext()[Job]!!
        if (cancel) f.beforeCode = { own.cancel() }
        failure = runCatching { f.run() }.exceptionOrNull()
      }
      task.join()
      if (cancel) assertTrue(failure is CancellationException)
      else assertEquals(when (code) { 401 -> "unauthorized"; 402 -> "credit_required"; else -> "rate_limited" }, (failure as TranscriptionError).code)
      f.outputFailure = null; f.beforeCode = {}; f.status = 200
      if (code == 429) f.now = f.deadline() + 1
      f.run(); assertEquals(2, f.posts)
    }
  }

  @Test fun `real system cancellation before any response keeps the dispatched intent uncertain across identity changes`() = runBlocking {
    val f = Fixture("groq")
    val task = launch {
      val own = currentCoroutineContext()[Job]!!
      f.beforeOutput = { own.cancel(); throw IOException("fixture disconnected before status") }
      runCatching { f.run() }
    }
    task.join(); assertTrue(task.isCancelled); assertEquals(0, f.posts)
    val bytes = f.marker().readBytes()
    f.beforeOutput = {}; f.key = "replacement-key"; f.account = "replacement-account"
    assertTrue(runCatching { f.run() }.exceptionOrNull() is IOException)
    assertEquals(0, f.posts); assertArrayEquals(bytes, f.marker().readBytes())
  }

  @Test fun `unknown network timeout cancellation and interrupted success never authorize rebilling`() = runBlocking {
    for (kind in listOf("network", "timeout", "cancel-before-status", "cancel-200", "body-200")) {
      val f = Fixture("mistral", paidFirst = true)
      // Complete A, then fail B at the chosen boundary.
      f.beforeCode = { if (f.posts == 2) when (kind) {
        "network" -> throw IOException("fixture lost status")
        "timeout" -> throw SocketTimeoutException("fixture uncertain timeout")
        "cancel-before-status", "cancel-200" -> throw CancellationException("fixture uncertain stop")
        else -> f.bodyFailure = IOException("fixture 200 body interrupted")
      } }
      assertNotNull(runCatching { f.run() }.exceptionOrNull())
      val bytes = f.paidBytes(); val marker = f.marker().readBytes(); val before = f.posts
      f.beforeCode = {}; f.bodyFailure = null; f.headersFailure = false; f.status = 200
      assertTrue(runCatching { f.run() }.exceptionOrNull() is IOException)
      assertEquals(before, f.posts); f.assertPaid(bytes); assertArrayEquals(marker, f.marker().readBytes())
    }
    // Actual Job cancellation, after an observed 2xx, also leaves the intent uncertain.
    val f = Fixture("groq")
    val task = launch { val own = currentCoroutineContext()[Job]!!; f.beforeCode = { own.cancel() }; runCatching { f.run() } }
    task.join(); assertTrue(task.isCancelled); f.beforeCode = {}
    assertTrue(runCatching { f.run() }.exceptionOrNull() is IOException); assertEquals(1, f.posts)
  }

  @Test fun `rejection persistence or quota persistence failure refuses another paid request`() = runBlocking {
    for (code in listOf(401, 402, 429)) for (partial in listOf(false, true)) {
      val f = Fixture("mistral", paidFirst = true).also { it.status = code }
      val failing = object : TranscriptionWorkStorage() {
        override fun write(file: File, text: String) {
          if (text.startsWith("rejected:")) {
            if (partial) File(file.parentFile, file.name + ".pending").writeText(text.take(5))
            throw IOException("fixture disposition disk failure")
          }
          super.write(file, text)
        }
      }
      assertNotNull(runCatching { f.run(storage = failing) }.exceptionOrNull())
      val bytes = f.paidBytes(); f.status = 200
      if (code == 429) f.now = f.deadline() + 1
      assertTrue(runCatching { f.run() }.exceptionOrNull() is IOException)
      assertEquals(2, f.posts); f.assertPaid(bytes)
    }
    val f = Fixture("groq").also { it.status = 429 }
    f.beforeCode = { if (it == 429) f.quota.writeText("corrupt-at-observation") }
    assertNotNull(runCatching { f.run() }.exceptionOrNull())
    f.beforeCode = {}; f.quota.delete(); f.status = 200
    assertTrue(runCatching { f.run() }.exceptionOrNull() is IOException); assertEquals(1, f.posts)
  }

  @Test fun `metadata and unrelated cooldown cannot release an earlier uncertain audio intent`() = runBlocking {
    val f = Fixture("openrouter")
    f.bodyFailure = IOException("fixture lost successful body")
    assertNotNull(runCatching { f.run() }.exceptionOrNull())
    val marker = f.marker().readBytes()
    f.bodyFailure = null; f.metadataStatus = 429
    assertTrue(runCatching { f.http().getJson("${f.url}/key", mapOf("Authorization" to "Bearer ${f.key}")) }.exceptionOrNull() is TranscriptionError.RateLimited)
    f.now = f.deadline() + 1
    assertTrue(runCatching { f.run() }.exceptionOrNull() is IOException)
    assertEquals(1, f.posts); assertArrayEquals(marker, f.marker().readBytes())
    // Even metadata invoked inside the same provider coroutine cannot settle its earlier POST.
    val g = Fixture("mistral")
    val direct = g.provider(); val http = g.http()
    val wrapper = object : TranscriptionProvider by direct {
      override suspend fun transcribe(file: File, mime: String, request: TranscribeRequest, onProgress: (UploadProgress) -> Unit, onRemote: (RemoteProgress) -> Unit): TranscriptResult {
        g.bodyFailure = IOException("uncertain POST")
        runCatching { direct.transcribe(file, mime, request, onProgress, onRemote) }
        g.bodyFailure = null; g.metadataStatus = 429
        http.getJson("${g.url}/models", mapOf("Authorization" to "Bearer ${g.key}"))
        error("unreachable")
      }
    }
    assertTrue(runCatching { g.run(provider = wrapper) }.exceptionOrNull() is TranscriptionError.RateLimited)
    g.now = g.deadline() + 1
    assertTrue(runCatching { g.run() }.exceptionOrNull() is IOException); assertEquals(1, g.posts)
  }

  @Test fun `known guard metadata and identity refusals before upload remain explicitly retryable`() = runBlocking {
    for (kind in listOf("metadata", "privacy", "account", "key")) {
      val f = Fixture("mistral")
      val direct = f.provider()
      val guarded = CloudTranscriptionGuard(direct, beforeUpload = {
        when (kind) {
          "metadata" -> { f.metadataStatus = 429; f.http().getJson("${f.url}/models", mapOf("Authorization" to "Bearer ${f.key}")) }
          "privacy" -> throw TranscriptionError.CompanionOnly()
          else -> throw TranscriptionError.CloudControl("cloud_${kind}_changed")
        }
        it
      }, customOnly = { false })
      assertNotNull(runCatching { f.run(provider = guarded) }.exceptionOrNull()); assertEquals(0, f.posts)
      if (kind == "metadata") f.now = f.deadline() + 1
      f.run(); assertEquals(1, f.posts)
    }
    // Real gate detects a key changed before upload; correcting it allows this same job.
    val f = Fixture("groq")
    assertTrue(runCatching { f.run(provider = f.provider(credential = "old-key")) }.exceptionOrNull() is TranscriptionError.CloudControl)
    assertEquals(0, f.posts); f.run(); assertEquals(1, f.posts)
  }

  @Test fun `cancelled observed metadata refusal settles only the new intent with no audio dispatch`() = runBlocking {
    for (code in listOf(401, 402, 429)) {
      val f = Fixture("openrouter").also { it.metadataStatus = code }
      val direct = f.provider()
      val guarded = CloudTranscriptionGuard(direct, beforeUpload = {
        f.http().getJson("${f.url}/key", mapOf("Authorization" to "Bearer ${f.key}")); it
      }, customOnly = { false })
      val task = launch {
        val own = currentCoroutineContext()[Job]!!
        f.beforeCode = { own.cancel() }
        runCatching { f.run(provider = guarded) }
      }
      task.join(); assertTrue(task.isCancelled); assertEquals(0, f.posts)
      f.beforeCode = {}; f.metadataStatus = 200
      if (code == 429) {
        assertTrue(runCatching { f.run() }.exceptionOrNull() is TranscriptionError.RateLimited)
        assertEquals(0, f.posts); f.now = f.deadline() + 1
      }
      f.run(); assertEquals(1, f.posts)
    }
  }
}
