import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.repo.*
import dev.pampa.pampanotes.core.settings.*
import dev.pampa.pampanotes.core.spending.CloudSpending
import dev.pampa.pampanotes.core.transcription.*
import dev.pampa.pampanotes.work.TranscriptionQueueWorker
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.provider.ProviderId
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import java.io.File
import java.lang.reflect.InvocationTargetException

class C2PreStartMigrationFixture {
  @Test fun privacyMigrationAfterBindingEscapesWorkerRecoveryBeforeStart() = runTest {
    val jobs = mockk<JobDao>()
    val sessions = mockk<SessionDao>()
    val transcripts = mockk<TranscriptDao>()
    val parts = mockk<AudioPartDao>()
    val settings = mockk<PampaSettingsStore>()
    val keys = mockk<AiKeyStore>()
    val http = mockk<TranscriptionHttp>()
    val cooldowns = mockk<CloudCooldownStore>()
    val files = mockk<dev.pampa.pampanotes.core.files.AppFiles>()
    val old = JobEntity("fixture", "session", JobType.TRANSCRIBE, "openrouter", model="chosen/model", optionsJson="""{"cloudChunkMinutes":10,"groqMaxUploadMb":25}""", createdAt=1, updatedAt=1)
    var authoritative = old
    var prefs = PampaSettings(syncAccount="fixture-account")
    coEvery { settings.current() } answers { prefs }
    coEvery { jobs.get("fixture") } answers { authoritative }
    coEvery { sessions.get("session") } returns null
    coEvery { transcripts.bySession("session") } returns emptyList()
    coEvery { keys.key(ProviderId.OPENROUTER) } returns "fixture-key"
    val contextsFile=File(System.getProperty("java.io.tmpdir"), "prestart-contexts.json")
    every { cooldowns.contexts } returns DurableCloudJobContexts(contextsFile)
    val part=mockk<AudioPartEntity> { every { id } returns "part" }
    // Exact pause point: partsOf is a suspend DB call after the final privacy check/bind.
    coEvery { parts.bySession("session") } answers {
      prefs = prefs.copy(customOnly=true)
      authoritative=old.copy(provider="custom", model=null)
      listOf(part)
    }
    val repo=TranscriptionRepository(jobs,sessions,mockk(),parts,transcripts,mockk(),settings,keys,http,mockk(),mockk(),mockk(),mockk(),mockk(),files,mockk<CloudSpending>(),mockk(),cooldowns)
    val unsafeField=sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible=true }
    val worker=(unsafeField.get(null) as sun.misc.Unsafe).allocateInstance(TranscriptionQueueWorker::class.java) as TranscriptionQueueWorker
    for ((name,value) in mapOf("repository" to repo,"settingsStore" to settings)) worker.javaClass.getDeclaredField(name).apply { isAccessible=true;set(worker,value) }
    val method=worker.javaClass.declaredMethods.single { it.name=="transcribe" }.apply { isAccessible=true }
    val error=try {
      suspendCoroutineUninterceptedOrReturn<Any?> { continuation -> method.invoke(worker,old,continuation) }
      null
    } catch (ex:InvocationTargetException) { ex.targetException } catch (ex:Throwable) { ex }
    assertTrue("actual private transcribe must expose CompanionOnly before its local catch", error is TranscriptionError.CompanionOnly)
    assertEquals("custom",authoritative.provider)
    assertEquals(JobState.QUEUED,authoritative.state)
    assertNull(authoritative.model)
    coVerify(exactly=0) { jobs.start(any(),any(),any(),any(),any()) }
    coVerify(exactly=0) { http.getJson(any(),any(),any()) }
    println("OBSERVED actual cached worker transcribe escaped companion_only; current row is custom/QUEUED/null; no start, catalogue or upload")
  }
}

