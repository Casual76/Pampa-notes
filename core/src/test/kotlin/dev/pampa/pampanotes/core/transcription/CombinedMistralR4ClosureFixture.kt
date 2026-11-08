package dev.pampa.pampanotes.core.transcription

import android.content.Context
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.pampa.pampanotes.core.alignment.AlignmentProtocol
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.repo.TranscriptionRepository
import dev.pampa.pampanotes.core.settings.*
import dev.pampa.pampanotes.core.spending.*
import io.mockk.*
import java.io.*
import java.net.HttpURLConnection
import kotlinx.serialization.json.*

/** Real HTTP/provider/guard/runner/private upload journal/ledger. Only Android/DAO access and
 * transport are mocked. Files contain synthetic bytes; no sockets, codecs, Room or real audio.
 * Recreate provider, gate, runner, job context and cost store on each run to exercise disk replay.
 */
internal class CombinedMistralR4ClosureFixture(val root: File, val paidFirst: Boolean = true) {
  val model = MistralTranscriptionProvider.DEFAULT_MODEL
  val key = "r4-synthetic-key"
  val account = CloudRetry.fingerprint("r4-synthetic-account")
  val fingerprint = CloudRetry.fingerprint(key)
  val context = mockk<Context> {
    every { filesDir } returns File(root, "files")
    every { cacheDir } returns File(root, "cache")
    every { noBackupFilesDir } returns File(root, "private")
  }
  val files = AppFiles(context)
  val quota = File(root, "quota.json")
  val jobContexts = File(root, "job-contexts.json")
  var now = System.currentTimeMillis()
  var status = 200
  var retryAfter: String? = "2"
  var headerFailure: Throwable? = null
  var atStatus: () -> Unit = {}
  var atHeaders: () -> Unit = {}
  var posts = 0
  var headerReads = 0
  var body: String = response(JsonPrimitive("B exact text."))
  val parts = (if (paidFirst) listOf("a", "b") else listOf("b")).mapIndexed { index, id ->
    val audio = files.audioFile("$id.wav").also { it.writeBytes(byteArrayOf(index.toByte(), 42, 7)) }
    AudioPartEntity(id, "session", index, audio.name, audio.name, "audio/wav", audio.length(),
      60_000, AlignmentProtocol.sha256(audio), 1)
  }
  private val settings = mockk<PampaSettingsStore> {
    coEvery { current() } returns PampaSettings(syncAccount = "r4-synthetic-account")
  }
  private val keys = mockk<AiKeyStore>() // Mistral credentials use the provider-neutral key seam.
  private val credentials = mockk<CloudCredentials> { coEvery { key("mistral") } returns this@CombinedMistralR4ClosureFixture.key }
  private val db = mockk<PampaDatabase>()

  init {
    val folders = mockk<FolderDao> { coEvery { all() } returns listOf(FolderEntity("folder", "Folder", createdAt = 1, updatedAt = 1)) }
    val sessions = mockk<SessionDao> {
      coEvery { get("session") } returns SessionEntity("session", "note", date = "2026-11-08", position = 0, createdAt = 1, updatedAt = 1)
    }
    val notes = mockk<NoteDao> { coEvery { get("note") } returns NoteEntity("note", "folder", "Note", createdAt = 1, updatedAt = 1) }
    val audio = mockk<AudioPartDao> { coEvery { get(any()) } answers { parts.single { it.id == firstArg<String>() } } }
    every { db.folders() } returns folders
    every { db.sessions() } returns sessions
    every { db.notes() } returns notes
    every { db.audioParts() } returns audio
    CloudCostStore(context).durable.update { it.copy(
      settings = it.settings.copy(budgetUsd = "0.20", perJobUsd = "0.20"),
      prices = mapOf(model to ModelPrice(model, PriceUnit.AUDIO_SECOND, "0.001", "0.001",
        fetchedAt = System.currentTimeMillis(), complete = true)),
    ) }
  }

  fun cooldowns() = DurableCloudCooldowns(quota)
  fun deadline() = cooldowns().deadline("mistral", fingerprint, account)!!
  fun timingKnown() = cooldowns().retryAfterKnown("mistral", fingerprint, account)
  fun charges() = CloudCostStore(context).durable.read().charges
  fun result(id: String) = files.jobs.resolve("job/part-$id/whole.json")
  fun marker() = File(result("b").path + ".sending")
  fun http() = TranscriptionHttp("offline-combined-r4", cloudGate = CloudRequestGate(cooldowns(),
    { account }, { fingerprint }, { now }), connectionFactory = { target ->
    check(target.host == "api.mistral.ai" && target.path.endsWith("/audio/transcriptions")) { "Unexpected request: $target" }
    object : HttpURLConnection(target) {
      private var attempt = 0
      private var code: Int? = null
      override fun connect() = Unit
      override fun disconnect() = Unit
      override fun usingProxy() = false
      override fun getOutputStream(): OutputStream {
        check(requestMethod == "POST")
        if (attempt == 0) attempt = ++posts
        return ByteArrayOutputStream()
      }
      override fun getResponseCode(): Int = code ?: (if (paidFirst && attempt == 1) 200 else status).also {
        code = it
        if (!paidFirst || attempt > 1) atStatus()
      }
      override fun getHeaderFields(): Map<String?, List<String>> {
        headerReads++
        atHeaders()
        headerFailure?.let { throw it }
        return retryAfter?.let { mapOf("Retry-After" to listOf(it)) } ?: emptyMap()
      }
      override fun getInputStream(): InputStream = ByteArrayInputStream(
        (if (paidFirst && attempt == 1) response(JsonPrimitive(A_TEXT), language = JsonPrimitive(" IT ")) else body).toByteArray(Charsets.UTF_8))
      override fun getErrorStream(): InputStream = ByteArrayInputStream("{}".toByteArray())
    }
  })

  fun repository(): TranscriptionRepository {
    val cooldownStore = mockk<CloudCooldownStore>()
    every { cooldownStore.durable } returns cooldowns()
    every { cooldownStore.contexts } returns DurableCloudJobContexts(jobContexts)
    val spending = CloudSpending(CloudCostStore(context), db, settings, keys, credentials, http())
    return TranscriptionRepository(mockk(), db.sessions(), db.notes(), db.audioParts(), mockk(), mockk(),
      settings, keys, http(), mockk(), db, mockk(), mockk(), mockk(), files, spending, credentials, cooldownStore)
  }

  suspend fun run(job: String = "job", repositoryGuard: Boolean = false): SessionTranscript {
    val contexts = DurableCloudJobContexts(jobContexts)
    check(contexts.bind(job, account).accountFingerprint == account)
    val spending = CloudSpending(CloudCostStore(context), db, settings, keys, credentials, http())
    val provider = if (repositoryGuard) repository().bind("mistral", accountFingerprint = account)!!.provider
      else CloudTranscriptionGuard(MistralTranscriptionProvider(http(), key),
      beforeUpload = { spending.beforeUpload("mistral", fingerprint, it) }, customOnly = { false })
    return try {
      TranscriptionRunner(files, mockk(), TranscriptionWorkStorage()).transcribeSession(job, parts, provider,
        TranscribeRequest(model), 10)
    } catch (limited: TranscriptionError.RateLimited) {
      limited.scopeFingerprint?.let { contexts.setCooldown(job, it, account) }
      throw limited
    }
  }

  companion object {
    const val A_TEXT = "  Paid A,\tunchanged!\r\n "
    fun response(text: JsonElement?, language: JsonElement = JsonPrimitive("it"), segments: JsonArray = JsonArray(emptyList())): String =
      buildJsonObject {
        text?.let { put("text", it) }
        put("language", language)
        put("segments", segments)
        put("duration", 60)
        put("usage", buildJsonObject { put("cost", 0.01) })
      }.toString()
  }
}
