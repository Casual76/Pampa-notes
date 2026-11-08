package dev.pampa.pampanotes.core.spending

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic decimal amounts only; no provider/billing claims. */
class ExactCloudAttemptLedgerRegressionTest {
  @get:Rule val temp = TemporaryFolder()
  private val now = System.currentTimeMillis()
  private fun charge(id: String) = CloudCharge(id, "same-job", "openrouter", "synthetic-key-hash", "fixture/model", 60_000,
    now, reservedUsd = "0.06", priceVerified = true)

  @Test fun `exact refusal releases only B while paid A and unknown C remain committed after restart`() {
    val file = File(temp.root, "ledger.json")
    val store = DurableCloudCostStore(file)
    store.update { it.copy(settings = it.settings.copy(budgetUsd = "0.09", perJobUsd = "0.09"),
      charges = listOf(charge("A"), charge("B"), charge("C"))) }
    store.settle("A", "0.01", now)
    store.refuse("B", now)
    store.refuse("B", now + 1) // Repeated/stale rejection is idempotent.
    val reopened = DurableCloudCostStore(file).read()
    assertEquals(listOf("A", "B", "C"), reopened.charges.map { it.id })
    assertEquals("0.01", reopened.charges[0].actualUsd)
    assertEquals("0", reopened.charges[1].actualUsd)
    assertNotNull(reopened.charges[1].refusedAt)
    assertNull(reopened.charges[2].actualUsd); assertNull(reopened.charges[2].settledAt)
    val totals = SpendingLedger.totals(reopened, now)
    assertEquals("0.01", totals.actualUsd); assertEquals("0.06", totals.reservedUsd)
    assertEquals("cloud_budget_exceeded", SpendingLedger.check(reopened, "same-job", CostEstimate("0.06", "0.06", PriceUnit.AUDIO_SECOND), now))
    // C is still genuinely uncertain, so no blanket same-job release can admit this retry.
    store.settle("C", "0", now, reconciled = true)
    assertNull(SpendingLedger.check(store.read(), "same-job", CostEstimate("0.06", "0.06", PriceUnit.AUDIO_SECOND), now))
  }

  @Test fun `reported actual cost wins in either callback order and explicit reconciliation is final`() {
    for (refusalFirst in listOf(false, true)) {
      val store = DurableCloudCostStore(File(temp.newFolder(), "ledger.json"))
      store.update { it.copy(charges = listOf(charge("B"))) }
      if (refusalFirst) { store.refuse("B", now); store.settle("B", "0.02", now) }
      else { store.settle("B", "0.02", now); store.refuse("B", now) }
      store.refuse("B", now + 1)
      assertEquals("0.02", store.read().charges.single().actualUsd)
      assertNull(store.read().charges.single().refusedAt)
    }
    val store = DurableCloudCostStore(File(temp.newFolder(), "ledger.json"))
    store.update { it.copy(charges = listOf(charge("B"))) }
    store.refuse("B", now)
    store.settle("B", "0.03", now, reconciled = true)
    store.settle("B", "0.07", now)
    store.refuse("B", now)
    assertEquals("0.03", store.read().charges.single().actualUsd)
    assertTrue(store.read().charges.single().reconciled)
  }
}
