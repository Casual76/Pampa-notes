package dev.pampa.pampanotes.core.alignment

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.importing.*
import org.junit.Assert.*
import org.junit.Test

class LegacyCloudPortableProjectionRegressionTest {
  private val text = "  Ciao,\tmondo!\r\n"
  private val part = AudioPartEntity("a", "s", 0, "a.wav", "a.wav", "audio/wav", 1, 3000, AlignmentProtocol.sha256("current audio"), 1)
  private val legacy = TranscriptEntity("r", "s", TranscriptKind.RAW, "groq", "whisper", "Italian", text, wordCount = 2, createdAt = 42)
  private fun discovered(parts: List<AudioPartEntity>) = legacy.copy(cloudSourceJson = CloudSources.encode(CloudSources.legacy(legacy, parts)!!))

  @Test fun `single part discovery exact session export and import remap keep unknown hash and never bind alignment`() {
    val raw = discovered(listOf(part))
    val doc = CloudSources.decode(raw.cloudSourceJson)!!
    assertEquals("", doc.parts.single().sha256)
    assertNull(CloudSources.bindSource(doc, listOf(part)))
    for (whole in listOf(false, true)) {
      val p = CloudSources.portableProjection(raw, listOf(part), retainOriginals = whole)
      assertEquals(text, p.text)
      assertFalse(p.alignmentApplicable)
      val exported = raw.copy(text = p.text, cloudSourceJson = p.cloudSourceJson)
      assertTrue(CloudSources.validatePortable(exported, listOf(part)))
      val note = NoteEntity("n", "f", "Legacy", "Associated body", createdAt = 1, updatedAt = 2)
      val graph = PampaBundleNote(note, sessions = listOf(SessionEntity("s", "n", date = "2026-11-08", position = 0,
        activeTranscriptId = "r", createdAt = 1, updatedAt = 2)), parts = listOf(part), transcripts = listOf(exported))
      val data = PampaBundleData(folders = listOf(FolderEntity("f", "Recordings", createdAt = 1, updatedAt = 2)), notes = listOf(graph))
      val imported = PampaBundleImporter.remap(data, "copy").notes.single()
      val after = imported.transcripts.single()
      assertEquals(text, after.text)
      assertTrue(CloudSources.validatePortable(after, imported.parts))
      val evidence = CloudSources.decode(after.cloudSourceJson)!!
      assertEquals("portable_unbound", evidence.alignmentBinding)
      assertEquals("", evidence.parts.single().sha256)
      assertEquals("", evidence.originals.single().parts.single().sha256)
      assertEquals(text, evidence.originals.single().text)
      assertTrue(evidence.overlays.isEmpty())
      assertNull(CloudSources.bindSource(evidence, imported.parts))
      assertNull(AlignmentPresentation.forRaw(after, imported.parts))
    }
    assertNull(legacy.cloudSourceJson)
  }

  @Test fun `known historical mismatch still fails even if current projection hash is unknown`() {
    val raw = discovered(listOf(part))
    val doc = CloudSources.decode(raw.cloudSourceJson)!!
    val conflicting = raw.copy(cloudSourceJson = CloudSources.encode(doc.copy(originals = doc.originals.map { original ->
      original.copy(parts = original.parts.map { it.copy(sha256 = AlignmentProtocol.sha256("different historical audio")) })
    })))
    assertThrows(IllegalArgumentException::class.java) { CloudSources.portableProjection(conflicting, listOf(part)) }
  }

  @Test fun `unknown multipart boundary is preserved whole and rejected for either selected subset`() {
    val parts = listOf(part, part.copy(id = "b", position = 1))
    val raw = discovered(parts)
    for (selected in listOf(parts.take(1), parts.drop(1))) {
      assertThrows(IllegalArgumentException::class.java) { CloudSources.portableProjection(raw, selected) }
    }
    val p = CloudSources.portableProjection(raw, parts)
    assertEquals(text, p.text)
    assertTrue(CloudSources.validatePortable(raw.copy(cloudSourceJson = p.cloudSourceJson), parts))
    assertTrue(CloudSources.decode(p.cloudSourceJson)!!.parts.all { it.text == null && it.sha256.isEmpty() })
  }
}
