package dev.pampa.pampanotes.ui.session

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.TranscriptEntity
import dev.pampa.pampanotes.core.db.TranscriptKind
import org.junit.Assert.*
import org.junit.Test

class SessionAudioAvailabilityTest {
  private val omitted = AudioPartEntity("p", "s", 0, "missing-audio.m4a", "Lecture", "audio/mp4", 10, 1000, "sha", 1)

  @Test fun `omitted recordings disable playback and transcription while real remote files remain fetchable`() {
    val copy = SessionUiState(parts = listOf(omitted), missing = listOf(omitted))
    assertFalse(copy.playable)
    assertFalse(copy.fetchable)
    assertFalse(copy.transcribableHere)
    val remote = omitted.copy(fileName = "uuid.m4a", archivedAt = 1)
    val sameAccount = copy.copy(parts = listOf(remote), missing = listOf(remote))
    assertFalse(sameAccount.playable)
    assertTrue(sameAccount.fetchable)
    assertTrue(sameAccount.transcribableHere)
    assertFalse(copy.copy(missing = null).transcribableHere)
    assertFalse(copy.copy(missing = emptyList()).playable)
  }

  @Test fun `state RAW follows selected author rather than retained newer local history`() {
    val author = TranscriptEntity("author", "s", TranscriptKind.RAW, "custom", "whisper", text = "Author", wordCount = 1, createdAt = 100)
    val local = author.copy(id = "local", text = "Local", createdAt = 200)
    val session = SessionEntity("s", "n", date = "2026-11-05", position = 0, activeTranscriptId = "author", createdAt = 1, updatedAt = 1)
    assertEquals(author, SessionUiState(session = session, transcripts = listOf(author, local)).raw)
  }
}
