package dev.pampa.pampanotes.core.transcription

import android.content.Context
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.files.AppFiles
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudChunkRecoveryTest {
  @get:Rule val temp = TemporaryFolder()
  @Test fun `Mistral local context window has no companion duration tolerance`() {
    val provider = MistralTranscriptionProvider(TranscriptionHttp("fixture"), "fake")
    val decision = TranscriptionRunner.chunkDecision(provider.capabilities, "fixture.m4a", 1000, 11 * 60_000, 20)
    assertTrue(decision is dev.pampa.pampanotes.core.audio.ChunkDecision.Split)
  }
  @Test fun `existing Groq paid cache remains readable without another upload`() = runBlocking {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder("legacy-files")
    every { context.cacheDir } returns temp.newFolder("legacy-cache")
    val files = AppFiles(context)
    files.audioFile("a.wav").writeBytes(byteArrayOf(1, 2, 3))
    val part = AudioPartEntity("a", "s", 0, "a.wav", "a.wav", "audio/wav", 3, 1000, "fixture-sha", 1)
    val provider = object : TranscriptionProvider {
      override val id = "groq"
      override val capabilities = TranscriptionCapabilities(null, true, false)
      override suspend fun listModels() = emptyList<String>()
      override suspend fun health() = EndpointHealth(true, 0)
      override suspend fun transcribe(file: File, mime: String, request: TranscribeRequest, onProgress: (UploadProgress) -> Unit, onRemote: (RemoteProgress) -> Unit): TranscriptResult = error("Must reuse the paid cache")
    }
    val signature = StoredChunks.signature("groq", provider.capabilities, 10)
    val job = files.jobDir("legacy")
    File(job, StoredChunks.WORK_FILE).writeText(signature)
    val directory = File(job, "part-a").apply { mkdirs() }
    File(directory, "whole.json").writeText(StoredChunks.encode(ChunkTranscript(dev.pampa.pampanotes.core.audio.ChunkSpec(0, 0, 1000), listOf(RawSegment(0, 1000, "Already paid."))), signature))
    val result = TranscriptionRunner(files, mockk()).transcribeSession("legacy", listOf(part), provider, TranscribeRequest("whisper-large-v3-turbo"), 10)
    assertEquals("Already paid.", result.text)
  }
  @Test fun `rate wait releases runner and restart reuses paid text with detected language and part identity`() = runBlocking {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder("files")
    every { context.cacheDir } returns temp.newFolder("cache")
    val files = AppFiles(context)
    val calls = mutableListOf<String>()
    var limited = true
    val provider = object : TranscriptionProvider {
      override val id = "mistral"
      override val capabilities = TranscriptionCapabilities(null, true, false)
      override suspend fun listModels() = listOf("voxtral-mini-transcribe-3")
      override suspend fun health() = EndpointHealth(true, 0)
      override suspend fun transcribe(file: File, mime: String, request: TranscribeRequest, onProgress: (UploadProgress) -> Unit, onRemote: (RemoteProgress) -> Unit): TranscriptResult {
        calls += file.name
        if (file.name == "b.wav" && limited) throw TranscriptionError.RateLimited(30.0, "fixture")
        return TranscriptResult(if (file.name == "a.wav") "  Ciao, mondo!\nCome va?  " else "Second part.", emptyList(), if (file.name == "a.wav") "it" else "en", 1000)
      }
    }
    val parts = listOf("a", "b").mapIndexed { index, id ->
      files.audioFile("$id.wav").writeBytes(byteArrayOf(1, 2, 3))
      AudioPartEntity(id, "session", index, "$id.wav", "$id.wav", "audio/wav", 3, 1000, "fixture-$id", 1)
    }
    val request = TranscribeRequest("voxtral-mini-transcribe-3")
    assertTrue(runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", parts, provider, request, 10) }.exceptionOrNull() is TranscriptionError.RateLimited)
    assertEquals(listOf("a.wav", "b.wav"), calls)
    limited = false
    val result = TranscriptionRunner(files, mockk()).transcribeSession("job", parts, provider, request, 10)
    assertEquals(listOf("a.wav", "b.wav", "b.wav"), calls)
    assertEquals("  Ciao, mondo!\nCome va?  \n\nSecond part.", result.text)
    assertEquals(listOf("a", "b"), result.sourceParts.map { it.partId })
    assertEquals("it", result.sourceParts.first().language)
    assertEquals("  Ciao, mondo!\nCome va?  ", result.sourceParts.first().text)
    assertEquals("mistral", result.provider)
    assertEquals(request.model, result.model)
  }
}
