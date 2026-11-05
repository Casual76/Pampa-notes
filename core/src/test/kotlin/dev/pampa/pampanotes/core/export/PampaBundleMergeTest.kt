package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.sync.NotePayload
import dev.pampa.pampanotes.core.sync.SyncCodec
import dev.pampa.pampanotes.core.transcription.VoiceNames
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files

class PampaBundleMergeTest {
  private val note = NoteEntity("n", "f", "Title", "Base\nMiddle\nEnd", createdAt = 1, updatedAt = 1)
  private val folder = FolderEntity("f", "Subject", createdAt = 1, updatedAt = 1)
  private fun session(id: String, position: Int) = SessionEntity(id, "n", date = "2026-11-05", position = position, activeTranscriptId = "raw-$id", createdAt = 1, updatedAt = 1)
  private fun part(id: String, session: String, position: Int) = AudioPartEntity(id, session, position, "$id.m4a", "$id.m4a", "audio/mp4", 0, 1000, "", 1)
  private fun raw(s: String) = TranscriptEntity("raw-$s", s, TranscriptKind.RAW, "custom", "test", text = "", wordCount = 0, createdAt = 1)
  private fun graph(vararg pairs: Pair<String, List<String>>): PampaBundleNote {
    val sessions = pairs.mapIndexed { index, (id, _) -> session(id, index) }
    val parts = pairs.flatMap { (s, ids) -> ids.mapIndexed { index, id -> part(id, s, index) } }
    val segments = parts.map { p -> SegmentEntity(transcriptId = "raw-${p.sessionId}", partId = p.id, indexInPart = 0, partStartMs = 100, partEndMs = 800,
      sessionStartMs = p.position * 1000L + 100, sessionEndMs = p.position * 1000L + 800, text = p.id, wordsJson = "0,700,${p.id}", speaker = "SPEAKER_00") }
    val transcripts = sessions.map { s -> raw(s.id).copy(text = parts.filter { it.sessionId == s.id }.joinToString(" ") { it.id }) }
    return PampaBundleNote(note, sessions = sessions, parts = parts, transcripts = transcripts, segments = segments,
      attachments = parts.map { PampaBundleAttachment(it.id, true) })
  }

  /** A real writer->reader accepts the final graph, not just matching test-side arithmetic. */
  private fun reexport(input: PampaBundleNote): PampaBundleNote {
    val directory = Files.createTempDirectory("pampa-bundle-merge").toFile()
    try {
      val sessions = input.sessions.mapIndexed { index, s ->
        val trans = input.transcripts.firstOrNull { it.id == s.activeTranscriptId }
        val raw = if (trans?.kind == TranscriptKind.RAW) trans else input.transcripts.firstOrNull { it.id == trans?.parentId }
        val parts = input.parts.filter { it.sessionId == s.id }.sortedBy { it.position }
        var offset = 0L
        ExportSession(s.id, index + 1, s.title, s.date, parts.map { p -> ExportPart(p.id, p.originalName, p.fileName, p.durationMs, offset, p.sizeBytes).also { offset += p.durationMs } }, trans, raw,
          input.segments.filter { it.transcriptId == raw?.id }, VoiceNames.decode(s.voiceNames))
      }
      val export = ExportNote(input.note, listOf("Subject"), input.tags, emptyList(), sessions, native = input)
      val file = File(directory, "bundle.zip")
      file.outputStream().use { BundleWriter(directory, directory).write(ExportSet("Subject", "subject", listOf(export), "Pampa Notes test", 1, folders = listOf(folder)), ExportOptions(), it) }
      return PampaBundleArchive.read(file).data.notes.single()
    } finally { directory.deleteRecursively() }
  }

  @Test fun `stable IDs survive multihop and return to author while copies get independent IDs`() {
    val original = graph("s" to listOf("A"))
    val data = PampaBundleData(folders = listOf(folder), notes = listOf(original))
    val first = PampaBundleImporter.remap(data, "origin").notes.single()
    val second = PampaBundleImporter.remap(data.copy(notes = listOf(reexport(first))), "origin").notes.single()
    val returned = PampaBundleMerge.merge(original, reexport(second))
    assertEquals("n", returned.note.id)
    assertEquals(listOf("A"), returned.parts.map { it.id })
    assertEquals(listOf("raw-s"), returned.transcripts.map { it.id })
    assertEquals(note.body, returned.note.body)
    val copy = PampaBundleImporter.remap(data, "copy").notes.single()
    assertNotEquals("n", copy.note.id)
    assertEquals(copy.note.id, copy.sessions.single().noteId)
    assertEquals(copy.parts.single().id, copy.segments.single().partId)
  }

  @Test fun `competing appends keep local parts with unique positions and rebase every raw version`() {
    val local = graph("s" to listOf("A", "L"), "local-session" to listOf("Z"))
    val incoming = graph("s" to listOf("A", "B"), "new-session" to listOf("N"))
    val merged = PampaBundleMerge.merge(local, incoming)
    assertEquals(listOf("s", "new-session", "local-session"), merged.sessions.map { it.id })
    assertEquals(listOf(0, 1, 2), merged.sessions.map { it.position })
    assertEquals(listOf("A", "B", "L"), merged.parts.filter { it.sessionId == "s" }.map { it.id })
    assertEquals(listOf(0, 1, 2), merged.parts.filter { it.sessionId == "s" }.map { it.position })
    assertEquals(2100L, merged.segments.single { it.partId == "L" }.sessionStartMs)
    assertEquals("A B L", merged.transcripts.single { it.id == "raw-s" }.text)
    assertEquals("A B L", reexport(merged).transcripts.single { it.id == "raw-s" }.text)
    val again = PampaBundleMerge.merge(merged, incoming)
    assertEquals(merged, again)
  }

  @Test fun `split and merge moves same-note audio and keeps all version references and voice keys valid`() {
    val original = graph("s1" to listOf("A", "B"))
    val local = original.copy(sessions = original.sessions.map { it.copy(voiceNames = VoiceNames.encode(mapOf("B|SPEAKER_00" to "Teacher"))) })
    val split = PampaBundleMerge.merge(local, graph("s1" to listOf("A"), "s2" to listOf("B")))
    assertEquals("s2", split.parts.single { it.id == "B" }.sessionId)
    assertEquals("Teacher", VoiceNames.decode(split.sessions.single { it.id == "s2" }.voiceNames)["B|SPEAKER_00"])
    assertTrue(VoiceNames.decode(split.sessions.single { it.id == "s1" }.voiceNames).isEmpty())
    assertEquals("A", split.transcripts.single { it.id == "raw-s1" }.text)
    assertEquals("B", reexport(split).transcripts.single { it.id == "raw-s2" }.text)
    val combined = PampaBundleMerge.merge(split, graph("s1" to listOf("A", "B")))
    assertEquals(listOf("A", "B"), combined.parts.filter { it.sessionId == "s1" }.map { it.id })
    assertTrue(combined.segments.all { seg -> combined.transcripts.single { it.id == seg.transcriptId }.sessionId == combined.parts.single { it.id == seg.partId }.sessionId })
    assertEquals("A B", reexport(combined).transcripts.single { it.id == "raw-s1" }.text)
  }

  @Test fun `moved stable raw carries dependent refinement and incompatible timed refinement fails safely`() {
    val initial = graph("s1" to listOf("A", "B"))
    val refined = initial.transcripts.single().copy(id = "refined", kind = TranscriptKind.REFINED, parentId = "raw-s1", text = "Local refinement")
    val local = initial.copy(transcripts = initial.transcripts + refined)
    val incoming = graph("s2" to listOf("A", "B")).let { g -> g.copy(transcripts = g.transcripts.map { it.copy(id = "raw-s1") },
      segments = g.segments.map { it.copy(transcriptId = "raw-s1") }, sessions = g.sessions.map { it.copy(activeTranscriptId = "raw-s1") }) }
    val merged = PampaBundleMerge.merge(local, incoming)
    assertEquals("s2", merged.transcripts.single { it.id == "refined" }.sessionId)
    assertEquals("raw-s1", merged.transcripts.single { it.id == "refined" }.parentId)
    reexport(merged)
    val timed = local.copy(segments = local.segments + local.segments.first().copy(transcriptId = refined.id))
    try {
      PampaBundleMerge.merge(timed, graph("s1" to listOf("B"), "s2" to listOf("A")))
      fail("Expected incompatible timed refinement conflict")
    } catch (_: IllegalArgumentException) { }
  }

  @Test fun `moving audio without a fresh transcript adopts local words into a valid target raw`() {
    val local = graph("s1" to listOf("A", "B"))
    val incoming = graph("s2" to listOf("A")).let { it.copy(transcripts = emptyList(), segments = emptyList(), sessions = it.sessions.map { s -> s.copy(activeTranscriptId = null) }) }
    val merged = PampaBundleMerge.merge(local, incoming)
    assertEquals(listOf("A", "B"), merged.parts.map { it.id })
    assertEquals("B", merged.transcripts.single { it.id == "raw-s1" }.text)
    val target = merged.sessions.single { it.id == "s2" }.activeTranscriptId
    assertNotNull(target)
    assertEquals("A", merged.transcripts.single { it.id == target }.text)
    assertEquals(100L, merged.segments.single { it.partId == "B" }.sessionStartMs)
    assertEquals("A", reexport(merged).transcripts.single { it.id == target }.text)
    assertEquals(merged, PampaBundleMerge.merge(merged, incoming))
  }

  @Test fun `body merge preserves manual or PDF appends and repeated imports are idempotent`() {
    val base = note.body
    val first = PampaBundleMerge.merge(null, graph("s" to listOf("A")))
    val local = first.copy(note = first.note.copy(body = "$base\nPDF appendix"))
    val incoming = first.copy(note = note.copy(body = "Base corrected\nMiddle\nEnd"))
    val merged = PampaBundleMerge.merge(local, incoming)
    assertEquals("Base corrected\nMiddle\nEnd\nPDF appendix", merged.note.body)
    assertEquals(incoming.note.body, merged.note.bundleBodyBase)
    assertEquals(merged.note.body, PampaBundleMerge.merge(merged, incoming).note.body)
    val conflict = PampaBundleBodyMerge.merge(base, "Local rewrite", "Remote rewrite")
    assertTrue(conflict.contains("Local rewrite"))
    assertTrue(conflict.contains("Remote rewrite"))
    assertEquals(conflict, PampaBundleBodyMerge.merge("Remote rewrite", conflict, "Remote rewrite"))
    assertTrue(PampaBundleBodyMerge.merge(null, "Author edit", "Returned edit").contains("Author edit"))
    assertTrue(PampaBundleBodyMerge.merge(base, "", "Changed remotely").startsWith("<<<<<<<"))
  }

  @Test fun `baseline survives account sync without changing old null hashes or leaking via export`() {
    val withBase = note.copy(bundleBodyBase = "PRIVATE BASELINE")
    val encoded = SyncCodec.json.encodeToJsonElement(NotePayload.serializer(), NotePayload(withBase))
    assertEquals(withBase, SyncCodec.json.decodeFromJsonElement(NotePayload.serializer(), encoded).note)
    val plain = SyncCodec.json.encodeToJsonElement(NotePayload.serializer(), NotePayload(note))
    val old = JsonObject(plain.jsonObject + ("note" to JsonObject(plain.jsonObject.getValue("note").jsonObject - "bundleBodyBase")))
    assertEquals(SyncCodec.hash(old), SyncCodec.hash(plain))
    assertNotEquals(SyncCodec.hash(plain), SyncCodec.hash(encoded))
    assertNull(reexport(graph("s" to listOf("A")).copy(note = withBase)).note.bundleBodyBase)
  }
}
