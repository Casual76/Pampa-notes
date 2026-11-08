package dev.pampa.pampanotes.ui.recordings

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.repo.*
import dev.pampa.pampanotes.work.AlignmentScheduler
import io.mockk.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RecordingActionsDeleteTest {
  @Test fun `delete awaits each cancellation then uses the final core scope fence with exact IDs`() = runTest {
    for (phase in listOf("alignment", "observe", "cancel")) {
      val notes = mockk<NoteDao>(); val sessions = mockk<SessionDao>(); val folders = mockk<FolderRepository>()
      val repository = mockk<SessionRepository>(); val transcription = mockk<TranscriptionRepository>()
      val alignment = mockk<AlignmentScheduler>()
      val personal = FolderEntity("personal", "Recordings", createdAt = 1, updatedAt = 2, kind = FolderEntity.KIND_PERSONAL)
      val school = personal.copy(id = "school", kind = FolderEntity.KIND_SCHOOL)
      var note = NoteEntity("n", "personal", "Recording", createdAt = 1, updatedAt = 2)
      val s = SessionEntity("s", "n", date = "2026-11-01", position = 0, createdAt = 1, updatedAt = 2)
      val job = JobEntity("j", "s", JobType.TRANSCRIBE, "custom", createdAt = 1, updatedAt = 2)
      val events = mutableListOf<String>()
      fun interleave(where: String) { events += where; if (where == phase) note = note.copy(folderId = "school") }
      coEvery { folders.all() } returns listOf(personal, school)
      coEvery { sessions.getAll(any()) } returns listOf(s, s.copy(id = "school-session", noteId = "school-note"))
      coEvery { notes.get("n") } answers { note }
      coEvery { notes.get("school-note") } returns note.copy(id = "school-note", folderId = "school")
      coEvery { alignment.cancelSession("s") } coAnswers { interleave("alignment"); Unit }
      every { transcription.observeActive() } returns flow { interleave("observe"); emit(listOf(job, job.copy(id = "other", sessionId = "school-session"))) }
      coEvery { transcription.requestCancel("j") } coAnswers { interleave("cancel"); Unit }
      coEvery { repository.deletePersonalSessions(setOf("s"), "personal") } coAnswers {
        assertEquals("school", note.folderId)
        assertEquals(listOf("alignment", "observe", "cancel"), events)
        events += "fence"; Unit
      }
      RecordingActions(mockk(), notes, sessions, mockk(), mockk(), folders, repository, transcription,
        alignment, mockk(), mockk()).delete(setOf("s", "school-session"), "personal")
      assertEquals(listOf("alignment", "observe", "cancel", "fence"), events)
      coVerify(exactly = 1) { repository.deletePersonalSessions(setOf("s"), "personal") }
      coVerify(exactly = 0) { repository.deleteSession(any()) }
      coVerify(exactly = 0) { alignment.cancelSession("school-session") }
      coVerify(exactly = 0) { transcription.requestCancel("other") }
    }
  }
}
