package dev.pampa.pampanotes.core.export

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.alignment.*
import dev.pampa.pampanotes.core.archive.*
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.importing.PampaBundleArchive
import dev.pampa.pampanotes.core.relay.*
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.ComputerAuth
import dev.pampa.pampanotes.core.transcription.EndpointResolver
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** PREPARED ONLY. Real isolated in-memory Room -> gather -> plan -> production PN write/read.
 * All bytes are synthetic. Account/HTTP hooks fail if reached; no user DB or real audio. */
@RunWith(AndroidJUnit4::class)
class R6PampaHistoryScopeRoomTest {
  private lateinit var db: PampaDatabase
  private lateinit var directory: File
  private lateinit var files: AppFiles
  private lateinit var service: ExportService
  private val pn = ExportTarget.PAMPA.defaults()

  @Before fun setup() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    directory = File(app.cacheDir, "r6-history-${UUID.randomUUID()}").also { it.mkdirs() }
    val context = object : ContextWrapper(app) {
      override fun getFilesDir() = File(directory, "files").also { it.mkdirs() }
      override fun getCacheDir() = File(directory, "cache").also { it.mkdirs() }
    }
    db = PampaDatabase.inMemory(context)
    files = AppFiles(context)
    val inUse = FilesInUse()
    val relay = RelayRepository(object : RelayAccounts {
      override suspend fun snapshot(): RelaySession = error("Unexpected attachment discovery/account access")
      override suspend fun isCurrent(session: RelaySession): Boolean = error("Unexpected account access")
    }, AndroidRelayLocalFiles(db, files, inUse), RelayHttp { error("No HTTP allowed") },
      RelayJournal(File(directory, "journal")), object : RelayClock {
        override fun elapsedMillis() = 1000L
        override fun wallMillis() = 1000L
      })
    val settings = PampaSettingsStore(context)
    val fetcher = ArchiveFetcher(files, settings, EndpointResolver(), ArchiveHttp(userAgent = "Synthetic-test"),
      db.audioParts(), db.sources(), ComputerAuth(account = { null }, manualCode = { null },
        fetch = { _, _ -> error("No companion/account calls") }, clock = { 1000L }),
      ComputerOnlyScope(settings, db.folders(), db.sync()), relay)
    service = ExportService(context, db.notes(), db.folders(), db.tags(), db.sessions(), db.transcripts(),
      db.segments(), db.sources(), db.audioParts(), files, fetcher, inUse)
  }
  @After fun cleanup() { db.close(); directory.deleteRecursively() }

  private suspend fun seed(sibling: Boolean, typed: Boolean = false, active: String = "ref") {
    db.folders().upsert(FolderEntity("f", "Personal", createdAt = 1, updatedAt = 2, kind = FolderEntity.KIND_PERSONAL))
    db.notes().upsert(NoteEntity("n", "f", "Personal recording", "Associated material", createdAt = 1, updatedAt = 2))
    db.sessions().upsert(SessionEntity("s", "n", date = "2026-11-08", position = 0, createdAt = 1, updatedAt = 2))
    val p = AudioPartEntity("p", "s", 0, "p.wav", "p.wav", "audio/wav", 0, 1000, AlignmentProtocol.sha256(""), 1)
    db.audioParts().upsert(p); files.audioFile(p.fileName).writeBytes(byteArrayOf())
    var raw = TranscriptEntity("raw", "s", TranscriptKind.RAW, "custom", "whisper", text = "RAW words", wordCount = 2, createdAt = 1)
    if (typed) raw = raw.copy(cloudSourceJson = CloudSources.encode(CloudSources.create(raw, raw.text,
      listOf(CloudTextPart(p.id, p.sha256, raw.text, "en", raw.provider, raw.model)))))
    db.transcripts().upsert(raw)
    db.transcripts().upsert(raw.copy(id = "ref", kind = TranscriptKind.REFINED, parentId = raw.id, cloudSourceJson = null,
      text = "Selected refined words", createdAt = 2))
    db.sessions().upsert(db.sessions().get("s")!!.copy(activeTranscriptId = active))
    if (sibling) {
      db.sessions().upsert(SessionEntity("private", "n", date = "2026-11-08", position = 1, createdAt = 1, updatedAt = 2))
      db.audioParts().upsert(p.copy(id = "private-p", sessionId = "private", fileName = "private.wav"))
    }
  }

  private fun writeRead(set: ExportSet) = File(directory, "result.pn").let { file ->
    file.outputStream().use { PampaNoteWriter(files.audio, files.sources).write(set, it) }
    PampaBundleArchive.read(file).data.notes.single()
  }

  @Test fun legacySingleSessionIsBlockedUntilExplicitWholeNoteRequestThenAllHistoryRoundtrips() = runTest {
    seed(sibling = false)
    val before = db.transcripts().bySession("s")
    try { service.gather(ExportScope.Sessions(listOf("s"), "One"), pn, "R6 fixture", "All"); fail("Silent legacy history loss") }
    catch (failure: PampaHistoryScopeException) { assertEquals(listOf("n"), failure.noteIds) }
    assertEquals("ref", db.sessions().get("s")!!.activeTranscriptId)
    assertEquals(before, db.transcripts().bySession("s"))
    assertFalse(File(directory, "result.pn").exists())
    val whole = service.gather(ExportScope.Note("n"), pn, "R6 fixture", "All")
    assertTrue(service.plan(whole, pn).isEmpty)
    val restored = writeRead(whole)
    assertEquals(before, restored.transcripts)
    assertEquals("ref", restored.sessions.single().activeTranscriptId)
    assertEquals(before, db.transcripts().bySession("s"))
  }

  @Test fun legacyUnselectedRefinementAlsoBlocksTwoSessionRestrictedPNWithoutFetchingSibling() = runTest {
    seed(sibling = true, active = "raw")
    try { service.gather(ExportScope.Sessions(listOf("s"), "One"), pn, "R6 fixture", "All"); fail("Unselected history disappeared") }
    catch (_: PampaHistoryScopeException) { }
    assertEquals(2, db.sessions().plainByNote("n").size)
    assertFalse(files.audioFile("private.wav").exists())
    assertEquals("raw", db.sessions().get("s")!!.activeTranscriptId)
  }

  @Test fun provenTypedRefinementKeepsSelectionThroughRoomGatherPlanWriterReaderWithoutPrivateOriginalDiscovery() = runTest {
    seed(sibling = true, typed = true)
    db.sources().upsert(SourceEntity("private-source", "n", SourceKind.SDOCX, "Private container", "application/octet-stream",
      17, AlignmentProtocol.sha256("private-container"), "private.sdocx", importedAt = 1))
    val set = service.gather(ExportScope.Sessions(listOf("s"), "One"), pn, "R6 fixture", "All")
    assertTrue(service.plan(set, pn).isEmpty) // Account hook would throw if opaque/sibling discovery broadened.
    val restored = writeRead(set)
    assertEquals(listOf("s"), restored.sessions.map { it.id })
    assertEquals("ref", restored.sessions.single().activeTranscriptId)
    assertEquals(setOf("raw", "ref"), restored.transcripts.map { it.id }.toSet())
    assertEquals("Selected refined words", restored.transcripts.single { it.id == "ref" }.text)
    assertNull(restored.attachments.single { it.id == "private-source" }.path)
    assertFalse(files.audioFile("private.wav").exists())
    assertFalse(files.sourceFile("private.sdocx").exists())
  }
}
