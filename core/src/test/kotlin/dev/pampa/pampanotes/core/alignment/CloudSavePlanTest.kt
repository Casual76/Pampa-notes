package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.transcription.CloudPartText
import dev.pampa.pampanotes.core.transcription.SessionTranscript
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CloudSavePlanTest {
  private fun part(id: String, position: Int = 0) = AudioPartEntity(id, "s", position, "$id.wav", "$id.wav", "audio/wav", 4, 5000, AlignmentProtocol.sha256("original-$id"), 1)
  private val raw = TranscriptEntity("new-raw", "s", TranscriptKind.RAW, "mistral", "voxtral", "it", "", wordCount = 0, createdAt = 90)
  private val a = "  Esatto,\t😀!\n"; private val b = " Seconda  frase. "
  private fun result() = SessionTranscript("normalized aggregate", emptyList(), "it", "voxtral", "mistral",
    sourceParts = listOf(CloudPartText("a", a, "it"), CloudPartText("b", b, "en")))
  @Test fun `save preserves authoritative per part text full text provider model language and original hashes`() {
    val parts = listOf(part("a"), part("b", 1))
    val (body, doc) = CloudSavePlan.build(raw, listOf("a", "b"), result(), parts, parts, setOf("a", "b"), null, emptyList())
    assertEquals(a + "\n\n" + b, body)
    assertEquals("normalized aggregate", doc.originals.single().reportedText)
    assertEquals(listOf(a, b), doc.parts.map { it.text })
    assertEquals(listOf("it", "en"), doc.parts.map { it.language })
    assertEquals(parts.map { it.sha256 }, doc.originals.single().parts.map { it.sha256 })
    assertEquals("mistral", doc.originals.single().provider); assertEquals("voxtral", doc.originals.single().model)
  }
  @Test fun `same id replacement during cloud run retains old hash and rejects binding new audio`() {
    val originals = listOf(part("a"), part("b", 1))
    val replaced = originals.map { if (it.id == "a") it.copy(sha256 = AlignmentProtocol.sha256("replaced bytes")) else it }
    val (text, doc) = CloudSavePlan.build(raw, listOf("a", "b"), result(), originals, replaced, setOf("a", "b"), null, emptyList())
    assertEquals(a + "\n\n" + b, text)
    assertEquals(originals.first().sha256, doc.parts.first().sha256)
    assertNull(CloudSources.bindSource(doc, replaced))
    assertNotNull(CloudSources.bindSource(doc, originals))
  }
  @Test fun `moved deleted and reordered parts retain original full snapshot and exact texts`() {
    val originals = listOf(part("a"), part("b", 1))
    val current = listOf(originals.last().copy(sessionId = "new-session", position = 0))
    val (text, doc) = CloudSavePlan.build(raw.copy(sessionId = "new-session"), listOf("a", "b"), result(), originals, current, setOf("b"), null, emptyList())
    assertEquals(b, text); assertEquals(listOf("b"), doc.parts.map { it.id })
    assertEquals(listOf("a", "b"), doc.originals.single().parts.map { it.id })
    assertEquals(listOf(a, b), doc.originals.single().parts.map { it.text })
    assertEquals(originals.map { it.sha256 }, doc.originals.single().parts.map { it.sha256 })
    assertNotNull(CloudSources.bindSource(doc, current))
    val reordered = originals.reversed().mapIndexed { i, p -> p.copy(position = i) }
    val reorderedPlan = CloudSavePlan.build(raw, listOf("a", "b"), result(), originals, reordered, setOf("a", "b"), null, emptyList())
    assertEquals(b + "\n\n" + a, reorderedPlan.first)
    assertEquals(listOf("a", "b"), reorderedPlan.second.originals.single().parts.map { it.id })
  }
  @Test fun `absent original snapshot never rereads current rows as original and alignment defers`() {
    val current = listOf(part("a"), part("b", 1))
    val (text, doc) = CloudSavePlan.build(raw, listOf("a", "b"), result(), emptyList(), current, setOf("a", "b"), null, emptyList())
    assertEquals(a + "\n\n" + b, text)
    assertTrue(doc.parts.all { it.sha256.isEmpty() }); assertNull(CloudSources.bindSource(doc, current))
    assertEquals(doc, CloudSources.decode(CloudSources.encode(doc)))
  }
  @Test fun `mixed selected history retains original even when legacy multipart boundaries are unknown`() {
    val current = listOf(part("a"), part("b", 1), part("legacy", 2))
    val legacy = raw.copy(id = "selected-parent", provider = "groq", text = "  legacy exact  body\n", createdAt = 20)
    val coarse = listOf(SegmentEntity(transcriptId = legacy.id, partId = "legacy", indexInPart = 0, partStartMs = 0, partEndMs = 1000, sessionStartMs = 10000, sessionEndMs = 11000, text = "legacy coarse"))
    val (_, doc) = CloudSavePlan.build(raw, listOf("a", "b"), result(), current.take(2), current, setOf("a", "b"), legacy, coarse)
    assertEquals(2, doc.originals.size)
    assertEquals(legacy.text, doc.originals.single { it.rawId == legacy.id }.text)
    assertNull(doc.parts.single { it.id == "legacy" }.text)
    assertEquals(listOf(a, b), doc.parts.take(2).map { it.text })
  }
  @Test fun `replacement bytes with unchanged row hash fail rehash and traversal paths fail closed`() {
    val dir = Files.createTempDirectory("alignment-audio-fixture").toFile()
    try {
      val file = dir.resolve("a.wav"); file.writeText("original-a")
      val p = AlignmentPlacement("a", AlignmentProtocol.sha256(file), 0, 5000, "a.wav")
      assertTrue(AlignmentAudio.verify(dir, listOf(p)))
      file.writeText("replacement")
      assertFalse(AlignmentAudio.verify(dir, listOf(p)))
      try { AlignmentAudio.file(dir, "../secret"); fail("unsafe path") } catch (_: IllegalArgumentException) { }
    } finally { dir.deleteRecursively() }
  }
}
