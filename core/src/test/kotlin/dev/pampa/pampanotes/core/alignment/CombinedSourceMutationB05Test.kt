package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.transcription.CloudResponseText
import dev.pampa.pampanotes.core.transcription.SessionTranscript
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CombinedSourceMutationB05Test {
  private val text = "  Exact,\tcloud!\r\n"
  private val audioHash = AlignmentProtocol.sha256("synthetic audio")
  private fun raw(): TranscriptEntity {
    val raw = TranscriptEntity("r", "s", TranscriptKind.RAW, "openrouter", "pinned", "Italian", text, wordCount = 2, createdAt = 42)
    return raw.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(raw, text,
      listOf(CloudTextPart("a", audioHash, text, "Italian", "openrouter", "pinned",
        listOf(CloudResponseText(0, 0, 3000, text, "Italian", audioHash, "openrouter", "pinned")))))))
  }
  private fun snapshot(raw: TranscriptEntity): AlignmentSnapshot {
    val request = AlignmentRequest(source = AlignmentSource(raw.id, 42, AlignmentProtocol.sha256(text), listOf(AlignmentPart("a", audioHash))),
      partId = "a", text = text, textSha256 = AlignmentProtocol.sha256(text), language = "it", segments = emptyList())
    return AlignmentSnapshot("s", "n", "f", "refined-selected", "refined-selected", "selected-text-hash", raw.language,
      "owner", "privacy", "source", listOf(AlignmentPlacement("a", audioHash, 0, 3000, "a.fixture")), request)
  }
  private fun response(snapshot: AlignmentSnapshot) = AlignmentResponse(1, snapshot.request.source, "a", text,
    AlignmentProtocol.sha256(text), "it", "utf16", "ms", audioHash, "fresh-job-42", "instance", "partial",
    AlignmentProtocol.tokens(text).mapIndexed { i, span -> AlignmentTiming(span.first, span.last + 1,
      if (i == 0) 100 else null, if (i == 0) 500 else null, if (i == 0) "measured" else "missing") })

  @Test fun `unknown root part original response overlay and timing fields defer without rewriting RAW bytes or selection`() {
    val raw = raw(); val base = Json.parseToJsonElement(raw.cloudSourceJson!!).jsonObject
    val extra = "futureEvidence" to JsonPrimitive("IMMUTABLE_PUBLIC_CONTENT")
    val part = base.getValue("parts").jsonArray.single().jsonObject
    val original = base.getValue("originals").jsonArray.single().jsonObject
    val expected = snapshot(raw)
    val applied = AlignmentApplication.plan(expected, expected, raw, response(expected), "owner", false, "fresh-job-42")!!
    val aligned = Json.parseToJsonElement(applied.cloudSourceJson!!).jsonObject
    val overlay = aligned.getValue("overlays").jsonArray.single().jsonObject
    val timing = overlay.getValue("timings").jsonArray.first().jsonObject
    fun replace(root: JsonObject, key: String, item: JsonObject) = JsonObject(root + (key to JsonArray(listOf(item))))
    val responseJson = part.getValue("responses").jsonArray.single().jsonObject
    val changedTiming = JsonArray(listOf(JsonObject(timing + extra)) + overlay.getValue("timings").jsonArray.drop(1))
    val variants = listOf(
      JsonObject(base + extra),
      replace(base, "parts", JsonObject(part + extra)),
      replace(base, "originals", JsonObject(original + extra)),
      replace(base, "parts", replace(part, "responses", JsonObject(responseJson + extra))),
      replace(aligned, "overlays", JsonObject(overlay + extra)),
      replace(aligned, "overlays", JsonObject(overlay + ("timings" to changedTiming)))
    )
    for (value in variants) {
      val bytes = " \n$value\t"
      val future = raw.copy(cloudSourceJson = bytes)
      assertNull(CloudSources.decode(bytes))
      assertNull(AlignmentApplication.plan(expected, expected, future, response(expected), "owner", false, "fresh-job-42"))
      assertEquals(raw.text, future.text); assertEquals(bytes, future.cloudSourceJson)
      assertEquals("refined-selected", expected.activeTranscriptId)
    }
  }
  @Test fun `save planning cannot replace unsupported selected source with reconstructed legacy metadata`() {
    val before = raw()
    val bytes = before.cloudSourceJson!!.dropLast(1) + ",\"futureEvidence\":\"EXACT_PUBLIC_CONTENT\"}"
    val selected = before.copy(cloudSourceJson = bytes)
    val audio = AudioPartEntity("a", "s", 0, "a.fixture", "a.fixture", "audio/fixture", 2, 3000, audioHash, 1)
    val result = SessionTranscript("new result", emptyList(), "Italian", "pinned", "openrouter")
    assertThrows(IllegalArgumentException::class.java) {
      CloudSavePlan.build(before.copy(id = "new"), listOf("a"), result, listOf(audio), listOf(audio), setOf("a"), selected, emptyList())
    }
    assertEquals(bytes, selected.cloudSourceJson); assertEquals(before.text, selected.text)
  }
  @Test fun `supported measured overlay positive control leaves all original data unchanged`() {
    val raw = raw(); val expected = snapshot(raw)
    val updated = AlignmentApplication.plan(expected, expected, raw, response(expected), "owner", false, "fresh-job-42")!!
    assertEquals(raw, updated.copy(cloudSourceJson = raw.cloudSourceJson))
    val before = CloudSources.decode(raw.cloudSourceJson)!!; val after = CloudSources.decode(updated.cloudSourceJson)!!
    assertEquals(before, after.copy(overlays = emptyList()))
    assertEquals(100L, after.overlays.single().timings.first().startMs)
    assertNull(after.overlays.single().timings.last().startMs)
    val derived = raw.copy(cloudSourceJson = CloudSources.encode(before.copy(originals = before.originals.map { it.copy(contentScope = "derived_presentation") })))
    assertNull(AlignmentApplication.plan(expected, expected, derived, response(expected), "owner", false, "fresh-job-42"))
  }
}
