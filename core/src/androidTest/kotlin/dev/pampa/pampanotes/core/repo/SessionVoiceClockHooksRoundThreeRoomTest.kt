package dev.pampa.pampanotes.core.repo

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.importing.PampaBundleMerge
import dev.pampa.pampanotes.core.importing.PampaBundleNote
import dev.pampa.pampanotes.core.importing.SdocxRepair
import dev.pampa.pampanotes.core.transcription.SessionSegment
import dev.pampa.pampanotes.core.transcription.SessionTranscript
import dev.pampa.pampanotes.core.transcription.VoiceNames
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** PREPARED ONLY: actual Room/repository operations, no device execution in the PN lease. */
@RunWith(AndroidJUnit4::class)
class SessionVoiceClockHooksRoundThreeRoomTest {
  private lateinit var db: PampaDatabase
  private lateinit var repository: SessionRepository
  private lateinit var directory: File
  private val noteId = "clock-note"

  @Before fun setup() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    directory = File(context.cacheDir, "voice-hooks-${UUID.randomUUID()}").also { it.mkdirs() }
    val isolated = object : ContextWrapper(context) {
      override fun getFilesDir() = File(directory, "files").also { it.mkdirs() }
      override fun getCacheDir() = File(directory, "cache").also { it.mkdirs() }
    }
    db = PampaDatabase.inMemory(context)
    repository = SessionRepository(db.sessions(), db.audioParts(), db.transcripts(), db.segments(), db.notes(), AppFiles(isolated), db)
  }

  @After fun cleanup() { db.close(); directory.deleteRecursively() }

  private suspend fun seed(id: String = "session", time: Long = 10, position: Int = 0,
    names: Map<String, String> = emptyMap(), ids: List<String> = listOf("A", "B"), date: String = "2026-11-01"): SessionEntity {
    db.folders().upsert(FolderEntity("folder", "Folder", createdAt = 1, updatedAt = 1))
    db.notes().upsert(NoteEntity(noteId, "folder", "Note", "Body", createdAt = 1, updatedAt = 1))
    val session = SessionEntity(id, noteId, "Title", date, position, createdAt = 1, updatedAt = time, voiceNames = VoiceNames.encode(names))
    db.sessions().upsert(session)
    ids.forEachIndexed { index, part -> db.audioParts().upsert(
      AudioPartEntity(part, id, index, "$part.m4a", "$part.m4a", "audio/mp4", 0, 1000, "a".repeat(64), 1)) }
    return session
  }

  private suspend fun row(id: String = "session") = db.sessions().get(id)!!
  private suspend fun clocks(id: String = "session") = VoiceNames.clocks(row(id).voiceNames)
  private suspend fun names(id: String = "session") = VoiceNames.decode(row(id).voiceNames)
  private fun raw(id: String = "raw", session: String = "session") =
    TranscriptEntity(id, session, TranscriptKind.RAW, "custom", "test", text = "Original", wordCount = 1, createdAt = 1)

  @Test fun titleDateAndSelectionChangesKeepLegacyVoiceAuthorityAtItsPreviousClock() = runTest {
    seed(names = mapOf("A|SPEAKER_00" to "Alice"))
    val original = raw()
    val refined = original.copy(id = "refined", kind = TranscriptKind.REFINED, parentId = original.id)
    db.transcripts().upsert(original)
    db.transcripts().upsert(refined)
    repository.rename("session", "My title", "2026-11-08")
    assertEquals(mapOf("A" to 10L, "B" to 10L), clocks())
    repository.setActiveTranscript("session", refined.id)
    assertEquals(mapOf("A" to 10L, "B" to 10L), clocks())
    val localSession = row()
    val local = PampaBundleNote(db.notes().get(noteId)!!, sessions = listOf(localSession),
      parts = db.audioParts().bySession("session"), transcripts = db.transcripts().bySession("session"))
    val incoming = local.copy(sessions = listOf(localSession.copy(title = "Remote title", updatedAt = 50,
      voiceNames = VoiceNames.encode(mapOf("A|SPEAKER_00" to "Alice at 50", "B|SPEAKER_00" to "Bob at 50")))))
    val merged = PampaBundleMerge.merge(local, incoming)
    assertEquals("My title", merged.sessions.single().title)
    assertEquals(refined.id, merged.sessions.single().activeTranscriptId)
    assertEquals("Bob at 50", VoiceNames.decode(merged.sessions.single().voiceNames)["B|SPEAKER_00"])
    assertEquals(50L, VoiceNames.clocks(merged.sessions.single().voiceNames).getValue("B"))
  }

  @Test fun metadataSelectionAndVoiceNoopsDoNotCreateClockEvidenceOrTouchRows() = runTest {
    val original = seed(names = mapOf("A|SPEAKER_00" to "Alice"))
    val note = db.notes().get(noteId)
    repository.rename("session", " Title ", original.date)
    repository.setActiveTranscript("session", null)
    repository.renameVoice("session", "A|SPEAKER_00", " Alice ")
    repository.renameVoice("session", "B|SPEAKER_00", null)
    repository.renameVoice("session", "missing|SPEAKER_00", "Ghost")
    assertEquals(original, row())
    assertEquals(note, db.notes().get(noteId))
    assertTrue(clocks().isEmpty())
  }

  @Test fun voiceRenameAndRemovalShareThePersistedClockAndLeaveOtherRecordingsAt10() = runTest {
    seed(names = mapOf("A|SPEAKER_00" to "Alice"))
    repository.renameVoice("session", "A|SPEAKER_00", "Alicia")
    assertEquals(row().updatedAt, clocks().getValue("A"))
    assertEquals(10L, clocks().getValue("B"))
    assertEquals("Alicia", names()["A|SPEAKER_00"])
    repository.renameVoice("session", "A|SPEAKER_00", null)
    assertEquals(row().updatedAt, clocks().getValue("A"))
    assertEquals(10L, clocks().getValue("B"))
    assertTrue(names().isEmpty())
    assertNotNull(row().voiceNames)
  }

  @Test fun deletingAnUnnamedRecordingKeepsItsTombstoneAndTheSurvivingVoiceClock() = runTest {
    seed(names = mapOf("B|SPEAKER_00" to "Bob"))
    repository.deletePart("A")
    assertNull(db.audioParts().get("A"))
    assertTrue(clocks().getValue("A") > 10)
    assertEquals(10L, clocks().getValue("B"))
    assertEquals(mapOf("B|SPEAKER_00" to "Bob"), names())
  }

  @Test fun retranscriptionCapturesUntouchedPartsBeforeNewRawSelectionRaisesTheFallback() = runTest {
    seed(names = mapOf("B|SPEAKER_00" to "Bob"))
    val original = raw()
    db.transcripts().upsert(original)
    db.sessions().setActiveTranscript("session", original.id, 10)
    db.segments().insertAll(listOf(SegmentEntity(transcriptId = original.id, partId = "B", indexInPart = 0,
      partStartMs = 0, partEndMs = 100, sessionStartMs = 1000, sessionEndMs = 1100,
      text = "Retained B", wordsJson = "0,100,B", speaker = "SPEAKER_00")))
    val result = SessionTranscript("Fresh A", listOf(SessionSegment("A", 0, 0, 100, 0, 100, "Fresh A", null, null,
      wordsEncoded = "0,100,A", speaker = "SPEAKER_00")), "it", "test", "custom")
    val saved = repository.saveTranscription("session", listOf("A"), result)!!
    val transcript = saved.transcript!!
    assertEquals(transcript.createdAt, clocks().getValue("A"))
    assertEquals(10L, clocks().getValue("B"))
    assertEquals(transcript.id, row().activeTranscriptId)
    assertEquals(mapOf("B|SPEAKER_00" to "Bob"), names())
    assertTrue(db.segments().byTranscript(transcript.id).any { it.partId == "B" && it.wordsJson == "0,100,B" })
  }

  private suspend fun assertCarry(beforeMove: Boolean, alias: String?) {
    seed("source", 100, 0, alias?.let { mapOf("A|SPEAKER_00" to it) }.orEmpty(), listOf("A", "L"))
    seed("target", 10, 1, mapOf("B|SPEAKER_00" to "Bob"), listOf("B"))
    if (beforeMove) repository.applyRepair(noteId, SdocxRepair.Plan(layout = linkedMapOf("target" to listOf("B", "A"))))
    else repository.movePartTo("A", "target")
    assertEquals("target", db.audioParts().get("A")!!.sessionId)
    assertEquals(mapOf("A" to 100L, "B" to 10L), clocks("target"))
    assertEquals(mapOf("L" to 100L), clocks("source"))
    assertEquals(alias, names("target")["A|SPEAKER_00"])
    assertEquals("Bob", names("target")["B|SPEAKER_00"])
    assertFalse(names("source").containsKey("A|SPEAKER_00"))
  }

  @Test fun carryBeforeMovePreservesANameWithoutPromotingB() = runTest { assertCarry(true, "Alice") }
  @Test fun carryAfterMovePreservesANameWithoutPromotingB() = runTest { assertCarry(false, "Alice") }
  @Test fun carryBeforeMovePreservesAnEmptyRemovalWithoutPromotingB() = runTest { assertCarry(true, null) }
  @Test fun carryAfterMovePreservesAnEmptyRemovalWithoutPromotingB() = runTest { assertCarry(false, null) }

  @Test fun repairDateAndSessionOrderChangesCaptureFallbackOnceAndRepeatedRepairIsANoop() = runTest {
    seed("first", 10, 0, ids = listOf("A"), date = "2026-11-01")
    seed("second", 20, 1, ids = listOf("B"), date = "2026-11-02")
    val plan = SdocxRepair.Plan(redates = mapOf("first" to "2026-11-03"))
    repository.applyRepair(noteId, plan)
    assertEquals(1, row("first").position)
    assertEquals(0, row("second").position)
    assertEquals(mapOf("A" to 10L), clocks("first"))
    assertEquals(mapOf("B" to 20L), clocks("second"))
    val after = db.sessions().plainByNote(noteId)
    repository.applyRepair(noteId, plan)
    assertEquals(after, db.sessions().plainByNote(noteId))
  }

  @Test fun splitInsertionAndMergeRenumberingFreezeTheFollowingSessionVoiceClock() = runTest {
    seed("source", 100, 0, ids = listOf("A", "B"))
    seed("following", 20, 1, ids = listOf("C"))
    val split = repository.splitAt("B")!!
    assertEquals(mapOf("A" to 100L), clocks("source"))
    assertEquals(mapOf("B" to 100L), clocks(split.id))
    assertEquals(mapOf("C" to 20L), clocks("following"))
    assertEquals(2, row("following").position)
    repository.mergeIntoPrevious(split.id)
    assertEquals(mapOf("C" to 20L), clocks("following"))
    assertEquals(1, row("following").position)
  }

  @Test fun partRenumberingCapturesOldFallbackAndBoundaryMoveIsANoop() = runTest {
    seed()
    assertTrue(repository.movePart("B", -1))
    assertEquals(mapOf("A" to 10L, "B" to 10L), clocks())
    val after = row()
    assertFalse(repository.movePart("B", -1))
    assertEquals(after, row())
  }
}
