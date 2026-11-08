package dev.pampa.pampanotes.core.repo

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.alignment.CloudSources
import dev.pampa.pampanotes.core.alignment.CloudTextPart
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.transcription.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** PREPARED ONLY: real Room merge invariants; no model, audio processing or network. */
@RunWith(AndroidJUnit4::class)
class SessionOwnedMergeB06RoomTest {
  private lateinit var db: PampaDatabase
  private lateinit var files: AppFiles
  private lateinit var directory: File
  private lateinit var repository: SessionRepository

  @Before fun setup() = runBlocking {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    directory = File(base.cacheDir, "session-b06-${UUID.randomUUID()}").also { it.mkdirs() }
    val context = object : ContextWrapper(base) {
      override fun getFilesDir() = File(directory, "files").also { it.mkdirs() }
      override fun getCacheDir() = File(directory, "cache").also { it.mkdirs() }
    }
    db = PampaDatabase.inMemory(context)
    files = AppFiles(context)
    repository = SessionRepository(db.sessions(), db.audioParts(), db.transcripts(), db.segments(), db.notes(), files, db)
    db.folders().upsert(FolderEntity("folder", "Recordings", createdAt = 1, updatedAt = 1, kind = FolderEntity.KIND_PERSONAL))
    db.notes().upsert(NoteEntity("note", "folder", "Material", "Retain body", createdAt = 1, updatedAt = 1))
    db.sessions().upsert(SessionEntity("session", "note", date = "2026-11-08", position = 0,
      voiceNames = VoiceNames.encode(mapOf("A|SPEAKER_00" to "Alice", "B|SPEAKER_00" to "Bob")), createdAt = 1, updatedAt = 10))
    db.audioParts().upsert(part("A", 0))
    db.audioParts().upsert(part("B", 1))
  }

  @After fun cleanup() { db.close(); directory.deleteRecursively() }

  private fun part(id: String, position: Int) = AudioPartEntity(id, "session", position, "$id.wav", id,
    "audio/wav", 15, 1000, "a".repeat(64), 1,
    recordedAtLocal = "2026-11-08T09:0$position", recordedAtSource = "media_metadata", recordedAtPrecision = "minute")

  private suspend fun oldCloud(): TranscriptEntity {
    val draft = TranscriptEntity("old", "session", TranscriptKind.RAW, "openrouter", "synthetic",
      language = "it", text = " old A \n\n old B ", wordCount = 4, createdAt = 1)
    val source = CloudSources.create(draft, draft.text, listOf(
      CloudTextPart("A", "a".repeat(64), " old A ", "it", "openrouter", "synthetic"),
      CloudTextPart("B", "a".repeat(64), " old B ", "it", "openrouter", "synthetic")))
    val raw = draft.copy(cloudSourceJson = CloudSources.encode(source))
    db.transcripts().upsert(raw)
    db.segments().insertAll(listOf(SegmentEntity(transcriptId = raw.id, partId = "B", indexInPart = 0,
      partStartMs = 10, partEndMs = 30, sessionStartMs = 1010, sessionEndMs = 1030, text = "old B",
      wordsJson = "0,20,B", speaker = "SPEAKER_00")))
    return raw
  }

  @Test fun cloudSaveKeepsSelectedRefinementExactHistoryAndUnchangedRecordingClock() = runBlocking {
    val old = oldCloud()
    val refined = old.copy(id = "refined", kind = TranscriptKind.REFINED, parentId = old.id,
      text = "Selected refinement", cloudSourceJson = null)
    db.transcripts().upsert(refined)
    db.sessions().setActiveTranscript("session", refined.id, 10)
    val beforeParts = db.audioParts().bySession("session")
    val text = "  Exact new A\n "
    val result = SessionTranscript(text, emptyList(), "it", "synthetic", "openrouter",
      sourceParts = listOf(CloudPartText("A", text, "it", "a".repeat(64), provider = "openrouter",
        model = "synthetic", exactTextKnown = true)))
    val saved = repository.saveTranscription("session", listOf("A"), result, beforeParts.filter { it.id == "A" })!!
    val session = db.sessions().get("session")!!
    assertEquals(refined.id, session.activeTranscriptId)
    assertEquals(old.id, saved.transcript!!.id)
    assertEquals(old, db.transcripts().get(old.id))
    assertEquals(refined, db.transcripts().get(refined.id))
    val versions = db.transcripts().bySession("session")
    assertEquals(3, versions.size)
    val fresh = versions.single { it.id !in setOf(old.id, refined.id) }
    val source = CloudSources.decode(fresh.cloudSourceJson)!!
    assertEquals(text, source.parts.single { it.id == "A" }.text)
    assertTrue(source.originals.any { it.text == text })
    assertEquals(10L, VoiceNames.clocks(session.voiceNames).getValue("B"))
    assertEquals(fresh.createdAt, VoiceNames.clocks(session.voiceNames).getValue("A"))
    assertEquals(mapOf("B|SPEAKER_00" to "Bob"), VoiceNames.decode(session.voiceNames))
    assertEquals(beforeParts, db.audioParts().bySession("session"))
    assertEquals("0,20,B", db.segments().byTranscript(fresh.id).single { it.partId == "B" }.wordsJson)
  }

  @Test fun deletingAPartRetainsCanonicalSiblingBytesAndSurvivingVoiceClock() = runBlocking {
    val a = db.audioParts().get("A")!!
    db.audioParts().upsert(db.audioParts().get("B")!!.copy(fileName = "./${a.fileName}"))
    files.audioFile(a.fileName).writeText("shared synthetic bytes")
    repository.deletePart("A")
    assertNull(db.audioParts().get("A"))
    assertEquals("shared synthetic bytes", files.audioFile(a.fileName).readText())
    val session = db.sessions().get("session")!!
    assertEquals(10L, VoiceNames.clocks(session.voiceNames).getValue("B"))
    assertTrue(VoiceNames.clocks(session.voiceNames).getValue("A") > 10)
  }

  @Test fun deletionRollbackKeepsBothGraphAndFilesIncludingVoiceClockCapture() = runBlocking {
    files.audioFile("A.wav").writeText("A bytes")
    files.audioFile("B.wav").writeText("B bytes")
    val before = db.sessions().get("session")
    val beforeParts = db.audioParts().bySession("session")
    db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_b06_touch BEFORE UPDATE ON notes BEGIN SELECT RAISE(ABORT, 'B06 rollback'); END")
    try {
      try { repository.deletePart("A"); fail("Expected DB rollback") } catch (_: android.database.sqlite.SQLiteException) { }
      assertEquals(before, db.sessions().get("session"))
      assertEquals(beforeParts, db.audioParts().bySession("session"))
      assertEquals("A bytes", files.audioFile("A.wav").readText())
      assertEquals("B bytes", files.audioFile("B.wav").readText())
    } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_b06_touch") }
  }
}
