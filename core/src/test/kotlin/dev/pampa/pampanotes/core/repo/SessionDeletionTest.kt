package dev.pampa.pampanotes.core.repo

import androidx.room.withTransaction
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.files.AppFiles
import io.mockk.*
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual repository at the post-cancellation boundary, with transactional DAO fixtures.
 * Room locking/FKs are not emulated or claimed by these JVM regressions. */
class SessionDeletionTest {
  @get:Rule val temp = TemporaryFolder()
  private val db = mockk<PampaDatabase>()
  private val folders = mockk<FolderDao>()
  private val notes = mockk<NoteDao>()
  private val sessions = mockk<SessionDao>()
  private val parts = mockk<AudioPartDao>()
  private val segments = mockk<SegmentDao>()
  private val files = mockk<AppFiles>()
  private val tags = mockk<NoteTagDao>()
  private val sources = mockk<SourceDao>()
  private val folderRows = mutableListOf(
    FolderEntity("personal", "Recordings", createdAt = 1, updatedAt = 2, kind = FolderEntity.KIND_PERSONAL),
    FolderEntity("other", "Other recordings", createdAt = 1, updatedAt = 2, kind = FolderEntity.KIND_PERSONAL),
    FolderEntity("school", "School", createdAt = 1, updatedAt = 2))
  private val material = NoteEntity("n", "personal", "Interview", "User's legacy body", language = "it", createdAt = 1, updatedAt = 2)
  private val schoolMaterial = material.copy(id = "school-note", folderId = "school", title = "Lesson")
  private val noteRows = mutableMapOf(material.id to material, schoolMaterial.id to schoolMaterial)
  private fun session(id: String, noteId: String, position: Int) = SessionEntity(id, noteId,
    date = "2026-11-01", position = position, createdAt = 1, updatedAt = 2)
  private val sessionRows = listOf(session("s", "n", 0), session("sibling", "n", 1), session("school-session", "school-note", 0)).associateBy { it.id }.toMutableMap()
  private val partRows = sessionRows.keys.map { id -> AudioPartEntity("part-$id", id, 0,
    "$id.wav", "$id.wav", "audio/wav", 7, 1000, "a".repeat(64), 1) }.associateBy { it.id }.toMutableMap()
  private var inside = false
  private var committed = false
  private var transactionCount = 0
  private val deletedNames = mutableListOf<String>()
  private var failTouch = false
  private lateinit var repository: SessionRepository

  @Before fun seed() {
    mockkStatic("androidx.room.RoomDatabaseKt")
    coEvery { db.withTransaction(any<suspend () -> Any?>()) } coAnswers {
      val beforeSessions = sessionRows.toMap(); val beforeParts = partRows.toMap(); val beforeNotes = noteRows.toMap()
      assertFalse(inside); inside = true; transactionCount++
      try {
        val value = secondArg<suspend () -> Any?>().invoke()
        committed = true
        value
      } catch (error: Throwable) {
        sessionRows.clear(); sessionRows.putAll(beforeSessions)
        partRows.clear(); partRows.putAll(beforeParts)
        noteRows.clear(); noteRows.putAll(beforeNotes)
        throw error
      } finally { inside = false }
    }
    every { db.folders() } returns folders
    coEvery { folders.all() } answers { assertTrue(inside); folderRows.toList() }
    coEvery { notes.get(any()) } answers { assertTrue(inside); noteRows[firstArg()] }
    coEvery { notes.touch(any(), any()) } answers {
      assertTrue(inside)
      if (failTouch) error("injected DB failure")
      val id = firstArg<String>(); noteRows[id]?.let { noteRows[id] = it.copy(updatedAt = secondArg()) }; Unit
    }
    coEvery { sessions.getAll(any()) } answers { assertTrue(inside); firstArg<List<String>>().mapNotNull { sessionRows[it] } }
    coEvery { sessions.get(any()) } answers { assertTrue(inside); sessionRows[firstArg()] }
    coEvery { sessions.plainByNote(any()) } answers { sessionRows.values.filter { it.noteId == firstArg<String>() }.sortedBy { it.position } }
    coEvery { sessions.setPosition(any(), any(), any()) } answers {
      assertTrue(inside)
      val id = firstArg<String>(); sessionRows.getValue(id).let { sessionRows[id] = it.copy(position = secondArg(), updatedAt = thirdArg()) }; Unit
    }
    coEvery { sessions.delete(any()) } answers {
      assertTrue(inside)
      val id = firstArg<String>(); sessionRows.remove(id)
      partRows.entries.removeAll { it.value.sessionId == id }; Unit
    }
    coEvery { parts.bySession(any()) } answers { partRows.values.filter { it.sessionId == firstArg<String>() } }
    coEvery { parts.fileNames() } answers { assertTrue(inside); partRows.values.map { it.fileName } }
    coEvery { segments.deleteByPart(any()) } answers { assertTrue(inside); Unit }
    partRows.values.forEach { File(temp.root, it.fileName).writeText("synthetic fixture") }
    temp.newFile("source.txt").writeText("Original associated material")
    every { files.audio } returns temp.root
    every { files.audioFile(any()) } answers {
      // Resolving a path is read-only. Physical deletion alone requires a committed transaction.
      object : File(temp.root, firstArg<String>()) {
        override fun delete(): Boolean {
          assertFalse(inside); assertTrue("files only after successful commit", committed)
          deletedNames += path
          return super.delete()
        }
      }
    }
    repository = SessionRepository(sessions, parts, mockk(), segments, notes, files, db)
  }

  @After fun unmockTransaction() = unmockkStatic("androidx.room.RoomDatabaseKt")

  private fun denied() {
    assertEquals(setOf("s", "sibling", "school-session"), sessionRows.keys)
    assertTrue(File(temp.root, "s.wav").isFile)
    verify(exactly = 0) { files.audioFile(any()) }
    coVerify(exactly = 0) { sessions.delete(any()) }
    coVerify(exactly = 0) { notes.touch(any(), any()) }
    assertEquals(1, transactionCount)
  }

  @Test fun `note moved to school after selection is fenced before DB and file deletion`() = runTest {
    noteRows["n"] = material.copy(folderId = "school")
    repository.deletePersonalSessions(setOf("s"), "personal")
    denied()
    assertEquals(material.copy(folderId = "school"), noteRows["n"])
  }

  @Test fun `current folder change within personal is denied for a folder selection`() = runTest {
    noteRows["n"] = material.copy(folderId = "other")
    repository.deletePersonalSessions(setOf("s"), "personal")
    denied()
  }

  @Test fun `changing root kind or moving folder below school invalidates cached personal membership`() = runTest {
    for (rootChange in listOf(true, false)) {
      folderRows[0] = folderRows[0].copy(parentId = null, kind = FolderEntity.KIND_PERSONAL); transactionCount = 0
      folderRows[0] = if (rootChange) folderRows[0].copy(kind = FolderEntity.KIND_SCHOOL) else folderRows[0].copy(parentId = "school")
      repository.deletePersonalSessions(setOf("s"), null)
      denied()
    }
  }

  @Test fun `session reparented to school after selection is denied`() = runTest {
    sessionRows["s"] = sessionRows.getValue("s").copy(noteId = "school-note")
    repository.deletePersonalSessions(setOf("s"), null)
    denied()
  }

  @Test fun `missing session note or folder never triggers stale cleanup`() = runTest {
    for (missing in listOf("session", "note", "folder")) {
      sessionRows["s"] = session("s", "n", 0); noteRows["n"] = material
      folderRows.removeAll { it.id == "personal" }
      folderRows.add(0, FolderEntity("personal", "Recordings", createdAt = 1, updatedAt = 2, kind = FolderEntity.KIND_PERSONAL))
      transactionCount = 0
      when (missing) {
        "session" -> sessionRows.remove("s")
        "note" -> noteRows.remove("n")
        else -> folderRows.removeAll { row -> row.id == "personal" }
      }
      repository.deletePersonalSessions(setOf("s"), null)
      assertTrue(File(temp.root, "s.wav").isFile)
      verify(exactly = 0) { files.audioFile(any()) }
      coVerify(exactly = 0) { sessions.delete(any()) }
      assertEquals(1, transactionCount)
    }
  }

  @Test fun `commit deletes exact authorized sessions only and retains note material school and sibling files`() = runTest {
    val schoolBefore = sessionRows.getValue("school-session")
    repository.deletePersonalSessions(setOf("s", "school-session", "missing"), null)
    assertEquals(setOf("sibling", "school-session"), sessionRows.keys)
    assertEquals(schoolBefore, sessionRows["school-session"])
    assertEquals(material, noteRows.getValue("n").copy(updatedAt = material.updatedAt))
    assertEquals(schoolMaterial, noteRows["school-note"])
    assertFalse(File(temp.root, "s.wav").exists())
    assertTrue(File(temp.root, "sibling.wav").isFile)
    assertTrue(File(temp.root, "school-session.wav").isFile)
    assertTrue(File(temp.root, "source.txt").isFile)
    verify { listOf(tags, sources) wasNot Called }
    assertEquals(1, transactionCount)
  }

  @Test fun `rollback of destructive writes never removes physical files or sibling material`() = runTest {
    val beforeSessions = sessionRows.toMap(); val beforeParts = partRows.toMap(); val beforeNotes = noteRows.toMap()
    failTouch = true
    try { repository.deletePersonalSessions(setOf("s", "sibling"), null); fail("Expected rollback") } catch (_: IllegalStateException) { }
    assertEquals(beforeSessions, sessionRows); assertEquals(beforeParts, partRows); assertEquals(beforeNotes, noteRows)
    assertTrue(File(temp.root, "s.wav").isFile); assertTrue(File(temp.root, "sibling.wav").isFile)
    verify(exactly = 0) { files.audioFile(any()) }
    assertFalse(committed)
  }

  @Test fun `shared sibling audio path is retained after a committed deletion`() = runTest {
    partRows["part-sibling"] = partRows.getValue("part-sibling").copy(fileName = "s.wav")
    repository.deletePersonalSessions(setOf("s"), null)
    assertTrue(File(temp.root, "s.wav").isFile)
    assertTrue("sibling" in sessionRows)
    assertTrue(deletedNames.isEmpty())
  }

  @Test fun `generic school delete remains valid with committed file cleanup`() = runTest {
    repository.deleteSession("school-session")
    assertEquals(setOf("s", "sibling"), sessionRows.keys)
    assertFalse(File(temp.root, "school-session.wav").exists())
    assertTrue(File(temp.root, "s.wav").isFile)
    assertEquals(schoolMaterial, noteRows.getValue("school-note").copy(updatedAt = schoolMaterial.updatedAt))
  }

  @Test fun `equivalent audio paths preserve sibling bytes for personal and generic deletion`() = runTest {
    partRows["part-sibling"] = partRows.getValue("part-sibling").copy(fileName = "./s.wav")
    repository.deletePersonalSessions(setOf("s"), null)
    assertTrue(File(temp.root, "s.wav").isFile)
    assertTrue("sibling" in sessionRows)
    assertEquals("./s.wav", partRows.getValue("part-sibling").fileName)
    partRows["part-sibling"] = partRows.getValue("part-sibling").copy(fileName = "./school-session.wav")
    repository.deleteSession("school-session")
    assertTrue(File(temp.root, "school-session.wav").isFile)
    assertTrue("sibling" in sessionRows)
    assertTrue(deletedNames.isEmpty())
  }

  @Test fun `a stored path outside audio root cannot delete other private material`() = runTest {
    val audioRoot = temp.newFolder("bounded-audio")
    val kept = temp.newFile("keep-private.wav").apply { writeText("private original") }
    every { files.audio } returns audioRoot
    every { files.audioFile(any()) } answers { File(audioRoot, firstArg<String>()) }
    partRows["part-s"] = partRows.getValue("part-s").copy(fileName = "../keep-private.wav")
    repository.deletePersonalSessions(setOf("s"), null)
    assertFalse("s" in sessionRows)
    assertTrue(kept.isFile)
    assertEquals("private original", kept.readText())
  }
}
