package dev.pampa.pampanotes.core.spending

import android.content.Context
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.*
import dev.pampa.pampanotes.core.testing.TinyHttpServer
import dev.pampa.pampanotes.core.transcription.*
import io.mockk.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class CloudSpendingGuardTest {
  @get:Rule val temp = TemporaryFolder()
  private val model = "openai/whisper-1"
  private lateinit var server: TinyHttpServer
  private lateinit var spending: CloudSpending
  private lateinit var disk: CloudCostStore
  private lateinit var db: PampaDatabase
  private lateinit var settings: PampaSettingsStore
  private lateinit var keys: AiKeyStore
  private lateinit var provider: CloudTranscriptionGuard
  private lateinit var cooldowns: dev.pampa.pampanotes.core.transcription.CloudCooldownStore
  private var prefs = PampaSettings(preferredProvider = TranscriptionProviderId.OPENROUTER)
  private var noteFolder = "root"
  private var partSession = "session"
  private fun session(id: String) = SessionEntity(id, "note", date = "2026-11-07", position = 0, createdAt = 1, updatedAt = 1)
  private fun request() = TranscribeRequest(model, cloudSessionId = "session", cloudPartId = "part", cloudJobId = "job", cloudDurationMs = 60_000)
  private fun audio(): File = File(temp.root, "audio.mp3").apply { if (!exists()) writeBytes(byteArrayOf(1, 2, 3)) }

  @Before fun setup() {
    server = TinyHttpServer()
    val context = mockk<Context>()
    every { context.noBackupFilesDir } returns temp.root
    disk = CloudCostStore(context)
    cooldowns = dev.pampa.pampanotes.core.transcription.CloudCooldownStore(context)
    settings = mockk()
    coEvery { settings.current() } answers { prefs }
    keys = mockk(relaxed = true)
    coEvery { keys.key(dev.antigravity.fluidengine.ai.provider.ProviderId.OPENROUTER) } returns "fake"
    db = mockk()
    val folders = mockk<FolderDao>()
    val sessions = mockk<SessionDao>()
    val notes = mockk<NoteDao>()
    val parts = mockk<AudioPartDao>()
    every { db.folders() } returns folders
    every { db.sessions() } returns sessions
    every { db.notes() } returns notes
    every { db.audioParts() } returns parts
    coEvery { folders.all() } returns listOf(
      FolderEntity("root", "Root", createdAt = 1, updatedAt = 1),
      FolderEntity("denied", "Private", createdAt = 1, updatedAt = 1))
    coEvery { sessions.get(any()) } answers { session(firstArg()) }
    coEvery { notes.get(any()) } answers { NoteEntity("note", noteFolder, "Note", createdAt = 1, updatedAt = 1) }
    coEvery { parts.get("part") } answers { AudioPartEntity("part", partSession, 0, "audio.mp3", "audio.mp3", "audio/mpeg", 3, 60_000, "sha", 1) }
    disk.durable.update { it.copy(
      folders = mapOf("denied" to FolderCloudSetting(CloudFolderRule.DENY)),
      prices = mapOf(model to ModelPrice(model, PriceUnit.AUDIO_SECOND, "0.003", "0.003", fetchedAt = System.currentTimeMillis(), complete = true))) }
    spending = CloudSpending(disk, db, settings, keys, dev.pampa.pampanotes.core.transcription.CloudCredentials(context, keys), TranscriptionHttp("test"))
    provider = CloudTranscriptionGuard(OpenRouterTranscriptionProvider(TranscriptionHttp("test"), "fake", server.url("/api/v1")),
      beforeUpload = { spending.beforeUpload("openrouter", OpenRouterCostMetadata.fingerprint("fake"), it) }) { prefs.customOnly }
    server.respond { TinyHttpServer.Response(200, """{"text":"Hello","usage":{"cost":0.15}}""") }
  }
  @After fun teardown() { server.close() }
  @Test fun `Mistral uses its own credential and retains honest unknown accounting with privacy gates`() = runBlocking {
    val credentials = mockk<dev.pampa.pampanotes.core.transcription.CloudCredentials>()
    coEvery { credentials.key("mistral") } returns "fixture-mistral-key"
    val mistralSpending = CloudSpending(disk, db, settings, keys, credentials, TranscriptionHttp("test"))
    val mistralRequest = request().copy(model = "voxtral-small-transcribe-3")
    val fingerprint = OpenRouterCostMetadata.fingerprint("fixture-mistral-key")
    val authorized = mistralSpending.beforeUpload("mistral", fingerprint, mistralRequest)
    val charge = disk.durable.read().charges.single()
    assertEquals("mistral", charge.provider)
    assertEquals(mistralRequest.model, charge.model)
    assertEquals(fingerprint, charge.keyFingerprint)
    assertNull(charge.actualUsd)
    authorized.cloudAttemptFinished?.invoke()
    prefs = prefs.copy(customOnly = true)
    assertEquals("companion_only", reason { mistralSpending.beforeUpload("mistral", fingerprint, mistralRequest) })
    assertTrue(server.requests.isEmpty())
  }
  private suspend fun upload() = provider.transcribe(audio(), "audio/mpeg", request())
  private suspend fun reason(block: suspend () -> Unit): String? = try { block(); null } catch (e: TranscriptionError) { e.code }

  @Test fun `guard saves reservation before HTTP and actual usage before malformed transcript parsing`() = runBlocking {
    server.respond {
      val charge = disk.durable.read().charges.single()
      assertEquals("0.180000", charge.reservedUsd)
      assertNull(charge.actualUsd)
      TinyHttpServer.Response(200, """{"unexpected":"no transcript","usage":{"cost":0.123456789}}""")
    }
    assertEquals("parse", reason { upload() })
    assertEquals("0.123456789", disk.durable.read().charges.single().actualUsd)
    assertEquals("0", SpendingLedger.totals(disk.durable.read(), System.currentTimeMillis()).reservedUsd)
    assertEquals(1, server.requests.size)
  }

  @Test fun `error response usage is captured without a success transcript`() = runBlocking {
    server.respond { TinyHttpServer.Response(503, """{"error":{"message":"upstream"},"usage":{"cost":"0.02"}}""") }
    assertEquals("server", reason { upload() })
    assertEquals("0.02", disk.durable.read().charges.single().actualUsd)
  }

  @Test fun `moved note and later queued rule change both block the next chunk without HTTP`() = runBlocking {
    upload()
    noteFolder = "denied"
    assertEquals("cloud_folder_blocked", reason { upload() })
    noteFolder = "root"
    spending.setFolder("root", FolderCloudSetting(CloudFolderRule.DENY))
    assertEquals("cloud_folder_blocked", reason { upload() })
    assertEquals(1, server.requests.size)
    assertEquals(1, disk.durable.read().charges.size)
  }

  @Test fun `part moved to another session is resolved from its live owner`() = runBlocking {
    partSession = "moved-session"
    val liveSessions = db.sessions()
    coEvery { liveSessions.get("session") } returns null
    upload()
    coVerify { liveSessions.get("moved-session") }
    noteFolder = "denied"
    assertEquals("cloud_folder_blocked", reason { upload() })
    assertEquals(1, server.requests.size)
  }

  @Test fun `explicit cloud exception keeps folder policy and cost reservation`() = runBlocking {
    prefs = prefs.copy(customOnly = true)
    val fingerprint = OpenRouterCostMetadata.fingerprint("fake")
    assertEquals("companion_only", reason { spending.beforeUpload("openrouter", fingerprint, request()) })
    val authorized = spending.beforeUpload("openrouter", fingerprint, request(), allowExplicitCloud = true)
    assertEquals(1, disk.durable.read().charges.size)
    authorized.cloudAttemptFinished?.invoke()
    noteFolder = "denied"
    assertEquals("cloud_folder_blocked", reason { spending.beforeUpload("openrouter", fingerprint, request(), allowExplicitCloud = true) })
    assertEquals(1, disk.durable.read().charges.size)
  }

  @Test fun `companion only always stops paid requests and reservation creation`() = runBlocking {
    prefs = prefs.copy(customOnly = true)
    assertEquals("companion_only", reason { upload() })
    assertTrue(server.requests.isEmpty())
    assertTrue(disk.durable.read().charges.isEmpty())
  }

  @Test fun `each retry reserves again and cannot cross an in-flight budget`() = runBlocking {
    spending.configure(CloudCostSettings(budgetUsd = "0.3"))
    server.respond { TinyHttpServer.Response(503, """{"error":{"message":"retry later"}}""") }
    assertEquals("server", reason { upload() })
    assertEquals("cloud_budget_exceeded", reason { upload() })
    assertEquals(1, server.requests.size)
    assertEquals("0.180000", SpendingLedger.totals(disk.durable.read(), System.currentTimeMillis()).reservedUsd)
  }

  @Test fun `cancellation keeps durable uncertain charge and cannot be reconciled while active`() = runBlocking {
    val received = CountDownLatch(1)
    val release = CountDownLatch(1)
    server.respond { received.countDown(); release.await(5, TimeUnit.SECONDS); TinyHttpServer.Response(200, """{"text":"late"}""") }
    val task = async { upload() }
    try {
      assertTrue(withContext(Dispatchers.IO) { received.await(5, TimeUnit.SECONDS) })
      val id = spending.data().charges.single().id
      assertEquals("cloud_charge_inflight", reason { spending.reconcile(id, "0") })
      task.cancelAndJoin()
      assertNull(spending.data().charges.single().settledAt)
      assertEquals("0.180000", spending.data().charges.single().reservedUsd)
      spending.reconcile(id, "0") // Explicit provider statement reconciliation, never an automatic refund.
      assertEquals("0", spending.data().charges.single().actualUsd)
    } finally { task.cancel(); release.countDown() }
  }

  @Test fun `Groq has identical folder authorization and no implicit paid fallback`() = runBlocking {
    val fakeGroq = mockk<TranscriptionProvider>()
    every { fakeGroq.id } returns "groq"
    val guard = CloudTranscriptionGuard(fakeGroq, beforeUpload = { spending.beforeUpload("groq", "groq-key", it) }) { false }
    noteFolder = "denied"
    assertEquals("cloud_folder_blocked", reason { guard.transcribe(audio(), "audio/mpeg", request()) })
    coVerify(exactly = 0) { fakeGroq.transcribe(any(), any(), any(), any(), any()) }
  }

  @Test fun `enqueue used by import bulk selection and background persists a visible policy failure`() = runBlocking {
    val jobs = mockk<JobDao>()
    coEvery { jobs.activeForSession(any()) } returns null
    coEvery { jobs.upsert(any()) } just Runs
    val repo = TranscriptionRepository(jobs, db.sessions(), db.notes(), db.audioParts(), mockk(), mockk(),
      settings, keys, mockk(), mockk(), db, mockk(), mockk(), mockk(), mockk(), spending, mockk(), cooldowns)
    noteFolder = "denied"
    val refused = repo.enqueue("session", TranscriptionProviderId.OPENROUTER)!!
    assertEquals(JobState.FAILED, refused.state)
    assertEquals("cloud_folder_blocked", refused.errorCode)
    val companion = repo.enqueue("session", TranscriptionProviderId.CUSTOM)!!
    assertEquals(JobState.QUEUED, companion.state)
    assertEquals("custom", companion.provider)
    assertTrue(server.requests.isEmpty())
  }

  @Test fun `global budget blocks token-priced upload even if unknown execution is allowed`() = runBlocking {
    disk.durable.update { it.copy(settings = it.settings.copy(budgetUsd = "10"),
      prices = mapOf(model to ModelPrice(model, PriceUnit.TOKEN, "0.000002", "0.000002", fetchedAt = System.currentTimeMillis(), complete = true))) }
    assertEquals("cloud_price_unknown", reason { upload() })
    assertTrue(server.requests.isEmpty())
  }

  @Test fun `changed or removed key stops next upload and retains old ledger attribution`() = runBlocking {
    upload()
    coEvery { keys.key(dev.antigravity.fluidengine.ai.provider.ProviderId.OPENROUTER) } returns "replacement"
    assertEquals("cloud_key_changed", reason { upload() })
    assertEquals(1, server.requests.size)
    assertEquals(OpenRouterCostMetadata.fingerprint("fake"), spending.data().charges.single().keyFingerprint)
  }

  @Test fun `folder model change between chunks retains the pinned request model`() = runBlocking {
    upload()
    spending.setFolder("root", FolderCloudSetting(openRouterModel = "google/gemini-3.5-transcribe"))
    // Unknown metadata is not required to identify a model change. Preload it to keep this test offline.
    disk.durable.update { it.copy(prices = it.prices + ("google/gemini-3.5-transcribe" to ModelPrice("google/gemini-3.5-transcribe",
      fetchedAt = System.currentTimeMillis()))) }
    assertNull(reason { upload() })
    assertEquals(2, server.requests.size)
    assertTrue(server.requests.all { it.body.toString(Charsets.UTF_8).contains(model) })
  }

  @Test fun `removing a folder model override cannot change a pinned job`() = runBlocking {
    val previouslyPrepared = request().copy(model = "google/gemini-3.5-transcribe", cloudDefaultModel = model)
    disk.durable.update { it.copy(prices = it.prices + (previouslyPrepared.model to ModelPrice(previouslyPrepared.model, PriceUnit.AUDIO_SECOND, "0.003", "0.003", fetchedAt = System.currentTimeMillis(), complete = true))) }
    assertNull(reason { provider.transcribe(audio(), "audio/mpeg", previouslyPrepared) })
    assertEquals(1, server.requests.size)
    assertTrue(server.requests.single().body.toString(Charsets.UTF_8).contains(previouslyPrepared.model))
  }

  @Test fun `runner cached whole audio skips uploads and charge accounting on recovery`() = runBlocking {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder("files")
    every { context.cacheDir } returns temp.newFolder("cache")
    val files = dev.pampa.pampanotes.core.files.AppFiles(context)
    files.audioFile("audio.mp3").writeBytes(byteArrayOf(1, 2, 3))
    val runner = TranscriptionRunner(files, mockk())
    val part = db.audioParts().get("part")!!
    runner.transcribeSession("job", listOf(part), provider, request(), 10)
    runner.transcribeSession("job", listOf(part), provider, request(), 10)
    assertEquals(1, server.requests.size)
    assertEquals(1, spending.data().charges.size)
    assertEquals("0.15", spending.data().charges.single().actualUsd)
  }

  @Test fun `submitted overlapping chunk intervals and retries each reserve their full length`() = runBlocking {
    server.respond { TinyHttpServer.Response(503, """{"error":{"message":"later"}}""") }
    repeat(2) {
      assertEquals("server", reason { provider.transcribe(audio(), "audio/mpeg", request().copy(cloudDurationMs = 65_000)) })
    }
    val charges = spending.data().charges
    assertEquals(2, charges.size)
    assertTrue(charges.all { it.durationMs == 65_000L && it.reservedUsd == "0.195000" })
    assertEquals("0.390000", SpendingLedger.totals(spending.data(), System.currentTimeMillis()).reservedUsd)
  }
}
