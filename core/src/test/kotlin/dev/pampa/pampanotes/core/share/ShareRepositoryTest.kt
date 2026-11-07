package dev.pampa.pampanotes.core.share

import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.relay.RelayKind
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.sync.SyncReport
import dev.pampa.pampanotes.core.sync.SyncRepository
import io.mockk.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShareRepositoryTest {
  @get:Rule val temporary = TemporaryFolder()
  private val settings = mockk<PampaSettingsStore>()
  private val notes = mockk<NoteDao>()
  private val sessions = mockk<SessionDao>()
  private val files = mockk<AppFiles>()
  private val fetcher = mockk<ArchiveFetcher>()
  private val api = mockk<ShareApi>()
  private val sync = mockk<SyncRepository>()
  private val share = ShareInfo("share", "note", "Lecture", "https://share.test/s/link", 1)
  private val uploaded = mutableListOf<Pair<String, ByteArray>>()
  private val url = "https://sync.test"
  private val token = "test-token"

  private fun part(id: String = "part", archivedAt: Long = 0) = AudioPartEntity(
    id, "session", 0, "$id.m4a", "Lecture $id", "audio/mp4", 3, 1000, "a".repeat(64), 1, archivedAt)

  private fun repository(parts: List<AudioPartEntity>): ShareRepository {
    coEvery { settings.current() } returns PampaSettings(syncServerUrl = url)
    coEvery { settings.syncToken() } returns token
    coEvery { sessions.byNote("note") } returns listOf(SessionWithParts(
      SessionEntity("session", "note", date = "2026-11-07", position = 0, createdAt = 1, updatedAt = 1), parts))
    coEvery { notes.get("note") } returns NoteEntity("note", "folder", "Lecture", "", createdAt = 1, updatedAt = 1)
    every { files.audioFile(any()) } answers { File(temporary.root, firstArg<String>()) }
    coEvery { sync.syncNow() } returns SyncReport()
    coEvery { api.create(url, token, "note", "Lecture") } returns share
    coEvery { api.list(url, token) } returns listOf(share)
    coEvery { api.hasAudio(url, token, share.shareId, any()) } returns false
    coEvery { api.uploadAudio(url, token, share.shareId, any(), any(), any(), any()) } answers {
      val file = arg<File>(4)
      uploaded += arg<String>(3) to file.readBytes()
      file.length()
    }
    return ShareRepository(settings, notes, sessions, files, fetcher, api, sync)
  }

  @Test fun readyRelayMakesUnarchivedMissingAudioAvailableAndPublishesItsFetchedBytes() = runBlocking {
    val part = part()
    val repository = repository(listOf(part))
    coEvery { fetcher.relayAvailable(RelayKind.AUDIO, part.id) } returns true
    // Use the File returned by the fetcher, rather than assuming a particular storage adapter.
    val received = File(temporary.root, "verified.m4a")
    coEvery { fetcher.fetchPart(part, any()) } answers { received.apply { writeText("abc") } }
    val progress = mutableListOf<ShareProgress>()

    assertEquals(0, repository.plan("note").unavailable)
    assertEquals(share, repository.share("note", progress::add))

    assertEquals(listOf(part.id), uploaded.map { it.first })
    assertArrayEquals("abc".toByteArray(), uploaded.single().second)
    assertTrue(progress.any { it.stage == ShareStage.FETCHING })
    assertTrue(progress.any { it.stage == ShareStage.UPLOADING })
    assertEquals(ShareStage.DONE, progress.last().stage)
    coVerifyOrder {
      sync.syncNow()
      api.create(url, token, "note", "Lecture")
      fetcher.fetchPart(part, any())
      api.uploadAudio(url, token, share.shareId, part.id, received, part.mime, any())
    }
    coVerify(exactly = 0) { notes.upsert(any()) }
    assertEquals(0L, part.archivedAt)
  }

  @Test fun genuinelyMissingAudioIsUnavailableAndDoesNotBlockOtherParts() = runBlocking {
    val missing = part("missing")
    val local = part("local").copy(position = 1)
    val repository = repository(listOf(missing, local))
    files.audioFile(local.fileName).writeText("abc")
    coEvery { fetcher.relayAvailable(RelayKind.AUDIO, missing.id) } returns false

    val plan = repository.plan("note")
    assertTrue(plan.configured)
    assertEquals(2, plan.parts)
    assertEquals(6L, plan.bytes)
    assertEquals(1, plan.unavailable)
    assertEquals(share, repository.share("note"))

    assertEquals(listOf(local.id), uploaded.map { it.first })
    coVerify(exactly = 0) { fetcher.fetchPart(any(), any()) }
    coVerify(exactly = 0) { fetcher.relayAvailable(RelayKind.AUDIO, local.id) }
  }

  @Test fun localAudioUploadsWithoutRemoteAvailabilityOrFetch() = runBlocking {
    val part = part()
    val repository = repository(listOf(part))
    files.audioFile(part.fileName).writeText("abc")

    assertEquals(0, repository.plan("note").unavailable)
    repository.share("note")

    assertArrayEquals("abc".toByteArray(), uploaded.single().second)
    confirmVerified(fetcher)
  }

  @Test fun archivedAudioKeepsCompanionFallbackWithoutDependingOnRelayDiscovery() = runBlocking {
    val part = part(archivedAt = 77)
    val repository = repository(listOf(part))
    coEvery { fetcher.fetchPart(part, any()) } answers { files.audioFile(part.fileName).apply { writeText("abc") } }

    assertEquals(0, repository.plan("note").unavailable)
    repository.share("note")

    assertArrayEquals("abc".toByteArray(), uploaded.single().second)
    coVerify(exactly = 1) { fetcher.fetchPart(part, any()) }
    coVerify(exactly = 0) { fetcher.relayAvailable(any(), any()) }
    assertEquals(77L, part.archivedAt)
  }

  @Test fun alreadyPublishedAudioNeedsNeitherFetchNorAvailability() = runBlocking {
    val part = part()
    val repository = repository(listOf(part))
    coEvery { api.hasAudio(url, token, share.shareId, part.id) } returns true

    assertEquals(share, repository.share("note"))

    assertTrue(uploaded.isEmpty())
    confirmVerified(fetcher)
  }

  @Test fun cancellationDuringFetchPropagatesWithoutUploadOrDone() = runBlocking {
    val part = part()
    val repository = repository(listOf(part))
    val cancelled = CancellationException("cancel receive")
    coEvery { fetcher.relayAvailable(RelayKind.AUDIO, part.id) } returns true
    coEvery { fetcher.fetchPart(part, any()) } throws cancelled
    val progress = mutableListOf<ShareProgress>()

    try {
      repository.share("note", progress::add)
      fail("cancellation must propagate")
    } catch (error: CancellationException) { assertSame(cancelled, error) }

    assertTrue(uploaded.isEmpty())
    assertFalse(progress.any { it.stage == ShareStage.DONE })
  }

  @Test fun cancellationDuringAvailabilityDoesNotTurnIntoMissingAudio() = runBlocking {
    val part = part()
    val repository = repository(listOf(part))
    val cancelled = CancellationException("cancel discovery")
    coEvery { fetcher.relayAvailable(RelayKind.AUDIO, part.id) } throws cancelled

    try {
      repository.plan("note")
      fail("plan cancellation must propagate")
    } catch (error: CancellationException) { assertSame(cancelled, error) }
    try {
      repository.share("note")
      fail("share cancellation must propagate")
    } catch (error: CancellationException) { assertSame(cancelled, error) }
    assertTrue(uploaded.isEmpty())
  }
}
