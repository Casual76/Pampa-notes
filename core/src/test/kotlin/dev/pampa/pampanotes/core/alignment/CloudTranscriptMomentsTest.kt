package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import org.junit.Assert.*
import org.junit.Test

class CloudTranscriptMomentsTest {
  private val part = AudioPartEntity("p", "s", 0, "p.wav", "p.wav", "audio/wav", 1, 3000, "a".repeat(64), 1)
  private val raw = TranscriptEntity("r", "s", TranscriptKind.RAW, "mistral", "voxtral-mini-transcribe-3",
    language = "it", text = "  Ciao,\tmondo!\n", wordCount = 2, createdAt = 2)

  private fun measured(): TranscriptEntity {
    val source = CloudTextPart(part.id, part.sha256, raw.text, "it", raw.provider, raw.model)
    val spans = AlignmentProtocol.tokens(raw.text)
    val timings = spans.mapIndexed { index, span -> AlignmentTiming(span.first, span.last + 1,
      if (index == 0) 250 else null, if (index == 0) 800 else null,
      if (index == 0) "measured" else "missing") }
    val doc = CloudSources.create(raw, raw.text, listOf(source)).copy(
      overlays = listOf(CloudOverlay(part.id, AlignmentProtocol.sha256(raw.text), "it", timings)))
    return raw.copy(cloudSourceJson = CloudSources.encode(doc))
  }

  @Test fun `exact normalized search uses original offsets for measured token only`() {
    assertEquals(250L, CloudTranscriptMoments.moment(measured(), listOf(part), "ciao")?.timeMs)
    assertNull(CloudTranscriptMoments.moment(measured(), listOf(part), "mondo"))
  }

  @Test fun `legacy cloud text has no interpolated global-search moment`() {
    assertTrue(CloudTranscriptMoments.usesCloudEvidence(raw))
    assertNull(CloudTranscriptMoments.moment(raw, listOf(part), "Ciao"))
  }

  @Test fun `changed text or audio invalidates an old search moment`() {
    assertNull(CloudTranscriptMoments.moment(measured().copy(text = "Ciao, changed"), listOf(part), "Ciao"))
    assertNull(CloudTranscriptMoments.moment(measured(), listOf(part.copy(sha256 = "b".repeat(64))), "Ciao"))
  }

  @Test fun `FTS separate terms choose source order without borrowing missing evidence`() {
    val moment = CloudTranscriptMoments.moment(measured(), listOf(part), "mondo Ciao")
    assertEquals(250L, moment?.timeMs)
    assertEquals("Ciao", moment?.query)
  }

  @Test fun `computer-only legacy uses its existing timing path`() {
    assertFalse(CloudTranscriptMoments.usesCloudEvidence(raw.copy(provider = "custom")))
    assertTrue(CloudTranscriptMoments.usesCloudEvidence(measured().copy(provider = "mixed")))
  }
}
