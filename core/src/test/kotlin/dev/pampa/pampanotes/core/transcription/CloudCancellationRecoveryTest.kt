package dev.pampa.pampanotes.core.transcription

import android.content.Context
import dev.pampa.pampanotes.core.alignment.AlignmentProtocol
import dev.pampa.pampanotes.core.audio.ChunkSpec
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.repo.FailedJobs
import dev.pampa.pampanotes.core.repo.FailureStanding
import io.mockk.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Production runner/storage/guard/recovery reader; deterministic offline responses, no decoder. */
class CloudCancellationRecoveryTest {
  @get:Rule val temp = TemporaryFolder()
  private val exact = "  Ricevuta A,\tintatta!\r\n "
  private val request = TranscribeRequest("pinned-model")
  private fun files(): AppFiles {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder()
    every { context.cacheDir } returns temp.newFolder()
    return AppFiles(context)
  }
  private fun part(files: AppFiles, id: String, position: Int): AudioPartEntity {
    val audio = files.audioFile("$id.wav").also { it.writeText("original audio $id") }
    return AudioPartEntity(id, "session", position, audio.name, audio.name, "audio/wav", audio.length(),
      20_000, AlignmentProtocol.sha256(audio), 1)
  }
  private fun job(state: JobState = JobState.CANCELLED) = JobEntity("job", "session", JobType.TRANSCRIBE,
    "groq", model = request.model, state = state, createdAt = 1, updatedAt = 1)
  private class Provider(val response: suspend (File) -> TranscriptResult) : TranscriptionProvider {
    var calls = 0
    override val id = "groq"
    override val capabilities = TranscriptionCapabilities(null, false, false)
    override suspend fun health() = EndpointHealth(true, 0)
    override suspend fun listModels() = listOf("pinned-model")
    override suspend fun transcribe(file: File, mime: String, request: TranscribeRequest,
      onProgress: (UploadProgress) -> Unit, onRemote: (RemoteProgress) -> Unit): TranscriptResult {
      calls++
      return response(file)
    }
  }
  private fun spoken() = TranscriptResult(exact, emptyList(), "it", null)

  @Test fun `known refusal and derived audio do not masquerade as received work`() {
    val root = files().jobDir("refused")
    val part = File(root, "part-a").also { it.mkdirs() }
    val derived = File(part, "chunk-0.m4a").also { it.writeText("derived audio") }
    val result = File(part, "chunk-0.json")
    val storage = TranscriptionWorkStorage()
    val attempt = storage.beginUpload(result)
    assertTrue(storage.hasRetainedWork(root))
    attempt.rejected()
    assertFalse(storage.hasRetainedWork(root))
    val received = File(result.path + ".received").also { it.writeText("{\"text\":\"received text\"}") }
    assertTrue(storage.hasRetainedWork(root))
    storage.cleanUp(root)
    assertTrue(received.exists())
    assertFalse(derived.exists())
  }

  @Test fun `cancel during B keeps A readable after cleanup and restart without resending B`() = runBlocking {
    val files = files(); val parts = listOf(part(files, "a", 0), part(files, "b", 1))
    val provider = Provider { file ->
      currentCoroutineContext()[CloudUploadContext]!!.dispatch("https://api.groq.com/openai/v1/audio/transcriptions")
      if (file.name == "b.wav") throw CancellationException("user cancel during B")
      spoken()
    }
    val runner = TranscriptionRunner(files, mockk())
    assertTrue(runCatching { runner.transcribeSession("job", parts, provider, request, 10) }.exceptionOrNull() is CancellationException)
    val a = files.jobs.resolve("job/part-a/whole.json"); val bytes = a.readBytes()
    val marker = files.jobs.resolve("job/part-b/whole.json.sending"); val intent = marker.readBytes()
    files.jobs.resolve("job/part-b/audio.pcm").writeText("disposable")
    runner.cleanUp("job")
    assertFalse(files.jobs.resolve("job/part-b/audio.pcm").exists())
    val read = CloudWorkRecovery.read(files.jobs.resolve("job"), job(), parts, files.audio)!!
    assertEquals(listOf(RecoveryWindowState.RECEIVED, RecoveryWindowState.UNCERTAIN), read.windows.map { it.state })
    assertEquals(exact, read.windows[0].text); assertNull(read.windows[1].text); assertTrue(read.uncertain)
    assertEquals(20_000L, read.windows[0].endMs)
    assertTrue(runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", parts, provider, request, 10) }.exceptionOrNull() is UncertainCloudRequest)
    assertEquals(2, provider.calls); assertArrayEquals(bytes, a.readBytes()); assertArrayEquals(intent, marker.readBytes())
  }

  @Test fun `received response is durable before guard finalization can cancel its coroutine`() = runBlocking {
    val files = files(); val parts = listOf(part(files, "a", 0))
    val provider = Provider { spoken() }
    val interrupted = runCatching {
      withContext(Job()) {
        val own = currentCoroutineContext().job
        val guard = CloudTranscriptionGuard(provider, beforeUpload = { it.copy(cloudAttemptFinished = { own.cancel() }) }, customOnly = { false })
        TranscriptionRunner(files, mockk()).transcribeSession("job", parts, guard, request, 10)
      }
    }.exceptionOrNull()
    assertTrue(interrupted is CancellationException)
    TranscriptionRunner(files, mockk()).cleanUp("job")
    assertEquals(exact, CloudWorkRecovery.read(files.jobs.resolve("job"), job(), parts, files.audio)!!.windows.single().text)
    assertEquals(1, provider.calls)
  }

  @Test fun `fsynced pending response can be read but never grants permission to resend`() = runBlocking {
    val files = files(); val parts = listOf(part(files, "a", 0)); val provider = Provider { spoken() }
    val storage = object : TranscriptionWorkStorage() {
      override fun write(file: File, text: String) {
        if (file.name == "whole.json") {
          File(file.path + ".pending").writeText(text)
          throw IOException("interruption before atomic replacement")
        }
        super.write(file, text)
      }
    }
    val error = runCatching { TranscriptionRunner(files, mockk(), storage).transcribeSession("job", parts, provider, request, 10) }.exceptionOrNull()!!
    assertEquals("cloud_request_uncertain", TranscriptionError.from(error).code)
    assertFalse(TranscriptionError.from(error).retryable)
    TranscriptionRunner(files, mockk()).cleanUp("job")
    val read = CloudWorkRecovery.read(files.jobs.resolve("job"), job(), parts, files.audio)!!
    assertEquals(exact, read.windows.single().text); assertTrue(read.uncertain)
    assertTrue(runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", parts, provider, request, 10) }.isFailure)
    assertEquals(1, provider.calls)
  }

  @Test fun `recovery preserves split windows and gaps and never invents timing from plain text`() {
    val files = files(); val p = part(files, "a", 0)
    val root = files.jobDir("job"); val storage = TranscriptionWorkStorage()
    val signature = StoredChunks.signature("groq", TranscriptionCapabilities(null, false, true), 10)
    storage.prepare(root, signature, request, true); CloudWorkRecovery.prepare(root, listOf(p), storage)
    val dir = root.resolve("part-a").also { it.mkdirs() }
    storage.recordInput(dir, TranscriptionWorkStorage.Input(p.id, p.sha256, p.durationMs))
    val plan = listOf(ChunkSpec(0, 0, 10_000), ChunkSpec(1, 10_000, 20_000))
    storage.write(dir.resolve("plan.json"), StoredChunks.encodePlan(plan))
    storage.write(dir.resolve("chunk-0.json"), StoredChunks.encode(ChunkTranscript(plan[0], emptyList(), sourceText = exact,
      audioSha256 = p.sha256, sourceProvider = "groq", sourceModel = request.model), signature))
    val read = CloudWorkRecovery.read(root, job(), listOf(p), files.audio)!!
    assertEquals(listOf(RecoveryWindowState.RECEIVED, RecoveryWindowState.MISSING), read.windows.map { it.state })
    assertEquals(exact, read.windows[0].text); assertEquals(10_000L, read.windows[1].startMs)
    assertNull(read.windows[1].text); assertFalse(read.uncertain)
  }

  @Test fun `changed placement audio provider or model never relabels retained text`() = runBlocking {
    val files = files(); val p = part(files, "a", 0); val runner = TranscriptionRunner(files, mockk())
    runner.transcribeSession("job", listOf(p), Provider { spoken() }, request, 10)
    val root = files.jobs.resolve("job"); val a = root.resolve("part-a/whole.json"); val saved = a.readBytes()
    for (changed in listOf(p.copy(position = 1), p.copy(sessionId = "another"), p.copy(fileName = "replacement.wav"), p.copy(durationMs = 5), p.copy(createdAt = 2)))
      assertNull(CloudWorkRecovery.read(root, job(), listOf(changed), files.audio))
    assertNull(CloudWorkRecovery.read(root, job().copy(provider = "mistral"), listOf(p), files.audio))
    assertNull(CloudWorkRecovery.read(root, job().copy(model = "other"), listOf(p), files.audio))
    files.audioFile(p.fileName).writeText("replacement")
    assertEquals(RecoveryWindowState.UNAVAILABLE, CloudWorkRecovery.read(root, job(), listOf(p), files.audio)!!.windows.single().state)
    assertArrayEquals(saved, a.readBytes())
  }

  @Test fun `actual network failure remains retryable while uncertain disk evidence does not`() {
    val network = TranscriptionError.from(java.net.UnknownHostException("offline before audio dispatch"))
    assertEquals("network", network.code); assertTrue(network.retryable)
    val uncertain = job(JobState.FAILED).copy(errorCode = "cloud_request_uncertain")
    assertEquals(FailureStanding.UNCERTAIN, FailedJobs.standing(uncertain, true, emptyList(), emptyList()))
    assertEquals(FailureStanding.RETRYABLE, FailedJobs.standing(uncertain.copy(errorCode = "network"), true, emptyList(), emptyList()))
  }

  @Test fun `actual HTTP boundary rechecks ownership before output or response probes`() = runBlocking {
    val files = files(); val parts = listOf(part(files, "a", 0))
    var networkIo = 0
    val http = TranscriptionHttp("offline-dispatch", connectionFactory = { url ->
      object : HttpURLConnection(url) {
        override fun connect() { networkIo++ }
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getOutputStream(): java.io.OutputStream { networkIo++; return java.io.ByteArrayOutputStream() }
        override fun getResponseCode(): Int { networkIo++; return 200 }
      }
    })
    val error = runCatching {
      TranscriptionRunner(files, mockk()).transcribeSession("job", parts, GroqWhisperProvider(http, "offline-key"), request, 10,
        beforeCloudUpload = { throw TranscriptionError.CloudControl("cloud_recovery_changed") })
    }.exceptionOrNull()!!
    assertEquals("cloud_recovery_changed", TranscriptionError.from(error).code)
    assertEquals(0, networkIo)
    assertFalse(TranscriptionWorkStorage().hasUncertainUpload(files.jobs.resolve("job")))
    assertTrue(files.jobs.resolve("job/part-a/whole.json.sending").readText().startsWith("rejected:"))
  }

  @Test fun `recovery needs no local audio or download when the original job identity is retained`() = runBlocking {
    val files = files(); val p = part(files, "a", 0).copy(sha256 = "")
    val provider = Provider { spoken() }
    TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(p), provider, request, 10)
    assertTrue(files.audioFile(p.fileName).delete())
    assertEquals(exact, CloudWorkRecovery.read(files.jobs.resolve("job"), job(), listOf(p), files.audio)!!.windows.single().text)
    assertEquals(1, provider.calls)
  }

  @Test fun `complete HTTP response survives cancellation before returning from IO and provider parsing`() = runBlocking {
    val files = files(); val parts = listOf(part(files, "a", 0)); var uploads = 0
    val body = kotlinx.serialization.json.buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive(exact)) }.toString()
    val http = TranscriptionHttp("offline-received-body", connectionFactory = { url ->
      object : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getOutputStream(): java.io.OutputStream { uploads++; return java.io.ByteArrayOutputStream() }
        override fun getResponseCode() = 200
        override fun getInputStream() = java.io.ByteArrayInputStream(body.toByteArray())
      }
    })
    val provider = GroqWhisperProvider(http, "offline-key")
    val error = runCatching {
      withContext(Job()) {
        val own = currentCoroutineContext().job
        TranscriptionRunner(files, mockk()).transcribeSession("job", parts, provider,
          request.copy(cloudResponse = { own.cancel() }), 10)
      }
    }.exceptionOrNull()
    assertTrue(error is CancellationException)
    TranscriptionRunner(files, mockk()).cleanUp("job")
    val read = CloudWorkRecovery.read(files.jobs.resolve("job"), job(), parts, files.audio)!!
    assertEquals(exact, read.windows.single().text); assertEquals(RecoveryWindowState.RECEIVED, read.windows.single().state)
    assertTrue(read.uncertain); assertFalse(files.jobs.resolve("job/part-a/whole.json").exists())
    assertTrue(runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", parts, provider, request, 10) }.isFailure)
    assertEquals(1, uploads)
  }
}
