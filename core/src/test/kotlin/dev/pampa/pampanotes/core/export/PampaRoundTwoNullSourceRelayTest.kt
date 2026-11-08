package dev.pampa.pampanotes.core.export

import android.net.Uri
import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.archive.ArchiveMismatch
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.relay.RelayKind
import io.mockk.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real service/planner/fetch orchestration/codec/staging, mocked transport and Room DAO boundary. */
class PampaRoundTwoNullSourceRelayTest {
  @get:Rule val temporary = TemporaryFolder()
  private val options get() = ExportTarget.PAMPA.defaults()
  private val folder = FolderEntity("folder", "Subject", createdAt = 1, updatedAt = 1)
  private val note = NoteEntity("note", folder.id, "Direct export", "Retained body", createdAt = 1, updatedAt = 1)

  @Before fun androidUri() {
    mockkStatic(Uri::class)
    every { Uri.fromFile(any()) } returns mockk<Uri>()
  }
  @After fun resetAndroidUri() { unmockkStatic(Uri::class) }

  @Test fun sharedAndClipboardNullOriginalsAreRecoveredWithoutLocalDonors() = runBlocking {
    val bytes = "original".toByteArray()
    for (kind in listOf(SourceKind.SHARE, SourceKind.CLIPBOARD)) {
      for ((mime, archived) in listOf("application/octet-stream" to 0L, "text/plain" to 3L)) {
        val original = source("original", bytes).copy(kind = kind, mime = mime, archivedAt = archived)
        val fixture = Fixture(listOf(original))
        fixture.ready(original.id, bytes)
        val snapshot = set(listOf(original))
        val plan = fixture.service.plan(snapshot, options)
        assertEquals(listOf(original.id), plan.toFetch.map { it.id })
        assertTrue(plan.unobtainable.isEmpty())
        assertEquals(1, fixture.service.fetchMissing(plan).fetched)
        val exported = fixture.service.export(snapshot, options, ExportDestination.Share)
        assertEquals(1, exported.includedSources)
        assertEquals(0, exported.skippedSources)
        val read = PampaBundleArchive.read(exported.file!!)
        val attachment = read.data.notes.single().attachments.single()
        assertFalse(attachment.fileless)
        assertNotNull(attachment.path)
        java.util.zip.ZipFile(exported.file).use { zip ->
          assertArrayEquals(bytes, zip.getInputStream(zip.getEntry(attachment.path!!)).use { it.readBytes() })
        }
      }
    }
  }

  @Test fun genuinelyFilelessSharedTextDoesNotProbeTheRelay() = runBlocking {
    for (kind in listOf(SourceKind.SHARE, SourceKind.CLIPBOARD)) {
      val original = source("text", "Text provenance".toByteArray()).copy(kind = kind, mime = "text/plain")
      val fixture = Fixture(listOf(original))
      val plan = fixture.service.plan(set(listOf(original)), options)
      assertTrue(plan.isEmpty)
      coVerify(exactly = 0) { fixture.fetcher.relayAvailable(any(), any()) }
      assertNull(fixture.rows.getValue(original.id).storedFileName)
    }
  }

  @Test fun directPlanFetchAndExportRefreshNullOriginalWithoutSeparateReceive() = runBlocking {
    val bytes = "Original PDF bytes\n".toByteArray()
    val original = source("original", bytes)
    val fixture = Fixture(listOf(original))
    fixture.ready(original.id, bytes)
    val snapshot = set(listOf(original))
    val plan = fixture.service.plan(snapshot, options)
    val name = PampaBundleOmissions.expectedSourceName(original)!!
    assertEquals(name, plan.toFetch.single().fileName)
    assertEquals(setOf(plan.toFetch.single()), plan.relayReady)
    assertTrue(plan.unobtainable.isEmpty())
    assertTrue(FilesInUse.source(name) in fixture.inUse.current())
    assertFalse(FilesInUse.source("") in fixture.inUse.current())
    coVerify(exactly = 1) { fixture.fetcher.relayAvailable(RelayKind.SOURCE, original.id) }
    val outcome = fixture.service.fetchMissing(plan)
    assertEquals(1, outcome.fetched)
    assertTrue(outcome.missing.isEmpty())
    assertNull(snapshot.notes.single().native!!.sources.single().storedFileName)
    // Exactly the old ViewModel snapshot: no regather/manual receive before writing.
    var sawWriteHold = false
    val result = fixture.service.export(snapshot, options, ExportDestination.Share) {
      sawWriteHold = sawWriteHold || FilesInUse.source(name) in fixture.inUse.current()
    }
    assertTrue(sawWriteHold)
    assertEquals(1, result.includedSources)
    assertEquals(0, result.skippedSources)
    assertEquals(0L, fixture.rows.getValue(original.id).archivedAt)
    val archive = result.file!!
    val read = PampaBundleArchive.read(archive)
    assertTrue(read.warnings.isEmpty())
    assertNull(read.data.notes.single().sources.single().storedFileName)
    assertEquals(original, read.data.notes.single().sources.single())
    val target = temporary.newFolder()
    val stored = PampaBundleStaging(temporary.newFolder(), target).stage(archive, read.root,
      read.data.notes.single().attachments, {}, {})
    assertArrayEquals(bytes, File(target, stored.getValue(false to original.id)).readBytes())
    assertTrue(fixture.service.plan(snapshot, options).isEmpty)
    coVerify(exactly = 1) { fixture.fetcher.relayAvailable(RelayKind.SOURCE, original.id) }
  }

  @Test fun failedOriginalDoesNotSuppressIndependentReadyFileOrMakeWriterUseStaleRefs() = runBlocking {
    val bytes = "Independent ready original".toByteArray()
    val bad = source("bad", "Bad original".toByteArray())
    val good = source("good", bytes)
    val fixture = Fixture(listOf(bad, good))
    fixture.ready(bad.id, ByteArray(bad.sizeBytes.toInt()), fail = true)
    fixture.ready(good.id, bytes)
    val snapshot = set(listOf(bad, good))
    val plan = fixture.service.plan(snapshot, options)
    assertEquals(listOf(bad.id, good.id), plan.toFetch.map { it.id })
    val outcome = fixture.service.fetchMissing(plan)
    assertEquals(1, outcome.fetched)
    assertEquals(listOf(bad.id), outcome.missing.map { it.file.id })
    assertEquals(MissingReason.FAILED, outcome.missing.single().reason)
    val result = fixture.service.export(snapshot, options, ExportDestination.Share)
    assertEquals(1, result.includedSources)
    assertEquals(1, result.skippedSources)
    val read = PampaBundleArchive.read(result.file!!)
    assertEquals(listOf(PampaBundleWarning.OMITTED_ATTACHMENTS), read.warnings)
    val declarations = read.data.notes.single().attachments.associateBy { it.id }
    assertNull(declarations.getValue(bad.id).path)
    java.util.zip.ZipFile(result.file).use { zip ->
      assertArrayEquals(bytes, zip.getInputStream(zip.getEntry(declarations.getValue(good.id).path!!)).use { it.readBytes() })
    }
  }

  @Test fun failedDiscoveryStillAllowsOtherNullRefOriginalsToBePlanned() = runBlocking {
    val bad = source("bad", byteArrayOf(1))
    val good = source("good", byteArrayOf(2))
    val fixture = Fixture(listOf(bad, good))
    coEvery { fixture.fetcher.relayAvailable(RelayKind.SOURCE, bad.id) } throws IOException("discovery unavailable")
    fixture.ready(good.id, byteArrayOf(2))
    val plan = fixture.service.plan(set(listOf(bad, good)), options)
    assertEquals(listOf(good.id), plan.toFetch.map { it.id })
    assertEquals(listOf(bad.id), plan.unobtainable.map { it.file.id })
    assertEquals(MissingReason.NO_FILE, plan.unobtainable.single().reason)
    assertEquals(1, fixture.service.fetchMissing(plan).fetched)
  }

  @Test fun textOnlyClipboardAndShareMakeNoRelayCallsAndReportNoOmittedFiles() = runBlocking {
    val sources = listOf(SourceKind.CLIPBOARD, SourceKind.SHARE).map { kind ->
      source(kind.name, note.body.toByteArray()).copy(kind = kind, mime = "text/plain")
    }
    val fixture = Fixture(sources)
    val snapshot = set(sources)
    assertTrue(fixture.service.plan(snapshot, options).isEmpty)
    val result = fixture.service.export(snapshot, options, ExportDestination.Share)
    assertEquals(0, result.skippedSources)
    assertEquals(0, result.includedSources)
    assertTrue(PampaBundleArchive.read(result.file!!).warnings.isEmpty())
    coVerify(exactly = 0) { fixture.fetcher.relayAvailable(any(), any()) }
    coVerify(exactly = 0) { fixture.fetcher.fetchSource(any(), any()) }
  }

  @Test fun unknownHashAndChangedIdentityDoNotAdoptAnUnrelatedReservedFile() = runBlocking {
    val unknown = source("unknown", byteArrayOf(1)).copy(sha256 = "")
    val changed = source("changed", byteArrayOf(2))
    val fixture = Fixture(listOf(unknown, changed))
    coEvery { fixture.fetcher.relayAvailable(RelayKind.SOURCE, changed.id) } coAnswers {
      fixture.rows[changed.id] = changed.copy(storedFileName = "foreign.bin", sha256 = "a".repeat(64))
      File(fixture.sources, "foreign.bin").writeBytes(byteArrayOf(9))
      true
    }
    val snapshot = set(listOf(unknown, changed))
    val plan = fixture.service.plan(snapshot, options)
    assertTrue(plan.toFetch.isEmpty())
    assertEquals(setOf(unknown.id, changed.id), plan.unobtainable.map { it.file.id }.toSet())
    assertTrue(plan.unobtainable.all { it.reason == MissingReason.NO_FILE })
    coVerify(exactly = 0) { fixture.fetcher.relayAvailable(RelayKind.SOURCE, unknown.id) }
    val result = fixture.service.export(snapshot, options, ExportDestination.Share)
    assertEquals(2, result.skippedSources)
    assertTrue(PampaBundleArchive.read(result.file!!).data.notes.single().attachments.all { it.path == null })
  }

  @Test fun cancellationDuringNullRefDiscoveryIsNotAFilelessOrMissingSuccess() = runBlocking {
    val source = source("cancel", byteArrayOf(1))
    val fixture = Fixture(listOf(source))
    coEvery { fixture.fetcher.relayAvailable(RelayKind.SOURCE, source.id) } throws CancellationException("cancel")
    try { fixture.service.plan(set(listOf(source)), options); fail("Cancellation expected") }
    catch (_: CancellationException) { }
  }

  private inner class Fixture(initial: List<SourceEntity>) {
    val rows = initial.associateBy { it.id }.toMutableMap()
    val sources = temporary.newFolder()
    private val audio = temporary.newFolder()
    private val exports = temporary.newFolder()
    val inUse = FilesInUse()
    val fetcher = mockk<ArchiveFetcher>()
    private val dao = mockk<SourceDao>().also { sourceDao ->
      coEvery { sourceDao.get(any()) } answers { rows[firstArg<String>()] }
    }
    private val files = mockk<AppFiles> {
      every { sources } returns this@Fixture.sources
      every { audio } returns this@Fixture.audio
      every { exports } returns this@Fixture.exports
      every { sourceFile(any()) } answers { File(this@Fixture.sources, firstArg<String>()) }
      every { audioFile(any()) } answers { File(this@Fixture.audio, firstArg<String>()) }
    }
    val service = ExportService(mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), dao, mockk(), files, fetcher, inUse)

    fun ready(id: String, bytes: ByteArray, fail: Boolean = false) {
      coEvery { fetcher.relayAvailable(RelayKind.SOURCE, id) } coAnswers {
        val row = rows.getValue(id)
        if (row.storedFileName == null) rows[id] = row.copy(storedFileName = PampaBundleOmissions.expectedSourceName(row))
        true
      }
      coEvery { fetcher.fetchSource(match { it.id == id }, any()) } coAnswers {
        if (fail) throw ArchiveMismatch()
        val row = firstArg<SourceEntity>()
        assertEquals(rows.getValue(id), row)
        assertNotNull(row.storedFileName)
        secondArg<(Long, Long) -> Unit>()(bytes.size.toLong(), bytes.size.toLong())
        File(sources, row.storedFileName!!).also { it.writeBytes(bytes) }
      }
    }
  }

  private fun source(id: String, bytes: ByteArray) = SourceEntity(id, note.id, SourceKind.PDF, "$id.pdf", "application/pdf",
    bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, importedAt = 1)
  private fun set(sources: List<SourceEntity>) = ExportSet("Note", "note", listOf(ExportNote(note, listOf(folder.name), emptyList(), emptyList(),
    emptyList(), native = PampaBundleNote(note, sources = sources))), "test", 1, folders = listOf(folder))
}
