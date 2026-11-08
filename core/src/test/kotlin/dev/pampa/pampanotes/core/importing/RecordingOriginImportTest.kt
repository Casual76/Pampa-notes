package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import io.mockk.*
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingOriginImportTest {
  @get:Rule val temp = TemporaryFolder()

  @Test fun `raw import persists only explicit origin not sorting noon`() = runTest {
    val files = mockk<AppFiles>(); val sessions = mockk<SessionDao>(); val parts = mockk<AudioPartDao>()
    val session = SessionEntity("s", "n", date = "2025-09-22", position = 0, createdAt = 1, updatedAt = 2)
    coEvery { sessions.get("s") } returns session
    coEvery { sessions.upsert(any<SessionEntity>()) } just Runs
    coEvery { parts.nextPosition("s") } returns 0
    val saved = mutableListOf<AudioPartEntity>()
    coEvery { parts.upsert(capture(saved)) } just Runs
    every { files.newAudioName(any(), any(), any()) } answers { "${firstArg<String>()}.wav" }
    every { files.audioFile(any()) } answers { File(temp.root, firstArg<String>()) }
    val noon = RecordingDate.noonOf(java.time.LocalDate.parse("2025-09-22"))
    fun candidate(name: String, origin: RecordingOrigin?) = ImportCandidate(name, null, temp.newFile().apply { writeText("fixture") },
      name, SourceKind.AUDIO, "audio/wav", 7, "hash", durationMs = 1000, recordedAtMillis = noon, recordingOrigin = origin)
    val exact = RecordingOrigin.fromMetadata("2025-09-22T10:15:00Z")!!
    AudioImporter(files, sessions, parts).importAll(listOf(candidate("a.wav", exact), candidate("b.wav", null)), "n", AudioPlacement.Append("s"))
    assertEquals(exact.epochMs, saved[0].recordedAtEpochMs)
    assertEquals(exact.local, saved[0].recordedAtLocal)
    assertEquals(exact.zoneId, saved[0].recordedAtZoneId)
    assertEquals(exact.source, saved[0].recordedAtSource)
    assertEquals(exact.precision, saved[0].recordedAtPrecision)
    assertNull(saved[1].recordedAtEpochMs)
    assertNull(saved[1].recordedAtLocal)
    assertNull(saved[1].recordedAtSource)
  }
}
