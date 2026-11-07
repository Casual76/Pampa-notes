package dev.pampa.pampanotes.ui.cloudrelay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.*
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.relay.RelayResult
import dev.pampa.pampanotes.core.relay.RelayState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Measure the production panel in the real FullScreen host, including its scroll contract. */
class CloudRelayPanelTest {
  @get:Rule val compose = createComposeRule()
  private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

  @Test fun longNoteTransferPanelHasOneScrollOwnerAndCanScrollResendAndClose() {
    var sent = 0
    var resent: String? = null
    val state = CloudRelayUiState(configured = true, jobs = List(20) { index ->
      RelayJobUi(RelayResult(if (index == 19) RelayState.EXPIRED else RelayState.RECEIVED,
        jobId = "job-$index"), "Recording $index", "note-$index")
    })
    val actions = CloudRelayActions(send = { sent++ }, receive = {}, setAutomaticReceive = {},
      resume = {}, cancel = {}, resend = { resent = it })
    compose.setContent {
      FluidTheme(EngineSettings()) {
        val host = rememberFluidGlassModalHostState()
        var open by remember { mutableStateOf(true) }
        CompositionLocalProvider(LocalFluidGlassModalHostState provides host,
          LocalFluidLabels provides FluidLabels.english()) {
          Box(Modifier.fillMaxSize()) {
            if (open) CloudRelayPanel(state, actions, onDismiss = { open = false })
            FluidGlassModalHost(state = host, backdrop = null)
          }
        }
      }
    }

    compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
    compose.onNodeWithText(text(R.string.relay_send_note)).performClick()
    compose.runOnIdle { assertEquals(1, sent) }
    compose.onNodeWithText("Recording 19").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText(text(R.string.relay_resend_note)).performScrollTo().performClick()
    compose.runOnIdle { assertEquals("note-19", resent) }
    compose.onNodeWithText(text(R.string.relay_limits)).performScrollTo().assertIsDisplayed()
    compose.onNodeWithContentDescription("Close").performClick()
    compose.onNodeWithContentDescription("Close").assertDoesNotExist()
  }

  @Test fun accountAndBusyStatesKeepActionsDisabledAndCancellationAvailable() {
    val state = mutableStateOf(CloudRelayUiState(results = listOf(RelayResult(RelayState.AUTH_REQUIRED))))
    var cancelled = 0
    val actions = CloudRelayActions(send = {}, receive = {}, setAutomaticReceive = {},
      resume = {}, cancel = { cancelled++ }, resend = {})
    compose.setContent {
      FluidTheme(EngineSettings()) {
        val host = rememberFluidGlassModalHostState()
        CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
          Box(Modifier.fillMaxSize()) {
            CloudRelayPanel(state.value, actions, onDismiss = {})
            FluidGlassModalHost(state = host, backdrop = null)
          }
        }
      }
    }

    compose.onAllNodesWithText(text(R.string.relay_auth)).assertCountEquals(1)
    compose.onNodeWithText(text(R.string.relay_send_note)).assertIsNotEnabled()
    compose.onNodeWithText(text(R.string.relay_receive)).assertIsNotEnabled()
    compose.onNodeWithText(text(R.string.relay_auto)).assertDoesNotExist()
    compose.runOnIdle { state.value = state.value.copy(configured = true, working = true) }
    compose.onNodeWithText(text(R.string.relay_send_note)).assertIsNotEnabled()
    compose.onNodeWithText(text(R.string.relay_receive)).assertIsNotEnabled()
    compose.onNodeWithText(text(R.string.relay_cancel)).performScrollTo().performClick()
    compose.runOnIdle { assertEquals(1, cancelled) }
    compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
  }
}
