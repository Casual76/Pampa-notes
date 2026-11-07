package dev.pampa.pampanotes.ui.cloudcost

import dev.pampa.pampanotes.core.spending.*
import org.junit.Assert.*
import org.junit.Test

class CloudPreflightSummaryTest {
  private fun item(cost: String?, reason: String? = null, provider: String = "openrouter") = CloudPreflight(
    provider, "model", 60_000, EffectiveCloudPolicy(true), CostEstimate(cost, cost), SpendingTotals("0", "0", 0, "0.10"), reason)

  @Test fun `selection warns when individually affordable jobs exceed the combined remaining budget`() {
    val summary = CloudPreflightUiState(items = listOf(item("0.08"), item("0.08")), loading = false)
    assertTrue(summary.items.all { it.blockedReason == null })
    assertEquals("0.16", summary.predictedMaxUsd)
    assertEquals("0.10", summary.availableUsd)
    assertTrue(summary.batchOverBudget)
    assertTrue(summary.blocked)
  }

  @Test fun `unknown selection prices stay unknown and companion jobs add no paid estimate`() {
    val summary = CloudPreflightUiState(items = listOf(item(null, "cloud_price_unknown"), item("0", provider = "custom")), loading = false)
    assertEquals(1, summary.cloudItems.size)
    assertNull(summary.predictedMaxUsd)
    assertFalse(summary.batchOverBudget)
    assertTrue(summary.blocked)
    assertFalse(CloudPreflightUiState(items = listOf(item(null)), loading = false).blocked)
  }
}
