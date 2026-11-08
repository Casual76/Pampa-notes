package dev.pampa.pampanotes.core.transcription

import android.content.Context
import dev.pampa.pampanotes.core.alignment.*
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Prepared regression: real provider/parser -> runner -> durable chunk cache -> save plan.
 * HTTP and file fetching are mocked; bytes are synthetic. No server, audio decoder or provider.
 */
class CombinedOpenRouterExactSourceB04Test {
  @get:Rule val temp = TemporaryFolder()
  private fun files(): AppFiles {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder()
    every { context.cacheDir } returns temp.newFolder()
    return AppFiles(context)
  }
  private fun part(files: AppFiles, id: String, position: Int): AudioPartEntity {
    val file = files.audioFile("$id.m4a").also { it.writeBytes(byteArrayOf(position.toByte(), 42)) }
    return AudioPartEntity(id, "s", position, file.name, file.name, "audio/mp4", file.length(), 2000, AlignmentProtocol.sha256(file), 1)
  }
  private fun response(text: String?, absent: Boolean = false): JsonObject = buildJsonObject {
    if (!absent) put("text", text?.let(::JsonPrimitive) ?: JsonNull)
    put("language", "Italian")
    put("segments", buildJsonArray { add(buildJsonObject { put("start", 0); put("end", 1); put("text", "derived, segment!") }) })
  }
  private fun provider(answer: (java.io.File) -> JsonElement): Pair<OpenRouterTranscriptionProvider, TranscriptionHttp> {
    val http = mockk<TranscriptionHttp>()
    coEvery { http.postAudio(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers { answer(arg(3)) }
    return OpenRouterTranscriptionProvider(http, "synthetic-unused-key") to http
  }
  @Test fun `missing null and nonstring originals fail at actual provider before exact source cache`() = runBlocking {
    for (body in listOf(response(null, absent = true), response(null), JsonObject(response("x") + ("text" to JsonPrimitive(7))))) {
      val files = files(); val part = part(files, "a", 0)
      val (provider, http) = provider { body }
      val failure = runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(part), provider, TranscribeRequest("pinned", "it"), 10) }.exceptionOrNull()
      assertTrue(failure is TranscriptionError.Parse)
      assertFalse((failure as TranscriptionError).retryable)
      coVerify(exactly = 1) { http.postAudio(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
      assertFalse(files.jobDir("job").walkTopDown().any { it.name == "whole.json" })
    }
  }
  @Test fun `present whitespace and nonblank originals survive actual provider cache resume and save`() = runBlocking {
    val exact = listOf(" \t\r\n", "  Exact,\tcloud!\r\n ")
    val files = files(); val parts = listOf(part(files, "a", 0), part(files, "b", 1))
    val (provider, http) = provider { file -> response(exact[if (file.name == "a.m4a") 0 else 1]) }
    val request = TranscribeRequest("pinned", "it")
    val first = TranscriptionRunner(files, mockk()).transcribeSession("job", parts, provider, request, 10)
    // A fresh runner must consume the persisted exact originals without another HTTP request.
    val replay = TranscriptionRunner(files, mockk()).transcribeSession("job", parts, provider, request, 10)
    coVerify(exactly = 2) { http.postAudio(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    assertEquals(exact, first.sourceParts.map { it.text }); assertEquals(first, replay)
    assertTrue(replay.sourceParts.all { it.exactTextKnown })
    val draft = TranscriptEntity("raw", "s", TranscriptKind.RAW, provider.id, "pinned", replay.language, replay.text, wordCount = 2, createdAt = 42)
    val (body, doc) = CloudSavePlan.build(draft, parts.map { it.id }, replay, parts, parts, parts.map { it.id }.toSet(), null, emptyList())
    assertEquals(exact.joinToString("\n\n"), body)
    assertEquals(exact, doc.parts.map { it.text }); assertEquals(exact, doc.originals.single().parts.map { it.responses!!.single().text })
    assertEquals(listOf("Italian", "Italian"), doc.parts.map { it.language })
    assertEquals(listOf(0L, 0L), doc.parts.map { it.responses!!.single().startMs })
    assertEquals(parts.map { it.sha256 }, doc.parts.map { it.responses!!.single().audioSha256 })
    assertTrue(doc.parts.all { it.responses!!.single().provider == "openrouter" && it.responses.single().model == "pinned" })
    assertEquals("complete_original", doc.originals.single().contentScope)
    assertNotNull(CloudSources.bindSource(doc, parts))
    assertEquals(doc, CloudSources.decode(CloudSources.encode(doc)))
  }
  @Test fun `whitespace only without segments is still exact and untimed at parser boundary`() {
    val exact = " \t\r\n"
    val parsed = OpenRouterTranscriptionProvider.parseResponse(buildJsonObject { put("text", exact) })
    assertEquals(exact, parsed.text); assertTrue(parsed.segments.isEmpty())
  }
  @Test fun `nullable and verbatim optional response language survives actual provider cache and save`() = runBlocking {
    for (label in listOf(null, "", " IT ")) {
      val files = files(); val audio = part(files, "a", 0)
      val body = JsonObject(response(" Nonblank control. ") + ("language" to (label?.let(::JsonPrimitive) ?: JsonNull)))
      val (provider, http) = provider { body }
      val request = TranscribeRequest("pinned", "it")
      TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(audio), provider, request, 10)
      val result = TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(audio), provider, request, 10)
      coVerify(exactly = 1) { http.postAudio(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
      val draft = TranscriptEntity("raw", "s", TranscriptKind.RAW, provider.id, "pinned", result.language, result.text, wordCount = 2, createdAt = 42)
      val doc = CloudSavePlan.build(draft, listOf("a"), result, listOf(audio), listOf(audio), setOf("a"), null, emptyList()).second
      assertEquals(label?.takeIf { it.isNotBlank() }, doc.parts.single().language)
      assertEquals(label, doc.parts.single().responses!!.single().language)
      assertEquals(" Nonblank control. ", doc.parts.single().responses!!.single().text)
    }
  }
}
