package dev.pampa.pampanotes.core.repo

import androidx.room.withTransaction
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.refinement.RefinementResult
import dev.pampa.pampanotes.core.settings.RefinementPreset
import dev.pampa.pampanotes.core.transcription.VoiceNames
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Calls the real saveRefinement; only the Room transaction runner and DAOs are replaced. */
class TranscriptionRefinementVoiceClockRoundThreeTest {
  private val db = mockk<PampaDatabase>()
  private val sessions = mockk<SessionDao>()
  private val parts = mockk<AudioPartDao>()
  private val transcripts = mockk<TranscriptDao>()
  private var session = SessionEntity("session", "note", date = "2026-11-08", position = 0,
    activeTranscriptId = "raw", createdAt = 1, updatedAt = 10)
  private val original = TranscriptEntity("raw", "session", TranscriptKind.RAW, "custom", "test", text = "Original\n\nexact\n",
    wordCount = 2, createdAt = 1, cloudSourceJson = "{\"text\":\" Original source\\n \"}")
  private val versions = mutableListOf(original)
  private val recordings = listOf("A", "B").mapIndexed { index, id ->
    AudioPartEntity(id, "session", index, "$id.m4a", "$id.m4a", "audio/mp4", 0, 1000, "a".repeat(64), 1)
  }.toMutableList()
  private val writes = mutableListOf<String>()

  private fun repository() = TranscriptionRepository(jobs = mockk(), sessions = sessions, notes = mockk(), parts = parts,
    transcripts = transcripts, segments = mockk(), settingsStore = mockk(), keys = mockk(), http = mockk(), resolver = mockk(),
    db = db, computerAuth = mockk(), stats = mockk(), sessionRepository = mockk(), files = mockk(), spending = mockk(),
    credentials = mockk(), cooldowns = mockk())
  private val result = RefinementResult("Refined result", "test-model", RefinementPreset.CLEAN, 1f, false)

  @Before fun setup() {
    mockkStatic("androidx.room.RoomDatabaseKt")
    coEvery { db.withTransaction<Any?>(any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
    coEvery { sessions.get("session") } coAnswers { session }
    coEvery { parts.bySession("session") } coAnswers { recordings.toList() }
    coEvery { transcripts.get(any()) } coAnswers { versions.find { it.id == firstArg<String>() } }
    coEvery { transcripts.bySession("session") } coAnswers { versions.toList() }
    coEvery { transcripts.delete(any()) } coAnswers { versions.removeAll { it.id == firstArg<String>() }; Unit }
    coEvery { transcripts.upsert(any()) } coAnswers { versions += firstArg<TranscriptEntity>(); Unit }
    coEvery { sessions.setVoiceNames(any(), any(), any()) } coAnswers {
      writes += "capture"
      session = session.copy(voiceNames = secondArg(), updatedAt = thirdArg())
    }
    coEvery { sessions.setActiveTranscript(any(), any(), any()) } coAnswers {
      writes += "select"
      session = session.copy(activeTranscriptId = secondArg(), updatedAt = thirdArg())
    }
  }

  @After fun cleanup() { unmockkStatic("androidx.room.RoomDatabaseKt") }

  @Test fun selectingRefinementCapturesEveryLegacyRecordingBeforeRaisingTheFallback() = runTest {
    val saved = repository().saveRefinement("session", "raw", result, "prompt")
    assertEquals(listOf("capture", "select"), writes)
    assertEquals(mapOf("A" to 10L, "B" to 10L), VoiceNames.clocks(session.voiceNames))
    assertEquals(saved.id, session.activeTranscriptId)
    assertEquals(saved.createdAt, session.updatedAt)
    assertEquals(original, versions.single { it.kind == TranscriptKind.RAW })
  }

  @Test fun repeatedRefinementKeepsExplicitAndUntouchedVoiceClocksAndSourceExact() = runTest {
    session = session.copy(voiceNames = VoiceNames.encode(mapOf("A|SPEAKER_00" to "Alice"), mapOf("A" to 100L)))
    val repo = repository()
    repo.saveRefinement("session", "raw", result, "prompt-1")
    val expected = mapOf("A" to 100L, "B" to 10L)
    assertEquals(expected, VoiceNames.clocks(session.voiceNames))
    repo.saveRefinement("session", "raw", result.copy(text = "Another refinement"), "prompt-2")
    assertEquals(expected, VoiceNames.clocks(session.voiceNames))
    assertEquals(mapOf("A|SPEAKER_00" to "Alice"), VoiceNames.decode(session.voiceNames))
    assertEquals(original, versions.single { it.kind == TranscriptKind.RAW })
    assertEquals(1, versions.count { it.kind == TranscriptKind.REFINED })
    assertEquals(listOf("capture", "select", "select"), writes)
  }

  @Test fun textOnlyRefinementDoesNotInventRecordingClockEvidence() = runTest {
    recordings.clear()
    repository().saveRefinement("session", "raw", result, "prompt")
    assertNull(session.voiceNames)
    assertEquals(listOf("select"), writes)
    coVerify(exactly = 0) { sessions.setVoiceNames(any(), any(), any()) }
  }
}
