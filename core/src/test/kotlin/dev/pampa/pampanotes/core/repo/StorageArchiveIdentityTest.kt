package dev.pampa.pampanotes.core.repo

import android.content.Context
import dev.pampa.pampanotes.core.archive.ArchiveRepository
import dev.pampa.pampanotes.core.archive.ComputerOnlyItems
import dev.pampa.pampanotes.core.archive.ComputerOnlyScope
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.db.JobType
import dev.pampa.pampanotes.core.db.SizeTotal
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual StorageRepository and AppFiles, mocked row/policy/archive reads, synthetic text bytes. */
class StorageArchiveIdentityTest {
  @get:Rule val temp = TemporaryFolder()

  private inner class Fixture(val computerOnly: Boolean) {
    private val root = temp.newFolder()
    val files: AppFiles
    val inUse = FilesInUse()
    val parts = mutableListOf<AudioPartEntity>()
    val docs = mutableListOf<SourceEntity>()
    val active = mutableListOf<JobEntity>()
    var selectedParts: Set<String>? = null
    var selectedSources: Set<String>? = null
    var protected = emptySet<String>()
    var failure: String? = null
    var answer: Boolean? = true
    var duringConfirmation: suspend Fixture.() -> Unit = {}
    val requested = mutableListOf<List<String>>()
    val lostParts = mutableListOf<String>()
    val lostSources = mutableListOf<String>()
    val storage: StorageRepository

    init {
      val context = mockk<Context>()
      every { context.filesDir } returns File(root, "files")
      every { context.cacheDir } returns File(root, "cache")
      files = AppFiles(context)
      val audioDao = mockk<AudioPartDao>()
      val sourceDao = mockk<SourceDao>()
      val jobDao = mockk<JobDao>()
      val scope = mockk<ComputerOnlyScope>()
      val archive = mockk<ArchiveRepository>()
      coEvery { audioDao.all() } answers { check(failure != "parts"); parts.toList() }
      coEvery { sourceDao.all() } answers { check(failure != "sources"); docs.toList() }
      coEvery { jobDao.all() } answers { check(failure != "jobs"); active.toList() }
      coEvery { scope.current() } answers {
        check(failure != "scope")
        ComputerOnlyItems(parts = parts.filter { selectedParts == null || it.id in selectedParts!! },
          sources = docs.filter { selectedSources == null || it.id in selectedSources!! })
      }
      coEvery { audioDao.markArchived(any(), 0L) } answers { lostParts += firstArg<String>() }
      coEvery { sourceDao.markArchived(any(), 0L) } answers { lostSources += firstArg<String>() }
      coEvery { archive.presence(any()) } coAnswers {
        val hashes = firstArg<Collection<String>>().toList()
        requested += hashes
        yield()
        duringConfirmation()
        if (answer == null) emptyMap() else hashes.associateWith { answer!! }
      }
      storage = StorageRepository(audioDao, sourceDao, jobDao, files, scope, archive, inUse)
      storage.protectedSessionIds = {
        check(failure != "protected")
        if (failure == "cancel") throw CancellationException("fixture cancellation")
        protected
      }
    }

    fun audio(id: String = "personal", name: String = "shared.wav", archived: Long = 1, hash: String = "h"): File {
      parts += AudioPartEntity(id = id, sessionId = "s-$id", position = 0, fileName = name,
        originalName = id, mime = "audio/wav", sizeBytes = 15, durationMs = 1000,
        sha256 = hash, createdAt = 1, archivedAt = archived)
      return files.audioFile(name)
    }

    fun source(id: String = "personal", name: String = "shared.pdf", derived: String? = null,
      archived: Long = 1, hash: String = "h"): File {
      docs += SourceEntity(id = id, noteId = "n-$id", kind = SourceKind.PDF, originalName = id,
        mime = "application/pdf", sizeBytes = 15, sha256 = hash, storedFileName = name,
        importedAt = 1, archivedAt = archived, derivedFromId = derived)
      return files.sourceFile(name)
    }

    fun busy(session: String) {
      active += JobEntity(id = "job-$session", sessionId = session, type = JobType.TRANSCRIBE,
        provider = "custom", state = JobState.TRANSCRIBING, createdAt = 1, updatedAt = 1)
    }

    fun bytes(file: File): File = file.apply { parentFile!!.mkdirs(); writeText("synthetic bytes") }
    suspend fun evict(): SizeTotal = if (computerOnly) storage.evictComputerOnly() else storage.evictArchived(true, true)
    suspend fun retain(file: File) {
      assertEquals(SizeTotal(0, 0), evict())
      assertEquals("synthetic bytes", file.readText())
    }
  }

  private fun bothModes(check: suspend Fixture.() -> Unit) = runBlocking {
    listOf(true, false).forEach { mode -> Fixture(mode).check() }
  }

  @Test fun `protected school sibling survives aliases with a hold`() = bothModes {
    val shared = bytes(audio(name = "./shared.wav"))
    audio("school")
    selectedParts = setOf("personal")
    protected = setOf("s-school")
    inUse.hold(listOf(FilesInUse.audio("shared.wav")), 60_000)
    retain(shared)
    assertTrue("initial filtering must also use physical identity", requested.isEmpty())
  }

  @Test fun `protected school sibling survives aliases without a hold`() = bothModes {
    val shared = bytes(audio(name = "./shared.wav"))
    audio("school")
    selectedParts = setOf("personal")
    protected = setOf("s-school")
    retain(shared)
    assertTrue(requested.isEmpty())
  }

  @Test fun `unselected school sibling needs no explicit protection or hold`() = runBlocking {
    val f = Fixture(true)
    val shared = f.bytes(f.audio(name = "./shared.wav"))
    f.audio("school")
    f.selectedParts = setOf("personal")
    f.retain(shared)
  }

  @Test fun `busy sibling protects every equivalent path`() = bothModes {
    val shared = bytes(audio(name = "./shared.wav"))
    audio("sibling")
    busy("s-sibling")
    retain(shared)
    assertTrue(requested.isEmpty())
  }

  @Test fun `alias hold alone protects an otherwise eligible file`() = bothModes {
    val shared = bytes(audio(name = "./shared.wav"))
    inUse.hold(listOf(FilesInUse.audio("shared.wav")), 60_000)
    retain(shared)
    assertTrue(requested.isEmpty())
    inUse.release(listOf(FilesInUse.audio("shared.wav")))
    assertEquals(SizeTotal(1, 15), evict())
    assertFalse(shared.exists())
  }

  @Test fun `a hold acquired during archive confirmation wins`() = bothModes {
    val shared = bytes(audio(name = "./shared.wav"))
    duringConfirmation = { inUse.hold(listOf(FilesInUse.audio("shared.wav")), 60_000) }
    retain(shared)
    assertEquals(1, requested.size)
  }

  @Test fun `a sibling protected during archive confirmation wins`() = bothModes {
    val shared = bytes(audio(name = "./shared.wav"))
    audio("sibling")
    duringConfirmation = { protected = setOf("s-sibling") }
    retain(shared)
    assertEquals(1, requested.size)
  }

  @Test fun `a sibling becoming busy during archive confirmation wins`() = bothModes {
    val shared = bytes(audio(name = "./shared.wav"))
    audio("sibling")
    duringConfirmation = { busy("s-sibling") }
    retain(shared)
  }

  @Test fun `a new unarchived alias introduced during confirmation wins`() = bothModes {
    val shared = bytes(audio(name = "./shared.wav"))
    duringConfirmation = { audio("new", archived = 0) }
    retain(shared)
  }

  @Test fun `a sibling leaving current policy during confirmation wins`() = runBlocking {
    val f = Fixture(true)
    val shared = f.bytes(f.audio(name = "./shared.wav"))
    f.audio("sibling")
    f.duringConfirmation = { selectedParts = setOf("personal") }
    f.retain(shared)
  }

  @Test fun `ordinary eligible audio and source evict and count physical bytes`() = bothModes {
    val a = bytes(audio())
    val s = bytes(source())
    assertEquals(SizeTotal(2, 30), evict())
    assertFalse(a.exists()); assertFalse(s.exists())
    assertTrue(lostParts.isEmpty() && lostSources.isEmpty())
  }

  @Test fun `unarchived sibling retains bytes even if another reference is archived`() = bothModes {
    val shared = bytes(audio(name = "./shared.wav"))
    audio("pending", archived = 0)
    retain(shared)
    assertTrue(requested.isEmpty())
  }

  @Test fun `ordinary pending bytes never request archive confirmation`() = bothModes {
    val a = bytes(audio(archived = 0))
    val s = bytes(source(archived = 0))
    retain(a)
    assertTrue(s.exists()); assertTrue(requested.isEmpty())
  }

  @Test fun `missing archive retains bytes and marks every duplicate reference lost`() = bothModes {
    val a = bytes(audio(name = "./shared.wav"))
    audio("alias")
    val s = bytes(source(name = "./shared.pdf"))
    source("alias")
    answer = false
    retain(a)
    assertTrue(s.exists())
    assertEquals(setOf("personal", "alias"), lostParts.toSet())
    assertEquals(setOf("personal", "alias"), lostSources.toSet())
  }

  @Test fun `offline archive retains bytes without lost marking`() = bothModes {
    val a = bytes(audio())
    val s = bytes(source())
    answer = null
    retain(a)
    assertTrue(s.exists()); assertTrue(lostParts.isEmpty() && lostSources.isEmpty())
  }

  @Test fun `eligible duplicate canonical paths are removed once`() = bothModes {
    val a = bytes(audio(name = "./shared.wav"))
    audio("alias")
    val s = bytes(source(name = "./shared.pdf"))
    source("alias")
    assertEquals(SizeTotal(2, 30), evict())
    assertFalse(a.exists()); assertFalse(s.exists())
    assertEquals(setOf("h"), requested.single().toSet())
  }

  @Test fun `conflicting hashes on the same physical bytes defer eviction`() = bothModes {
    val a = bytes(audio(name = "./shared.wav"))
    audio("alias", hash = "other")
    retain(a)
    assertTrue(requested.isEmpty())
  }

  @Test fun `source alias hold protects bytes with no other reference`() = bothModes {
    val s = bytes(source(name = "./shared.pdf"))
    inUse.hold(listOf(FilesInUse.source("shared.pdf")), 60_000)
    retain(s)
    assertTrue(requested.isEmpty())
  }

  @Test fun `source hold acquired during archive confirmation wins`() = bothModes {
    val s = bytes(source(name = "./shared.pdf"))
    duringConfirmation = { inUse.hold(listOf(FilesInUse.source("shared.pdf")), 60_000) }
    retain(s)
  }

  @Test fun `unselected source sibling retains shared bytes`() = runBlocking {
    val f = Fixture(true)
    val s = f.bytes(f.source(name = "./shared.pdf"))
    f.source("school")
    f.selectedSources = setOf("personal")
    f.retain(s)
  }

  @Test fun `derived handwritten sibling protects shared source bytes`() = bothModes {
    val s = bytes(source(name = "./shared.pdf"))
    source("page", derived = "original")
    retain(s)
    assertTrue(requested.isEmpty())
  }

  @Test fun `a newly derived reference during confirmation wins`() = bothModes {
    val s = bytes(source(name = "./shared.pdf"))
    duringConfirmation = { source("page", derived = "original") }
    retain(s)
  }

  @Test fun `source and audio with the same basename have separate identities`() = bothModes {
    val a = bytes(audio(name = "same.bin"))
    val s = bytes(source(name = "same.bin", derived = "original"))
    inUse.hold(listOf(FilesInUse.source("same.bin")), 60_000)
    assertEquals(SizeTotal(1, 15), evict())
    assertFalse(a.exists()); assertTrue(s.exists())
  }

  @Test fun `outside root paths never remove private material`() = bothModes {
    val a = bytes(audio(name = "../private-a.bin"))
    val s = bytes(source(name = "../private-s.bin"))
    retain(a)
    assertTrue(s.exists()); assertTrue(requested.isEmpty())
  }

  @Test fun `cross root alias cannot bypass derived source protection`() = bothModes {
    val s = bytes(source(name = "page.png", derived = "original"))
    audio(name = "../sources/page.png")
    retain(s)
    assertTrue(requested.isEmpty())
  }

  @Test fun `path identity failure defers eviction`() = bothModes {
    val a = bytes(audio())
    audio("invalid", name = "bad\u0000name")
    retain(a)
    assertTrue(requested.isEmpty())
  }

  @Test fun `guard or snapshot read failures retain bytes before and after confirmation`() = runBlocking {
    listOf(true, false).forEach { mode ->
      (listOf("protected", "parts", "sources", "jobs") + if (mode) listOf("scope") else emptyList()).forEach { point ->
        listOf(false, true).forEach { after ->
          val f = Fixture(mode)
          val a = f.bytes(f.audio())
          if (after) f.duringConfirmation = { failure = point } else f.failure = point
          f.retain(a)
          assertEquals(if (after) 1 else 0, f.requested.size)
        }
      }
    }
  }

  @Test fun `replacement hash after confirmation cannot use an old remote confirmation`() = bothModes {
    val a = bytes(audio())
    duringConfirmation = { parts[0] = parts[0].copy(sha256 = "new") }
    retain(a)
  }

  @Test fun `missing archive response cannot clear a replacement row`() = bothModes {
    val a = bytes(audio())
    answer = false
    duringConfirmation = { parts[0] = parts[0].copy(sha256 = "new") }
    retain(a)
    assertTrue(lostParts.isEmpty())
  }

  @Test fun `current policy removal retains the selected file`() = runBlocking {
    val f = Fixture(true)
    val a = f.bytes(f.audio())
    f.duringConfirmation = { selectedParts = emptySet() }
    f.retain(a)
  }

  @Test fun `cancellation during guard read propagates without deleting`() = bothModes {
    val a = bytes(audio())
    duringConfirmation = { failure = "cancel" }
    var cancelled = false
    try { evict() } catch (_: CancellationException) { cancelled = true }
    assertTrue(cancelled); assertTrue(a.exists())
  }

  @Test fun `explicit category selection retains the other category`() = runBlocking {
    val f = Fixture(false)
    val a = f.bytes(f.audio())
    val s = f.bytes(f.source())
    assertEquals(SizeTotal(1, 15), f.storage.evictArchived(sources = false, audio = true))
    assertFalse(a.exists()); assertTrue(s.exists())
  }
}
