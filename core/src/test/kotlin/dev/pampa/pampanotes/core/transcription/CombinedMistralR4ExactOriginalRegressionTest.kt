package dev.pampa.pampanotes.core.transcription

import dev.pampa.pampanotes.core.alignment.*
import dev.pampa.pampanotes.core.db.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Prepared production-path regressions for R4-F1/R5-F2; no listener or fake provider result. */
class CombinedMistralR4ExactOriginalRegressionTest {
  @get:Rule val temp = TemporaryFolder()

  @Test fun `malformed successful B never becomes exact silence and restart cannot rebill A or B`() = runBlocking {
    val invalid = listOf(null, JsonNull, JsonPrimitive(0), JsonPrimitive(true), JsonArray(emptyList()), buildJsonObject {})
      .map { CombinedMistralR4ClosureFixture.response(it) } + listOf("null", "[]", "7", "{broken-json", """{"text":"","error":null}""")
    for (body in invalid) {
      val f = CombinedMistralR4ClosureFixture(temp.newFolder()).also { it.body = body }
      val failure = runCatching { f.run() }.exceptionOrNull()
      assertTrue("$body: $failure", failure is TranscriptionError.Parse)
      assertFalse((failure as TranscriptionError.Parse).retryable)
      assertEquals(2, f.posts)
      val a = f.result("a").readBytes()
      assertEquals(CombinedMistralR4ClosureFixture.A_TEXT,
        Json.parseToJsonElement(String(a, Charsets.UTF_8)).jsonObject["sourceText"]!!.jsonPrimitive.content)
      assertFalse(f.result("b").exists())
      assertTrue(f.marker().readText().startsWith("pending:"))
      val barrier = f.marker().readBytes()
      val ledger = f.charges()
      assertEquals(2, ledger.size)
      assertTrue(ledger.all { it.refusedAt == null }) // Malformed 2xx never masquerades as a refusal.
      assertEquals("0.01", ledger.first().actualUsd)
      assertEquals("mistral", ledger.last().provider)
      if ((runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject)?.containsKey("usage") == true) {
        assertEquals("0.01", ledger.last().actualUsd) // Reported billing survives parse failure too.
      } else {
        assertNull(ledger.last().actualUsd); assertNull(ledger.last().settledAt)
        assertNotNull(ledger.last().reservedUsd)
      }
      // A hypothetical good replacement response must never be requested automatically.
      f.body = CombinedMistralR4ClosureFixture.response(JsonPrimitive("replacement must not be sent"))
      assertTrue(runCatching { f.run() }.exceptionOrNull() is IOException)
      assertEquals(2, f.posts)
      assertArrayEquals(a, f.result("a").readBytes())
      assertArrayEquals(barrier, f.marker().readBytes())
      assertEquals(ledger, f.charges())
      assertEquals(f.account, DurableCloudJobContexts(f.jobContexts).get("job")!!.accountFingerprint)
    }
  }

  @Test fun `explicit empty whitespace and untimed remainder survive real HTTP runner cache and save plan`() = runBlocking {
    for (exact in listOf("", " \t\r\n", "  Timed words.\nUntimed remainder, unchanged!  ")) {
      for (language in listOf<JsonElement>(JsonNull, JsonPrimitive(""), JsonPrimitive(" IT "))) {
        val segments = if (exact.isBlank()) JsonArray(emptyList()) else Json.parseToJsonElement(
          """[{"start":0.1,"end":1.2,"text":"Timed words.","words":[{"word":"Timed","start":0.1,"end":0.2}]}]""").jsonArray
        val f = CombinedMistralR4ClosureFixture(temp.newFolder()).also {
          it.body = CombinedMistralR4ClosureFixture.response(JsonPrimitive(exact), language, segments)
        }
        val first = f.run()
        val a = f.result("a").readBytes()
        val b = f.result("b").readBytes()
        val ledger = f.charges()
        val replay = f.run()
        assertEquals(first, replay)
        assertEquals(2, f.posts)
        assertEquals(ledger, f.charges())
        assertArrayEquals(a, f.result("a").readBytes())
        assertArrayEquals(b, f.result("b").readBytes())
        assertFalse(f.marker().exists())
        assertEquals(listOf(CombinedMistralR4ClosureFixture.A_TEXT, exact), replay.sourceParts.map { it.text })
        assertTrue(replay.sourceParts.all { it.exactTextKnown })
        assertEquals(exact.isBlank(), "b" in replay.emptyPartIds)
        val source = replay.sourceParts.single { it.partId == "b" }
        val reportedLanguage = (language as? JsonPrimitive)?.takeIf { it.isString }?.content
        assertEquals(reportedLanguage, source.responses!!.single().language)
        assertEquals(exact, source.responses!!.single().text)
        // Preserve the actual coarse interval without using it to replace the full original.
        if (exact.isBlank()) assertTrue(replay.segments.isEmpty()) else {
          val segment = replay.segments.single()
          assertEquals("b", segment.partId)
          assertEquals("Timed words.", segment.text)
          assertEquals(100L, segment.partStartMs)
          assertEquals(1200L, segment.partEndMs)
          assertNull(segment.wordsEncoded)
        }
        val draft = TranscriptEntity("raw", "session", TranscriptKind.RAW, "mistral", f.model,
          replay.language, replay.text, wordCount = 2, createdAt = 42)
        val (saved, doc) = CloudSavePlan.build(draft, f.parts.map { it.id }, replay, f.parts, f.parts,
          f.parts.map { it.id }.toSet(), null, emptyList())
        assertEquals(listOf(CombinedMistralR4ClosureFixture.A_TEXT, exact).joinToString("\n\n"), saved)
        assertEquals(exact, doc.parts.single { it.id == "b" }.text)
        assertEquals(reportedLanguage, doc.parts.single { it.id == "b" }.responses!!.single().language)
        assertEquals("complete_original", doc.originals.single().contentScope)
        assertEquals(exact, doc.originals.single().parts.single { it.id == "b" }.responses!!.single().text)
        assertNotNull(CloudSources.bindSource(doc, f.parts))
        assertEquals(doc, CloudSources.decode(CloudSources.encode(doc)))
      }
    }
  }

  @Test fun `present blank original preserves optional result fields and report publication`() {
    ServerReports.drainSince(0)
    for (exact in listOf("", " \t\r\n")) {
      val since = System.currentTimeMillis()
      val root = buildJsonObject {
        put("text", exact); put("language", " IT "); put("duration", 1.25)
        put("segments", JsonArray(emptyList()))
        put("archived", true); put("chunks", 3); put("max_minutes_used", 10)
        put("processing_s", 2.5); put("audio_s", 1.25); put("device_used", " cpu ")
      }
      val parsed = MistralTranscriptionProvider.parseResponse(root)
      assertEquals(exact, parsed.text); assertEquals(" IT ", parsed.language)
      assertEquals(1250L, parsed.durationMs)
      assertTrue(parsed.archived); assertEquals(3, parsed.serverChunks); assertEquals(10, parsed.maxMinutesUsed)
      assertEquals(listOf(ServerReport(2500, 1250, "cpu")), ServerReports.drainSince(since))
    }
  }

  @Test fun `segments cannot invent an original or precise word intervals`() {
    val segment = Json.parseToJsonElement("""[{"start":0.1,"end":1.2,"text":"Ciao mondo","words":[{"word":"Ciao","start":0.1,"end":0.2}]}]""").jsonArray
    val root = Json.parseToJsonElement(CombinedMistralR4ClosureFixture.response(JsonPrimitive(" Ciao mondo "), segments = segment)).jsonObject
    val parsed = MistralTranscriptionProvider.parseResponse(root)
    assertEquals(" Ciao mondo ", parsed.text)
    assertEquals(100L, parsed.segments.single().startMs); assertEquals(1200L, parsed.segments.single().endMs)
    assertTrue(parsed.segments.single().words.isEmpty())
    val noOriginal = JsonObject(root - "text")
    assertTrue(runCatching { MistralTranscriptionProvider.parseResponse(noOriginal) }.exceptionOrNull() is TranscriptionError.Parse)
    val onlyUnexpectedWords = JsonObject(root - "segments" + ("words" to Json.parseToJsonElement("""[{"word":"Ciao","start":0.1,"end":0.2}]""")))
    assertTrue(MistralTranscriptionProvider.parseResponse(onlyUnexpectedWords).segments.isEmpty())
    assertTrue(runCatching { VerboseJson.parse(buildJsonObject { put("text", " \t\n") }) }.exceptionOrNull() is TranscriptionError.NoSpeech)
  }
}
