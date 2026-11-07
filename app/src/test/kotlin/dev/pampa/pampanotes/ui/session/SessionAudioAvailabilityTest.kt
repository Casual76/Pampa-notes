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

  @Test fun `live relay makes an unarchived existing row fetchable without treating it as locally playable`() {
    val remote = omitted.copy(fileName = "uuid.m4a", archivedAt = 0)
    val waiting = SessionUiState(parts = listOf(remote), missing = listOf(remote))
    assertFalse(waiting.fetchable)
    assertFalse(waiting.transcribableHere)
    val ready = waiting.copy(relayParts = setOf(remote.id))
    assertTrue(ready.fetchable)
    assertTrue(ready.transcribableHere)
    assertFalse(ready.playable)
    assertEquals(0L, remote.archivedAt)
    assertFalse(ready.copy(relayParts = emptySet()).fetchable)
    assertTrue(ready.copy(missing = emptyList()).playable)
  }

  @Test fun `every missing part must be obtainable and unknown disk state cannot transcribe`() {
    val first = omitted.copy(fileName = "a.m4a", id = "a", archivedAt = 1)
    val second = first.copy(id = "b", fileName = "b.m4a", archivedAt = 0)
    val partial = SessionUiState(parts = listOf(first, second), missing = listOf(first, second))
    assertFalse(partial.fetchable)
    assertTrue(partial.copy(relayParts = setOf(second.id)).fetchable)
    assertFalse(partial.copy(missing = null).transcribableHere)
    assertFalse(SessionUiState(parts = listOf(omitted), missing = listOf(omitted), relayParts = setOf(omitted.id)).transcribableHere)
  }
}
