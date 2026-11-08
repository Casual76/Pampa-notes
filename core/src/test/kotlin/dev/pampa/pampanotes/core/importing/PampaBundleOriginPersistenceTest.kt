package dev.pampa.pampanotes.core.importing

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.Hashing
import io.mockk.*
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Executes importer normalization AND graph persistence. Mock DAOs model writes, not Room/FKs. */
class PampaBundleOriginPersistenceTest {
  @get:Rule val temp = TemporaryFolder()
  private val note = NoteEntity("n", "f", "Recording", createdAt = 1, updatedAt = 2)
  private val session = SessionEntity("s", "n", date = "2026-11-01", position = 0, createdAt = 1, updatedAt = 2)
  private val audioA = "audio-a"
  private val audioB = "audio-b"
  private val unknown = AudioPartEntity("p", "s", 0, "p.wav", "Original", "audio/wav", 7, 1000, Hashing.sha256(audioA), 1, archivedAt = 20)
  private val exact = unknown.copy(recordedAtEpochMs = 1793523600000, recordedAtLocal = "2026-11-01T10:00",
    recordedAtZoneId = "+01:00", recordedAtSource = "media_metadata", recordedAtPrecision = "second")

  private suspend fun persist(old: AudioPartEntity, incoming: AudioPartEntity, staged: Boolean = false): AudioPartEntity {
    return persistAll(listOf(old), listOf(incoming), staged).getValue(incoming.id)
  }

  private suspend fun persistAll(oldParts: List<AudioPartEntity>, incomingParts: List<AudioPartEntity>, staged: Boolean = false): Map<String, AudioPartEntity> {
    val db = mockk<PampaDatabase>()
    val folders = mockk<FolderDao>(relaxed = true); val notes = mockk<NoteDao>(relaxed = true)
    val sessions = mockk<SessionDao>(); val parts = mockk<AudioPartDao>()
    val transcripts = mockk<TranscriptDao>(relaxed = true); val segments = mockk<SegmentDao>(relaxed = true)
    val sources = mockk<SourceDao>(relaxed = true); val tags = mockk<NoteTagDao>(relaxed = true)
    val jobs = mockk<JobDao>(relaxed = true); val files = mockk<AppFiles>()
    every { db.folders() } returns folders; every { db.notes() } returns notes; every { db.sessions() } returns sessions
    every { db.audioParts() } returns parts; every { db.transcripts() } returns transcripts; every { db.segments() } returns segments
    every { db.sources() } returns sources; every { db.tags() } returns tags; every { db.jobs() } returns jobs
    val sessionRows = oldParts.map { it.sessionId }.distinct().mapIndexed { i, id -> session.copy(id = id, position = i) }.associateBy { it.id }.toMutableMap()
    val partRows = oldParts.associateBy { it.id }.toMutableMap()
    coEvery { notes.get(note.id) } returns note
    coEvery { sessions.plainByNote(note.id) } answers { sessionRows.values.toList() }
    coEvery { sessions.get(any()) } answers { sessionRows[firstArg()] }
    coEvery { sessions.upsert(any<SessionEntity>()) } answers { firstArg<SessionEntity>().let { sessionRows[it.id] = it }; Unit }
    coEvery { sessions.delete(any()) } answers { sessionRows.remove(firstArg()); Unit }
    coEvery { parts.bySession(any()) } answers { partRows.values.filter { it.sessionId == firstArg<String>() } }
    coEvery { parts.all() } answers { partRows.values.toList() }
    coEvery { parts.get(any()) } answers { partRows[firstArg()] }
    coEvery { parts.upsert(any<AudioPartEntity>()) } answers { firstArg<AudioPartEntity>().let { partRows[it.id] = it }; Unit }
    coEvery { parts.delete(any()) } answers { partRows.remove(firstArg()); Unit }
    coEvery { jobs.activeForSession(any()) } returns null
    val helper = mockk<SupportSQLiteOpenHelper>(); val sql = mockk<SupportSQLiteDatabase>(relaxed = true)
    val cursor = mockk<Cursor>(relaxed = true)
    every { db.openHelper } returns helper; every { helper.writableDatabase } returns sql
    every { sql.query("SELECT id, sessionId FROM transcription_runs") } returns cursor
    every { cursor.moveToNext() } returns false
    every { files.audioFile(any()) } answers { File(temp.root, firstArg<String>()) }
    every { files.audio } returns temp.root
    fun bytes(part: AudioPartEntity) = when (part.sha256) {
      Hashing.sha256(audioA) -> audioA
      Hashing.sha256(audioB) -> audioB
      else -> error("Missing exact fixture bytes")
    }
    oldParts.forEach { old ->
      if (!staged) File(temp.root, old.fileName).writeText(bytes(old))
      else File(temp.root, old.fileName).delete()
    }
    val stored = if (staged) incomingParts.associate { (true to it.id) to "staged.wav" } else emptyMap()
    if (staged) {
      require(incomingParts.size == 1) { "Staging fixtures need independent names per entity" }
      File(temp.root, "staged.wav").writeText(bytes(incomingParts.single()))
    }
    val incomingSessions = incomingParts.map { it.sessionId }.distinct().mapIndexed { i, id -> session.copy(id = id, position = i) }
    val graph = PampaBundleNote(note, sessions = incomingSessions, parts = incomingParts)
    PampaBundleImporter(db, files).persist(PampaBundleData(folders = emptyList(), notes = listOf(graph)), null, stored, publicLegacy = false)
    return partRows
  }

  @Test fun `same audio native update keeps all five fields before DAO upsert with and without staged recovery`() = runTest {
    for (staged in listOf(false, true)) {
      val saved = persist(exact, unknown, staged)
      assertEquals(exact.copy(fileName = if (staged) "staged.wav" else exact.fileName), saved)
    }
  }

  @Test fun `native same audio precision policy matches pure merge without mixing fields`() = runTest {
    val day = unknown.copy(recordedAtLocal = "2026-11-01", recordedAtSource = "filename", recordedAtPrecision = "day")
    val partial = unknown.copy(recordedAtEpochMs = exact.recordedAtEpochMs)
    for (incoming in listOf(day, partial)) assertEquals(exact, persist(exact, incoming))
    assertEquals(exact, persist(day, exact))
  }

  @Test fun `changed staged audio never inherits old recording origin`() = runTest {
    val changed = unknown.copy(sha256 = Hashing.sha256(audioB))
    val saved = persist(exact, changed, staged = true)
    assertEquals(changed.copy(fileName = "staged.wav", archivedAt = 0), saved)
    assertNull(saved.recordedAtEpochMs)
  }

  @Test fun `production legacy alias resolves local evidence at its final ID before same audio normalization`() = runTest {
    val old = exact.copy(id = PampaBundleLegacyReconcile.id("part", "p"),
      sessionId = PampaBundleLegacyReconcile.id("session", "s"))
    assertEquals(exact, persist(old, unknown))
  }

  @Test fun `simultaneous legacy ID swap keeps each audio origin with its hash rather than previous ID occupant`() = runTest {
    val first = exact.copy(id = PampaBundleLegacyReconcile.id("part", "first"),
      sessionId = PampaBundleLegacyReconcile.id("session", "first"))
    val second = exact.copy(id = PampaBundleLegacyReconcile.id("part", "second"),
      sessionId = PampaBundleLegacyReconcile.id("session", "second"), fileName = "second.wav", sha256 = Hashing.sha256(audioB),
      recordedAtEpochMs = exact.recordedAtEpochMs!! + 86_400_000, recordedAtLocal = "2026-11-02T10:00", recordedAtSource = "second_recorder")
    val incoming = listOf(unknown.copy(id = first.id, sessionId = first.sessionId, sha256 = second.sha256),
      unknown.copy(id = second.id, sessionId = second.sessionId, sha256 = first.sha256))
    val rows = persistAll(listOf(first, second), incoming)
    assertEquals(first.copy(id = second.id, sessionId = second.sessionId), rows[second.id])
    assertEquals(second.copy(id = first.id, sessionId = first.sessionId), rows[first.id])
  }
}
