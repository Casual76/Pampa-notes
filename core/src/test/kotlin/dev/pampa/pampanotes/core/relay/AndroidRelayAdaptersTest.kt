package dev.pampa.pampanotes.core.relay

import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidRelayAdaptersTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun settingsSessionIsBoundToServerOwnerAndRejectsTokenReplacement() = runBlocking {
    val settings = mockk<PampaSettingsStore>()
    val http = mockk<RelayHttp>()
    val configured = PampaSettings(syncEnabled = true, syncServerUrl = "https://relay.test",
      syncDeviceId = "device-a", syncDeviceName = "Phone", syncAccount = "person@example.test")
    coEvery { settings.current() } returns configured
    coEvery { settings.syncOwnerId() } returns "owner-a"
    coEvery { settings.syncToken() } returns "token-a"
    every { settings.syncForeignOwner } returns flowOf("")
    coEvery { http.owner(any()) } returns "owner-a"
    val accounts = AndroidRelayAccounts(settings, http)
    val snapshot = accounts.snapshot()
    assertTrue(accounts.isCurrent(snapshot))
    coEvery { settings.syncToken() } returns "token-b"
    assertFalse(accounts.isCurrent(snapshot))
    assertEquals("RelaySession(redacted)", snapshot.toString())
  }

  @Test fun staleDatabaseOwnerCannotUseSessionOfAnotherAccount() = runBlocking {
    val settings = mockk<PampaSettingsStore>()
    val http = mockk<RelayHttp>()
    coEvery { settings.current() } returns PampaSettings(syncEnabled = true,
      syncServerUrl = "https://relay.test", syncDeviceId = "device-a")
    coEvery { settings.syncOwnerId() } returns "old-owner"
    coEvery { settings.syncToken() } returns "new-owner-session"
    every { settings.syncForeignOwner } returns flowOf("")
    coEvery { http.owner(any()) } returns "new-owner"
    try {
      AndroidRelayAccounts(settings, http).snapshot()
      fail("foreign owner must be rejected")
    } catch (failure: RelayFailure) {
      assertEquals(RelayState.ACCOUNT_CHANGED, failure.state)
      assertEquals("owner_mismatch", failure.code)
    }
  }

  @Test fun validatedAppFilesPathsRejectTraversalAndSeparators() {
    val root = temporary.newFolder()
    File(root, "audio").mkdirs()
    File(root, "sources").mkdirs()
    val files = mockk<AppFiles>()
    every { files.root } returns root
    val adapter = AndroidRelayLocalFiles(mockk<PampaDatabase>(), files, FilesInUse())
    val row = RelayLocalFile(RelayKind.SOURCE, "row", "note", "row.bin", "../../remote-name",
      "application/octet-stream", "a".repeat(64), 3, "revision")
    assertEquals(File(root, "sources/row.bin").canonicalFile, adapter.path(row))
    listOf("../outside", "..", ".", "a/b", "a\\b", "C:secret", "bad\nname").forEach {
      try { adapter.path(row.copy(fileName = it)); fail("accepted invalid local filename") }
      catch (failure: RelayFailure) { assertEquals("invalid_local_path", failure.code) }
    }
  }
}
