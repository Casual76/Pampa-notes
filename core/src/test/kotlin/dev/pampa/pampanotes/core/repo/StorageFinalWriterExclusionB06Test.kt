package dev.pampa.pampanotes.core.repo

import android.content.Context
import dev.pampa.pampanotes.core.archive.*
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.*
import dev.pampa.pampanotes.core.sync.SyncLocalFiles
import dev.pampa.pampanotes.core.testing.StorageWriterTransactionsB06
import io.mockk.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real repository/file removal with synthetic bytes. A shared mutex models writer exclusion;
 * concurrent mutations use that mutex, never an impossible commit inside the exclusion boundary.
 * This is NOT a Room runtime test. See StorageWriterBoundaryB06RoomTest for the prepared Room gate. */
class StorageFinalWriterExclusionB06Test {
  @get:Rule val temp = TemporaryFolder()

  private inner class Fixture(val computer: Boolean, val source: Boolean) {
    val writer = StorageWriterTransactionsB06()
    val parts = mutableListOf<AudioPartEntity>()
    val docs = mutableListOf<SourceEntity>()
    val jobs = mutableListOf<JobEntity>()
    val files: AppFiles
    val inUse = FilesInUse()
    val repository: StorageRepository
    val reads = AtomicInteger()
    var finalProtection: suspend () -> Unit = {}
    var finalRows: suspend () -> Unit = {}
    var confirmation: suspend () -> Unit = {}
    var allowed = true

    init {
      val root = temp.newFolder()
      val context = mockk<Context>()
      every { context.filesDir } returns File(root, "files")
      every { context.cacheDir } returns File(root, "cache")
      files = AppFiles(context)
      val audio = mockk<AudioPartDao>()
      val sources = mockk<SourceDao>()
      val jobDao = mockk<JobDao>()
      val scope = mockk<ComputerOnlyScope>()
      val archive = mockk<ArchiveRepository>()
      coEvery { audio.all() } answers { parts.toList() }
      coEvery { sources.all() } answers { docs.toList() }
      coEvery { jobDao.all() } coAnswers {
        if (writer.inside) finalRows()
        jobs.toList()
      }
      coEvery { scope.current() } answers {
        ComputerOnlyItems(parts = if (allowed) parts.toList() else emptyList(),
          sources = if (allowed) docs.toList() else emptyList())
      }
      coEvery { archive.presence(any()) } coAnswers {
        assertFalse("HEAD cannot hold the DB writer boundary", writer.inside)
        confirmation()
        firstArg<Collection<String>>().associateWith { true }
      }
      repository = StorageRepository(audio, sources, jobDao, files, scope, archive, inUse, writer.transactions)
      repository.protectedSessionIds = {
        if (reads.incrementAndGet() == 2) {
          assertFalse("finish the suspendable lookup before authoritative DB reads", writer.inside)
          finalProtection()
        }
        emptySet()
      }
    }

    // Call only before concurrency starts, or from writer.write when concurrent.
    fun add(id: String, name: String, archived: Long = 1) {
      if (source) docs += SourceEntity(id, "note-$id", SourceKind.PDF, id, "application/pdf",
        15, "h", name, importedAt = 1, archivedAt = archived)
      else parts += AudioPartEntity(id, "session-$id", 0, name, id, "audio/wav", 15,
        1000, "h", 1, archivedAt = archived)
    }

    val name get() = if (source) "shared.pdf" else "shared.wav"
    val file get() = if (source) files.sourceFile(name) else files.audioFile(name)
    fun seed() { add("original", "./$name"); file.writeText("synthetic bytes") }
    suspend fun evict() = if (computer) repository.evictComputerOnly() else repository.evictArchived(true, true)
  }

  private fun allKinds(check: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
    withTimeout(15_000) {
      for (computer in listOf(false, true)) for (source in listOf(false, true)) {
        check.invoke(this, Fixture(computer, source).also { it.seed() })
      }
    }
  }

  private fun committedAliasAtFinalProtection(computer: Boolean, source: Boolean) = runBlocking {
    withTimeout(10_000) {
      val f = Fixture(computer, source).also { it.seed() }
      val entered = CompletableDeferred<Unit>()
      val resume = CompletableDeferred<Unit>()
      f.finalProtection = { entered.complete(Unit); resume.await() }
      val eviction = async { f.evict() }
      entered.await() // SECOND lookup, after HEAD, not a mutation during HEAD.
      f.writer.write { f.add("new-unarchived", f.name, archived = 0) }
      assertTrue(f.file.exists())
      resume.complete(Unit)
      assertEquals(SizeTotal(0, 0), eviction.await())
      assertEquals(2, f.reads.get())
      assertEquals(2, if (source) f.docs.size else f.parts.size)
      assertEquals("synthetic bytes", f.file.readText())
    }
  }

  @Test fun archivedAudioAliasCommitsDuringFinalProtectionAndVetoes() = committedAliasAtFinalProtection(false, false)
  @Test fun archivedSourceAliasCommitsDuringFinalProtectionAndVetoes() = committedAliasAtFinalProtection(false, true)
  @Test fun computerOnlyAudioAliasCommitsDuringFinalProtectionAndVetoes() = committedAliasAtFinalProtection(true, false)
  @Test fun computerOnlySourceAliasCommitsDuringFinalProtectionAndVetoes() = committedAliasAtFinalProtection(true, true)

  @Test fun finalReferenceReadsAndPhysicalDeleteExcludeConcurrentWriters() = allKinds { f ->
    val atRows = CompletableDeferred<Unit>()
    val continueRows = CompletableDeferred<Unit>()
    val writerAttempted = CompletableDeferred<Unit>()
    f.finalRows = { atRows.complete(Unit); continueRows.await() }
    val eviction = async { f.evict() }
    atRows.await()
    val pendingWriter = async(start = CoroutineStart.UNDISPATCHED) {
      writerAttempted.complete(Unit)
      f.writer.write {
        // The delete must have finished BEFORE another writer enters, not merely before it exits.
        assertFalse(f.file.exists())
        f.add("independent", "independent.bin")
      }
    }
    writerAttempted.await()
    assertFalse(pendingWriter.isCompleted)
    assertTrue(f.file.exists())
    continueRows.complete(Unit)
    assertEquals(SizeTotal(1, 15), eviction.await())
    pendingWriter.await()
    assertFalse(f.writer.inside)
  }

  @Test fun archiveConfirmationPermitsARealFixtureWriterAndFinalSnapshotSeesItsAlias() = allKinds { f ->
    f.confirmation = { f.writer.write { f.add("pending", f.name, archived = 0) } }
    assertEquals(SizeTotal(0, 0), f.evict())
    assertTrue(f.file.exists())
  }

  @Test fun activePageAdoptionAndReleasedGenerationVetoEvictionThenFreshPassCanDelete() = allKinds { f ->
    val owner = SyncLocalFiles.beginWrites()
    try {
      f.finalProtection = { owner.hold(f.file) }
      assertEquals(SizeTotal(0, 0), f.evict())
      assertTrue(f.file.exists())
    } finally { owner.close() }
    // A fresh pass is allowed after the old generation has closed; no permanent hold leak.
    assertEquals(SizeTotal(1, 15), f.evict())

    f.file.writeText("synthetic bytes")
    f.reads.set(0)
    f.finalProtection = { SyncLocalFiles.beginWrites().use { page -> page.hold(f.file) } }
    assertEquals(SizeTotal(0, 0), f.evict())
    assertTrue(f.file.exists())
    assertEquals(SizeTotal(1, 15), f.evict())
  }

  @Test fun aFinalAliasHoldVetoesAndReleasingItAllowsSafeEviction() = allKinds { f ->
    val key = if (f.source) FilesInUse.source(f.name) else FilesInUse.audio(f.name)
    val owner = Any()
    try {
      f.finalProtection = { f.inUse.hold(listOf(key), 60_000, owner = owner) }
      assertEquals(SizeTotal(0, 0), f.evict())
      assertTrue(f.file.exists())
    } finally { f.inUse.release(listOf(key), owner) }
    assertEquals(SizeTotal(1, 15), f.evict())
  }

  @Test fun failedOrCancelledFinalLookupKeepsBytesAndClosesItsGeneration() = allKinds { f ->
    val marker = CancellationException("cancel final protection")
    val owner = Any()
    f.finalProtection = {
      AppFileWriteProtection.hold(f.file, owner)
      AppFileWriteProtection.release(owner)
      throw marker
    }
    try { f.evict(); fail("cancellation must propagate") } catch (_: CancellationException) { }
    assertTrue(f.file.exists())
    assertFalse(f.writer.inside)
    assertEquals(SizeTotal(1, 15), f.evict())
  }

  @Test fun finalTransactionFailureReleasesWriterBoundaryWithoutDeleting() = allKinds { f ->
    f.finalRows = { throw IllegalStateException("reference read failed") }
    assertEquals(SizeTotal(0, 0), f.evict())
    assertTrue(f.file.exists())
    assertFalse(f.writer.inside)
    f.finalRows = {}
    assertEquals(SizeTotal(1, 15), f.evict())
  }

  @Test fun policyAndBusyJobWritersAtFinalProtectionVetoTheCurrentRows() = allKinds { f ->
    f.finalProtection = {
      f.writer.write {
        if (f.computer) f.allowed = false
        else if (f.source) f.docs[0] = f.docs[0].copy(derivedFromId = "original")
        else f.jobs += JobEntity("job", "session-original", JobType.TRANSCRIBE, "custom",
          state = JobState.TRANSCRIBING, createdAt = 1, updatedAt = 1)
      }
    }
    assertEquals(SizeTotal(0, 0), f.evict())
    assertTrue(f.file.exists())
  }

  @Test fun independentSameBasenameBytesRemainIndependentAtTheFinalBoundary() = allKinds { f ->
    val other = if (f.source) f.files.audioFile(f.name) else f.files.sourceFile(f.name)
    other.writeText("independent bytes")
    assertEquals(SizeTotal(1, 15), f.evict())
    assertFalse(f.file.exists())
    assertEquals("independent bytes", other.readText())
  }

  @Test fun finalRootSubstitutionCannotAuthorizeEitherOriginalOrRedirectedBytes() = allKinds { f ->
    val original = f.file
    val redirected = temp.newFolder()
    val outside = File(redirected, f.name).also { it.writeText("outside bytes") }
    val field = AppFiles::class.java.getDeclaredField(if (f.source) "sources" else "audio").also { it.isAccessible = true }
    val old = field.get(f.files)
    try {
      f.finalProtection = { field.set(f.files, redirected) }
      assertEquals(SizeTotal(0, 0), f.evict())
      assertEquals("synthetic bytes", original.readText())
      assertEquals("outside bytes", outside.readText())
    } finally { field.set(f.files, old) }
  }
}
