package dev.pampa.pampanotes.core.sync

import androidx.room.withTransaction
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SyncDao
import dev.pampa.pampanotes.core.db.SyncOutboxEntity
import dev.pampa.pampanotes.core.db.SyncStateEntity
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the actual repository with mocked DB/API ports: no SQLite, HTTP, account or secrets. */
class SyncSizeRepositoryTest {
  @get:Rule val temporary = TemporaryFolder()
  private val db = mockk<PampaDatabase>(relaxed = true)
  private val dao = mockk<SyncDao>(relaxed = true)
  private val payloads = mockk<SyncPayloads>()
  private val applier = mockk<SyncApplier>()
  private val api = mockk<SyncApi>()
  private val settings = mockk<PampaSettingsStore>(relaxed = true)
  private val computer = mockk<ComputerSync>(relaxed = true)
  private val entries = mutableListOf(SyncOutboxEntity(id = 1, tbl = "transcripts", rowId = "raw", op = "U"))
  private var encoded = Encoded(JsonPrimitive("fits"), "content-hash", 42)
  private fun repository() = SyncRepository(db, payloads, applier, api, settings, computer)

  @Before fun setup() {
    mockkStatic(android.util.Log::class)
    every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    every { db.sync() } returns dao
    every { db.openHelper.writableDatabase.path } returns java.io.File(temporary.root, "fixture.db").path
    val recoveryCursor = mockk<android.database.Cursor>(relaxed = true)
    every { db.openHelper.readableDatabase.query(any<String>()) } returns recoveryCursor
    every { recoveryCursor.moveToNext() } returns false
    coEvery { dao.state() } returns SyncStateEntity(lastPullSeq = 0, deviceId = "device")
    coEvery { dao.outbox() } answers { entries.toList() }
    coEvery { dao.meta(any(), any()) } returns null
    coEvery { settings.current() } returns PampaSettings(syncServerUrl = "https://offline.invalid", syncDeviceName = "fixture")
    coEvery { settings.syncToken() } returns "synthetic-fixture"
    coEvery { settings.syncDeviceId() } returns "device"
    coEvery { settings.syncOwnerId() } returns "fixture-owner"
    every { settings.syncForeignOwner } returns flowOf("")
    coEvery { settings.syncOrphanAttempts() } returns emptyMap()
    coEvery { settings.syncReviveRoots() } returns emptySet()
    coEvery { api.status(any(), any()) } returns SyncStatus("fixture-owner", 0)
    coEvery { api.pull(any(), any(), any(), any(), any(), any()) } returns PullResponse(emptyList(), 0, false)
    coEvery { applier.apply(any(), any(), any(), any(), any()) } returns ApplyOutcome()
    coEvery { payloads.encode("transcripts", "raw") } answers { encoded }
    coEvery { api.push(any(), any(), any()) } returns PushResponse(1, 1)
  }
  @After fun teardown() { unmockkStatic(android.util.Log::class) }

  @Test fun `server too large retains real outbox and base and suppresses upload after recreation`() = runBlocking {
    coEvery { api.push(any(), any(), any()) } returns PushResponse(1, 0, listOf(Rejected("transcripts", "raw", "too_large")))
    val first = repository().syncNow()
    assertEquals(SyncSizeFailures.error(1), first.error)
    assertEquals(1, first.tooLarge)
    assertFalse(first.ok)
    assertTrue(first.completed)
    coVerify(exactly = 0) { dao.clearOutbox(any(), any(), any()) }
    coVerify(exactly = 0) { dao.upsertMeta(any()) }
    coVerify { settings.setLastSync(any(), SyncSizeFailures.error(1)) }
    clearMocks(api, answers = false)
    val second = repository().syncNow()
    assertEquals(1, second.tooLarge)
    coVerify(exactly = 0) { api.push(any(), any(), any()) }
    coVerify(exactly = 0) { dao.clearOutbox(any(), any(), any()) }
  }

  @Test fun `unsupported local size never uploads but other rows still sync`() = runBlocking {
    encoded = Encoded(JsonPrimitive("x".repeat(1_900_000)), "oversize", 42)
    entries += SyncOutboxEntity(id = 2, tbl = "notes", rowId = "n", op = "U")
    coEvery { payloads.encode("notes", "n") } returns Encoded(JsonPrimitive("other row"), "other-hash", 1)
    val report = repository().syncNow()
    assertEquals(1, report.tooLarge)
    assertEquals(1, report.pushed)
    coVerify(exactly = 1) { api.push(any(), any(), match { it.changes.single().id == "n" }) }
    coVerify(exactly = 0) { dao.clearOutbox("transcripts", "raw", any()) }
    coVerify(exactly = 1) { dao.clearOutbox("notes", "n", 2) }
  }

  @Test fun `edit retries new content and clears the old durable failure after success`() = runBlocking {
    encoded = Encoded(JsonPrimitive("x".repeat(1_900_000)), "oversize", 42)
    assertEquals(1, repository().syncNow().tooLarge)
    encoded = Encoded(JsonPrimitive("edited and fits"), "edited-hash", 43)
    val report = repository().syncNow()
    assertTrue(report.ok)
    assertEquals(0, report.tooLarge)
    coVerify(exactly = 1) { api.push(any(), any(), match { it.changes.single().hash == "edited-hash" }) }
    coVerify(exactly = 1) { dao.clearOutbox("transcripts", "raw", 1) }
    coVerify(exactly = 1) { dao.upsertMeta(match { it.hash == "edited-hash" }) }
    coVerify { settings.setLastSync(any(), "") }
  }

  @Test fun `previously silently dropped sourced row is recovered once through normal incremental push`() = runBlocking {
    mockkStatic("androidx.room.RoomDatabaseKt")
    try {
      coEvery { db.withTransaction<Unit>(any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
      val cursor = mockk<android.database.Cursor>(relaxed = true)
      every { db.openHelper.readableDatabase.query(any<String>()) } returns cursor
      every { cursor.moveToNext() } returnsMany listOf(true, false)
      every { cursor.getString(0) } returns "raw"
      val sql = db.openHelper.readableDatabase
      coEvery { dao.outboxEntry("transcripts", "raw") } returns null
      entries.clear()
      coEvery { dao.markDirty(any()) } answers { entries += firstArg<SyncOutboxEntity>().copy(id = 10) }
      val first = repository().syncNow()
      assertTrue(first.ok)
      assertEquals(1, first.pushed)
      coVerify(exactly = 1) { dao.markDirty(match { it.tbl == "transcripts" && it.rowId == "raw" && it.op == "U" }) }
      verify(exactly = 1) { sql.query(any<String>()) }
      clearMocks(sql, answers = false)
      repository().syncNow()
      verify(exactly = 0) { sql.query(any<String>()) }
    } finally { unmockkStatic("androidx.room.RoomDatabaseKt") }
  }

  @Test fun `dropped sourced row is dirty before a nonempty first pull can apply the old server version`() =
    recoveryBeforePull(rebaseline = false)

  @Test fun `dropped sourced row is dirty before rebaseline can apply the old server version`() =
    recoveryBeforePull(rebaseline = true)

  @Test fun `old post-pull recovery marker cannot skip the corrected pre-pull protection`() =
    recoveryBeforePull(rebaseline = false, previousRecovery = true)

  private fun recoveryBeforePull(rebaseline: Boolean, previousRecovery: Boolean = false) = runBlocking {
    mockkStatic("androidx.room.RoomDatabaseKt")
    try {
      coEvery { db.withTransaction<Unit>(any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
      val cursor = mockk<android.database.Cursor>(relaxed = true)
      every { db.openHelper.readableDatabase.query(any<String>()) } returns cursor
      every { cursor.moveToNext() } returnsMany listOf(true, false)
      every { cursor.getString(0) } returns "raw"
      if (previousRecovery) {
        val scope = dev.pampa.pampanotes.core.files.Hashing.sha256("wire1\nhttps://offline.invalid\nfixture-owner\ndevice")
        java.io.File(temporary.root, "fixture.db.sync-size-$scope.json")
          .writeText("""{"@cloud-source-wire-recovery/1":"1"}""")
      }
      entries.clear()
      coEvery { dao.outboxEntry("transcripts", "raw") } answers { entries.firstOrNull { it.rowId == "raw" } }
      coEvery { dao.markDirty(any()) } answers { entries += firstArg<SyncOutboxEntity>().copy(id = 10) }
      val remote = WireChange("transcripts", "raw", "U", 1, "old-server-hash", JsonPrimitive("OLD SERVER RAW"), seq = 1, deviceId = "other")
      var pulls = 0
      coEvery { api.pull(any(), any(), any(), any(), any(), any()) } answers {
        assertTrue("Recovery must precede the first remote page", entries.any { it.tbl == "transcripts" && it.rowId == "raw" })
        pulls++
        if (rebaseline && pulls == 1) PullResponse(emptyList(), 0, false, rebaseline = true)
        else PullResponse(listOf(remote), 1, false)
      }
      coEvery { applier.apply(any(), any(), any(), any(), any()) } coAnswers {
        assertTrue("Remote application must see dirty local evidence", entries.any { it.rowId == "raw" })
        assertEquals(listOf(remote), firstArg<List<WireChange>>())
        ApplyOutcome()
      }
      val report = repository().syncNow()
      assertTrue(report.ok)
      assertEquals(1, report.pushed)
      assertEquals(if (rebaseline) 2 else 1, pulls)
      coVerify { api.push(any(), any(), match { it.changes.single().hash == encoded.hash }) }
    } finally { unmockkStatic("androidx.room.RoomDatabaseKt") }
  }

  @Test fun `foreign account is rejected before recovery or pull touches local evidence`() = runBlocking {
    coEvery { api.status(any(), any()) } returns SyncStatus("foreign-owner", 0)
    coEvery { dao.metaCount() } returns 1
    val report = repository().syncNow()
    assertTrue(report.foreignAccount)
    coVerify(exactly = 0) { api.pull(any(), any(), any(), any(), any(), any()) }
    coVerify(exactly = 0) { dao.markDirty(any()) }
    verify(exactly = 0) { db.openHelper.readableDatabase.query(any<String>()) }
  }
}
