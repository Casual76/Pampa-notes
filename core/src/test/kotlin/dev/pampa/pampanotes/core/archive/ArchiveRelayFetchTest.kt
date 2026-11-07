package dev.pampa.pampanotes.core.archive

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.relay.*
import io.mockk.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveRelayFetchTest {
  @get:Rule val temporary = TemporaryFolder()
  private val files = mockk<AppFiles>()
  private val parts = mockk<AudioPartDao>(relaxed = true)
  private val sources = mockk<SourceDao>(relaxed = true)
  private val relay = mockk<RelayRepository>()
  private val http = mockk<ArchiveHttp>()
  private fun fetcher() = ArchiveFetcher(files, mockk(), mockk(), http, parts, sources, mockk(), mockk(), relay)
  private fun part(archivedAt: Long = 0) = AudioPartEntity("part", "session", 0, "part.m4a", "Lecture",
    "audio/mp4", 3, 1000, "a".repeat(64), 1, archivedAt = archivedAt)

  @Test fun unarchivedAudioCanBeReceivedWithoutCreatingArchiveMarker() = runBlocking {
    val row = part()
    val target = File(temporary.root, "part.m4a")
    every { files.audioFile(row.fileName) } returns target
    coEvery { relay.fetch(RelayKind.AUDIO, row.id, any()) } answers {
      target.writeText("abc")
      RelayResult(RelayState.RECEIVED, file = target)
    }
    assertEquals(target, fetcher().fetchPart(row))
    assertEquals(0L, row.archivedAt)
    coVerify(exactly = 0) { parts.markArchived(any(), any()) }
    confirmVerified(http)
  }

  @Test fun storedOriginalUsesRelayWithoutComputerOrArchiveMarker() = runBlocking {
    val row = SourceEntity(id = "source", noteId = "note", kind = SourceKind.TEXT, originalName = "Text.txt",
      storedFileName = "source.txt", mime = "text/plain", sha256 = "a".repeat(64), sizeBytes = 3,
      status = SourceStatus.OK, extractedChars = 3, importedAt = 1)
    val target = File(temporary.root, "source.txt")
    every { files.sourceFile("source.txt") } returns target
    coEvery { relay.fetch(RelayKind.SOURCE, row.id, any()) } answers {
      target.writeText("abc")
      RelayResult(RelayState.RECEIVED, file = target)
    }
    assertEquals(target, fetcher().fetchSource(row))
    assertEquals(0L, row.archivedAt)
    coVerify(exactly = 0) { sources.markArchived(any(), any()) }
    confirmVerified(http)
  }

  @Test fun expiredUnarchivedAudioExplainsResendAndDoesNotInventAnArchive() = runBlocking {
    val row = part()
    every { files.audioFile(row.fileName) } returns File(temporary.root, "absent")
    coEvery { relay.fetch(RelayKind.AUDIO, row.id, any()) } returns RelayResult(RelayState.EXPIRED)
    try { fetcher().fetchPart(row); fail("expired transfer must not be a success")
    } catch (error: RelayUnavailable) { assertEquals(RelayState.EXPIRED, error.result.state) }
    coVerify(exactly = 0) { parts.markArchived(any(), any()) }
    confirmVerified(http)
  }

  @Test fun localFileNeedsNeitherRelayNorCompanion() = runBlocking {
    val row = part()
    val target = temporary.newFile("local").apply { writeText("abc") }
    every { files.audioFile(row.fileName) } returns target
    assertEquals(target, fetcher().fetchPart(row))
    coVerify(exactly = 0) { relay.fetch(any(), any(), any()) }
    confirmVerified(http)
  }

  @Test fun absentRelayPreservesCompanionFallbackAndItsArchiveMarker() = runBlocking {
    val row = part(archivedAt = 77)
    val target = File(temporary.root, "fallback.m4a")
    val staging = File(temporary.root, "staging.part")
    every { files.audioFile(row.fileName) } returns target
    every { files.tempFile(any(), any()) } returns staging
    coEvery { relay.fetch(RelayKind.AUDIO, row.id, any()) } returns RelayResult(RelayState.EXPIRED)
    val settings = mockk<dev.pampa.pampanotes.core.settings.PampaSettingsStore>()
    coEvery { settings.current() } returns dev.pampa.pampanotes.core.settings.PampaSettings(endpointUrl = "http://computer.test")
    val resolver = mockk<dev.pampa.pampanotes.core.transcription.EndpointResolver>()
    coEvery { resolver.resolve(any(), any()) } returns dev.pampa.pampanotes.core.transcription.ResolvedEndpoint("http://computer.test", true)
    val auth = dev.pampa.pampanotes.core.transcription.ComputerAuth(account = { null }, manualCode = { "test" },
      fetch = { _, _ -> error("no account fetch expected") }, clock = System::currentTimeMillis)
    coEvery { http.download(any(), any(), staging, any()) } answers { staging.writeText("abc"); row.sha256 }
    val fetcher = ArchiveFetcher(files, settings, resolver, http, parts, sources, auth, mockk(), relay)
    assertEquals(target, fetcher.fetchPart(row))
    assertEquals("abc", target.readText())
    assertEquals(77L, row.archivedAt)
    coVerifyOrder { relay.fetch(RelayKind.AUDIO, row.id, any()); resolver.resolve(any(), any()); http.download(any(), any(), staging, any()) }
    coVerify(exactly = 0) { parts.markArchived(any(), any()) }
  }
}
