package dev.pampa.pampanotes.ui.session

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.importing.PampaBundleOmissions
import org.junit.Assert.*
import org.junit.Test

class SessionReceivedOmissionAvailabilityTest {
  private val part = AudioPartEntity("omitted", "session", 0, PampaBundleOmissions.expectedFileName("omitted"),
    "Recording.m4a", "audio/mp4", 3, 1000, "a".repeat(64), 1)

  @Test fun receivedBytesEnablePlaybackAndTranscriptionWithoutChangingTheRow() {
    val waiting = SessionUiState(parts = listOf(part), missing = listOf(part))
    assertFalse(waiting.playable)
    assertFalse(waiting.transcribableHere)
    val received = waiting.copy(missing = emptyList())
    assertTrue(received.playable)
    assertTrue(received.transcribableHere)
    assertFalse(received.fetchable)
    assertEquals(waiting.parts, received.parts)
    assertEquals(0L, received.parts.single().archivedAt)
    assertFalse(PampaBundleOmissions.isOmitted(part, localAvailable = true))
  }

  @Test fun liveRelayRecoversOmissionsAndExpiryDoesNotMasqueradeAsReceipt() {
    val waiting = SessionUiState(parts = listOf(part), missing = listOf(part))
    val ready = waiting.copy(relayParts = setOf(part.id))
    assertTrue(ready.fetchable)
    assertTrue(ready.transcribableHere)
    assertFalse(ready.playable)
    assertTrue(PampaBundleOmissions.omitted(ready.missing!!, ready.relayParts).isEmpty())
    assertEquals(listOf(part), PampaBundleOmissions.remote(ready.missing, ready.relayParts))
    assertFalse(ready.copy(relayParts = emptySet()).transcribableHere)
    assertFalse(ready.copy(missing = null).playable)
    assertFalse(ready.copy(missing = null).transcribableHere)
  }

  @Test fun oneReceiptDoesNotHideAnotherMissingOmission() {
    val other = part.copy(id = "other", fileName = PampaBundleOmissions.expectedFileName("other"))
    val partial = SessionUiState(parts = listOf(part, other), missing = listOf(other))
    assertFalse(partial.playable)
    assertFalse(partial.transcribableHere)
    assertEquals(listOf(other), PampaBundleOmissions.omitted(partial.missing!!))
    assertTrue(partial.copy(relayParts = setOf(other.id)).fetchable)
    assertTrue(partial.copy(missing = emptyList()).playable)
  }
}
