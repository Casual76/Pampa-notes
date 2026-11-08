package dev.pampa.pampanotes.core.transcription

import android.content.Context
import dev.pampa.pampanotes.core.alignment.*
import dev.pampa.pampanotes.core.audio.*
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import io.mockk.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic bytes, fake providers and mocked codecs only: no media, network or model execution. */
class CloudProvenanceRecoveryTest {
  @get:Rule val temp = TemporaryFolder()
  private fun files(): AppFiles {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder()
    every { context.cacheDir } returns temp.newFolder()
    return AppFiles(context)
  }
  private fun part(files: AppFiles, id: String = "a", position: Int = 0): AudioPartEntity {
    val file = files.audioFile("$id.fixture").also { it.writeBytes(byteArrayOf(position.toByte(), 42)) }
    return AudioPartEntity(id, "s", position, file.name, file.name, "audio/fixture", file.length(), 20000, AlignmentProtocol.sha256(file), 1)
  }
  private fun provider(id: String, chunked: Boolean = false, answer: (File) -> TranscriptResult) = object : TranscriptionProvider {
    override val id = id
    override val capabilities = TranscriptionCapabilities(null, true, chunked, acceptedExtensions = if (chunked) emptySet() else null)
    override suspend fun listModels() = emptyList<String>()
    override suspend fun health() = EndpointHealth(true, 0)
    override suspend fun transcribe(file: File, mime: String, request: TranscribeRequest, onProgress: (UploadProgress) -> Unit, onRemote: (RemoteProgress) -> Unit) = answer(file)
  }
  private fun save(result: SessionTranscript, before: List<AudioPartEntity>, current: List<AudioPartEntity> = before): Pair<String, CloudSourceDocument> {
    val draft = TranscriptEntity("raw", "s", TranscriptKind.RAW, result.provider, result.model, result.language, result.text, wordCount = 0, createdAt = 1)
    return CloudSavePlan.build(draft, before.map { it.id }, result, before, current, current.map { it.id }.toSet(), null, emptyList())
  }
  @Test fun `null response language stays null for every whole provider fresh cache and saved original`() = runBlocking {
    for (id in listOf("groq", "mistral", "openrouter")) {
      val files = files(); val p = part(files); var calls = 0
      val exact = "  Exact,\tresponse!\r\n "
      val provider = provider(id) { calls++; TranscriptResult(exact, emptyList(), null, null) }
      val request = TranscribeRequest("pinned", "it")
      val first = TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(p), provider, request, 10)
      val replay = TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(p), provider, request, 10)
      assertEquals(1, calls); assertEquals(exact, replay.text)
      assertNull(first.language); assertNull(replay.language); assertNull(replay.sourceParts.single().language)
      val doc = save(replay, listOf(p)).second
      assertNull(doc.parts.single().language); assertNull(doc.originals.single().language)
      assertEquals(doc, CloudSources.decode(CloudSources.encode(doc)))
    }
  }
  @Test fun `paid A then B quota replacement retry save binding and rehash retain old input without another A upload`() = runBlocking {
    val files = files(); val a = part(files); val b = part(files, "b", 1)
    var limited = true; val calls = mutableListOf<String>()
    val provider = provider("groq") { file ->
      calls += file.name
      if (file.name == b.fileName && limited) throw TranscriptionError.RateLimited(120.0, "fixture")
      TranscriptResult("  paid ${file.name}!\t\n", emptyList(), "Italian", null)
    }
    val request = TranscribeRequest("pinned", "it")
    assertTrue(runCatching { TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(a, b), provider, request, 10) }.exceptionOrNull() is TranscriptionError.RateLimited)
    files.audioFile(a.fileName).writeBytes(byteArrayOf(99))
    val replacement = a.copy(sha256 = AlignmentProtocol.sha256(files.audioFile(a.fileName)))
    limited = false
    val current = listOf(replacement, b)
    val runner = TranscriptionRunner(files, mockk())
    val result = runner.transcribeSession("job", current, provider, request, 10)
    val (text, doc) = save(result, current)
    assertEquals(listOf(a.fileName, b.fileName, b.fileName), calls)
    assertTrue(text.contains("paid ${a.fileName}"))
    assertEquals(a.sha256, doc.parts.first().sha256)
    assertEquals(a.sha256, doc.originals.single().parts.first().sha256)
    assertNull(CloudSources.bindSource(doc, current))
    assertNotNull(CloudSources.bindSource(doc, listOf(a, b)))
    assertFalse(AlignmentAudio.verify(files.audio, listOf(AlignmentPlacement(a.id, a.sha256, 0, a.durationMs, a.fileName))))
    val persisted = CloudSources.encode(doc)
    runner.cleanUp("job")
    assertFalse(files.jobs.resolve("job").exists())
    assertEquals(a.sha256, CloudSources.decode(persisted)!!.originals.single().parts.first().sha256)
    // A separate explicitly created manual job can transcribe replacement audio.
    val fresh = runner.transcribeSession("manual-new", listOf(replacement), provider, request, 10)
    assertEquals(replacement.sha256, save(fresh, listOf(replacement)).second.parts.single().sha256)
  }
  @Test fun `actual overlapping legacy cached responses survive runner cleanup save reorder and portable histories exactly`() = runBlocking {
    val files = files(); val a = part(files); val b = part(files, "b", 1)
    val exact = listOf("  First,\tchunk!\r\n", "  Second  chunk?\n ")
    val provider = provider("groq", true) { error("No paid rerun") }
    val signature = StoredChunks.signature(provider.id, provider.capabilities, 10)
    val job = files.jobDir("job"); File(job, StoredChunks.WORK_FILE).writeText(signature)
    for (p in listOf(a, b)) {
      val directory = File(job, "part-${p.id}").also { it.mkdirs() }
      val specs = listOf(ChunkSpec(0, 0, 10000), ChunkSpec(1, 9000, 20000))
      File(directory, "plan.json").writeText(StoredChunks.encodePlan(specs))
      specs.forEachIndexed { i, spec -> File(directory, "chunk-$i.json").writeText(StoredChunks.encode(
        ChunkTranscript(spec, listOf(RawSegment(0, 1000, "wrong normalized $i")), sourceText = exact[i], language = "it"), signature)) }
    }
    val runner = TranscriptionRunner(files, mockk())
    val result = runner.transcribeSession("job", listOf(a, b), provider, TranscribeRequest("pinned", "it"), 10)
    val perPart = exact.joinToString("\n\n")
    assertEquals("$perPart\n\n$perPart", result.text)
    assertNull(result.language) // Legacy hint substitution cannot be treated as reported evidence.
    val (body, doc) = save(result, listOf(a, b))
    assertEquals(result.text, body); assertTrue(doc.parts.all { it.sha256.isEmpty() })
    assertNull(CloudSources.bindSource(doc, listOf(a, b)))
    val encoded = CloudSources.encode(doc); runner.cleanUp("job")
    val recovered = CloudSources.decode(encoded)!!
    assertEquals(exact, recovered.originals.single().parts.first().responses!!.map { it.text })
    val raw = TranscriptEntity("raw", "s", TranscriptKind.RAW, "groq", "pinned", null, body, wordCount = 0, createdAt = 1, cloudSourceJson = encoded)
    val reordered = CloudSources.project(recovered, raw, listOf("b", "a"), emptyList()).second
    assertEquals(exact, reordered.originals.single().parts.first().responses!!.map { it.text })
    val projection = CloudSources.portableProjection(raw, listOf(a), retainOriginals = false)
    val portable = CloudSources.decode(projection.cloudSourceJson)!!
    assertEquals(exact, portable.originals.single().parts.single().responses!!.map { it.text })
    assertEquals(listOf("a"), portable.originals.single().parts.map { it.id })
    assertEquals("portable_unbound", portable.alignmentBinding)
  }
  @Test fun `three providers chunk fresh and resumed keep exact response labels and unknown aggregate`() = runBlocking {
    mockkObject(PcmDecoder, ChunkEncoder)
    try {
      every { PcmDecoder.decodeToPcm(any(), any(), any()) } answers {
        val pcm = secondArg<File>().also { it.writeBytes(byteArrayOf(1, 2)) }
        DecodedAudio(pcm, 16000, FloatArray(1200), 1000, 1200000)
      }
      every { ChunkEncoder.encode(any(), any(), any(), any(), any()) } answers {
        val spec = thirdArg<ChunkSpec>()
        EncodedChunk(spec, File(arg<File>(3), "encoded-${spec.index}.fixture").also { it.writeBytes(byteArrayOf(1)) }, "audio/fixture")
      }
      for (id in listOf("groq", "mistral", "openrouter")) {
        for (labels in listOf(listOf(null, null), listOf("Italian", null), listOf("IT", "ita"))) {
        val files = files(); val p = part(files); var calls = 0
        val provider = provider(id, true) { val language = labels[calls++]; TranscriptResult("  chunk\t$calls!\n", emptyList(), language, null) }
        val request = TranscribeRequest("pinned", "it")
        val runner = TranscriptionRunner(files, mockk())
        val first = runner.transcribeSession("job", listOf(p), provider, request, 10)
        val count = calls
        val replay = runner.transcribeSession("job", listOf(p), provider, request, 10)
        assertEquals(count, calls); assertEquals(2, count)
        assertEquals(CloudSources.reportedLanguage(labels), first.language); assertEquals(first.language, replay.language)
        val doc = save(replay, listOf(p)).second
        assertEquals(labels, doc.parts.single().responses!!.map { it.language })
        assertEquals(first.text, replay.text)
        }
      }
    } finally { unmockkObject(PcmDecoder, ChunkEncoder) }
  }
  @Test fun `aggregate accepts equivalent reported names and codes and preserves unknown or mixed labels`() {
    assertEquals("Italian", CloudSources.reportedLanguage(listOf("Italian", "IT", "ita")))
    assertEquals("English", CloudSources.reportedLanguage(listOf("English", "en", "eng")))
    assertNull(CloudSources.reportedLanguage(listOf("it", null)))
    assertNull(CloudSources.reportedLanguage(listOf("Italian", "English")))
  }
  @Test fun `segment-only legacy cache remains an explicitly derived presentation with missing original evidence`() = runBlocking {
    val files = files(); val a = part(files)
    val provider = provider("groq") { error("No paid rerun") }
    val signature = StoredChunks.signature(provider.id, provider.capabilities, 10)
    val directory = files.jobDir("job"); File(directory, StoredChunks.WORK_FILE).writeText(signature)
    val cached = File(directory, "part-a/whole.json").also { it.parentFile.mkdirs() }
    cached.writeText(StoredChunks.encode(ChunkTranscript(ChunkSpec(0, 0, a.durationMs), listOf(RawSegment(0, 1000, "derived legacy words")), language = "it"), signature))
    val result = TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(a), provider, TranscribeRequest("pinned", "it"), 10)
    val (body, doc) = save(result, listOf(a))
    assertEquals("derived legacy words", body)
    assertNull(doc.parts.single().text); assertNull(doc.parts.single().language)
    assertNull(doc.parts.single().responses!!.single().text)
    assertEquals("", doc.parts.single().sha256); assertEquals("", doc.parts.single().model)
    assertEquals("derived_presentation", doc.originals.single().contentScope)
    assertNull(CloudSources.bindSource(doc, listOf(a)))
    assertEquals(doc, CloudSources.decode(CloudSources.encode(doc)))
  }
  @Test fun `optional response evidence is bounded and survives public wire compaction without private state`() = runBlocking {
    val files = files(); val a = part(files)
    val text = " Exact,\tresponse!\r\n".repeat(2500)
    val provider = provider("mistral") { TranscriptResult(text, emptyList(), "Italian", null) }
    val result = TranscriptionRunner(files, mockk()).transcribeSession("private-job-never-export", listOf(a), provider, TranscribeRequest("pinned", "it"), 10)
    val (body, doc) = save(result, listOf(a))
    val encoded = CloudSources.encode(doc)
    val payload = buildJsonObject { put("text", body); put("cloudSourceJson", encoded) }
    val wire = dev.pampa.pampanotes.core.sync.SyncCloudSourceWire.toWire(payload)
    assertTrue(wire.jsonObject.getValue("cloudSourceJson").jsonPrimitive.content.startsWith("pampa-cloud-source-sync:1:"))
    val decoded = dev.pampa.pampanotes.core.sync.SyncCloudSourceWire.toLocal(wire)
    assertEquals(payload, decoded)
    assertEquals(text, CloudSources.decode(decoded.jsonObject.getValue("cloudSourceJson").jsonPrimitive.content)!!.parts.single().responses!!.single().text)
    assertFalse(encoded.contains("private-job-never-export")); assertFalse(encoded.contains("options.json")); assertFalse(encoded.contains(files.root.path))
    val p = doc.parts.single(); val response = p.responses!!.single()
    assertThrows(IllegalArgumentException::class.java) { CloudSources.encode(doc.copy(parts = listOf(p.copy(responses = listOf(response.copy(index = -1)))))) }
    assertThrows(IllegalArgumentException::class.java) { CloudSources.encode(doc.copy(parts = listOf(p.copy(responses = listOf(response, response))))) }
    val invalid = p.copy(responses = listOf(response.copy(audioSha256 = AlignmentProtocol.sha256("different"))))
    assertThrows(IllegalArgumentException::class.java) { CloudSources.encode(doc.copy(parts = listOf(invalid))) }
    // Optional additions do not force historical source evidence into legacy null/empty states.
    val raw = TranscriptEntity("legacy", "s", TranscriptKind.RAW, "groq", "pinned", null, body, wordCount = 0, createdAt = 2)
    assertNull(CloudSources.portableProjection(raw, listOf(a)).cloudSourceJson)
    assertEquals("", CloudSources.portableProjection(raw.copy(cloudSourceJson = ""), listOf(a)).cloudSourceJson)
  }
}
