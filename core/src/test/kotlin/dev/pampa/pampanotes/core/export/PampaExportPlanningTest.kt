package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.importing.PampaBundleNote
import org.junit.Assert.*
import org.junit.Test

class PampaExportPlanningTest {
  @Test fun pampaAlwaysIncludesAttachmentsAndKeepsAiPreferencesForLater() {
    val raw = ExportTarget.CHAT.defaults().copy(transcript = TranscriptChoice.RAW, timestamps = false)
    val pn = raw.withTarget(ExportTarget.PAMPA)
    assertEquals(ExportFormat.PAMPA, pn.format)
    assertTrue(pn.carriesAttachments)
    assertTrue(pn.wantsAudio)
    assertTrue(pn.wantsSources)
    assertFalse(pn.includeSkill)
    assertFalse(pn.isCustomized)
    val modified = pn.copy(includeAudio = false, includeSources = false, includeSkill = true)
    assertEquals(ExportFiles.wanted(fixture(), pn), ExportFiles.wanted(fixture(), modified))
    assertEquals(pn, ExportOptionsCodec.decode(ExportOptionsCodec.encode(pn)))
    assertEquals(TranscriptChoice.RAW, pn.withTarget(ExportTarget.PROJECT).transcript)
    assertFalse(pn.withTarget(ExportTarget.PROJECT).timestamps)
    assertEquals(ExportTarget.PAMPA, ExportOptionsCodec.decode("""{"format":"PAMPA"}""").target)
    assertEquals(ExportFormat.PAMPA, ExportOptionsCodec.decode("""{"target":"PAMPA"}""").format)
  }

  @Test fun nativeGraphPlansEveryOriginalAndDerivedSourceEvenWithoutAnAiPage() {
    val wanted = ExportFiles.wanted(fixture(), ExportTarget.PAMPA.defaults())
    assertEquals(listOf("part", "source", "page", "unavailable"), wanted.map { it.id })
    assertEquals(listOf(ExportFileKind.AUDIO, ExportFileKind.SOURCE, ExportFileKind.PAGE, ExportFileKind.SOURCE), wanted.map { it.kind })
    // Original and derived source have identical content hashes, but distinct entity IDs.
    assertEquals(2, wanted.count { it.fileName in setOf("source.bin", "page.png") })
    assertTrue(wanted.first { it.id == "page" }.archived)
    val plan = ExportFiles.plan(fixture(), ExportTarget.PAMPA.defaults()) { false }
    assertEquals(listOf("part", "source", "page"), plan.toFetch.map { it.id })
    assertEquals(MissingReason.NO_FILE, plan.unobtainable.single().reason)
    assertEquals("unavailable", plan.unobtainable.single().file.id)
  }

  @Test fun missingReferencesCannotBeMistakenForAPresentDirectory() {
    val plan = ExportFiles.plan(fixture(), ExportTarget.PAMPA.defaults()) { true }
    assertEquals(listOf("unavailable"), plan.unobtainable.map { it.file.id })
    assertEquals(MissingReason.NO_FILE, plan.unobtainable.single().reason)
  }

  @Test fun estimateCountsAllHistoriesAndTimingsWithoutAiRulesOrTokens() {
    val set = fixture()
    val pn = ExportTarget.PAMPA.defaults()
    val estimate = ExportEstimator.estimate(set, pn)
    val native = set.notes.single().native!!
    val reduced = set.copy(notes = set.notes.map { it.copy(native = native.copy(transcripts = native.transcripts.take(1), segments = emptyList())) })
    assertTrue(estimate.bytes > ExportEstimator.estimate(reduced, pn).bytes)
    assertEquals(64L, estimate.audioBytes)
    assertEquals(128L, estimate.sourceBytes)
    assertEquals(1, estimate.pages)
    assertEquals(0L, estimate.tokens)
    assertEquals(estimate, ExportEstimator.estimate(set, pn.copy(transcript = TranscriptChoice.RAW, timestamps = false,
      includeAudio = false, includeSources = false, includeSkill = true)))
    assertTrue(ExportEstimator.warnings(estimate, pn).isEmpty())
    // Estimating must not synthesize timing for OpenRouter's untimed history.
    assertEquals(listOf("timed"), native.segments.map { it.transcriptId })
    assertEquals("refined", native.sessions.single().activeTranscriptId)
    assertEquals(456L, native.transcripts.last().createdAt)
  }

  private fun fixture(): ExportSet {
    val note = NoteEntity("note", "folder", "Title", "Notes", createdAt = 1, updatedAt = 2)
    val session = SessionEntity("session", note.id, date = "2026-11-08", position = 0,
      activeTranscriptId = "refined", createdAt = 12, updatedAt = 34, voiceNames = "{\"part:speaker\":\"Ada\"}")
    val part = AudioPartEntity("part", session.id, 0, "part.m4a", "Audio", "audio/mp4", 64, 1000, "a".repeat(64), 12)
    val raw = TranscriptEntity("timed", session.id, TranscriptKind.RAW, "custom", "whisper", text = "Timed speech", wordCount = 2, createdAt = 123)
    val untimed = raw.copy(id = "untimed", provider = "openrouter", text = "Untimed text", createdAt = 234)
    val refined = raw.copy(id = "refined", kind = TranscriptKind.REFINED, parentId = raw.id, text = "Refined text", createdAt = 456)
    val segment = SegmentEntity(transcriptId = raw.id, partId = part.id, indexInPart = 0,
      partStartMs = 100, partEndMs = 800, sessionStartMs = 100, sessionEndMs = 800, text = raw.text,
      wordsJson = "100,300,Timed\n300,800,speech", wordsEstimated = true, speaker = "speaker")
    val source = SourceEntity("source", note.id, SourceKind.PDF, "Original", "application/pdf", 128, "b".repeat(64),
      storedFileName = "source.bin", importedAt = 56)
    val page = source.copy(id = "page", originalName = "Page", storedFileName = "page.png", derivedFromId = source.id)
    val unavailable = source.copy(id = "unavailable", originalName = "Metadata only", storedFileName = null, sizeBytes = 0)
    val sources = listOf(source, page, unavailable)
    val exported = ExportNote(note, listOf("Folder"), listOf("tag"), emptyList(), listOf(
      ExportSession(session.id, 1, "", session.date,
        listOf(ExportPart(part.id, part.originalName, part.fileName, part.durationMs, 0, part.sizeBytes, archived = true)),
        refined, raw, listOf(segment))),
      // AI presentation omits unavailable pages; PN still has their metadata and fetch references.
      handwriting = emptyList(),
      native = PampaBundleNote(note, listOf("tag"), listOf(session), listOf(part), listOf(raw, untimed, refined), listOf(segment), sources),
      sourceFiles = sources.map { ExportSource(it.originalName, it.kind, it.sha256, it.sizeBytes, it.storedFileName, it.status, 0, it.id, archived = true) })
    return ExportSet("Title", "title", listOf(exported), "test", 123)
  }
}
