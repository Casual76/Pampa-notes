package dev.pampa.pampanotes.core.transcription

import android.content.Context
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampanotes.core.alignment.AlignmentProtocol
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.settings.*
import dev.pampa.pampanotes.core.spending.*
import io.mockk.*
import java.io.*
import java.net.HttpURLConnection
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic rates/bytes, real guard + ledger + HTTP + runner; no sockets or codecs. */
class ExactCloudRefusalBudgetRegressionTest {
  @get:Rule val temp = TemporaryFolder()

  private inner class Fixture(val paidFirst: Boolean = false) {
    val root = temp.newFolder()
    val model = "openai/whisper-1"
    val context = mockk<Context> {
      every { filesDir } returns File(root, "files")
      every { cacheDir } returns File(root, "cache")
      every { noBackupFilesDir } returns File(root, "private")
    }
    val files = AppFiles(context)
    val disk = CloudCostStore(context)
    val quota = File(root, "quota.json")
    var key = "synthetic-key"
    var now = System.currentTimeMillis()
    var status = 200
    var posts = 0
    var headersUnreadable = false
    var bodyUnreadable = false
    var statusUnknown = false
    var successBody: String? = null
    var errorCost: String? = null
    var atStatus: () -> Unit = {}
    var atHeaders: () -> Unit = {}
    var atBody: () -> Unit = {}
    val parts = (if (paidFirst) listOf("a", "b") else listOf("b")).mapIndexed { index, id ->
      val audio = files.audioFile("$id.wav").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
      AudioPartEntity(id, "session", index, audio.name, audio.name, "audio/wav", 3, 60_000, AlignmentProtocol.sha256(audio), 1)
    }
    val settings = mockk<PampaSettingsStore> { coEvery { current() } returns PampaSettings() }
    val keys = mockk<AiKeyStore> { coEvery { key(ProviderId.OPENROUTER) } answers { this@Fixture.key } }
    val credentials = mockk<CloudCredentials> { coEvery { key("openrouter") } answers { this@Fixture.key } }
    val db = mockk<PampaDatabase>()
    init {
      val folders = mockk<FolderDao> { coEvery { all() } returns listOf(FolderEntity("folder", "Folder", createdAt = 1, updatedAt = 1)) }
      val sessions = mockk<SessionDao> { coEvery { get("session") } returns SessionEntity("session", "note", date = "2026-11-08", position = 0, createdAt = 1, updatedAt = 1) }
      val notes = mockk<NoteDao> { coEvery { get("note") } returns NoteEntity("note", "folder", "Note", createdAt = 1, updatedAt = 1) }
      val audio = mockk<AudioPartDao> { coEvery { get(any()) } answers { parts.single { it.id == firstArg<String>() } } }
      every { db.folders() } returns folders
      every { db.sessions() } returns sessions
      every { db.notes() } returns notes
      every { db.audioParts() } returns audio
      disk.durable.update { it.copy(settings = it.settings.copy(budgetUsd = "0.09", perJobUsd = "0.09"),
        prices = mapOf(model to ModelPrice(model, PriceUnit.AUDIO_SECOND, "0.001", "0.001", fetchedAt = System.currentTimeMillis(), complete = true))) }
    }
    fun http() = TranscriptionHttp("offline-exact-attempt", cloudGate = CloudRequestGate(DurableCloudCooldowns(quota),
      { CloudRetry.fingerprint("account") }, { CloudRetry.fingerprint(key) }, { now }), connectionFactory = { url ->
      object : HttpURLConnection(url) {
        var attempt = 0
        var response: Int? = null
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream {
          check(url.path.endsWith("/audio/transcriptions")) { "Unexpected metadata/network request: $url" }
          if (attempt == 0) attempt = ++posts
          return ByteArrayOutputStream()
        }
        override fun getResponseCode(): Int {
          if (statusUnknown && (!paidFirst || attempt > 1)) throw IOException("synthetic unknown status")
          return response ?: (if (paidFirst && attempt == 1) 200 else status).also { response = it; if (it != 200) atStatus() }
        }
        override fun getHeaderFields(): Map<String?, List<String>> {
          atHeaders()
          if (headersUnreadable) throw IOException("synthetic disconnected header")
          return mapOf("Retry-After" to listOf("172800.125"))
        }
        override fun getInputStream(): InputStream {
          atBody()
          if (bodyUnreadable && (!paidFirst || attempt > 1)) throw IOException("synthetic lost 2xx body")
          val body = (if (paidFirst && attempt == 1) null else successBody)
            ?: """{"text":"Exact paid $attempt.","usage":{"cost":0.01}}"""
          return ByteArrayInputStream(body.toByteArray())
        }
        override fun getErrorStream(): InputStream {
          atBody()
          return ByteArrayInputStream((errorCost?.let { """{"usage":{"cost":$it}}""" } ?: "{}").toByteArray())
        }
      }
    })
    fun spending() = CloudSpending(disk, db, settings, keys, credentials, http())
    fun provider(): TranscriptionProvider {
      val spending = spending()
      return CloudTranscriptionGuard(OpenRouterTranscriptionProvider(http(), key),
        beforeUpload = { spending.beforeUpload("openrouter", CloudRetry.fingerprint(key), it) }, customOnly = { false })
    }
    suspend fun run(job: String = "job", provider: TranscriptionProvider = provider()) = TranscriptionRunner(files, mockk())
      .transcribeSession(job, parts, provider, TranscribeRequest(model), 10)
    fun result(part: String) = files.jobs.resolve("job/part-$part/whole.json")
    fun marker() = File(result("b").path + ".sending")
    fun waitUntil() = DurableCloudCooldowns(quota).deadline("openrouter", CloudRetry.fingerprint(key), CloudRetry.fingerprint("account"))!!
    fun totals() = SpendingLedger.totals(DurableCloudCostStore(File(context.noBackupFilesDir, "cloud-transcription/costs.json")).read(), System.currentTimeMillis())
  }

  @Test fun `exact refusals permit affordable capped retry after reopen without rebilling completed A`() = runBlocking {
    for (code in listOf(401, 402, 429)) for (paid in listOf(false, true)) {
      val f = Fixture(paid).also { it.status = code }
      val error = runCatching { f.run() }.exceptionOrNull() as TranscriptionError
      assertEquals(when (code) { 401 -> "unauthorized"; 402 -> "credit_required"; else -> "rate_limited" }, error.code)
      val paidBytes = if (paid) f.result("a").readBytes() else null
      val charges = f.spending().data().charges
      assertEquals(if (paid) 2 else 1, charges.size)
      assertEquals("0", charges.last().actualUsd)
      assertNotNull(charges.last().refusedAt)
      assertNotNull(charges.last().settledAt)
      assertEquals(0, java.math.BigDecimal(f.totals().reservedUsd).signum())
      assertEquals(if (paid) "0.01" else "0", f.totals().actualUsd)
      val before = f.posts
      if (code == 429) {
        assertTrue(f.waitUntil() >= f.now + 172_800_000)
        assertTrue(runCatching { f.run("other-job") }.exceptionOrNull() is TranscriptionError.RateLimited)
        assertEquals(before, f.posts)
        f.now = f.waitUntil() + 1
      }
      if (code == 401) f.key = "corrected-synthetic-key"
      f.status = 200
      f.run()
      assertEquals(before + 1, f.posts)
      assertEquals(if (paid) "0.02" else "0.01", f.totals().actualUsd)
      assertEquals(0, java.math.BigDecimal(f.totals().reservedUsd).signum())
      if (paidBytes != null) assertArrayEquals(paidBytes, f.result("a").readBytes())
      val finalCharges = f.spending().data().charges
      f.run()
      assertEquals(before + 1, f.posts)
      assertEquals(finalCharges, f.spending().data().charges)
    }
  }

  @Test fun `known 429 survives cancellation in unreadable headers and records honestly unknown timing`() = runBlocking {
    for (throwsCancellation in listOf(false, true)) {
    val f = Fixture(true).also { it.status = 429; it.headersUnreadable = true }
    var observed: Throwable? = null
    val task = launch {
      val own = currentCoroutineContext()[Job]!!
      f.atHeaders = {
        val stop = CancellationException("synthetic stop while headers disconnect")
        own.cancel(stop)
        if (throwsCancellation) throw stop
      }
      observed = runCatching { f.run() }.exceptionOrNull()
    }
    task.join()
    assertTrue(task.isCancelled)
    assertTrue(observed is CancellationException)
    assertTrue(f.marker().readText().startsWith("rejected:"))
    assertEquals(false, DurableCloudCooldowns(f.quota).retryAfterKnown("openrouter", CloudRetry.fingerprint(f.key), CloudRetry.fingerprint("account")))
    assertTrue(f.waitUntil() >= f.now + CloudRetry.DEFAULT_WAIT_MS)
    assertTrue(f.waitUntil() < f.now + 172_800_000) // Unreadable two-day header was NOT claimed honored.
    assertEquals("0.01", f.totals().actualUsd)
    val paid = f.result("a").readBytes()
    f.atHeaders = {}; f.headersUnreadable = false; f.status = 200
    assertTrue(runCatching { f.run("other-job") }.exceptionOrNull() is TranscriptionError.RateLimited)
    assertEquals(2, f.posts)
    f.now = f.waitUntil() + 1
    f.run(); assertEquals(3, f.posts); assertArrayEquals(paid, f.result("a").readBytes())
    }
  }

  @Test fun `known refusals retain exact accounting and full readable wait through system stop`() = runBlocking {
    for (code in listOf(401, 402, 429)) {
      val f = Fixture().also { it.status = code }
      var observed: Throwable? = null
      val task = launch {
        val own = currentCoroutineContext()[Job]!!
        f.atStatus = { own.cancel(CancellationException("synthetic stop after known status")) }
        observed = runCatching { f.run() }.exceptionOrNull()
      }
      task.join(); assertTrue(task.isCancelled); assertTrue(observed is CancellationException)
      assertTrue(f.marker().readText().startsWith("rejected:"))
      assertEquals("0", f.spending().data().charges.single().actualUsd)
      f.atStatus = {}; f.status = 200
      if (code == 429) {
        assertEquals(true, DurableCloudCooldowns(f.quota).retryAfterKnown("openrouter", CloudRetry.fingerprint(f.key), CloudRetry.fingerprint("account")))
        assertTrue(f.waitUntil() >= f.now + 172_800_000)
        f.now = f.waitUntil() + 1
      }
      f.run(); assertEquals(2, f.posts)
    }
  }

  @Test fun `reported error cost wins over provisional zero and is never released by stale refusal`() = runBlocking {
    val f = Fixture(true).also { it.status = 402; it.errorCost = "0.02" }
    assertTrue(runCatching { f.run() }.exceptionOrNull() is TranscriptionError.CreditRequired)
    val rejected = f.spending().data().charges.last()
    assertEquals("0.02", rejected.actualUsd); assertNull(rejected.refusedAt)
    f.disk.durable.refuse(rejected.id, System.currentTimeMillis())
    assertEquals("0.03", f.totals().actualUsd)
    f.status = 200; f.run(); assertEquals(3, f.posts)
    assertEquals("0.04", f.totals().actualUsd)
  }

  @Test fun `unknown status interrupted success and accounting disk failure keep exact barriers across restart`() = runBlocking {
    for (failure in listOf("unknown", "success-body", "success-parse", "success-ledger", "ledger")) {
      val f = Fixture(true)
      when (failure) {
        "unknown" -> f.statusUnknown = true
        "success-body" -> f.bodyUnreadable = true
        "success-parse" -> f.successBody = """{"usage":{"cost":0.02}}"""
        "success-ledger" -> f.atBody = { if (f.posts == 2) File(f.context.noBackupFilesDir, "cloud-transcription/costs.json.new").mkdir() }
        else -> { f.status = 402; f.atStatus = { File(f.context.noBackupFilesDir, "cloud-transcription/costs.json.new").mkdir() } }
      }
      assertNotNull(runCatching { f.run() }.exceptionOrNull())
      val marker = f.marker().readBytes(); val paid = f.result("a").readBytes()
      assertTrue(String(marker).startsWith("pending:"))
      val before = f.posts
      if (failure == "success-parse") {
        assertEquals("0.02", f.spending().data().charges.last().actualUsd)
        assertEquals("0.03", f.totals().actualUsd)
      } else if (failure != "ledger") {
        assertNull(f.spending().data().charges.last().settledAt)
        assertEquals("0.060000", f.totals().reservedUsd)
      }
      f.statusUnknown = false; f.bodyUnreadable = false; f.status = 200; f.atStatus = {}; f.atBody = {}; f.successBody = null
      assertTrue(runCatching { f.run() }.exceptionOrNull() is IOException)
      assertEquals(before, f.posts); assertArrayEquals(marker, f.marker().readBytes()); assertArrayEquals(paid, f.result("a").readBytes())
    }
  }

  @Test fun `quota storage failure cannot authorize retry even after exact spending settlement`() = runBlocking {
    val f = Fixture().also { it.status = 429; it.atHeaders = { File(it.quota.path + ".tmp").mkdir() } }
    assertNotNull(runCatching { f.run() }.exceptionOrNull())
    assertTrue(f.marker().readText().startsWith("pending:"))
    f.status = 200; f.atHeaders = {}; f.now = f.waitUntil() + 1
    assertTrue(runCatching { f.run() }.exceptionOrNull() is IOException)
    assertEquals(1, f.posts)
  }
}
