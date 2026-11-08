package c3independent

import android.content.Context
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.pampa.pampanotes.core.alignment.AlignmentProtocol
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.settings.*
import dev.pampa.pampanotes.core.spending.*
import dev.pampa.pampanotes.core.transcription.*
import io.mockk.*
import java.io.*
import java.net.*
import kotlinx.coroutines.*

/** Tiny offline caller against cached integrated production classes.
 * Synthetic bytes, whole-file path, in-memory HTTP. No listeners or codecs.
 */
object AdversarialC3 {
  private val root = File(System.getProperty("review.root"))
  private const val key = "offline-fixture-key"
  private const val account = "offline-fixture-account"
  private const val model = "openai/whisper-1"
  private class Fixture(name: String) {
    val dir = File(root, name + "-" + java.util.UUID.randomUUID()).also { check(it.mkdirs()) }
    val context = mockk<Context> {
      every { filesDir } returns File(dir, "files")
      every { cacheDir } returns File(dir, "cache")
      every { noBackupFilesDir } returns File(dir, "private")
    }
    val files = AppFiles(context)
    val audio = files.audioFile("part.wav").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
    val part = AudioPartEntity("p", "s", 0, audio.name, audio.name, "audio/fixture", 3, 60_000, AlignmentProtocol.sha256(audio), 1)
    val quota = File(dir, "quota.json")
    var now = System.currentTimeMillis()
    var status = 429
    var posts = 0
    var own: Job? = null
    var boundary = "none"
    @Volatile var quotaBlockedAndCancelled = false
    fun stopWhileQuotaWriteWaits() {
      val monitor = DurableCloudCooldowns::class.java.getDeclaredField("lock").also { it.isAccessible = true }.get(null)
      val held = java.util.concurrent.CountDownLatch(1)
      val release = java.util.concurrent.CountDownLatch(1)
      kotlin.concurrent.thread(name="offline-quota-monitor", isDaemon=true) {
        synchronized(monitor) { held.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
      }
      check(held.await(5, java.util.concurrent.TimeUnit.SECONDS))
      kotlin.concurrent.thread(name="offline-quota-cancel", isDaemon=true) {
        try {
          val until = System.nanoTime() + 4_000_000_000L
          while (System.nanoTime() < until) {
            if (Thread.getAllStackTraces().any { (thread, frames) ->
                thread.state == Thread.State.BLOCKED && frames.any { it.className.endsWith("DurableCloudCooldowns") && it.methodName == "extend" } }) {
              quotaBlockedAndCancelled = true
              own!!.cancel(CancellationException("independent system stop during blocked cooldown IO"))
              break
            }
            Thread.yield()
          }
        } finally { release.countDown() }
      }
    }
    fun deadline() = DurableCloudCooldowns(quota).deadline("openrouter", CloudRetry.fingerprint(key), CloudRetry.fingerprint(account))
    val http = TranscriptionHttp("independent-c3", cloudGate = CloudRequestGate(DurableCloudCooldowns(quota),
      { CloudRetry.fingerprint(account) }, { CloudRetry.fingerprint(key) }, { now }), connectionFactory = { url ->
      object : HttpURLConnection(url) {
        var seen = false
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getResponseCode(): Int { if (!seen) { seen=true; posts++ }; return status }
        override fun getHeaderFields(): Map<String?, List<String>> {
          if (boundary == "quota-block") stopWhileQuotaWriteWaits()
          if (boundary.startsWith("header")) {
            own!!.cancel(CancellationException("independent system stop in header IO"))
            if (boundary == "header-failure") throw IOException("headers unavailable after disconnect")
          }
          return mapOf("Retry-After" to listOf("3600"))
        }
        override fun getErrorStream(): InputStream {
          if (boundary == "body-cancel") {
            own!!.cancel(CancellationException("independent system stop in body IO"))
            throw IOException("error body disconnected")
          }
          return ByteArrayInputStream("{}".toByteArray())
        }
        override fun getInputStream(): InputStream =
          ByteArrayInputStream("""{"text":"paid text","language":"it"}""".toByteArray())
      }
    })
    val direct = OpenRouterTranscriptionProvider(http, key)
    val request = TranscribeRequest(model)
    suspend fun run(provider: TranscriptionProvider = direct) =
      TranscriptionRunner(files, mockk()).transcribeSession("job", listOf(part), provider, request, 10)
    fun marker() = files.jobs.resolve("job/part-p/whole.json.sending")
  }
  @JvmStatic fun main(args: Array<String>) = runBlocking {
    for (boundary in listOf("header-return", "body-cancel", "quota-block", "header-failure")) {
      val f = Fixture(boundary); f.boundary = boundary
      var first: Throwable? = null
      val job = launch {
        f.own = currentCoroutineContext()[Job]!!
        first = runCatching { f.run() }.exceptionOrNull()
      }
      job.join()
      println("FIRST_BOUNDARY $boundary cancelled=" + job.isCancelled + " first=" + first)
      check(job.isCancelled && first is CancellationException)
      if (boundary == "quota-block") check(f.quotaBlockedAndCancelled)
      val markerBefore = f.marker().readText()
      val at = f.deadline()
      f.boundary = "none"; f.status = 200
      f.now = (at ?: f.now + 3_600_000L) + 1
      val retry = runCatching { f.run() }.exceptionOrNull()
      println("HEADER_BOUNDARY $boundary first=" + first!!::class.simpleName + " quota=$at marker=$markerBefore retry=" + retry + " posts=" + f.posts)
      if (boundary == "header-failure") {
        check(at == null && markerBefore.startsWith("pending:") && retry is IOException && f.posts == 1)
        println("REPRODUCED known429 header IO cancellation loses quota and permanently blocks automatic recovery")
      } else check(at != null && markerBefore.startsWith("rejected:") && retry == null && f.posts == 2)
    }

    // Real CloudSpending + guard + HTTP + runner; verified 60 s request estimate = USD 0.60.
    for (budget in listOf<String?>(null, "1")) {
      val f = Fixture("budget-" + (budget ?: "none"))
      val settings = mockk<PampaSettingsStore>()
      coEvery { settings.current() } returns PampaSettings(preferredProvider=TranscriptionProviderId.OPENROUTER)
      val keys = mockk<AiKeyStore>(relaxed=true)
      coEvery { keys.key(ProviderId.OPENROUTER) } returns key
      val db = mockk<PampaDatabase>()
      val folders = mockk<FolderDao>(); val sessions = mockk<SessionDao>(); val notes = mockk<NoteDao>(); val parts = mockk<AudioPartDao>()
      every { db.folders() } returns folders
      every { db.sessions() } returns sessions
      every { db.notes() } returns notes
      every { db.audioParts() } returns parts
      coEvery { folders.all() } returns listOf(FolderEntity("folder","Folder",createdAt=1,updatedAt=1))
      coEvery { sessions.get("s") } returns SessionEntity("s","n",date="2026-11-08",position=0,createdAt=1,updatedAt=1)
      coEvery { notes.get("n") } returns NoteEntity("n","folder","Note",createdAt=1,updatedAt=1)
      coEvery { parts.get("p") } returns f.part
      val disk = CloudCostStore(f.context)
      disk.durable.update { it.copy(settings=it.settings.copy(budgetUsd=budget),
        prices=mapOf(model to ModelPrice(model,PriceUnit.AUDIO_SECOND,"0.01","0.01",fetchedAt=System.currentTimeMillis(),complete=true))) }
      val spending = CloudSpending(disk,db,settings,keys,CloudCredentials(f.context,keys),f.http)
      val provider = CloudTranscriptionGuard(f.direct,
        beforeUpload={spending.beforeUpload("openrouter",CloudRetry.fingerprint(key),it)},customOnly={false})
      val first = runCatching { f.run(provider) }.exceptionOrNull()
      check(first is TranscriptionError.RateLimited)
      val reserved = disk.durable.read().charges.single()
      check(reserved.reservedUsd == "0.600000" && reserved.settledAt == null)
      f.now=f.deadline()!!+1; f.status=200
      val second=runCatching { f.run(provider) }.exceptionOrNull()
      println("BUDGET_RECOVERY budget=$budget first=" + first.code + " marker=" + f.marker().takeIf { it.exists() }?.readText() + " reservation=" + reserved.reservedUsd + " settledAt=" + reserved.settledAt + " retry=" + second + " posts=" + f.posts + " charges=" + disk.durable.read().charges.size)
      if (budget == null) check(second == null && f.posts == 2)
      else {
        check(second is TranscriptionError.CloudControl && second.code=="cloud_budget_exceeded" && f.posts==1)
        println("REPRODUCED definitive429 consumes reservation and prevents affordable automatic retry after cooldown")
      }
    }
  }
}

