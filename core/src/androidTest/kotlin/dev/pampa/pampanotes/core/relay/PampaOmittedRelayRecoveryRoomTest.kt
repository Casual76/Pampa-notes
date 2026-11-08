package dev.pampa.pampanotes.core.relay

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.export.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.importing.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Source/compile-only in this workflow. Real Room/import/adapter/HTTP validation/relay/PN writer;
 * an in-memory HTTP transport supplies test bytes, with no deployed service or Android QA claim. */
@RunWith(AndroidJUnit4::class)
class PampaOmittedRelayRecoveryRoomTest {
  private lateinit var db: PampaDatabase
  private lateinit var files: AppFiles
  private lateinit var directory: File
  private lateinit var availability: RelayFileAvailability
  private lateinit var local: AndroidRelayLocalFiles
  private val bytes = "Original attachment\n".toByteArray()

  @Before fun setup() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    directory = File(context.cacheDir, "omitted-relay-${UUID.randomUUID()}").also { it.mkdirs() }
    val isolated = object : ContextWrapper(context) {
      override fun getFilesDir() = File(directory, "files").also { it.mkdirs() }
      override fun getCacheDir() = File(directory, "cache").also { it.mkdirs() }
    }
    db = PampaDatabase.inMemory(context)
    files = AppFiles(isolated)
    availability = RelayFileAvailability()
    local = AndroidRelayLocalFiles(db, files, FilesInUse(), availability)
  }

  @After fun cleanup() { db.close(); directory.deleteRecursively() }

  private fun exportSet(graph: PampaBundleNote) = ExportSet("Transfer", "transfer",
    listOf(ExportNote(graph.note, listOf("Folder"), emptyList(), emptyList(), emptyList(), native = graph)),
    "test", 1, folders = listOf(FolderEntity("folder", "Folder", createdAt = 1, updatedAt = 1)))

  private suspend fun importOmitted(): PampaBundleNote {
    val note = NoteEntity("note", "folder", "Omitted attachments", "Body", createdAt = 1, updatedAt = 1)
    val session = SessionEntity("session", note.id, date = "2026-11-08", position = 0, createdAt = 1, updatedAt = 1)
    val part = AudioPartEntity("part-a", session.id, 0, "absent-a.m4a", "Recording.m4a", "audio/mp4",
      bytes.size.toLong(), 1000, relayHash(bytes), 1, recordedAtLocal = "2026-11-08", recordedAtPrecision = "day")
    val raw = TranscriptEntity("raw", session.id, TranscriptKind.RAW, "openrouter", "cloud", text = "Exact text",
      wordCount = 2, createdAt = 1, cloudSourceJson = "  exact cloud source  ")
    val source = SourceEntity("source", note.id, SourceKind.TEXT, "Original.txt", "text/plain",
      bytes.size.toLong(), relayHash(bytes), storedFileName = "absent.txt", importedAt = 1)
    val graph = PampaBundleNote(note, sessions = listOf(session), parts = listOf(part,
      part.copy(id = "part-b", position = 1, fileName = "absent-b.m4a")), transcripts = listOf(raw), sources = listOf(source))
    val archive = File(directory, "omitted.pn")
    archive.outputStream().use { PampaNoteWriter(files.audio, files.sources).write(exportSet(graph), it) }
    assertTrue(PampaBundleArchive.read(archive).data.notes.single().attachments.all { it.path == null })
    PampaBundleImporter(db, files).import(archive, updateExisting = true)
    return graph.copy(parts = db.audioParts().all(), sources = db.sources().all(), transcripts = db.transcripts().bySession(session.id))
  }

  @Test fun importedOmissionsReceiveVerifiedBytesAtStablePathsAndReexportEveryIdentity() = runTest {
    val imported = importOmitted()
    val refs = local.list("note")
    assertEquals(3, refs.size)
    assertEquals(3, refs.map { it.fileName }.toSet().size)
    refs.forEach { assertTrue(it.fileName.startsWith("missing-")); assertFalse(local.path(it).exists()) }
    val fixture = ReceiptTransport(refs.filter { it.kind == RelayKind.SOURCE || it.entityId == "part-a" })
    val result = fixture.repository().receiveReady()
    assertEquals(3, result.results.size)
    assertTrue(result.results.all { it.state == RelayState.RECEIVED })
    assertEquals(3L, availability.changes.value)
    // The adapter never renames a receipt or changes its revision after verified placement.
    refs.forEach { ref ->
      assertEquals(ref, local.get(ref.kind, ref.entityId))
      val received = result.results.single { it.entityId == ref.entityId }
      assertEquals(local.path(ref), received.file)
      assertArrayEquals(bytes, received.file!!.readBytes())
    }
    assertEquals(imported.parts, db.audioParts().all())
    assertEquals(imported.sources, db.sources().all())
    assertEquals(imported.transcripts, db.transcripts().bySession("session"))
    imported.parts.forEach { assertFalse(PampaBundleOmissions.isOmitted(it, files.audioFile(it.fileName).isFile)) }
    val reexport = File(directory, "recovered.pn")
    reexport.outputStream().use { PampaNoteWriter(files.audio, files.sources).write(exportSet(imported), it) }
    val read = PampaBundleArchive.read(reexport)
    val attachments = read.data.notes.single().attachments
    assertTrue(read.warnings.isEmpty())
    assertEquals(3, attachments.size)
    assertEquals(3, attachments.map { it.id }.toSet().size)
    assertTrue(attachments.all { it.path != null })
    // Shared bytes deduplicate, while all three entity IDs survive.
    assertEquals(1, attachments.map { it.path }.toSet().size)
    val audio = File(directory, "staged-audio").also { it.mkdirs() }
    val sources = File(directory, "staged-sources").also { it.mkdirs() }
    val staged = PampaBundleStaging(audio, sources).stage(reexport, read.root, attachments, {}, {})
    attachments.forEach { attachment ->
      assertArrayEquals(bytes, File(if (attachment.audio) audio else sources,
        staged.getValue(attachment.audio to attachment.id)).readBytes())
    }
  }

  @Test fun olderNullSourceReferencesAreReservedOnceAndClipboardShareUnknownHashesStayUntouched() = runTest {
    val imported = importOmitted()
    val legacy = imported.sources.single().copy(storedFileName = null)
    db.sources().upsert(legacy)
    val excluded = listOf(legacy.copy(id = "clipboard", kind = SourceKind.CLIPBOARD),
      legacy.copy(id = "share", kind = SourceKind.SHARE), legacy.copy(id = "unknown", sha256 = ""))
    excluded.forEach { db.sources().upsert(it) }
    // Manual receiveReady discovers and reserves old null references through adapter.list().
    val incoming = RelayLocalFile(RelayKind.SOURCE, legacy.id, legacy.noteId,
      PampaBundleOmissions.expectedSourceName(legacy)!!, legacy.originalName, legacy.mime,
      legacy.sha256, legacy.sizeBytes, "sender-revision")
    val received = ReceiptTransport(listOf(incoming)).repository().receiveReady().results.single()
    assertEquals(RelayState.RECEIVED, received.state)
    val ref = local.get(RelayKind.SOURCE, legacy.id)!!
    assertEquals(PampaBundleOmissions.expectedSourceName(legacy), ref.fileName)
    assertEquals(legacy.copy(storedFileName = ref.fileName), db.sources().get(legacy.id))
    assertEquals(ref, local.get(ref.kind, ref.entityId))
    excluded.forEach { assertNull(local.get(RelayKind.SOURCE, it.id)); assertEquals(it, db.sources().get(it.id)) }
    assertEquals(local.path(ref), received.file)
    assertArrayEquals(bytes, received.file!!.readBytes())
    assertEquals(0L, db.sources().get(legacy.id)!!.archivedAt)
    assertEquals(1L, availability.changes.value)
  }

  @Test fun changedRowsAndFailedPlacementDoNotPublishAvailability() = runTest {
    importOmitted()
    val ref = local.get(RelayKind.AUDIO, "part-a")!!
    val changed = db.audioParts().get(ref.entityId)!!.copy(originalName = "Changed.m4a")
    db.audioParts().upsert(changed)
    assertFalse(local.placeIfCurrent(ref) { fail("stale placement") })
    assertEquals(0L, availability.changes.value)
    val current = local.get(ref.kind, ref.entityId)!!
    try {
      local.placeIfCurrent(current) { throw java.io.IOException("interrupted placement") }
      fail("placement must fail")
    } catch (_: java.io.IOException) { }
    assertFalse(local.path(ref).exists())
    assertEquals(changed, db.audioParts().get(ref.entityId))
    assertEquals(0L, availability.changes.value)
  }

  @Test fun corruptBytesAndAccountSwitchCannotCreateAReceiptOrAvailabilitySignal() = runTest {
    importOmitted()
    val ref = local.get(RelayKind.AUDIO, "part-a")!!
    val corrupt = ReceiptTransport(listOf(ref), corrupt = true).repository().fetch(ref.kind, ref.entityId)
    assertEquals(RelayState.INTEGRITY_ERROR, corrupt.state)
    assertFalse(local.path(ref).exists())
    assertEquals(0L, availability.changes.value)
    val switched = ReceiptTransport(listOf(ref), switchAccount = true).repository().fetch(ref.kind, ref.entityId)
    assertEquals(RelayState.ACCOUNT_CHANGED, switched.state)
    assertFalse(local.path(ref).exists())
    assertEquals(0L, availability.changes.value)
    assertEquals(0L, db.audioParts().get(ref.entityId)!!.archivedAt)
  }

  /** Test transport only; production RelayHttp still checks every header/length/hash and the
   * repository still checks account, row, kind, TTL, assembled hash and transactional placement. */
  private inner class ReceiptTransport(refs: List<RelayLocalFile>, val corrupt: Boolean = false,
    val switchAccount: Boolean = false) {
    private val now = 1_794_000_000_000L
    private var current = true
    private val session = RelaySession("https://relay.fixture.invalid", "owner", "test-token", "receiver", "Receiver")
    private val transfers = refs.map { ref -> RelayTransfer(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
      ref.kind.wire, ref.entityId, ref.sha256, ref.bytes, ref.mime, ref.originalName, "sender", "Sender", "ready",
      now, now + RelayLimits.TTL_MS, now, RelayLimits.CHUNK_BYTES, 1, listOf(RelayChunk(0, bytes.size, relayHash(bytes)))) }

    fun repository() = RelayRepository(object : RelayAccounts {
      override suspend fun snapshot() = session
      override suspend fun isCurrent(session: RelaySession) = current && session === this@ReceiptTransport.session
    }, local, RelayHttp(::connection), RelayJournal(File(directory, "journal-${UUID.randomUUID()}")), object : RelayClock {
      override fun elapsedMillis() = 10_000L
      override fun wallMillis() = now
    })

    private fun connection(url: URL): HttpURLConnection {
      val transfer = transfers.firstOrNull { url.path.contains(it.transferId) }
      val chunk = url.path.endsWith("/chunks/0")
      val payload = if (chunk) bytes.copyOf().also {
        if (corrupt) it[0] = (it[0].toInt() xor 1).toByte()
      } else (if (transfer != null) RelayLimits.json.encodeToString(RelayTransfer.serializer(), transfer)
        else RelayLimits.json.encodeToString(RelayDiscovery.serializer(), RelayDiscovery(now, transfers.map { it.copy(chunks = emptyList()) })))
        .toByteArray()
      return object : HttpURLConnection(url) {
        override fun connect() { }
        override fun disconnect() { }
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(payload).also { if (chunk && switchAccount) current = false }
        override fun getHeaderField(name: String): String? = when (name) {
          "Content-Length" -> payload.size.toString()
          "X-Chunk-SHA256", "X-File-SHA256" -> transfer?.sha256
          "X-Expires-At" -> transfer?.expiresAt?.toString()
          else -> null
        }
      }
    }
  }
}
