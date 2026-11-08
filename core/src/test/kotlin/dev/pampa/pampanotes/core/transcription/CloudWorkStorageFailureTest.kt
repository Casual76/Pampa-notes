package dev.pampa.pampanotes.core.transcription

import android.content.Context
import dev.pampa.pampanotes.core.alignment.AlignmentProtocol
import dev.pampa.pampanotes.core.audio.*
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.files.AppFiles
import io.mockk.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudWorkStorageFailureTest {
  @get:Rule val temp = TemporaryFolder()
  private fun files(): AppFiles {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder()
    every { context.cacheDir } returns temp.newFolder()
    return AppFiles(context)
  }
  private fun part(files: AppFiles, id: String = "a", position: Int = 0): AudioPartEntity {
    val file = files.audioFile("$id.fixture").also { it.writeBytes(byteArrayOf(1, 2)) }
    return AudioPartEntity(id, "s", position, file.name, file.name, "audio/fixture", 2, 20000, AlignmentProtocol.sha256(file), 1)
  }
  private class Provider(val chunked: Boolean = false) : TranscriptionProvider {
    var calls = 0; var limited = false
    var failure: Throwable? = null
    override val id = "groq"
    override val capabilities = TranscriptionCapabilities(null, false, chunked, acceptedExtensions = if (chunked) emptySet() else null)
    override suspend fun listModels() = emptyList<String>()
    override suspend fun health() = EndpointHealth(true, 0)
    override suspend fun transcribe(file: File, mime: String, request: TranscribeRequest, onProgress: (UploadProgress) -> Unit, onRemote: (RemoteProgress) -> Unit): TranscriptResult {
      calls++
      failure?.let { throw it }
      if (limited && file.name.startsWith("b")) throw TranscriptionError.RateLimited(60.0, "fixture")
      return TranscriptResult("  paid,\t${file.name}!\n", emptyList(), "it", null)
    }
  }
  private val request = TranscribeRequest("pinned", "it")
  @Test fun `production runner sends zero uploads on deterministic options stamp input and intent write failure`() = runBlocking {
    for (name in listOf("options.json", StoredChunks.WORK_FILE, "input.json", "whole.json.sending")) {
      val files = files(); val p = part(files); val provider = Provider()
      val failing = object : TranscriptionWorkStorage() {
        override fun write(file: File, text: String) {
          if (file.name == name) throw IOException("fixture $name write failure")
          super.write(file, text)
        }
      }
      val error = runCatching { TranscriptionRunner(files, mockk(), failing).transcribeSession("job", listOf(p), provider, request, 10) }.exceptionOrNull()
      assertTrue("$name: $error", error is IOException); assertEquals(name, 0, provider.calls)
    }
  }
  @Test fun `filesystem invalid stamp and changed unstamped plans retain prior paid bytes and never upload`() = runBlocking {
    for (state in listOf("stamp-is-directory", "stamp-missing", "changed-signature", "pending-write")) {
      val files = files(); val p = part(files); val provider = Provider()
      val directory = files.jobDir("job"); val signature = StoredChunks.signature(provider.id, provider.capabilities, 10)
      when (state) {
        "stamp-is-directory" -> File(directory, StoredChunks.WORK_FILE).mkdirs()
        "changed-signature" -> File(directory, StoredChunks.WORK_FILE).writeText("other-provider|whole|0")
        "pending-write" -> File(directory, "work.json.pending").writeText(signature)
      }
      val paid = File(directory, "part-a/whole.json").also { it.parentFile.mkdirs(); it.writeText("precious paid result") }
      assertNotNull(runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(p), provider, request, 10) }.exceptionOrNull())
      assertEquals(0, provider.calls); assertEquals("precious paid result", paid.readText())
    }
  }
  @Test fun `model language prompt and temperature changes cannot delete or rerun existing paid cache`() = runBlocking {
    val files = files(); val p = part(files); val provider = Provider()
    val first = TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(p), provider, request, 10)
    val paid = files.jobs.resolve("job/part-a/whole.json"); val bytes = paid.readBytes()
    for (changed in listOf(request.copy(model = "other"), request.copy(language = "en"), request.copy(prompt = "vocabulary"), request.copy(temperature = 0.1))) {
      assertTrue(runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(p), provider, changed, 10) }.exceptionOrNull() is IOException)
      assertArrayEquals(bytes, paid.readBytes()); assertEquals(1, provider.calls)
    }
    assertEquals(first, TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(p), provider, request, 10))
  }
  @Test fun `prior completed result remains reusable after failure persisting another part input`() = runBlocking {
    val files = files(); val a = part(files); val b = part(files, "b", 1); val provider = Provider()
    val storage = object : TranscriptionWorkStorage() {
      override fun write(file: File, text: String) {
        if (file.parentFile.name == "part-b" && file.name == "input.json") throw IOException("fixture input failure")
        super.write(file, text)
      }
    }
    assertTrue(runCatching { TranscriptionRunner(files, mockk(), storage).transcribeSession("job", listOf(a, b), provider, request, 10) }.exceptionOrNull() is IOException)
    assertEquals(1, provider.calls)
    val paid = files.jobs.resolve("job/part-a/whole.json"); val bytes = paid.readBytes()
    val result = TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(a, b), provider, request, 10)
    assertEquals(2, provider.calls); assertArrayEquals(bytes, paid.readBytes()); assertTrue(result.text.contains("paid"))
  }
  @Test fun `failed completed-result persistence leaves durable uncertain request and prevents billing it again`() = runBlocking {
    val files = files(); val p = part(files); val provider = Provider()
    val storage = object : TranscriptionWorkStorage() {
      override fun write(file: File, text: String) {
        if (file.name == "whole.json") throw IOException("fixture completed write failure")
        super.write(file, text)
      }
    }
    assertTrue(runCatching { TranscriptionRunner(files, mockk(), storage).transcribeSession("job", listOf(p), provider, request, 10) }.exceptionOrNull() is IOException)
    assertEquals(1, provider.calls)
    assertTrue(files.jobs.resolve("job/part-a/whole.json.sending").isFile)
    assertTrue(runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(p), provider, request, 10) }.exceptionOrNull() is IOException)
    assertEquals(1, provider.calls)
  }
  @Test fun `unknown connection or cancellation outcome never loops or repeats a paid request after restart`() = runBlocking {
    for (failure in listOf(IOException("lost connection after send"), kotlinx.coroutines.CancellationException("system stop"))) {
      val files = files(); val p = part(files); val provider = Provider().also { it.failure = failure }
      val observed = runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(p), provider, request, 10) }.exceptionOrNull()
      // Coroutine stack-trace recovery may copy the exception at withContext boundaries.
      // Preserve its type, message and original cause, rather than requiring wrapper identity.
      if (failure is kotlinx.coroutines.CancellationException) {
        assertEquals(failure.javaClass, observed?.javaClass)
        assertEquals(failure.message, observed?.message)
      } else {
        assertTrue(observed is UncertainCloudRequest)
        assertEquals("cloud_request_uncertain", TranscriptionError.from(observed!!).code)
        assertFalse(TranscriptionError.from(observed).retryable)
      }
      assertTrue(generateSequence(observed) { it.cause }.any { it === failure })
      assertEquals(1, provider.calls)
      provider.failure = null
      assertTrue(runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(p), provider, request, 10) }.exceptionOrNull() is IOException)
      assertEquals(1, provider.calls)
    }
  }
  @Test fun `legacy partial chunk cache without input identity is preserved and cannot trigger paid continuation`() = runBlocking {
    val files = files(); val p = part(files); val provider = Provider(true)
    val job = files.jobDir("job"); val signature = StoredChunks.signature(provider.id, provider.capabilities, 10)
    File(job, StoredChunks.WORK_FILE).writeText(signature)
    val directory = File(job, "part-a").also { it.mkdirs() }
    val specs = listOf(ChunkSpec(0, 0, 10000), ChunkSpec(1, 9000, 20000))
    File(directory, "plan.json").writeText(StoredChunks.encodePlan(specs))
    val paid = File(directory, "chunk-0.json").also { it.writeText(StoredChunks.encode(ChunkTranscript(specs.first(), emptyList(), sourceText = "paid original"), signature)) }
    val bytes = paid.readBytes()
    assertTrue(runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(p), provider, request, 10) }.exceptionOrNull() is IOException)
    assertEquals(0, provider.calls); assertArrayEquals(bytes, paid.readBytes())
  }
  @Test fun `partial paid chunk replacement safely refuses continuation and unchanged original resumes without paid rerun`() = runBlocking {
    val files = files(); val p = part(files); val provider = Provider(true).also { it.limited = true }
    mockkObject(PcmDecoder, ChunkEncoder)
    try {
      every { PcmDecoder.decodeToPcm(any(), any(), any()) } answers {
        val pcm = secondArg<File>().also { it.writeBytes(byteArrayOf(1, 2)) }
        DecodedAudio(pcm, 16000, FloatArray(1200), 1000, 1200000)
      }
      every { ChunkEncoder.encode(any(), any(), any(), any(), any()) } answers {
        assertArrayEquals(byteArrayOf(1, 2), firstArg<File>().readBytes())
        val spec = thirdArg<ChunkSpec>()
        val name = if (spec.index == 0) "a-encoded.fixture" else "b-encoded.fixture"
        EncodedChunk(spec, File(arg<File>(3), name).also { it.writeBytes(byteArrayOf(1)) }, "audio/fixture")
      }
      assertTrue(runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(p), provider, request, 10) }.exceptionOrNull() is TranscriptionError.RateLimited)
      assertEquals(2, provider.calls)
      val paid = files.jobs.resolve("job/part-a/chunk-0.json"); val paidBytes = paid.readBytes()
      files.audioFile(p.fileName).writeBytes(byteArrayOf(99))
      val changed = p.copy(sha256 = AlignmentProtocol.sha256(files.audioFile(p.fileName)))
      provider.limited = false
      assertTrue(runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(changed), provider, request, 10) }.exceptionOrNull() is IOException)
      assertEquals(2, provider.calls); assertArrayEquals(paidBytes, paid.readBytes())
      files.audioFile(p.fileName).writeBytes(byteArrayOf(1, 2))
      files.jobs.resolve("job/part-a/audio.pcm").writeBytes(byteArrayOf(99))
      val result = TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(p), provider, request, 10)
      assertEquals(3, provider.calls); assertArrayEquals(paidBytes, paid.readBytes())
      assertEquals(p.sha256, result.sourceParts.single().audioSha256)
      assertEquals(listOf(p.sha256, p.sha256), result.sourceParts.single().responses!!.map { it.audioSha256 })
      verify(exactly = 2) { PcmDecoder.decodeToPcm(any(), any(), any()) }
    } finally { unmockkObject(PcmDecoder, ChunkEncoder) }
  }
  @Test fun `interrupted cache remains untouched and cannot cause another upload`() = runBlocking {
    val files = files(); val p = part(files); val provider = Provider()
    val directory = files.jobDir("job")
    File(directory, StoredChunks.WORK_FILE).writeText(StoredChunks.signature(provider.id, provider.capabilities, 10))
    val paid = File(directory, "part-a/whole.json").also { it.parentFile.mkdirs(); it.writeText("{incomplete") }
    assertTrue(runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(p), provider, request, 10) }.exceptionOrNull() is IOException)
    assertEquals(0, provider.calls); assertEquals("{incomplete", paid.readText())
  }
  @Test fun `chunk plan persistence failure occurs before any upload with mocked codecs`() = runBlocking {
    val files = files(); val p = part(files); val provider = Provider(true)
    mockkObject(PcmDecoder)
    try {
      every { PcmDecoder.decodeToPcm(any(), any(), any()) } answers {
        val pcm = secondArg<File>().also { it.writeBytes(byteArrayOf(1, 2)) }
        DecodedAudio(pcm, 16000, FloatArray(20), 1000, 20000)
      }
      val storage = object : TranscriptionWorkStorage() {
        override fun write(file: File, text: String) {
          if (file.name == "plan.json") throw IOException("fixture chunk plan failure")
          super.write(file, text)
        }
      }
      assertTrue(runCatching { TranscriptionRunner(files, mockk(), storage).transcribeSession("job", listOf(p), provider, request, 10) }.exceptionOrNull() is IOException)
      assertEquals(0, provider.calls)
    } finally { unmockkObject(PcmDecoder) }
  }
}
