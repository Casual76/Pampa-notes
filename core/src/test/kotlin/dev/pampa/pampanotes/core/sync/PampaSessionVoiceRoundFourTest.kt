package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.transcription.VoiceNames
import org.junit.Assert.*
import org.junit.Test

/** Pure production page helper. Compilation/execution belongs to the parent's Gradle lease. */
class PampaSessionVoiceRoundFourTest {
  private fun raw(p: String?, q: String?, pc: Long, qc: Long) = VoiceNames.encode(
    buildMap { p?.let { put("p|SPEAKER_00", it) }; q?.let { put("q|SPEAKER_00", it) } }, mapOf("p" to pc, "q" to qc))
  private fun row(id: String = "s", at: Long = 10, names: String? = null) = SessionEntity(
    id = id, noteId = "n", title = "Title $at", date = "2026-11-08", position = 0,
    activeTranscriptId = "selection-$at", createdAt = 1, updatedAt = at, voiceNames = names)
  private fun resolve(local: SessionEntity, remote: SessionEntity, metadata: SessionEntity): SessionEntity {
    val owners = mapOf("p" to "s", "q" to "s")
    val merge = SyncSessionVoiceMerge(listOf(local), owners)
    merge.receive(remote)
    return merge.resolve(listOf(metadata), owners).singleOrNull() ?: metadata
  }

  @Test fun applyMetadataCannotReplaceANewerRecordingClockOrPromoteUntouchedMaps() {
    val local = row(names = raw("Alice", "Anna", 100, 10))
    val remote = row(at = 50, names = raw("Stale Alice", "Anna", 20, 10))
    val result = resolve(local, remote, remote)
    assertEquals(remote.copy(voiceNames = local.voiceNames), result)
    assertEquals(mapOf("p" to 100L, "q" to 10L), VoiceNames.clocks(result.voiceNames))
  }

  @Test fun newClientsMergeDistinctConcurrentRecordingsOnApplyAndSkip() {
    val remote = row(at = 300, names = raw("Stale Alice", "Giulia", 20, 300))
    for (localAt in listOf(10L, 900L)) {
      val local = row(at = localAt, names = raw("Alice", "Anna", 100, 10))
      val metadata = if (localAt < remote.updatedAt) remote else local
      val result = resolve(local, remote, metadata)
      assertEquals(metadata.copy(voiceNames = raw("Alice", "Giulia", 100, 300)), result)
    }
  }

  @Test fun tombstoneWinsAgainstAStaleNameWhenOnlyAnotherRecordingWasEdited() {
    val local = row(names = raw(null, "Anna", 200, 10))
    val remote = row(at = 300, names = raw("Marco", "Giulia", 100, 300))
    val result = resolve(local, remote, remote)
    assertEquals(mapOf("q|SPEAKER_00" to "Giulia"), VoiceNames.decode(result.voiceNames))
    assertEquals(mapOf("p" to 200L, "q" to 300L), VoiceNames.clocks(result.voiceNames))
    assertEquals(remote.updatedAt, result.updatedAt)
  }

  @Test fun newerLegacyNullRemovesOwnedMapsWithoutAffectingMetadataClock() {
    val local = row(names = raw("Alice", "Anna", 100, 10))
    val remote = row(at = 300)
    val result = resolve(local, remote, remote)
    assertTrue(VoiceNames.decode(result.voiceNames).isEmpty())
    assertEquals(mapOf("p" to 300L, "q" to 300L), VoiceNames.clocks(result.voiceNames))
    assertEquals(300L, result.updatedAt)
  }

  @Test fun actualMoveTransfersSourceAuthorityAndDoesNotBorrowDestinationClock() {
    val source = row("source", 20, VoiceNames.encode(mapOf("p|SPEAKER_00" to "Alice")))
    val target = row("target", 900)
    val remoteSource = source.copy(voiceNames = null, updatedAt = 1000)
    val remoteTarget = target.copy(voiceNames = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Stale"), mapOf("p" to 10L)))
    val merge = SyncSessionVoiceMerge(listOf(source, target), mapOf("p" to source.id))
    merge.receive(remoteSource); merge.receive(remoteTarget)
    val changed = merge.resolve(listOf(remoteSource, remoteTarget), mapOf("p" to target.id)).associateBy { it.id }
    val result = changed[target.id] ?: remoteTarget
    assertNull((changed[source.id] ?: remoteSource).voiceNames)
    assertEquals(mapOf("p|SPEAKER_00" to "Alice"), VoiceNames.decode(result.voiceNames))
    assertEquals(mapOf("p" to 20L), VoiceNames.clocks(result.voiceNames))
    assertEquals(target.updatedAt, result.updatedAt)
  }

  @Test fun destinationSessionBeforeAudioAndAudioBeforeSessionConvergeToActualOwner() {
    val source = row("source", names = VoiceNames.encode(emptyMap(), mapOf("p" to 200L)))
    val target = row("target", at = 50)
    val received = target.copy(voiceNames = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Stale Marco"), mapOf("p" to 100L)))
    val oldOwners = mapOf("p" to source.id)
    val newOwners = mapOf("p" to target.id)
    for (sessionFirst in listOf(true, false)) {
      var rows = listOf(source, target)
      var owners = oldOwners
      for (sessionPage in listOf(sessionFirst, !sessionFirst)) {
        val page = SyncSessionVoiceMerge(rows, owners)
        if (sessionPage) {
          page.receive(received)
          rows = rows.map { if (it.id == target.id) received else it }
        } else owners = newOwners
        val changed = page.resolve(rows, owners).associateBy { it.id }
        rows = rows.map { changed[it.id] ?: it }
      }
      assertNull(rows.first { it.id == source.id }.voiceNames)
      val moved = rows.first { it.id == target.id }
      assertTrue(VoiceNames.decode(moved.voiceNames).isEmpty())
      assertEquals(mapOf("p" to 200L), VoiceNames.clocks(moved.voiceNames))
      assertEquals(target.updatedAt, moved.updatedAt)
    }
  }

  @Test fun rejectedMoveCannotApplyADestinationMapToTheActualSourceOwner() {
    val source = row("source", names = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Alice"), mapOf("p" to 100L)))
    val target = row("target")
    val pending = target.copy(voiceNames = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Other"), mapOf("p" to 900L)))
    val owners = mapOf("p" to source.id)
    val merge = SyncSessionVoiceMerge(listOf(source, target), owners)
    merge.receive(pending)
    val changed = merge.resolve(listOf(source, pending), owners)
    assertFalse(changed.any { it.id == source.id })
  }

  @Test fun namesBeforeANewRecordingRemainPendingAndTheCanonicalEchoMakesNoChanges() {
    val remote = row(names = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Alice"), mapOf("p" to 100L)))
    val page = SyncSessionVoiceMerge(emptyList(), emptyMap())
    page.receive(remote)
    assertTrue(page.resolve(listOf(remote), emptyMap()).isEmpty())
    val audioPage = SyncSessionVoiceMerge(listOf(remote), emptyMap())
    assertTrue(audioPage.resolve(listOf(remote), mapOf("p" to remote.id)).isEmpty())
    val echo = SyncSessionVoiceMerge(listOf(remote), mapOf("p" to remote.id))
    echo.receive(remote)
    assertTrue(echo.resolve(listOf(remote), mapOf("p" to remote.id)).isEmpty())
  }

  @Test fun workerPreservedSourcePageCannotInventARemovalBeforeTheAudioMoveArrives() {
    val source = row("source", names = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Alice"), mapOf("p" to 100L)))
    val target = row("target", at = 50)
    // Worker keeps p:100 while publishing the source's new metadata, until audio is received.
    val publishedSource = source.copy(updatedAt = 600, title = "Source after move")
    val sourcePage = SyncSessionVoiceMerge(listOf(source, target), mapOf("p" to source.id))
    sourcePage.receive(publishedSource)
    assertTrue(sourcePage.resolve(listOf(publishedSource, target), mapOf("p" to source.id)).isEmpty())
    val publishedTarget = target.copy(updatedAt = 600, voiceNames = source.voiceNames)
    val movePage = SyncSessionVoiceMerge(listOf(publishedSource, target), mapOf("p" to source.id))
    movePage.receive(publishedTarget)
    val changed = movePage.resolve(listOf(publishedSource, publishedTarget), mapOf("p" to target.id)).associateBy { it.id }
    assertNull(changed.getValue(source.id).voiceNames)
    assertEquals(source.voiceNames, (changed[target.id] ?: publishedTarget).voiceNames)
    assertEquals(publishedSource.updatedAt, changed.getValue(source.id).updatedAt)
  }

  @Test fun lastRecordingMoveKeepsTheModernOwnershipOmissionDistinctFromLegacyNull() {
    val raw = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Alice"), mapOf("p" to 100L))
    val (source, target) = VoiceNames.carry(raw, null, listOf("p"))
    assertEquals("{\"_pampaVoiceClocks\":{}}", source)
    assertTrue(VoiceNames.decode(source).isEmpty())
    assertTrue(VoiceNames.clocks(source).isEmpty())
    assertEquals(source, VoiceNames.encode(VoiceNames.decode(source)))
    assertEquals(source to target, VoiceNames.carry(source, target, listOf("p")))
    assertEquals(raw, target)
    assertNull(VoiceNames.encode(VoiceNames.decode(null)))
  }

  @Test fun aLaterSourceCleanupCannotRestoreNamesForAnAlreadyMovedRecording() {
    val names = VoiceNames.encode(mapOf("p|SPEAKER_00" to "Alice"), mapOf("p" to 100L))
    val source = row("source", names = names)
    val target = row("target", names = names)
    // The earlier audio page already moved p; this source row still contains stale evidence.
    val page = SyncSessionVoiceMerge(listOf(source, target), mapOf("p" to target.id))
    val remote = source.copy(voiceNames = null, updatedAt = 600)
    page.receive(remote)
    assertTrue(page.resolve(listOf(remote, target), mapOf("p" to target.id)).isEmpty())
    assertEquals(names, target.voiceNames)
  }
}
