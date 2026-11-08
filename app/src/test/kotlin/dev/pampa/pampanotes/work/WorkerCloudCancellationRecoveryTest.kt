package dev.pampa.pampanotes.work

import android.content.Context
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.transcription.*
import io.mockk.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual Worker.cancel + runner + disk. WorkManager/Android watcher runtime is not simulated. */
class WorkerCloudCancellationRecoveryTest {
  @get:Rule val temp = TemporaryFolder()
  @Test fun `ordinary worker cancellation preserves A and B intent and blocks a later upload`() = runBlocking {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder()
    every { context.cacheDir } returns temp.newFolder()
    val files = AppFiles(context)
    val parts = listOf("a", "b").mapIndexed { index, id ->
      val file = files.audioFile("$id.wav").also { it.writeText("audio $id") }
      AudioPartEntity(id, "session", index, file.name, file.name, "audio/wav", file.length(), 1000,
        dev.pampa.pampanotes.core.alignment.AlignmentProtocol.sha256(file), 1)
    }
    var uploads = 0
    val provider = object : TranscriptionProvider {
      override val id = "groq"
      override val capabilities = TranscriptionCapabilities(null, false, false)
      override suspend fun health() = EndpointHealth(true, 0)
      override suspend fun listModels() = emptyList<String>()
      override suspend fun transcribe(file: File, mime: String, request: TranscribeRequest,
        onProgress: (UploadProgress) -> Unit, onRemote: (RemoteProgress) -> Unit): TranscriptResult {
        uploads++
        if (file.name == "b.wav") throw CancellationException("user cancelled B")
        return TranscriptResult("Received A", emptyList(), null, null)
      }
    }
    val runner = TranscriptionRunner(files, mockk())
    val request = TranscribeRequest("model")
    assertTrue(runCatching { runner.transcribeSession("job", parts, provider, request, 10) }.exceptionOrNull() is CancellationException)
    val paid = files.jobs.resolve("job/part-a/whole.json"); val saved = paid.readBytes()
    val marker = files.jobs.resolve("job/part-b/whole.json.sending"); val intent = marker.readBytes()
    val repository = mockk<TranscriptionRepository>()
    val running = JobEntity("job", "session", JobType.TRANSCRIBE, "groq", "model", JobState.TRANSCRIBING,
      attempts = 1, createdAt = 1, updatedAt = 1)
    coEvery { repository.markCancelled(running) } returns true
    val unsafe = Class.forName("sun.misc.Unsafe")
    val instance = unsafe.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val worker = unsafe.getMethod("allocateInstance", Class::class.java).invoke(instance, TranscriptionQueueWorker::class.java) as TranscriptionQueueWorker
    for ((name, value) in mapOf("repository" to repository, "runner" to runner))
      worker.javaClass.getDeclaredField(name).apply { isAccessible = true; set(worker, value) }
    val cancel = worker.javaClass.declaredMethods.single { it.name == "cancel" }.apply { isAccessible = true }
    suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
      try { cancel.invoke(worker, running, continuation) }
      catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
    }
    coVerify(exactly = 1) { repository.markCancelled(running) }
    assertArrayEquals(saved, paid.readBytes()); assertArrayEquals(intent, marker.readBytes())
    val error = runCatching { runner.transcribeSession("job", parts, provider, request, 10) }.exceptionOrNull()!!
    assertEquals("cloud_request_uncertain", TranscriptionError.from(error).code)
    assertEquals(2, uploads)
  }
}
