package dev.pampa.pampanotes.core.importing

import androidx.room.withTransaction
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.transcription.VoiceNames
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Functional importer regressions; no media probe, Room, Android runtime or provider required. */
class AudioImporterVoiceClockRoundThreeTest {
  @get:Rule val temp = TemporaryFolder()
  private val sessions = mockk<SessionDao>()
  private val parts = mockk<AudioPartDao>()
  private val files = mockk<AppFiles>()
  private val db = mockk<PampaDatabase>()
  private var session = SessionEntity("session", "note", date = "2026-11-08", position = 0, createdAt = 1, updatedAt = 10)
  private val rows = mutableListOf<AudioPartEntity>()
  private val importer = AudioImporter(files, sessions, parts, db)
  private lateinit var audio: File

  @Before fun setup() {
    mockkStatic("androidx.room.RoomDatabaseKt")
    coEvery { db.withTransaction<Any?>(any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
    audio = temp.newFolder()
    rows += AudioPartEntity("A", session.id, 0, "old.m4a", "old.m4a", "audio/mp4", 3, 1000, "a".repeat(64), 1)
    coEvery { sessions.get(any()) } coAnswers { session.takeIf { it.id == firstArg<String>() } }
    coEvery { sessions.upsert(any()) } coAnswers { session = firstArg(); Unit }
    coEvery { parts.nextPosition(session.id) } coAnswers { rows.size }
    coEvery { parts.bySession(session.id) } coAnswers { rows.toList() }
    coEvery { parts.upsert(any()) } coAnswers { rows += firstArg<AudioPartEntity>(); Unit }
    every { files.newAudioName(any(), any(), any()) } answers { firstArg<String>() + ".m4a" }
    every { files.audioFile(any()) } answers { File(audio, firstArg<String>()) }
  }

  @After fun cleanup() { unmockkStatic("androidx.room.RoomDatabaseKt") }

  private fun candidate(file: File? = temp.newFile().also { it.writeBytes(byteArrayOf(1, 2, 3)) }) =
    ImportCandidate("candidate", null, file, "new.m4a", SourceKind.AUDIO, "audio/mp4", 3, "b".repeat(64), durationMs = 1000)

  @Test fun appendCapturesUnnamedLegacyRecordingsAtOldFallbackInTheSameSessionWrite() = runTest {
    val result = importer.importAll(listOf(candidate()), "note", AudioPlacement.Append(session.id))
    assertEquals(SourceStatus.OK, result.single().status)
    assertEquals(2, rows.size)
    assertTrue(VoiceNames.decode(session.voiceNames).isEmpty())
    assertEquals(rows.associate { it.id to 10L }, VoiceNames.clocks(session.voiceNames))
    assertTrue(session.updatedAt > 10)
    coVerify(exactly = 1) { sessions.upsert(match { VoiceNames.clocks(it.voiceNames).values.toSet() == setOf(10L) }) }
  }

  @Test fun appendPreservesAlreadySeparatedVoiceAuthorityAndVisibleNames() = runTest {
    session = session.copy(voiceNames = VoiceNames.encode(mapOf("A|SPEAKER_00" to "Alice"), mapOf("A" to 100L)))
    importer.importAll(listOf(candidate()), "note", AudioPlacement.Append(session.id))
    val added = rows.single { it.id != "A" }
    assertEquals(mapOf("A" to 100L, added.id to 10L), VoiceNames.clocks(session.voiceNames))
    assertEquals(mapOf("A|SPEAKER_00" to "Alice"), VoiceNames.decode(session.voiceNames))
  }

  @Test fun emptyAndUnavailableImportsDoNotCaptureOrTouchAnUnchangedSession() = runTest {
    val original = session
    assertTrue(importer.importAll(emptyList(), "note", AudioPlacement.Append(session.id)).isEmpty())
    val failed = importer.importAll(listOf(candidate(null)), "note", AudioPlacement.Append(session.id))
    assertEquals(SourceStatus.FAILED, failed.single().status)
    assertEquals(original, session)
    coVerify(exactly = 0) { sessions.upsert(any()) }
    coVerify(exactly = 0) { parts.upsert(any()) }
  }

  @Test fun rejectedAppendDoesNotCreateVoiceEvidenceOrAdvanceTheSessionClock() = runTest {
    coEvery { parts.upsert(any()) } throws IllegalStateException("Rejected append")
    val original = session
    val result = importer.importAll(listOf(candidate()), "note", AudioPlacement.Append(session.id))
    assertEquals(SourceStatus.FAILED, result.single().status)
    assertEquals(original, session)
    coVerify(exactly = 0) { sessions.upsert(any()) }
    assertTrue(audio.listFiles().orEmpty().isEmpty())
  }
}
