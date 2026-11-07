package dev.pampa.pampanotes.core.relay

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RelayRepositoryTest {
  @get:Rule val temporary = TemporaryFolder()
  private fun fixture() = RelayFixture(temporary.newFolder())
  private fun large() = ByteArray(RelayLimits.CHUNK_BYTES + 37) { (it % 251).toByte() }

  @Test fun uploadIsAuthenticatedSequentialAndRetainsOriginal() = runBlocking {
    fixture().use { f ->
      val bytes = large()
      val row = f.local.row(bytes, kind = RelayKind.AUDIO)
      val progress = mutableListOf<RelayProgress>()
      val result = f.repository().uploadNote(row.noteId, onProgress = progress::add)
      assertEquals(RelayState.READY, result.results.single().state)
      assertEquals(listOf(0, 1), f.puts)
      assertTrue(f.beginWasDurable)
      assertArrayEquals(bytes, f.local.path(row).readBytes())
      assertEquals(bytes.size.toLong(), progress.last().bytes)
      assertEquals(0, f.authFailures.get())
      assertTrue(f.failures.isEmpty())
      val raw = File(temporary.root, "").walkTopDown().filter { it.extension == "json" }.joinToString { it.readText() }
      assertFalse(raw.contains(f.accounts.session.token))
      assertFalse(raw.contains("Bearer"))
    }
  }

  @Test fun lostBeginResponseReusesExactPersistedBodyAfterFreshnessWindow() = runBlocking {
    fixture().use { f ->
      f.local.row("abc".toByteArray())
      f.lostBeginOnce = true
      val repository = f.repository()
      repository.queueAllLocalFiles()
      assertEquals(RelayState.RETRY, repository.resumeUploads().results.single().state)
      val expiry = f.transfer!!.expiresAt
      f.clock.advance(181_000)
      assertEquals(RelayState.READY, f.repository().resumeUploads().results.single().state)
      assertEquals(2, f.beginBodies.size)
      assertEquals(f.beginBodies[0], f.beginBodies[1])
      assertEquals(expiry, f.transfer!!.expiresAt)
      assertTrue(f.beginWasDurable)
    }
  }

  @Test fun acknowledgedChunkIsResumedAfterLostPutAndProcessDeath() = runBlocking {
    fixture().use { f ->
      f.local.row(large())
      f.failPutOnce = 0
      assertEquals(RelayState.RETRY, f.repository().uploadAllLocalFiles().results.single().state)
      assertEquals(RelayState.READY, f.repository().resumeUploads().results.single().state)
      assertEquals(listOf(0, 1), f.puts)
      assertEquals(1, f.beginBodies.size)
    }
  }

  @Test fun lostCommitResponseResumesStatusWithoutPuttingAgain() = runBlocking {
    fixture().use { f ->
      f.local.row("abc".toByteArray())
      f.failCommitOnce = true
      assertEquals(RelayState.RETRY, f.repository().uploadAllLocalFiles().results.single().state)
      val deadline = f.transfer!!.expiresAt
      assertEquals(RelayState.READY, f.repository().resumeUploads().results.single().state)
      assertEquals(listOf(0), f.puts)
      assertEquals(deadline, f.transfer!!.expiresAt)
    }
  }

  @Test fun allLocalQueueIncludesBothKindsAndPerNoteQueueIsScoped() = runBlocking {
    fixture().use { f ->
      val first = f.local.row("one".toByteArray(), kind = RelayKind.AUDIO)
      val second = f.local.row("two".toByteArray(), "row-b")
      f.local.rows[1] = second.copy(noteId = "note-b")
      val onlyFirst = f.repository().queueNote(first.noteId)
      assertEquals(listOf(first.entityId), onlyFirst.results.map { it.entityId })
      val all = f.repository().queueAllLocalFiles()
      assertEquals(setOf(RelayKind.AUDIO, RelayKind.SOURCE), all.results.map { it.kind }.toSet())
      assertEquals(2, f.journal.all().size)
      assertTrue(f.beginBodies.isEmpty())
    }
  }

  @Test fun serverQuotaIsTerminalUntilExplicitAction() = runBlocking {
    fixture().use { f ->
      f.local.row("abc".toByteArray())
      f.quotaBegin = true
      assertEquals(RelayState.QUOTA, f.repository().uploadAllLocalFiles().results.single().state)
      assertTrue(f.repository().resumeUploads().results.isEmpty())
      assertEquals(1, f.beginBodies.size)
    }
  }

  @Test fun invalidUploadAcknowledgementCannotPublishReady() = runBlocking {
    fixture().use { f ->
      f.local.row("abc".toByteArray())
      f.invalidAck = true
      assertEquals(RelayState.INTEGRITY_ERROR, f.repository().uploadAllLocalFiles().results.single().state)
      assertEquals("uploading", f.transfer!!.state)
    }
  }

  @Test fun sourceChangedDuringUploadIsDetectedBeforeCommit() = runBlocking {
    fixture().use { f ->
      val row = f.local.row("abc".toByteArray())
      f.afterPut = { f.local.path(row).writeText("xyz") }
      assertEquals(RelayState.SOURCE_CHANGED, f.repository().uploadAllLocalFiles().results.single().state)
      assertEquals("uploading", f.transfer!!.state)
    }
  }

  @Test fun staleBeginStopsAutomaticRetriesAndExplicitResendCreatesNewIdentity() = runBlocking {
    fixture().use { f ->
      val row = f.local.row("abc".toByteArray())
      f.staleBegin = true
      assertEquals(RelayState.EXPIRED, f.repository().uploadNote(row.noteId).results.single().state)
      assertTrue(f.repository().resumeUploads().results.isEmpty())
      assertEquals(1, f.beginBodies.size)
      f.staleBegin = false
      f.clock.advance(10)
      assertEquals(RelayState.READY, f.repository().uploadNote(row.noteId, resend = true).results.single().state)
      val first = RelayLimits.json.decodeFromString<RelayBegin>(f.beginBodies[0])
      val second = RelayLimits.json.decodeFromString<RelayBegin>(f.beginBodies[1])
      assertNotEquals(first.requestId, second.requestId)
      assertNotEquals(first.requestedAt, second.requestedAt)
    }
  }

  @Test fun expiryOfUnknownBeginCannotSilentlyRenewWindow() = runBlocking {
    fixture().use { f ->
      f.local.row("abc".toByteArray())
      f.lostBeginOnce = true
      assertEquals(RelayState.RETRY, f.repository().uploadAllLocalFiles().results.single().state)
      f.clock.advance(RelayLimits.TTL_MS + RelayLimits.BEGIN_FRESHNESS_MS)
      assertTrue(f.repository().resumeUploads().results.isEmpty())
      assertEquals(RelayState.EXPIRED, f.repository().jobs().single().state)
      assertEquals(1, f.beginBodies.size)
    }
  }

  @Test fun changedSourceWithSameSizeIsRejectedBeforeRetry() = runBlocking {
    fixture().use { f ->
      val row = f.local.row(large())
      f.failPutOnce = 0
      assertEquals(RelayState.RETRY, f.repository().uploadAllLocalFiles().results.single().state)
      val path = f.local.path(row)
      val mtime = path.lastModified()
      java.io.RandomAccessFile(path, "rw").use { it.writeByte(123) }
      path.setLastModified(mtime)
      assertEquals(RelayState.SOURCE_CHANGED, f.repository().resumeUploads().results.single().state)
      assertEquals(listOf(0), f.puts)
      assertEquals(1, f.beginBodies.size)
    }
  }

  @Test fun unsupportedZeroAndOversizedFilesDoNotBegin() = runBlocking {
    fixture().use { f ->
      f.local.row(ByteArray(0))
      val row = f.local.row("a".toByteArray(), "oversized")
      f.local.rows[1] = row.copy(bytes = RelayLimits.MAX_FILE_BYTES + 1)
      assertTrue(f.repository().queueAllLocalFiles().results.all { it.state == RelayState.UNSUPPORTED })
      assertTrue(f.beginBodies.isEmpty())
    }
  }

  @Test fun receiveMatchesSyncedBytesAndUsesOwnPathNotRemoteName() = runBlocking {
    fixture().use { f ->
      val bytes = "abc".toByteArray()
      val row = f.local.row(bytes, present = false)
      f.ready(bytes, row, "../../outside.bin")
      f.transfer = f.transfer!!.copy(entityId = "different-origin-row")
      val result = f.repository().fetch(row.kind, row.entityId)
      assertEquals(RelayState.RECEIVED, result.state)
      assertArrayEquals(bytes, f.local.path(row).readBytes())
      assertEquals(f.local.path(row), result.file)
      assertEquals(1, f.local.placements)
      assertTrue(f.journal.stage(result.jobId!!).listFiles().isNullOrEmpty())
    }
  }

  @Test fun evictedReceiptDownloadsSameTransferAgainAfterRestartWithoutRenewingItsWindow() = runBlocking {
    fixture().use { f ->
      val bytes = "received original".toByteArray()
      val row = f.local.row(bytes, present = false)
      f.ready(bytes, row)
      val first = f.repository().fetch(row.kind, row.entityId)
      assertEquals(RelayState.RECEIVED, first.state)
      assertArrayEquals(bytes, first.file!!.readBytes())
      val saved = f.journal.all().single()
      assertTrue(first.file.delete())
      f.clock.advance(30_000)

      val second = f.repository().fetch(row.kind, row.entityId)

      assertEquals(RelayState.RECEIVED, second.state)
      assertArrayEquals(bytes, second.file!!.readBytes())
      assertEquals(first.jobId, second.jobId)
      assertEquals(first.transferId, second.transferId)
      assertEquals(first.expiresAt, second.expiresAt)
      assertEquals(saved.deadline, f.journal.all().single().deadline)
      assertEquals(2, f.journal.all().single().attempts)
      assertEquals(listOf(0, 0), f.gets)
      assertTrue(f.beginBodies.isEmpty())
    }
  }

  @Test fun receivedFileWithSameLengthButWrongHashIsRecovered() = runBlocking {
    fixture().use { f ->
      val bytes = "abc".toByteArray()
      val row = f.local.row(bytes, present = false)
      f.ready(bytes, row)
      val first = f.repository().fetch(row.kind, row.entityId)
      assertEquals(RelayState.RECEIVED, first.state)
      first.file!!.writeText("xyz")

      val second = f.repository().receiveReady().results.single()

      assertEquals(RelayState.RECEIVED, second.state)
      assertEquals(first.jobId, second.jobId)
      assertArrayEquals(bytes, f.local.path(row).readBytes())
      assertEquals(listOf(0, 0), f.gets)
      assertTrue(f.beginBodies.isEmpty())
    }
  }

  @Test fun expiredReceiptCannotReportSuccessForAnEvictedFile() = runBlocking {
    fixture().use { f ->
      val bytes = "abc".toByteArray()
      val row = f.local.row(bytes, present = false)
      f.ready(bytes, row)
      val first = f.repository().fetch(row.kind, row.entityId)
      assertEquals(RelayState.RECEIVED, first.state)
      assertTrue(first.file!!.delete())
      // Discovery raced server expiry; the authoritative status must still reject the receipt.
      f.expiredStatus = true

      val second = f.repository().fetch(row.kind, row.entityId)

      assertEquals(RelayState.EXPIRED, second.state)
      assertNull(second.file)
      assertFalse(f.local.path(row).exists())
      assertEquals(listOf(0), f.gets)
      assertEquals(first.expiresAt, second.expiresAt)
      assertTrue(f.beginBodies.isEmpty())
    }
  }

  @Test fun receiptAtOriginalDeadlineDoesNotTriggerAnotherDownloadOrBegin() = runBlocking {
    fixture().use { f ->
      val bytes = "abc".toByteArray()
      val row = f.local.row(bytes, present = false)
      f.ready(bytes, row)
      val first = f.repository().fetch(row.kind, row.entityId)
      assertEquals(RelayState.RECEIVED, first.state)
      assertTrue(first.file!!.delete())
      f.clock.advance(RelayLimits.TTL_MS)

      val second = f.repository().fetch(row.kind, row.entityId)

      assertEquals(RelayState.UNMATCHED, second.state)
      assertNull(second.file)
      assertFalse(f.local.path(row).exists())
      assertEquals(listOf(0), f.gets)
      assertEquals(first.expiresAt, f.journal.all().single().deadline)
      assertTrue(f.beginBodies.isEmpty())
    }
  }

  @Test fun repeatedEvictionsKeepTheOriginalAttemptBudget() = runBlocking {
    fixture().use { f ->
      val bytes = "abc".toByteArray()
      val row = f.local.row(bytes, present = false)
      f.ready(bytes, row)
      var first: RelayResult? = null
      repeat(RelayLimits.MAX_ATTEMPTS) { index ->
        val result = f.repository().fetch(row.kind, row.entityId)
        assertEquals(RelayState.RECEIVED, result.state)
        if (first == null) first = result
        assertEquals(first!!.jobId, result.jobId)
        assertEquals(first!!.expiresAt, result.expiresAt)
        assertEquals(index + 1, f.journal.all().single().attempts)
        assertTrue(result.file!!.delete())
      }

      repeat(2) {
        val result = f.repository().fetch(row.kind, row.entityId)
        assertEquals(RelayState.FAILED, result.state)
        assertEquals("retry_limit", result.code)
        assertNull(result.file)
      }
      assertEquals(RelayLimits.MAX_ATTEMPTS, f.gets.size)
      assertEquals(RelayLimits.MAX_ATTEMPTS, f.journal.all().single().attempts)
      assertFalse(f.local.path(row).exists())
      assertTrue(f.beginBodies.isEmpty())
    }
  }

  @Test fun receiptRecoveryStillRejectsCorruptDownloadAndPreservesTheExistingTarget() = runBlocking {
    fixture().use { f ->
      val bytes = "abc".toByteArray()
      val row = f.local.row(bytes, present = false)
      f.ready(bytes, row)
      assertEquals(RelayState.RECEIVED, f.repository().fetch(row.kind, row.entityId).state)
      f.local.path(row).writeText("xyz")
      f.corruptGet = 0

      val result = f.repository().fetch(row.kind, row.entityId)

      assertEquals(RelayState.INTEGRITY_ERROR, result.state)
      assertNull(result.file)
      assertEquals("xyz", f.local.path(row).readText())
      assertEquals(1, f.local.placements)
      assertEquals(listOf(0, 0), f.gets)
    }
  }

  @Test fun receiptRecoveryStillChecksCurrentRowBeforePlacement() = runBlocking {
    fixture().use { f ->
      val bytes = "abc".toByteArray()
      val row = f.local.row(bytes, present = false)
      f.ready(bytes, row)
      val first = f.repository().fetch(row.kind, row.entityId)
      assertEquals(RelayState.RECEIVED, first.state)
      assertTrue(first.file!!.delete())
      f.local.beforePlace = { f.local.rows[0] = row.copy(revision = "replaced") }

      assertEquals(RelayState.ROW_CHANGED, f.repository().fetch(row.kind, row.entityId).state)
      assertFalse(f.local.path(row).exists())
      assertEquals(1, f.local.placements)
    }
  }

  @Test fun receiptRecoveryStillChecksCurrentAccountAfterDownload() = runBlocking {
    fixture().use { f ->
      val bytes = "abc".toByteArray()
      val row = f.local.row(bytes, present = false)
      f.ready(bytes, row)
      val first = f.repository().fetch(row.kind, row.entityId)
      assertEquals(RelayState.RECEIVED, first.state)
      assertTrue(first.file!!.delete())
      f.afterGet = { f.accounts.current = false }

      assertEquals(RelayState.ACCOUNT_CHANGED, f.repository().fetch(row.kind, row.entityId).state)
      assertFalse(f.local.path(row).exists())
      assertEquals(1, f.local.placements)
    }
  }

  @Test fun unmatchedTransferWaitsForTextSyncWithoutCreatingRows() = runBlocking {
    fixture().use { f ->
      val bytes = "abc".toByteArray()
      val row = f.local.row(bytes, present = false)
      f.ready(bytes, row)
      f.local.rows.clear()
      assertEquals(RelayState.UNMATCHED, f.repository().receiveReady().results.single().state)
      assertTrue(f.gets.isEmpty())
      assertTrue(f.local.rows.isEmpty())
      f.local.rows += row
      assertEquals(RelayState.RECEIVED, f.repository().receiveReady().results.single().state)
    }
  }

  @Test fun matchingRequiresKindHashAndByteCount() = runBlocking {
    fixture().use { f ->
      val bytes = "abc".toByteArray()
      val row = f.local.row(bytes, present = false)
      f.ready(bytes, row)
      f.local.rows[0] = row.copy(kind = RelayKind.AUDIO)
      assertEquals(RelayState.UNMATCHED, f.repository().receiveReady().results.single().state)
      f.local.rows[0] = row.copy(bytes = 4)
      assertEquals(RelayState.UNMATCHED, f.repository().receiveReady().results.single().state)
      f.local.rows[0] = row.copy(sha256 = "0".repeat(64))
      assertEquals(RelayState.UNMATCHED, f.repository().receiveReady().results.single().state)
      assertTrue(f.gets.isEmpty())
    }
  }

  @Test fun partialDownloadResumesVerifiedChunksAfterProcessDeath() = runBlocking {
    fixture().use { f ->
      val bytes = large()
      val row = f.local.row(bytes, present = false)
      f.ready(bytes, row)
      f.failGetOnce = 1
      assertEquals(RelayState.RETRY, f.repository().receiveReady().results.single().state)
      assertFalse(f.local.path(row).exists())
      val saved = f.journal.all().single()
      assertEquals(RelayLimits.CHUNK_BYTES.toLong(), saved.doneBytes)
      assertEquals(RelayState.RECEIVED, f.repository().receiveReady().results.single().state)
      assertEquals(listOf(0, 1, 1), f.gets)
      assertArrayEquals(bytes, f.local.path(row).readBytes())
    }
  }

  @Test fun corruptedSavedChunkIsRevalidatedAndDownloadedAgain() = runBlocking {
    fixture().use { f ->
      val bytes = large()
      val row = f.local.row(bytes, present = false)
      f.ready(bytes, row)
      f.failGetOnce = 1
      f.repository().receiveReady()
      val saved = f.journal.all().single()
      File(f.journal.stage(saved.id), "0.chunk").writeBytes(byteArrayOf(42))
      assertEquals(RelayState.RECEIVED, f.repository().receiveReady().results.single().state)
      assertEquals(listOf(0, 1, 0, 1), f.gets)
    }
  }

  @Test fun chunkHashFailurePreservesExistingTargetAndNeverExposesPartialFile() = runBlocking {
    fixture().use { f ->
      val row = f.local.row("abc".toByteArray(), present = false)
      f.local.path(row).writeText("previous target")
      f.ready("abc".toByteArray(), row)
      f.corruptGet = 0
      assertEquals(RelayState.INTEGRITY_ERROR, f.repository().receiveReady().results.single().state)
      assertEquals("previous target", f.local.path(row).readText())
      assertEquals(0, f.local.placements)
    }
  }

  @Test fun responseLengthMismatchIsRejectedBeforeVisiblePlacement() = runBlocking {
    fixture().use { f ->
      val row = f.local.row("abc".toByteArray(), present = false)
      f.ready("abc".toByteArray(), row)
      f.wrongLengthHeader = true
      assertEquals(RelayState.INTEGRITY_ERROR, f.repository().receiveReady().results.single().state)
      assertFalse(f.local.path(row).exists())
    }
  }

  @Test fun wholeFileDigestMustMatchSynchronizedRow() = runBlocking {
    fixture().use { f ->
      val row = f.local.row("abc".toByteArray(), present = false)
      f.ready("xyz".toByteArray(), row)
      assertEquals(RelayState.INTEGRITY_ERROR, f.repository().receiveReady().results.single().state)
      assertFalse(f.local.path(row).exists())
    }
  }

  @Test fun rowDeletedJustBeforePlacementCannotBeResurrected() = runBlocking {
    fixture().use { f ->
      val row = f.local.row("abc".toByteArray(), present = false)
      f.ready("abc".toByteArray(), row)
      f.local.beforePlace = { f.local.rows.clear() }
      assertEquals(RelayState.ROW_CHANGED, f.repository().receiveReady().results.single().state)
      assertFalse(f.local.path(row).exists())
      assertEquals(0, f.local.placements)
    }
  }

  @Test fun rowReplacedJustBeforePlacementIsRejected() = runBlocking {
    fixture().use { f ->
      val row = f.local.row("abc".toByteArray(), present = false)
      f.ready("abc".toByteArray(), row)
      f.local.beforePlace = { f.local.rows[0] = row.copy(revision = "replacement") }
      assertEquals(RelayState.ROW_CHANGED, f.repository().receiveReady().results.single().state)
      assertFalse(f.local.path(row).exists())
    }
  }

  @Test fun accountSwitchDuringDownloadPreventsPlacement() = runBlocking {
    fixture().use { f ->
      val row = f.local.row("abc".toByteArray(), present = false)
      f.ready("abc".toByteArray(), row)
      f.afterGet = { f.accounts.current = false }
      assertEquals(RelayState.ACCOUNT_CHANGED, f.repository().receiveReady().results.single().state)
      assertFalse(f.local.path(row).exists())
      assertEquals(0, f.local.placements)
    }
  }

  @Test fun accountSwitchAtFinalGuardPreventsPlacement() = runBlocking {
    fixture().use { f ->
      val row = f.local.row("abc".toByteArray(), present = false)
      f.ready("abc".toByteArray(), row)
      f.local.beforePlace = { f.accounts.current = false }
      assertEquals(RelayState.ACCOUNT_CHANGED, f.repository().receiveReady().results.single().state)
      assertFalse(f.local.path(row).exists())
    }
  }

  @Test fun validExistingTargetIsPreservedWithoutDownloading() = runBlocking {
    fixture().use { f ->
      val row = f.local.row("abc".toByteArray())
      f.ready("abc".toByteArray(), row)
      val timestamp = f.local.path(row).lastModified()
      assertEquals(RelayState.ALREADY_LOCAL, f.repository().receiveReady().results.single().state)
      assertTrue(f.gets.isEmpty())
      assertEquals(timestamp, f.local.path(row).lastModified())
      assertEquals(0, f.local.placements)
    }
  }

  @Test fun expiredStatusStopsRetryAndCleansStaging() = runBlocking {
    fixture().use { f ->
      val bytes = large()
      val row = f.local.row(bytes, present = false)
      f.ready(bytes, row)
      f.failGetOnce = 1
      f.repository().receiveReady()
      val saved = f.journal.all().single()
      assertTrue(f.journal.stage(saved.id).exists())
      f.expiredStatus = true
      assertEquals(RelayState.EXPIRED, f.repository().receiveReady().results.single().state)
      assertFalse(f.journal.stage(saved.id).exists())
      val count = f.gets.size
      assertEquals(RelayState.EXPIRED, f.repository().receiveReady().results.single().state)
      assertEquals(count, f.gets.size)
    }
  }

  @Test fun missingTransferAfterPartialUploadIsTerminalAndDoesNotBeginAgain() = runBlocking {
    fixture().use { f ->
      f.local.row(large())
      f.failPutOnce = 0
      f.repository().uploadAllLocalFiles()
      f.missingStatus = true
      assertEquals(RelayState.EXPIRED, f.repository().resumeUploads().results.single().state)
      assertTrue(f.repository().resumeUploads().results.isEmpty())
      assertEquals(1, f.beginBodies.size)
    }
  }

  @Test fun clockDiscontinuityDoesNotRenewSavedBegin() = runBlocking {
    fixture().use { f ->
      f.local.row("abc".toByteArray())
      f.repository().queueAllLocalFiles()
      f.clock.elapsed = 1
      assertTrue(f.repository().resumeUploads().results.isEmpty())
      assertEquals(RelayState.EXPIRED, f.repository().jobs().single().state)
      assertTrue(f.beginBodies.isEmpty())
    }
  }
}
