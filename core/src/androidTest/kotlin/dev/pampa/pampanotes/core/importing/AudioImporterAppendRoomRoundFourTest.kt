package dev.pampa.pampanotes.core.importing

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.repo.SessionRepository
import dev.pampa.pampanotes.core.transcription.VoiceNames
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room transactions and SessionRepository writers; prepared for Android execution. */
@RunWith(AndroidJUnit4::class)
class AudioImporterAppendRoomRoundFourTest {
  private lateinit var db: PampaDatabase
  private lateinit var files: AppFiles
  private lateinit var directory: File
  private val original = SessionEntity("session", "note", title = "Old title", date = "2026-11-08",
    position = 0, createdAt = 1, updatedAt = 10, voiceNames = VoiceNames.encode(mapOf("A|SPEAKER_00" to "Alice")))

  @Before fun setup() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    directory = File(context.cacheDir, "audio-append-r4-${UUID.randomUUID()}").apply { mkdirs() }
    val isolatedContext = object : ContextWrapper(context) {
      override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
      override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
    }
    db = PampaDatabase.inMemory(isolatedContext)
    files = AppFiles(isolatedContext)
    runBlocking {
      db.folders().upsert(FolderEntity("folder", "Folder", createdAt = 1, updatedAt = 1))
      db.notes().upsert(NoteEntity("note", "folder", "Note", createdAt = 1, updatedAt = 1))
      db.sessions().upsert(original)
      db.audioParts().upsert(AudioPartEntity("A", "session", 0, "old.m4a", "old.m4a", "audio/mp4", 3, 1000, "", 1))
    }
  }

  @After fun cleanup() {
    db.close()
    directory.deleteRecursively()
  }

  private fun repository(sessions: SessionDao = db.sessions()) = SessionRepository(
    sessions, db.audioParts(), db.transcripts(), db.segments(), db.notes(), files, db,
  )

  private fun candidate(file: File? = File(files.temp, "candidate.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }) =
    ImportCandidate("candidate", null, file, "new.m4a", SourceKind.AUDIO, "audio/mp4", 3, "b".repeat(64), durationMs = 1000)

  @Test fun repositoryTitleAndRemovalWaitWhileImportIsSuspendedAfterReadingSession() = runBlocking {
    withTimeout(10_000) {
      val readGap = CompletableDeferred<Unit>()
      val finishRead = CompletableDeferred<Unit>()
      val writerRead = CompletableDeferred<Unit>()
      val gateOnce = AtomicBoolean(true)
      val gatedParts = object : AudioPartDao by db.audioParts() {
        override suspend fun bySession(sessionId: String): List<AudioPartEntity> {
          if (gateOnce.getAndSet(false)) {
            // This is the reported production suspension, after sessions.get and before upsert.
            assertTrue("The metadata read is inside a real Room transaction", db.inTransaction())
            readGap.complete(Unit)
            finishRead.await()
          }
          return db.audioParts().bySession(sessionId)
        }
      }
      val writerSessions = object : SessionDao by db.sessions() {
        override suspend fun get(id: String): SessionEntity? {
          writerRead.complete(Unit)
          return db.sessions().get(id)
        }
      }
      val writer = repository(writerSessions)
      val importer = AudioImporter(files, db.sessions(), gatedParts, db)
      val appended = async(Dispatchers.Default) {
        importer.importAll(listOf(candidate()), "note", AudioPlacement.Append("session"))
      }
      try {
        readGap.await()
        val edited = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
          writer.rename("session", "Concurrent title", "2026-11-09")
          writer.renameVoice("session", "A|SPEAKER_00", null)
        }
        // Bound the observation: a competing Room transaction cannot enter its first DAO read.
        assertNull(withTimeoutOrNull(500) { writerRead.await() })
        finishRead.complete(Unit)
        assertEquals(SourceStatus.OK, appended.await().single().status)
        edited.await()

        val saved = db.sessions().get("session")!!
        assertEquals("Concurrent title", saved.title)
        assertEquals("2026-11-09", saved.date)
        assertTrue(VoiceNames.decode(saved.voiceNames).isEmpty())
        assertTrue(VoiceNames.clocks(saved.voiceNames).getValue("A") > 10)
        assertEquals(2, db.audioParts().bySession("session").size)
      } finally { finishRead.complete(Unit) }
    }
  }

  @Test fun editCommittedAfterPartInsertIsReadByTheSubsequentMetadataTransaction() = runBlocking {
    withTimeout(10_000) {
      val inserted = CompletableDeferred<Unit>()
      val finishInsert = CompletableDeferred<Unit>()
      val gatedParts = object : AudioPartDao by db.audioParts() {
        override suspend fun upsert(part: AudioPartEntity) {
          db.audioParts().upsert(part)
          inserted.complete(Unit)
          finishInsert.await()
        }
      }
      val importer = AudioImporter(files, db.sessions(), gatedParts, db)
      val appended = async(Dispatchers.Default) {
        importer.importAll(listOf(candidate()), "note", AudioPlacement.Append("session"))
      }
      try {
        inserted.await()
        val writer = repository()
        writer.rename("session", "Committed title", "2026-11-09")
        writer.renameVoice("session", "A|SPEAKER_00", null)
        val beforeTouch = db.sessions().get("session")!!
        finishInsert.complete(Unit)
        assertEquals(SourceStatus.OK, appended.await().single().status)
        val afterTouch = db.sessions().get("session")!!
        assertEquals(beforeTouch.title, afterTouch.title)
        assertEquals(beforeTouch.date, afterTouch.date)
        assertEquals(beforeTouch.voiceNames, afterTouch.voiceNames)
        assertTrue(VoiceNames.decode(afterTouch.voiceNames).isEmpty())
        assertEquals(2, db.audioParts().bySession("session").size)
      } finally { finishInsert.complete(Unit) }
    }
  }

  @Test fun rejectedOrUnavailableAppendAndEmptyBatchDoNotTouchSessionOrLoseInput() = runBlocking {
    val rejectedParts = object : AudioPartDao by db.audioParts() {
      override suspend fun upsert(part: AudioPartEntity): Unit = error("Rejected append")
    }
    val importer = AudioImporter(files, db.sessions(), rejectedParts, db)
    val input = candidate()
    assertTrue(importer.importAll(emptyList(), "note", AudioPlacement.Append("session")).isEmpty())
    assertEquals(SourceStatus.FAILED, importer.importAll(listOf(candidate(null)), "note", AudioPlacement.Append("session")).single().status)
    assertEquals(SourceStatus.FAILED, importer.importAll(listOf(input), "note", AudioPlacement.Append("session")).single().status)
    assertEquals(original, db.sessions().get("session"))
    assertEquals(listOf("A"), db.audioParts().bySession("session").map { it.id })
    assertTrue(input.file!!.exists())
    assertTrue(files.audio.listFiles().orEmpty().isEmpty())
  }
}
