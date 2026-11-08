package dev.pampa.pampanotes.core.export

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.archive.*
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.relay.*
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.ComputerAuth
import dev.pampa.pampanotes.core.transcription.EndpointResolver
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** PREPARED ONLY: real Room, source reservation, repository, fetcher, service and PN codec.
 * HTTP is an in-memory connection fixture. No socket/server, provider, key or user audio. */
@RunWith(AndroidJUnit4::class)
class CombinedSelectedRelayDIntegrationRoomTest {
  private lateinit var db: PampaDatabase
  private lateinit var directory: File
  private lateinit var files: AppFiles
  private lateinit var context: ContextWrapper
  private val now = 1_794_000_000_000L
  private val paths = mutableListOf<String>()
  private val content = "Allowed PDF synthetic bytes".toByteArray()
  private val forbidden = "Opaque original carries sibling audio".toByteArray()

  @Before fun setup() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    directory = File(app.cacheDir, "combined-d-${UUID.randomUUID()}").also { it.mkdirs() }
    context = object : ContextWrapper(app) {
      override fun getFilesDir() = File(directory, "files").also { it.mkdirs() }
      override fun getCacheDir() = File(directory, "cache").also { it.mkdirs() }
    }
    files = AppFiles(context)
    db = PampaDatabase.inMemory(context)
  }
  @After fun cleanup() { db.close(); directory.deleteRecursively() }

  @Test fun staleSnapshotReservesAndFetchesOnlyAllowedOriginalThroughActualRelayAvailability() = runTest {
    val folder = FolderEntity("f", "Personal", createdAt = 1, updatedAt = 2, kind = FolderEntity.KIND_PERSONAL)
    val note = NoteEntity("n", folder.id, "Selected", "Associated body", createdAt = 1, updatedAt = 2)
    val session = SessionEntity("s", note.id, date = "2026-11-08", position = 0, createdAt = 1, updatedAt = 2)
    val pdf = source("pdf", SourceKind.PDF, content)
    val opaque = source("opaque", SourceKind.SDOCX, forbidden)
    db.folders().upsert(folder); db.notes().upsert(note)
    db.sessions().upsert(session); db.sessions().upsert(session.copy(id = "sibling", position = 1))
    db.sources().upsert(pdf); db.sources().upsert(opaque)
    val inUse = FilesInUse()
    val local = AndroidRelayLocalFiles(db, files, inUse)
    val account = RelaySession("https://fixture.invalid", "synthetic-owner", "synthetic-token", "receiver", "Fixture")
    val accounts = object : RelayAccounts {
      override suspend fun snapshot() = account
      override suspend fun isCurrent(session: RelaySession) = session === account
    }
    val allowedTransfer = transfer(pdf, "allowed")
    val opaqueTransfer = transfer(opaque, "forbidden")
    val relay = RelayRepository(accounts, local, RelayHttp { url ->
      paths += url.path
      check(!url.path.contains(opaqueTransfer.transferId)) { "Forbidden attachment fetched" }
      val body = when (url.path) {
        "/v1/relay" -> RelayLimits.json.encodeToString(RelayDiscovery.serializer(), RelayDiscovery(now,
          listOf(allowedTransfer.copy(chunks = emptyList()), opaqueTransfer.copy(chunks = emptyList())))).toByteArray()
        "/v1/relay/${allowedTransfer.transferId}" -> RelayLimits.json.encodeToString(RelayTransfer.serializer(), allowedTransfer).toByteArray()
        "/v1/relay/${allowedTransfer.transferId}/chunks/0" -> content
        else -> error("Unexpected fixture request")
      }
      connection(url, body, allowedTransfer)
    }, RelayJournal(File(directory, "journal")), object : RelayClock {
      override fun elapsedMillis() = 1000L
      override fun wallMillis() = now
    })
    val settings = PampaSettingsStore(context)
    val computerOnly = ComputerOnlyScope(settings, db.folders(), db.sync())
    val auth = ComputerAuth(account = { null }, manualCode = { null },
      fetch = { _, _ -> error("No companion or account request allowed") }, clock = { now })
    val fetcher = ArchiveFetcher(files, settings, EndpointResolver(), ArchiveHttp(userAgent = "Synthetic-test"),
      db.audioParts(), db.sources(), auth, computerOnly, relay)
    val service = ExportService(context, db.notes(), db.folders(), db.tags(), db.sessions(), db.transcripts(),
      db.segments(), db.sources(), db.audioParts(), files, fetcher, inUse)
    val options = ExportTarget.PAMPA.defaults()
    val stale = service.gather(ExportScope.Sessions(listOf(session.id), "Selected"), options, "Pampa Notes test", "All")
    assertTrue(stale.notes.single().native!!.sources.all { it.storedFileName == null })
    val plan = service.plan(stale, options)
    assertEquals(listOf(pdf.id), plan.toFetch.map { it.id })
    assertEquals(plan.toFetch.toSet(), plan.relayReady)
    assertTrue(plan.unobtainable.isEmpty())
    assertNull(db.sources().get(opaque.id)!!.storedFileName)
    val reserved = PampaBundleOmissions.expectedSourceName(pdf)!!
    assertEquals(reserved, db.sources().get(pdf.id)!!.storedFileName)
    assertTrue(FilesInUse.source(reserved) in inUse.current())
    val progress = mutableListOf<Float>()
    assertEquals(1, service.fetchMissing(plan) { progress += it.fraction }.fetched)
    assertTrue(progress.all { it in 0f..1f } && progress.zipWithNext().all { (a, b) -> a <= b })
    assertArrayEquals(content, files.sourceFile(reserved).readBytes())
    assertNull(stale.notes.single().native!!.sources.single { it.id == pdf.id }.storedFileName)
    val result = service.export(stale, options, ExportDestination.Share)
    assertEquals(1, result.includedSources)
    assertEquals(0, result.skippedSources)
    val archive = result.file!!
    val read = PampaBundleArchive.read(archive)
    val graph = read.data.notes.single()
    assertEquals(listOf(session.id), graph.sessions.map { it.id })
    assertNull(graph.attachments.single { it.id == opaque.id }.path)
    val output = File(directory, "staged-sources").also { it.mkdirs() }
    val staging = PampaBundleStaging(File(directory, "staged-audio").also { it.mkdirs() }, output)
    try {
      val stored = staging.stage(archive, read.root, graph.attachments, {}, {})
      assertArrayEquals(content, File(output, stored.getValue(false to pdf.id)).readBytes())
      assertFalse(stored.containsKey(false to opaque.id))
    } finally { staging.release() }
    assertTrue(paths.any { it.endsWith("/chunks/0") })
    assertFalse(paths.any { it.contains(opaqueTransfer.transferId) })
    assertTrue(service.plan(stale, options).isEmpty)
  }

  private fun source(id: String, kind: SourceKind, bytes: ByteArray) = SourceEntity(id, "n", kind, "$id.bin",
    "application/octet-stream", bytes.size.toLong(), relayHash(bytes), importedAt = 1)
  private fun transfer(source: SourceEntity, seed: String) = RelayTransfer(
    UUID.nameUUIDFromBytes(seed.toByteArray()).toString(), UUID.nameUUIDFromBytes("request-$seed".toByteArray()).toString(),
    "source", source.id, source.sha256, source.sizeBytes, source.mime, source.originalName, "sender", "Fixture", "ready",
    now, now + RelayLimits.TTL_MS, now, RelayLimits.CHUNK_BYTES, 1,
    listOf(RelayChunk(0, source.sizeBytes.toInt(), source.sha256)))
  private fun connection(url: URL, body: ByteArray, transfer: RelayTransfer): HttpURLConnection = object : HttpURLConnection(url) {
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy() = false
    override fun getResponseCode() = 200
    override fun getInputStream() = ByteArrayInputStream(body)
    override fun getHeaderField(name: String): String? = when (name) {
      "Content-Length" -> body.size.toString()
      "X-Chunk-SHA256", "X-File-SHA256" -> transfer.sha256
      "X-Expires-At" -> transfer.expiresAt.toString()
      else -> null
    }
  }
}
