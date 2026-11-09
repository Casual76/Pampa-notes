package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.transcription.*
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PampaProviderWordTimingTest {
  @get:Rule val temp = TemporaryFolder()
  private val audio by lazy { temp.newFolder("audio") }
  private val sources by lazy { temp.newFolder("sources") }

  /** Actual provider parser and session assembly, including measured words across coarse bounds. */
  private fun graph(id: String): PampaBundleNote {
    val file = File(audio, "$id.wav").also { it.writeText("original audio $id") }
    val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    val note = NoteEntity("note-$id", "folder-$id", "Lesson $id", "Original notes", createdAt = 1, updatedAt = 1)
    val part = AudioPartEntity("part-$id", "session-$id", 0, file.name, file.name, "audio/wav", file.length(), 1000, hash, 1)
    val parsed = VerboseJson.parse(Json.parseToJsonElement("""{"text":"Hello world","language":"en","segments":[{"start":0.1,"end":0.8,"text":"Hello world","words":[{"start":0.05,"end":0.4,"word":"Hello"},{"start":0.4,"end":0.85,"word":"world"}]}]}"""))
    val stitched = parsed.segments.map { StitchedSegment(it.startMs, it.endMs, it.text, it.noSpeechProb, it.avgLogProb, 0, it.words, it.speaker) }
    val result = SessionAssembler.assemble(listOf(PartTranscript(part, parsed.text, stitched, parsed.language)), "custom", "whisperx")
    val raw = TranscriptEntity("raw-$id", part.sessionId, TranscriptKind.RAW, "custom", "whisperx", "en", result.text, wordCount = 2, createdAt = 2)
    val refined = raw.copy(id = "refined-$id", kind = TranscriptKind.REFINED, parentId = raw.id, text = "Hello, world!", createdAt = 3)
    val session = SessionEntity(part.sessionId, note.id, date = "2026-11-09", position = 0, activeTranscriptId = refined.id, createdAt = 1, updatedAt = 3)
    val segments = result.segments.map { SegmentEntity(transcriptId = raw.id, partId = it.partId, indexInPart = it.indexInPart,
      partStartMs = it.partStartMs, partEndMs = it.partEndMs, sessionStartMs = it.sessionStartMs, sessionEndMs = it.sessionEndMs,
      text = it.text, wordsJson = it.wordsEncoded, wordsEstimated = it.wordsEstimated, speaker = it.speaker) }
    return PampaBundleNote(note, sessions = listOf(session), parts = listOf(part), transcripts = listOf(raw, refined), segments = segments)
  }

  private fun write(notes: List<PampaBundleNote>): File {
    val folders = notes.map { FolderEntity(it.note.folderId, "Subject ${it.note.id}", createdAt = 1, updatedAt = 1) }
    val set = ExportSet("Selected notes", "selected", notes.map { ExportNote(it.note, listOf("Subject"), emptyList(), emptyList(), emptyList(), native = it) },
      "Pampa Notes test", 1, folders = folders)
    return temp.newFile().also { file -> file.outputStream().use { PampaNoteWriter(audio, sources).write(set, it) } }
  }

  @Test fun `provider words outside coarse segment roundtrip unchanged with full histories and selection`() {
    val original = graph("one")
    val segment = original.segments.single()
    assertEquals("-50,300,Hello\n300,750,world", segment.wordsJson)
    assertFalse(segment.wordsEstimated)
    val read = PampaBundleArchive.read(write(listOf(original))).data.notes.single()
    assertEquals(segment, read.segments.single())
    assertEquals(original.transcripts, read.transcripts)
    assertEquals(original.sessions.single().activeTranscriptId, read.sessions.single().activeTranscriptId)
    assertEquals(original.parts.single().sha256, read.parts.single().sha256)
  }

  @Test fun `two selected subjects preserve both notes and independent measured words`() {
    val originals = listOf(graph("one"), graph("two"))
    val read = PampaBundleArchive.read(write(originals)).data
    assertEquals(originals.map { it.note }, read.notes.map { it.note })
    assertEquals(originals.flatMap { it.segments }, read.notes.flatMap { it.segments })
    assertEquals(2, read.folders.size)
  }

  @Test fun `word observations on audio boundaries remain exact`() {
    val original = graph("edges")
    val segment = original.segments.single().copy(wordsJson = "-100,900,whole")
    val read = PampaBundleArchive.read(write(listOf(original.copy(segments = listOf(segment))))).data.notes.single()
    assertEquals(segment.wordsJson, read.segments.single().wordsJson)
  }

  @Test fun `negative absolute reversed out of audio malformed and overflow word timings remain rejected`() {
    val original = graph("invalid")
    for (encoded in listOf("-101,0,early", "0,901,late", "100,99,reversed", "bad,100,broken", "${Long.MIN_VALUE},0,overflow", "0,${Long.MAX_VALUE},overflow")) {
      val changed = original.copy(segments = original.segments.map { it.copy(wordsJson = encoded) })
      val error = assertThrows(IllegalArgumentException::class.java) { write(listOf(changed)) }
      assertEquals("Invalid word timings", error.message)
    }
  }

  @Test fun `segment and session timeline integrity remain enforced`() {
    val original = graph("segment")
    for (segment in listOf(original.segments.single().copy(partStartMs = -1),
      original.segments.single().copy(partEndMs = 1001), original.segments.single().copy(sessionStartMs = 99))) {
      assertThrows(IllegalArgumentException::class.java) { write(listOf(original.copy(segments = listOf(segment)))) }
    }
  }
}
