package dev.pampa.pampanotes.core.repo

import android.content.Context
import app.cash.turbine.test
import androidx.room.withTransaction
import dev.pampa.pampanotes.core.alignment.AlignmentProtocol
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.settings.*
import dev.pampa.pampanotes.core.transcription.*
import io.mockk.*
import java.io.File
import dev.pampa.pampanotes.core.audio.ChunkSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

/** Real repository decisions and local files, with DAO/transaction seams; no Room runtime claim. */
class CloudRecoveryRepositoryTest {
  @get:Rule val temp = TemporaryFolder()
  private val jobs = mockk<JobDao>()
  private val sessions = mockk<SessionDao>()
  private val parts = mockk<AudioPartDao>()
  private val transcripts = mockk<TranscriptDao>()
  private val db = mockk<PampaDatabase>()
  private val settings = mockk<PampaSettingsStore>()
  private val cooldowns = mockk<CloudCooldownStore>()
  private val sessionRepository = mockk<SessionRepository>()
  private lateinit var files: AppFiles
  private lateinit var repo: TranscriptionRepository
  private lateinit var original: List<AudioPartEntity>
  private var prefs = PampaSettings(syncAccount = "original-account")
  private var row = JobEntity("job", "session", JobType.TRANSCRIBE, "groq", "model", JobState.TRANSCRIBING,
    attempts = 1, createdAt = 100, updatedAt = 100)
  private var session = SessionEntity("session", "note", date = "2026-11-08", position = 0, createdAt = 1, updatedAt = 1)
  private var versions = emptyList<TranscriptEntity>()
  private var currentParts = emptyList<AudioPartEntity>()

  @Before fun setup() {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder()
    every { context.cacheDir } returns temp.newFolder()
    files = AppFiles(context)
    val audio = files.audioFile("a.wav").also { it.writeText("original bytes") }
    original = listOf(AudioPartEntity("a", "session", 0, audio.name, audio.name, "audio/wav", audio.length(), 1000, AlignmentProtocol.sha256(audio), 1))
    currentParts = original
    mockkStatic("androidx.room.RoomDatabaseKt")
    coEvery { db.withTransaction<Any?>(any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
    coEvery { jobs.get("job") } answers { row }
    coEvery { jobs.all() } answers { listOf(row) }
    coEvery { sessions.get("session") } answers { session }
    coEvery { parts.bySession("session") } answers { currentParts }
    coEvery { transcripts.bySession("session") } answers { versions }
    coEvery { settings.current() } answers { prefs }
    every { cooldowns.contexts } returns DurableCloudJobContexts(File(temp.root, "contexts.json"))
    cooldowns.contexts.bind(row.id, CloudRetry.fingerprint(prefs.syncAccount))
    repo = TranscriptionRepository(jobs, sessions, mockk(), parts, transcripts, mockk(), settings, mockk(), mockk(),
      mockk(), db, mockk(), mockk(), sessionRepository, files, mockk(), mockk(), cooldowns)
  }
  @After fun cleanup() = unmockkStatic("androidx.room.RoomDatabaseKt")
  private fun result() = SessionTranscript("Received", emptyList(), null, "model", "groq",
    sourceParts = listOf(CloudPartText("a", "Received", null, original.single().sha256)))
  private fun paidAndUncertain() {
    files.jobs.resolve("job/part-a").mkdirs()
    files.jobs.resolve("job/part-a/whole.json").writeText("paid evidence")
    files.jobs.resolve("job/part-a/whole.json.sending").writeText("pending:11111111-1111-4111-8111-111111111111")
  }

  private fun receivedWork() {
    val root = files.jobDir(row.id); val storage = TranscriptionWorkStorage()
    val signature = StoredChunks.signature("groq", TranscriptionCapabilities(null, false, false), 10)
    storage.prepare(root, signature, TranscribeRequest("model"), true)
    CloudWorkRecovery.prepare(root, original, storage)
    val dir = root.resolve("part-a").also { it.mkdirs() }; val part = original.single()
    storage.recordInput(dir, TranscriptionWorkStorage.Input(part.id, part.sha256, part.durationMs))
    storage.write(dir.resolve("whole.json"), StoredChunks.encode(ChunkTranscript(ChunkSpec(0, 0, 1000), emptyList(),
      sourceText = "Paid local text", audioSha256 = part.sha256), signature))
  }

  @Test fun `terminal received results follow privacy changes in both consumers without changing dispatch or bytes`() = runTest {
    receivedWork()
    row = row.copy(state = JobState.FAILED, errorCode = "unauthorized")
    val persisted = row
    val paid = files.jobs.resolve("job/part-a/whole.json"); val bytes = paid.readBytes()
    val prefsFlow = MutableStateFlow(prefs)
    every { settings.settings } returns prefsFlow
    every { jobs.observeAll() } returns MutableStateFlow(listOf(row))
    every { jobs.observeBySession("session") } returns MutableStateFlow(listOf(row))
    for (consumer in listOf(repo.observeAll(), repo.observeBySession("session"))) {
      prefs = prefs.copy(customOnly = false); prefsFlow.value = prefs
      consumer.test {
        assertEquals("unauthorized", awaitItem().single().errorCode)
        prefs = prefs.copy(customOnly = true); prefsFlow.value = prefs
        val displayed = awaitItem().single()
        assertEquals("cloud_recovery_changed", displayed.errorCode)
        assertTrue(FailedJobs.requiresNewRequest(displayed))
        assertEquals(FailureStanding.RECOVERY_ONLY, repo.failureStandings(listOf(persisted))[row.id])
        assertNull(repo.retry(row.id))
        assertEquals(persisted, row); assertArrayEquals(bytes, paid.readBytes())
        prefs = prefs.copy(customOnly = false); prefsFlow.value = prefs
        assertEquals("unauthorized", awaitItem().single().errorCode)
        cancelAndIgnoreRemainingEvents()
      }
    }
    coVerify(exactly = 0) { jobs.retry(any(), any(), any(), any()) }
    coVerify(exactly = 0) { jobs.delete(any()) }
  }

  @Test fun `computer only can still retry a known failure with no received or uncertain work`() = runTest {
    prefs = prefs.copy(customOnly = true)
    row = row.copy(state = JobState.FAILED, errorCode = "unauthorized")
    coEvery { jobs.retry(any(), any(), any(), any()) } answers {
      row = row.copy(provider = arg(1), state = JobState.QUEUED, errorCode = null, optionsJson = arg(2)); 1
    }
    assertEquals(FailureStanding.RETRYABLE, repo.failureStandings(listOf(row))[row.id])
    assertEquals("custom", repo.retry(row.id)?.provider)
    coVerify(exactly = 1) { jobs.retry(row.id, "custom", any(), any()) }
  }

  @Test fun `late cloud completion cannot replace newer history or a changed selection`() = runTest {
    val snapshot = repo.cloudSaveSnapshot("session")!!
    val local = TranscriptEntity("local", "session", TranscriptKind.RAW, "custom", "local", text = "New local result", wordCount = 3, createdAt = 200)
    versions = listOf(local); session = session.copy(activeTranscriptId = local.id)
    assertNull(repo.saveOwnedCloudTranscript(row, original, snapshot, result()))
    assertEquals(listOf(local), versions); assertEquals(local.id, session.activeTranscriptId)
    coVerify(exactly = 0) { sessionRepository.saveTranscription(any(), any(), any(), any()) }
    // Selection alone is authoritative even with the same history rows.
    versions = emptyList()
    assertNull(repo.saveOwnedCloudTranscript(row, original, snapshot, result()))
  }

  @Test fun `completion and cancellation are fenced by exact owner account and original audio`() = runTest {
    val expected = row
    val snapshot = repo.cloudSaveSnapshot("session")!!
    for (changed in listOf(expected.copy(state = JobState.CANCEL_REQUESTED), expected.copy(provider = "custom"),
      expected.copy(model = "replacement"), expected.copy(attempts = 2), expected.copy(optionsJson = "changed"))) {
      row = changed
      assertNull(repo.saveOwnedCloudTranscript(expected, original, snapshot, result()))
    }
    row = expected; prefs = prefs.copy(syncAccount = "another-account")
    assertNull(repo.saveOwnedCloudTranscript(expected, original, snapshot, result()))
    prefs = prefs.copy(syncAccount = "original-account")
    currentParts = original.map { it.copy(fileName = "replacement.wav") }
    assertNull(repo.saveOwnedCloudTranscript(expected, original, snapshot, result()))
    currentParts = original
    files.audioFile(original.single().fileName).writeText("changed in place")
    assertNull(repo.saveOwnedCloudTranscript(expected, original, snapshot, result()))
    coVerify(exactly = 0) { sessionRepository.saveTranscription(any(), any(), any(), any()) }
  }

  @Test fun `unchanged complete result uses production save and archive metadata alone does not invalidate ownership`() = runTest {
    val snapshot = repo.cloudSaveSnapshot("session")!!
    currentParts = original.map { it.copy(archivedAt = 200) }
    val saved = SavedTranscription("session", null, listOf("session"))
    coEvery { sessionRepository.saveTranscription("session", listOf("a"), any(), original) } returns saved
    assertEquals(saved, repo.saveOwnedCloudTranscript(row, original, snapshot, result()))
  }

  @Test fun `uncertain jobs and cancelled jobs cannot retry or migrate and clear keeps recovery reachable`() = runTest {
    paidAndUncertain()
    row = row.copy(state = JobState.FAILED, errorCode = "network") // Legacy persisted misclassification.
    assertNull(repo.retry(row.id))
    assertEquals(FailureStanding.UNCERTAIN, repo.failureStandings(listOf(row))[row.id])
    coEvery { jobs.movableTranscriptions(any()) } answers { if (firstArg<String>() == row.provider) listOf(row) else emptyList() }
    assertEquals(0, repo.moveCloudTranscriptionsToComputer())
    repo.clearFinished()
    row = row.copy(state = JobState.CANCELLED)
    assertNull(repo.retry(row.id)); repo.clearFinished()
    coVerify(exactly = 0) { jobs.delete(any()) }
    coVerify(exactly = 0) { jobs.retry(any(), any(), any(), any()) }
    coVerify(exactly = 0) { jobs.moveTranscription(any(), any(), any(), any(), any()) }
    assertTrue(files.jobs.resolve("job/part-a/whole.json.sending").isFile)
  }

  @Test fun `cancellation passes exact dispatch identity to production DAO and retains request bytes`() = runTest {
    paidAndUncertain()
    val expected = row
    coEvery { jobs.cancelOwned(any(), any(), any(), any(), any(), any(), any()) } answers {
      if (arg<String>(2) != row.provider || arg<String?>(3) != row.model || arg<String?>(4) != row.optionsJson || arg<Int>(5) != row.attempts) 0
      else { row = row.copy(state = JobState.CANCELLED); 1 }
    }
    assertTrue(repo.markCancelled(expected))
    assertEquals(JobState.CANCELLED, row.state)
    row = row.copy(attempts = 2, state = JobState.TRANSCRIBING)
    assertFalse(repo.markCancelled(expected))
    assertTrue(files.jobs.resolve("job/part-a/whole.json.sending").exists())
  }

  @Test fun `restart classifies old network error as uncertain before pinning or migrating provider`() = runTest {
    paidAndUncertain()
    row = row.copy(state = JobState.QUEUED)
    coEvery { jobs.fail(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
      row = row.copy(state = JobState.FAILED, errorCode = arg(1)); 1
    }
    repo.classifyUncertainCloudWork()
    assertEquals("cloud_request_uncertain", row.errorCode); assertEquals(JobState.FAILED, row.state)
    assertEquals("cloud_request_uncertain", (runCatching { repo.pinForRun(row) }.exceptionOrNull() as TranscriptionError).code)
    assertEquals("groq", row.provider)
    row = row.copy(state = JobState.CANCELLED)
    repo.classifyUncertainCloudWork()
    assertEquals(JobState.CANCELLED, row.state)
    coVerify(exactly = 1) { jobs.fail(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
  }

  @Test fun `true network failure without an uncertain POST keeps the ordinary same job retry`() = runTest {
    row = row.copy(state = JobState.FAILED, errorCode = "network")
    coEvery { jobs.retry(any(), any(), any(), any()) } answers {
      row = row.copy(state = JobState.QUEUED, errorCode = null, optionsJson = arg(2)); 1
    }
    assertEquals(row.id, repo.retry(row.id)?.id)
    assertEquals(JobState.QUEUED, row.state); assertEquals("groq", row.provider)
    coVerify(exactly = 1) { jobs.retry(any(), any(), any(), any()) }
  }

  @Test fun `open recovery reader is revoked when the account changes without any transcript writes`() = runTest {
    val root = files.jobDir(row.id); val storage = TranscriptionWorkStorage()
    val request = TranscribeRequest("model")
    val signature = StoredChunks.signature("groq", TranscriptionCapabilities(null, false, false), 10)
    storage.prepare(root, signature, request, true); CloudWorkRecovery.prepare(root, original, storage)
    val dir = root.resolve("part-a").also { it.mkdirs() }; val part = original.single()
    storage.recordInput(dir, TranscriptionWorkStorage.Input(part.id, part.sha256, part.durationMs))
    storage.write(dir.resolve("whole.json"), StoredChunks.encode(ChunkTranscript(ChunkSpec(0, 0, 1000), emptyList(),
      sourceText = "Paid local text", audioSha256 = part.sha256), signature))
    row = row.copy(state = JobState.CANCELLED)
    val prefsFlow = MutableStateFlow(prefs)
    every { settings.settings } returns prefsFlow
    every { jobs.observe(row.id) } returns MutableStateFlow(row)
    every { sessions.observe("session") } returns MutableStateFlow(SessionWithParts(session, original))
    repo.observeCloudRecovery(row.id).test {
      assertNull(awaitItem())
      assertEquals("Paid local text", awaitItem()!!.windows.single().text)
      prefs = prefs.copy(syncAccount = "another-account"); prefsFlow.value = prefs
      assertNull(awaitItem())
      cancelAndIgnoreRemainingEvents()
    }
    coVerify(exactly = 0) { sessionRepository.saveTranscription(any(), any(), any(), any()) }
    coVerify(exactly = 0) { transcripts.upsert(any()) }
  }

  @Test fun `dispatch fence rejects cancellation changed account and replaced audio rows`() = runTest {
    val expected = row
    repo.validateCloudDispatch(expected, original)
    row = row.copy(state = JobState.CANCEL_REQUESTED)
    assertTrue(runCatching { repo.validateCloudDispatch(expected, original) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    row = expected; currentParts = original.map { it.copy(fileName = "another.wav") }
    assertEquals("cloud_recovery_changed", (runCatching { repo.validateCloudDispatch(expected, original) }.exceptionOrNull() as TranscriptionError).code)
    currentParts = original; prefs = prefs.copy(syncAccount = "replacement")
    assertEquals("cloud_account_changed", (runCatching { repo.validateCloudDispatch(expected, original) }.exceptionOrNull() as TranscriptionError).code)
  }
}
