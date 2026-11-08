package dev.pampa.pampanotes.core.importing

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.Hashing
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Runs the production persistence pipeline with observable DAO writes; Room/FKs have separate tests. */
class PampaBundleRekeyPersistenceTest {
  @get:Rule val temp = TemporaryFolder()

  @Test fun `simultaneous session and audio swaps retain live destinations and update references once by row ID`() = assertSwap(false)
  @Test fun `recovering verified evicted audio retains archive identity during the same swap`() = assertSwap(true)

  private fun assertSwap(recover: Boolean) = runTest {
    val db = mockk<PampaDatabase>()
    val folders = mockk<FolderDao>(relaxed = true); val notes = mockk<NoteDao>(relaxed = true)
    val sessions = mockk<SessionDao>(relaxed = true); val parts = mockk<AudioPartDao>(relaxed = true)
    val transcripts = mockk<TranscriptDao>(relaxed = true); val segments = mockk<SegmentDao>(relaxed = true)
    val sources = mockk<SourceDao>(relaxed = true); val tags = mockk<NoteTagDao>(relaxed = true)
    val jobs = mockk<JobDao>(relaxed = true); val files = mockk<AppFiles>()
    val audioDirectory = temp.newFolder("audio")
    val sourceDirectory = temp.newFolder("sources")
    every { files.audio } returns audioDirectory
    every { files.sources } returns sourceDirectory
    every { files.audioFile(any()) } answers { File(audioDirectory, firstArg<String>()) }
    every { files.sourceFile(any()) } answers { File(sourceDirectory, firstArg<String>()) }
    every { db.folders() } returns folders; every { db.notes() } returns notes; every { db.sessions() } returns sessions
    every { db.audioParts() } returns parts; every { db.transcripts() } returns transcripts; every { db.segments() } returns segments
    every { db.sources() } returns sources; every { db.tags() } returns tags; every { db.jobs() } returns jobs
    val folder = FolderEntity("folder", "Subject", createdAt = 1, updatedAt = 1)
    val note = NoteEntity("note", folder.id, "Old", "Old body", createdAt = 1, updatedAt = 1)
    val oldSessions = (0..1).map { i -> SessionEntity(PampaBundleLegacyReconcile.id("session", "note:session:$i"), note.id,
      if (i == 0) "Alpha" else "Beta", "2026-11-05", i, "raw-$i", createdAt = 1, updatedAt = 1) }
    val oldParts = oldSessions.mapIndexed { i, s -> AudioPartEntity(PampaBundleLegacyReconcile.id("part", "${s.id}:part:0"), s.id, 0,
      "$i.wav", "$i.wav", "audio/wav", 1, 1000,
      Hashing.sha256((if (i == 0) "A" else "B").byteInputStream()), 1, archivedAt = 10L + i) }
    val oldTranscripts = oldSessions.mapIndexed { i, s -> TranscriptEntity("raw-$i", s.id, TranscriptKind.RAW, "custom", "personal", text = "WordsFrom$i", wordCount = 1, createdAt = 2) }
    val oldSegments = oldParts.mapIndexed { i, part -> SegmentEntity(transcriptId = "raw-$i", partId = part.id, indexInPart = 0,
      partStartMs = 100, partEndMs = 800, sessionStartMs = 100, sessionEndMs = 800, text = "WordsFrom$i", wordsJson = "0,700,WordsFrom$i") }
    oldParts.forEachIndexed { i, part ->
      if (!recover || i != 0) File(audioDirectory, part.fileName).writeText(if (i == 0) "A" else "B")
    }
    val restoredName = if (recover) File(audioDirectory, "restored.wav").apply { writeText("A") }.name else oldParts.first().fileName
    val staged = if (recover) mapOf((true to oldParts[1].id) to restoredName) else emptyMap()
    coEvery { folders.get(any()) } returns folder; coEvery { notes.get(note.id) } returns note
    coEvery { folders.all() } returns listOf(folder)
    coEvery { parts.all() } returns oldParts
    coEvery { sources.all() } returns emptyList()
    coEvery { sessions.plainByNote(note.id) } returns oldSessions
    oldSessions.forEachIndexed { i, s ->
      coEvery { sessions.get(s.id) } returns s
      coEvery { parts.bySession(s.id) } returns listOf(oldParts[i])
      coEvery { transcripts.bySession(s.id) } returns listOf(oldTranscripts[i])
      coEvery { segments.byTranscript("raw-$i") } returns listOf(oldSegments[i])
    }
    oldParts.forEach { coEvery { parts.get(it.id) } returns it }
    oldTranscripts.forEach { coEvery { transcripts.get(it.id) } returns it }
    coEvery { jobs.activeForSession(any()) } returns null
    val oldJobs = oldSessions.mapIndexed { i, s -> JobEntity("job-$i", s.id, JobType.TRANSCRIBE, "custom", state = JobState.DONE, createdAt = 1, updatedAt = 2) }
    coEvery { jobs.all() } returns oldJobs
    val sql = mockk<SupportSQLiteDatabase>(relaxed = true); val helper = mockk<SupportSQLiteOpenHelper>()
    every { db.openHelper } returns helper; every { helper.writableDatabase } returns sql
    val cursor = mockk<Cursor>(relaxed = true); var row = -1
    every { sql.query("SELECT id, sessionId FROM transcription_runs") } returns cursor
    every { cursor.moveToNext() } answers { ++row < 2 }
    every { cursor.getString(0) } answers { "run-$row" }
    every { cursor.getString(1) } answers { oldSessions[row].id }
    val incoming = PampaBundleNote(note.copy(title = "Native", body = "Native body"),
      sessions = oldSessions.mapIndexed { i, s -> s.copy(title = oldSessions[1-i].title, activeTranscriptId = null) },
      parts = oldParts.mapIndexed { i, part -> part.copy(sha256 = oldParts[1-i].sha256) })
    PampaBundleImporter(db, files).persist(PampaBundleData(folders = listOf(folder), notes = listOf(incoming)), null, staged, publicLegacy = false)
    coVerify(exactly = 0) { sessions.delete(any()) }; coVerify(exactly = 0) { parts.delete(any()) }
    for (i in 0..1) {
      val destination = oldSessions[1-i].id
      coVerify { jobs.upsert(oldJobs[i].copy(sessionId = destination)) }
      verify(exactly = 1) { sql.execSQL("UPDATE transcription_runs SET sessionId = ? WHERE id = ?", arrayOf(destination, "run-$i")) }
      coVerify { transcripts.upsert(match { it.id == "raw-$i" && it.sessionId == destination && it.text == "WordsFrom$i" }) }
      coVerify { parts.upsert(match { it.id == oldParts[1-i].id && it.fileName == (if (i == 0) restoredName else oldParts[i].fileName) && it.sha256 == oldParts[i].sha256 && it.archivedAt == oldParts[i].archivedAt }) }
      coVerify { segments.insertAll(match { rows -> rows.any { it.transcriptId == "raw-$i" && it.partId == oldParts[1-i].id && it.wordsJson == "0,700,WordsFrom$i" } }) }
    }
    verify(exactly = 2) { sql.execSQL(any(), any<Array<Any>>()) }
  }
}
