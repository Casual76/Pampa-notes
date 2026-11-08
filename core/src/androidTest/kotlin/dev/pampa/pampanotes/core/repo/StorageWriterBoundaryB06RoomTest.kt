package dev.pampa.pampanotes.core.repo

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.antigravity.fluidengine.ai.keys.SecretCipher
import dev.pampa.pampanotes.core.archive.ComputerOnlyScope
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** PREPARED ONLY. Real Room, real StorageRepository, offline confirmation port, synthetic bytes.
 * No HTTP/server/provider and no mock transaction runner. Not executed in the implementation lease. */
@RunWith(AndroidJUnit4::class)
class StorageWriterBoundaryB06RoomTest {
  private class Fixture(val computer: Boolean, val source: Boolean) : AutoCloseable {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(base.cacheDir, "storage-b06-${UUID.randomUUID()}").also { it.mkdirs() }
    private val context = object : ContextWrapper(base) {
      override fun getFilesDir() = File(directory, "files").also { it.mkdirs() }
      override fun getCacheDir() = File(directory, "cache").also { it.mkdirs() }
    }
    val db = PampaDatabase.inMemory(context)
    val files = AppFiles(context)
    private val data = object : DataStore<Preferences> {
      override val data = MutableStateFlow(emptyPreferences())
      override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
    }
    private val cipher = object : SecretCipher {
      override fun encrypt(plain: String) = error("no secrets in storage regression")
      override fun decrypt(blob: String) = error("no secrets in storage regression")
    }
    private val settings = PampaSettingsStore(data, cipher, cipher)
    val scope = ComputerOnlyScope(settings, db.folders(), db.sync())
    val protections = AtomicInteger()
    val jobReads = AtomicInteger()
    var finalProtection: suspend () -> Unit = {}
    var finalRows: suspend () -> Unit = {}
    var atConfirmation: suspend () -> Unit = {}
    private val jobs = object : JobDao by db.jobs() {
      override suspend fun all(): List<JobEntity> {
        if (jobReads.incrementAndGet() == 2) {
          assertTrue("final reference queries must be inside real Room", db.inTransaction())
          finalRows()
        }
        return db.jobs().all()
      }
    }
    val storage = StorageRepository(db.audioParts(), db.sources(), jobs, files, scope,
      archivePresence = { hashes ->
        assertFalse("HEAD outside Room", db.inTransaction())
        atConfirmation()
        hashes.associateWith { true }
      }, inUse = FilesInUse(), transactions = DatabaseTransactions(db)).also { repository ->
        repository.protectedSessionIds = {
          if (protections.incrementAndGet() == 2) {
            assertFalse("final lookup must permit a real row writer", db.inTransaction())
            finalProtection()
          }
          emptySet()
        }
      }
    val name = if (source) "shared.pdf" else "shared.wav"
    val file get() = if (source) files.sourceFile(name) else files.audioFile(name)
    val part = AudioPartEntity("original", "session", 0, "./$name", name, "audio/wav", 15,
      1000, "h", 1, archivedAt = 1)
    val document = SourceEntity("original", "note", SourceKind.PDF, name, "application/pdf", 15,
      "h", "./$name", importedAt = 1, archivedAt = 1)

    suspend fun seed() {
      db.folders().upsert(FolderEntity("folder", "Folder", createdAt = 1, updatedAt = 1))
      db.notes().upsert(NoteEntity("note", "folder", "Note", createdAt = 1, updatedAt = 1))
      db.sessions().upsert(SessionEntity("session", "note", date = "2026-11-08", position = 0, createdAt = 1, updatedAt = 1))
      if (source) db.sources().upsert(document) else db.audioParts().upsert(part)
      settings.setComputerOnlyNote("note", true)
      file.writeText("synthetic bytes")
    }

    suspend fun alias(id: String = "alias", archived: Long = 0) {
      if (source) db.sources().upsert(document.copy(id = id, storedFileName = name, archivedAt = archived))
      else db.audioParts().upsert(part.copy(id = id, fileName = name, position = 1, archivedAt = archived))
    }

    suspend fun evict() = if (computer) storage.evictComputerOnly() else storage.evictArchived(true, true)
    override fun close() { db.close(); directory.deleteRecursively() }
  }

  private fun aliasAtFinalProtection(computer: Boolean, source: Boolean) = runBlocking {
    withTimeout(15_000) {
      Fixture(computer, source).use { f ->
        f.seed()
        val suspended = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        f.finalProtection = { suspended.complete(Unit); resume.await() }
        val eviction = async { f.evict() }
        suspended.await()
        // An ordinary production DAO upsert, actually committed before lookup resumes.
        f.alias()
        assertEquals(2, if (source) f.db.sources().all().size else f.db.audioParts().all().size)
        resume.complete(Unit)
        assertEquals(SizeTotal(0, 0), eviction.await())
        assertEquals("synthetic bytes", f.file.readText())
        assertEquals(2, f.protections.get())
      }
    }
  }

  @Test fun archivedAudioRetainsAliasCommittedDuringFinalProtection() = aliasAtFinalProtection(false, false)
  @Test fun archivedSourceRetainsAliasCommittedDuringFinalProtection() = aliasAtFinalProtection(false, true)
  @Test fun computerOnlyAudioRetainsAliasCommittedDuringFinalProtection() = aliasAtFinalProtection(true, false)
  @Test fun computerOnlySourceRetainsAliasCommittedDuringFinalProtection() = aliasAtFinalProtection(true, true)

  @Test fun roomWriterCannotEnterBetweenFinalQueriesAndPhysicalDeletion() = runBlocking {
    withTimeout(30_000) {
      for (computer in listOf(false, true)) for (source in listOf(false, true)) Fixture(computer, source).use { f ->
        f.seed()
        val snapshotRead = CompletableDeferred<Unit>()
        val continueDelete = CompletableDeferred<Unit>()
        val writerAttempted = CompletableDeferred<Unit>()
        f.finalRows = { snapshotRead.complete(Unit); continueDelete.await() }
        val eviction = async { f.evict() }
        snapshotRead.await()
        val writer = async(start = CoroutineStart.UNDISPATCHED) {
          writerAttempted.complete(Unit)
          f.db.withTransaction {
            assertFalse("physical delete precedes the next writer's entry", f.file.exists())
            f.alias("later-archived", archived = 1)
          }
        }
        writerAttempted.await()
        assertFalse(writer.isCompleted)
        assertTrue(f.file.exists())
        continueDelete.complete(Unit)
        assertEquals(SizeTotal(1, 15), eviction.await())
        writer.await()
      }
    }
  }

  @Test fun cancellingTheFinalRoomSnapshotRetainsBytesAndReleasesWriterExclusion() = runBlocking {
    withTimeout(30_000) {
      for (computer in listOf(false, true)) for (source in listOf(false, true)) Fixture(computer, source).use { f ->
        f.seed()
        f.finalRows = { throw CancellationException("cancel final DB read") }
        try { f.evict(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals("synthetic bytes", f.file.readText())
        // A real new transaction must be able to enter after the failed one.
        f.db.withTransaction { f.alias() }
        assertEquals(SizeTotal(0, 0), f.evict())
        assertTrue(f.file.exists())
      }
    }
  }

  @Test fun rolledBackAliasDoesNotBlockSafeArchivedDeletion() = runBlocking {
    withTimeout(30_000) {
      for (computer in listOf(false, true)) for (source in listOf(false, true)) Fixture(computer, source).use { f ->
        f.seed()
        f.finalProtection = {
          try {
            f.db.withTransaction { f.alias(); throw CancellationException("rollback alias") }
          } catch (_: CancellationException) { }
        }
        assertEquals(SizeTotal(1, 15), f.evict())
        assertFalse(f.file.exists())
        assertEquals(1, if (source) f.db.sources().all().size else f.db.audioParts().all().size)
      }
    }
  }
}
