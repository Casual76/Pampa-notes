package dev.pampa.pampanotes.ui.cloudrelay

import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.relay.*
import org.junit.Assert.*
import org.junit.Test

class RelayPresentationTest {
  @Test fun queueAndReadyNeverClaimReceiverSuccess() {
    assertEquals(R.string.relay_queued, relayMessage(RelayResult(RelayState.QUEUED)))
    assertEquals(R.string.relay_ready, relayMessage(RelayResult(RelayState.READY)))
    assertEquals(R.string.relay_received, relayMessage(RelayResult(RelayState.RECEIVED)))
    assertNotEquals(relayMessage(RelayResult(RelayState.QUEUED)), relayMessage(RelayResult(RelayState.RECEIVED)))
  }

  @Test fun mixedBatchKeepsFailuresAndActionableProtocolErrors() {
    val report = RelayReport(listOf(RelayResult(RelayState.READY), RelayResult(RelayState.QUOTA), RelayResult(RelayState.EXPIRED)))
    assertEquals(2, report.results.count { relayIsProblem(it.state) })
    assertTrue(relayCanResend(RelayState.QUOTA))
    assertTrue(relayCanResend(RelayState.EXPIRED))
    assertFalse(relayCanResend(RelayState.UPLOADING))
    assertEquals(R.string.relay_protocol, relayMessage(RelayResult(RelayState.FAILED, code = "invalid_response")))
    assertEquals(R.string.relay_auth, relayMessage(RelayResult(RelayState.AUTH_REQUIRED)))
    assertEquals(R.string.relay_unmatched, relayMessage(RelayResult(RelayState.UNMATCHED)))
  }

  @Test fun availabilityStopsAtTheServerDerivedDeadline() {
    val available = RelayFileAvailability(mapOf("first" to 150L, "second" to 200L))
    assertEquals(setOf("first", "second"), available.live(now = 149))
    assertEquals(setOf("second"), available.live(now = 150))
    assertTrue(available.live(now = 200).isEmpty())
  }
}
