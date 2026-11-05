package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.audio.AudioInfo
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.transcription.SessionAssembler
import io.mockk.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioSystem

class PampaBundleAudioMetadataTest {
  @get:Rule val temp = TemporaryFolder()
  private val note = NoteEntity("n", "f", "Title", createdAt = 1, updatedAt = 2)
  private val session = SessionEntity("s", "n", date = "2026-11-05", position = 0, createdAt = 1, updatedAt = 2)
  private fun part(id: String, position: Int, duration: Long = 0) = AudioPartEntity(id, "s", position, "", "$id.wav", "audio/mp4", 0, duration, "", 1)

  private fun wav(file: File, milliseconds: Int) {
    val data = milliseconds * 16 // 8kHz mono 16-bit PCM
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("RIFF".toByteArray()); putInt(36 + data); put("WAVEfmt ".toByteArray()); putInt(16)
      putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
      put("data".toByteArray()); putInt(data)
    }.array()
    file.outputStream().use { it.write(header); it.write(ByteArray(data)) }
  }

  @Test fun `probe boundary completes two real WAV fixtures and preserves native timing on default reexport`() {
    val first = temp.newFile("first.wav").also { wav(it, 1000) }
    val second = temp.newFile("second.wav").also { wav(it, 2000) }
    val files = mockk<AppFiles>()
    every { files.audioFile(first.name) } returns first
    every { files.audioFile(second.name) } returns second
    val probe = mockk<PampaBundleAudioProbe>()
    // JVM adapter measures actual WAV frames; production Android MediaMetadataRetriever is
    // exercised by PampaBundleRoomTest on a target, never by a JVM Android stub.
    every { probe.probe(any()) } answers {
      val file = firstArg<File>()
      AudioSystem.getAudioInputStream(file).use { stream ->
        AudioInfo((stream.frameLength * 1000 / stream.format.frameRate).toLong(), file.length(), "audio/x-wav", 8000, 1, 128000)
      }
    }
    val data = PampaBundleData(folders = listOf(FolderEntity("f", "Subject", createdAt = 1, updatedAt = 2)), notes = listOf(
      PampaBundleNote(note, sessions = listOf(session), parts = listOf(part("A", 0), part("B", 1)))))
    val importer = PampaBundleImporter(mockk(), files, probe)
    val measured = importer.completeAudioMetadata(data, mapOf((true to "A") to first.name, (true to "B") to second.name))
    val parts = measured.notes.single().parts
    assertEquals(listOf(1000L, 2000L), parts.map { it.durationMs })
    assertTrue(parts.all { it.mime == "audio/x-wav" })
    val timeline = parts.map { SessionAssembler.Part(it.id, it.durationMs) }
    assertEquals(3000L, SessionAssembler.totalDurationMs(timeline))
    assertEquals(1000L, SessionAssembler.offsets(timeline).getValue("B"))
    assertEquals(SessionAssembler.Position(1, "B", 250), SessionAssembler.locate(timeline, 1250))
    val native = PampaBundleMerge.merge(null, measured.notes.single())
    val export = ExportNote(note, listOf("Subject"), emptyList(), emptyList(), listOf(ExportSession("s", 1, "", session.date,
      parts.map { ExportPart(it.id, it.originalName, "", it.durationMs, SessionAssembler.offsets(timeline).getValue(it.id), 0) }, null, null, emptyList())), native = native)
    val archive = temp.newFile("reexport.zip")
    archive.outputStream().use { BundleWriter(temp.root, temp.root).write(ExportSet("Subject", "subject", listOf(export), "Pampa Notes test", 1, folders = data.folders), ExportOptions(), it) }
    val restored = PampaBundleArchive.read(archive).data.notes.single()
    assertEquals(listOf(1000L, 2000L), restored.parts.map { it.durationMs })
    assertTrue(restored.attachments.all { it.path == null })
    verify(exactly = 1) { probe.probe(first) }
    verify(exactly = 1) { probe.probe(second) }
  }

  @Test fun `absent attachments and known exact durations are not probed`() {
    val probe = mockk<PampaBundleAudioProbe>()
    val data = PampaBundleData(folders = emptyList(), notes = listOf(PampaBundleNote(note, parts = listOf(part("missing", 0), part("known", 1, 1234)))))
    val measured = PampaBundleImporter(mockk(), mockk(), probe).completeAudioMetadata(data, mapOf((true to "known") to "present.wav"))
    assertEquals(data, measured)
    verify { probe wasNot Called }
  }

  @Test fun `unreadable included audio fails before persistence and cancellation propagates`() {
    val file = temp.newFile()
    val files = mockk<AppFiles>(); every { files.audioFile("bad") } returns file
    val probe = mockk<PampaBundleAudioProbe>()
    every { probe.probe(file) } returns AudioInfo(0, 0, null, null, null, null)
    val importer = PampaBundleImporter(mockk(), files, probe)
    val data = PampaBundleData(folders = emptyList(), notes = listOf(PampaBundleNote(note, parts = listOf(part("A", 0)))))
    try { importer.completeAudioMetadata(data, mapOf((true to "A") to "bad")); fail("Expected probe failure") } catch (_: IllegalArgumentException) { }
    try { importer.completeAudioMetadata(data, mapOf((true to "A") to "bad")) { throw java.util.concurrent.CancellationException() }; fail("Expected cancellation") } catch (_: java.util.concurrent.CancellationException) { }
  }
}
