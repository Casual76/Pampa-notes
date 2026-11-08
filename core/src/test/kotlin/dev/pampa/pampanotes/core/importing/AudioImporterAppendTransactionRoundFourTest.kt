package dev.pampa.pampanotes.core.importing

import androidx.room.withTransaction
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.repo.SessionRepository
import dev.pampa.pampanotes.core.transcription.VoiceNames
import io.mockk.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real importer/repository calls, with a controlled serialized transaction runner, not Room runtime. */
class AudioImporterAppendTransactionRoundFourTest {
  @get:Rule val temp = TemporaryFolder()
  private val db = mockk<PampaDatabase>()
  private val sessions = mockk<SessionDao>()
  private val parts = mockk<AudioPartDao>()
  private val notes = mockk<NoteDao>()
  private val files = mockk<AppFiles>()
  private val transaction = Mutex()
  private val inTransaction = AtomicBoolean(false)
  @Volatile private var session = SessionEntity("session", "note", title = "Old title", date = "2026-11-08",
    position = 0, createdAt = 1, updatedAt = 10, voiceNames = VoiceNames.encode(mapOf("A|SPEAKER_00" to "Alice")))
  private val rows = mutableListOf(AudioPartEntity("A", "session", 0, "old.m4a", "old.m4a", "audio/mp4", 3, 1000, "", 1))
  private lateinit var audio: File
  private val importer = AudioImporter(files, sessions, parts, db)

  @Before fun setup() {
    audio = temp.newFolder()
    mockkStatic("androidx.room.RoomDatabaseKt")
    coEvery { db.withTransaction<Any?>(any()) } coAnswers {
      val block = secondArg<suspend () -> Any?>()
      transaction.withLock {
        assertFalse(inTransaction.getAndSet(true))
        try { block() } finally { inTransaction.set(false) }
      }
    }
    coEvery { sessions.get("session") } coAnswers { session }
    coEvery { sessions.upsert(any()) } coAnswers {
      assertTrue("The final session write belongs to the same transaction as its read", inTransaction.get())
      session = firstArg(); Unit
    }
    coEvery { sessions.rename(any(), any(), any(), any()) } coAnswers {
      session = session.copy(title = secondArg(), date = thirdArg(), updatedAt = arg(3)); Unit
    }
    coEvery { sessions.setVoiceNames(any(), any(), any()) } coAnswers {
      session = session.copy(voiceNames = secondArg(), updatedAt = thirdArg()); Unit
    }
    coEvery { notes.touch(any(), any()) } returns Unit
    coEvery { parts.nextPosition("session") } coAnswers { rows.size }
    coEvery { parts.bySession("session") } coAnswers { rows.toList() }
    coEvery { parts.upsert(any()) } coAnswers {
      assertFalse("Part writes precede the short metadata transaction", inTransaction.get())
      rows += firstArg<AudioPartEntity>(); Unit
    }
    every { files.newAudioName(any(), any(), any()) } answers { firstArg<String>() + ".m4a" }
    every { files.audioFile(any()) } answers {
      assertFalse("File copying must not hold the metadata transaction", inTransaction.get())
      File(audio, firstArg<String>())
    }
  }

  @After fun cleanup() { unmockkStatic("androidx.room.RoomDatabaseKt") }

  private fun candidate(file: File? = temp.newFile().also { it.writeBytes(byteArrayOf(1, 2, 3)) }) =
    ImportCandidate("candidate", null, file, "new.m4a", SourceKind.AUDIO, "audio/mp4", 3, "b".repeat(64), durationMs = 1000)

  @Test fun localTitleAndVoiceRemovalCannotCommitInTheMetadataReadWriteGap() = runBlocking {
    withTimeout(5000) {
      val readGap = CompletableDeferred<Unit>()
      val finishRead = CompletableDeferred<Unit>()
      val gateOnce = AtomicBoolean(true)
      coEvery { parts.bySession("session") } coAnswers {
        if (gateOnce.getAndSet(false)) {
          readGap.complete(Unit)
          finishRead.await()
        }
        rows.toList()
      }
      val repository = SessionRepository(sessions, parts, mockk(), mockk(), notes, files, db)
      val appended = async(Dispatchers.Default) {
        importer.importAll(listOf(candidate()), "note", AudioPlacement.Append("session"))
      }
      try {
        readGap.await() // importInto has read the old row and is suspended in parts.bySession.
        val edit = async(start = CoroutineStart.UNDISPATCHED) {
          repository.rename("session", "Concurrent title", "2026-11-09")
          repository.renameVoice("session", "A|SPEAKER_00", null)
        }
        // An unprotected importer lets both real repository edits finish here, then overwrites them.
        assertFalse("SessionRepository must wait for the import metadata transaction", edit.isCompleted)
        assertEquals("Old title", session.title)
        finishRead.complete(Unit)
        assertEquals(SourceStatus.OK, appended.await().single().status)
        edit.await()
        assertEquals("Concurrent title", session.title)
        assertEquals("2026-11-09", session.date)
        assertTrue(VoiceNames.decode(session.voiceNames).isEmpty())
        assertTrue(VoiceNames.clocks(session.voiceNames).getValue("A") > 10)
        assertEquals(2, rows.size)
      } finally { finishRead.complete(Unit) }
    }
  }

  @Test fun editCommittedBeforeMetadataTransactionKeepsTitleAndExactRemovalClock100() = runBlocking {
    coEvery { parts.upsert(any()) } coAnswers {
      assertFalse(inTransaction.get())
      rows += firstArg<AudioPartEntity>()
      session = session.copy(title = "Concurrent title", date = "2026-11-09", updatedAt = 100,
        voiceNames = VoiceNames.encode(emptyMap(), mapOf("A" to 100L)))
      Unit
    }

    assertEquals(SourceStatus.OK, importer.importAll(listOf(candidate()), "note", AudioPlacement.Append("session")).single().status)

    assertEquals("Concurrent title", session.title)
    assertEquals("2026-11-09", session.date)
    assertTrue(VoiceNames.decode(session.voiceNames).isEmpty())
    assertEquals(rows.associate { it.id to 100L }, VoiceNames.clocks(session.voiceNames))
    coVerify(exactly = 1) { db.withTransaction<Any?>(any()) }
  }

  @Test fun failedAndNoOpAppendsDoNotOpenTheMetadataTransactionOrChangeTheSession() = runBlocking {
    val original = session
    assertTrue(importer.importAll(emptyList(), "note", AudioPlacement.Append("session")).isEmpty())
    assertEquals(SourceStatus.FAILED, importer.importAll(listOf(candidate(null)), "note", AudioPlacement.Append("session")).single().status)
    val input = candidate()
    coEvery { parts.upsert(any()) } throws IllegalStateException("Rejected append")
    assertEquals(SourceStatus.FAILED, importer.importAll(listOf(input), "note", AudioPlacement.Append("session")).single().status)
    assertEquals(original, session)
    assertEquals(1, rows.size)
    assertTrue(input.file!!.exists())
    assertTrue(audio.listFiles().orEmpty().isEmpty())
    coVerify(exactly = 0) { db.withTransaction<Any?>(any()) }
    coVerify(exactly = 0) { sessions.upsert(any()) }
  }

  @Test fun cancellationStillPropagatesAndKeepsBothCopiesWithoutTouchingMetadata() = runBlocking {
    val original = session
    val input = candidate()
    coEvery { parts.upsert(any()) } throws kotlin.coroutines.cancellation.CancellationException("Cancelled append")
    try {
      importer.importAll(listOf(input), "note", AudioPlacement.Append("session"))
      fail("Cancellation must propagate")
    } catch (_: kotlin.coroutines.cancellation.CancellationException) { }
    assertEquals(original, session)
    assertTrue(input.file!!.exists())
    assertEquals(1, audio.listFiles().orEmpty().size)
    coVerify(exactly = 0) { db.withTransaction<Any?>(any()) }
    coVerify(exactly = 0) { sessions.upsert(any()) }
  }
}
