package dev.pampa.pampanotes.core.spending

import dev.pampa.pampanotes.core.transcription.TranscriptionError
import java.io.File
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpendingLedgerTest {
  @get:Rule val temp = TemporaryFolder()
  private val now = Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
  private fun charge(id: String, job: String = "job", key: String = "first-key", at: Long = now) = CloudCharge(id, job, "openrouter", key, "openai/whisper-1", 60_000, at)
  private fun estimate(cost: String) = CostEstimate(cost, cost, PriceUnit.AUDIO_SECOND)
  private fun store(cap: String = "1"): DurableCloudCostStore = DurableCloudCostStore(File(temp.newFolder(), "costs.json")).apply {
    update { it.copy(settings = CloudCostSettings(budgetUsd = cap, zoneId = "Europe/Rome")) }
  }
  private fun blocked(block: () -> Unit): String? = (runCatching(block).exceptionOrNull() as? TranscriptionError.CloudControl)?.reason

  @Test fun `exact boundary reserves and the next micro unit is refused`() {
    val s = store("0.300000")
    s.reserve(charge("a"), estimate("0.1")); s.reserve(charge("b"), estimate("0.2"))
    assertEquals("cloud_budget_exceeded", blocked { s.reserve(charge("c"), estimate("0.000001")) })
    assertEquals(2, s.read().charges.size)
    assertEquals(0, java.math.BigDecimal(SpendingLedger.totals(s.read(), now).availableUsd).compareTo(java.math.BigDecimal.ZERO))
  }

  @Test fun `comma budgets are normalized before persistence and cannot disappear from checks`() {
    val s = store()
    s.update { it.copy(settings = it.settings.copy(budgetUsd = "0,2", perJobUsd = "0,3")) }
    assertEquals("0.2", s.read().settings.budgetUsd)
    assertEquals("0.3", s.read().settings.perJobUsd)
    assertEquals("cloud_budget_exceeded", blocked { s.reserve(charge("a"), estimate("0.200001")) })
  }

  @Test fun `parallel providers stores and keys share a single serialized budget`() {
    val file = File(temp.newFolder(), "costs.json")
    val one = DurableCloudCostStore(file)
    one.update { it.copy(settings = CloudCostSettings(budgetUsd = "1")) }
    val pool = Executors.newFixedThreadPool(8)
    try {
      val success = pool.invokeAll((1..30).map { index -> Callable {
        runCatching { DurableCloudCostStore(file).reserve(charge("$index", key = "key-$index"), estimate("0.1")) }.isSuccess
      } }).count { it.get() }
      assertEquals(10, success)
      assertEquals(10, one.read().charges.size)
    } finally { pool.shutdownNow() }
  }

  @Test fun `actual settles once before transcript save and keeps original response precision`() {
    val s = store()
    s.reserve(charge("a"), estimate("0.4"))
    s.settle("a", "0.123456789012", now)
    s.settle("a", "0.8", now + 1) // Duplicate callback/recovery is not a second charge.
    assertEquals("0.123456789012", s.read().charges.single().actualUsd)
    assertEquals("0.123456789012", SpendingLedger.totals(s.read(), now).actualUsd)
    assertEquals("0", SpendingLedger.totals(s.read(), now).reservedUsd)
  }

  @Test fun `timeout cancel crash and retry retain all attempted requests without refund`() {
    val dir = temp.newFolder()
    val file = File(dir, "costs.json")
    var s = DurableCloudCostStore(file)
    s.update { it.copy(settings = CloudCostSettings(budgetUsd = "1", perJobUsd = "0.6")) }
    s.reserve(charge("timed-out"), estimate("0.2"))
    s = DurableCloudCostStore(file) // Process died before a result could be persisted.
    s.reserve(charge("cancelled-retry"), estimate("0.2"))
    s.reserve(charge("third-attempt"), estimate("0.2"))
    assertEquals("cloud_job_limit", blocked { s.reserve(charge("fourth-attempt"), estimate("0.2")) })
    assertEquals("0.6", SpendingLedger.totals(s.read(), now).reservedUsd)
  }

  @Test fun `unknown historical and user estimated reservations prevent later cap bypass`() {
    val s = DurableCloudCostStore(File(temp.newFolder(), "costs.json"))
    s.reserve(charge("unknown"), CostEstimate())
    s.update { it.copy(settings = it.settings.copy(budgetUsd = "10")) }
    assertEquals("cloud_charge_unresolved", blocked { s.reserve(charge("next"), estimate("0.01")) })
    s.settle("unknown", "0.3", now, reconciled = true)
    s.reserve(charge("next"), estimate("0.01"))
    assertTrue(s.read().charges.first().reconciled)
  }

  @Test fun `a key change does not reset installation spending or mingle its quota`() {
    val s = store("0.5")
    s.reserve(charge("a", key = "old"), estimate("0.3")); s.settle("a", "0.3", now)
    assertEquals("cloud_budget_exceeded", blocked { s.reserve(charge("b", key = "new"), estimate("0.3")) })
    s.update { it.copy(quotas = mapOf("old" to KeyQuota("old", now, remaining = "5"), "new" to KeyQuota("new", now, remaining = "1"))) }
    assertEquals("5", s.read().quotas["old"]?.remaining)
    assertEquals("1", s.read().quotas["new"]?.remaining)
  }

  @Test fun `local period and confirmed reset never discard unsettled previous period charges`() {
    val s = store("1")
    s.reserve(charge("pending"), estimate("0.4")); s.reserve(charge("done"), estimate("0.2")); s.settle("done", "0.2", now)
    val december = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli()
    assertEquals("0", SpendingLedger.totals(s.read(), december).actualUsd)
    assertEquals("0.4", SpendingLedger.totals(s.read(), december).reservedUsd)
    s.update { it.copy(settings = it.settings.copy(resetAt = now + 1)) }
    assertEquals("0", SpendingLedger.totals(s.read(), now + 1).actualUsd)
    assertEquals("0.4", SpendingLedger.totals(s.read(), now + 1).reservedUsd)
    assertEquals(2, s.read().charges.size)
  }

  @Test fun `daily period follows configured timezone and daylight saving boundaries`() {
    val settings = CloudCostSettings(period = BudgetPeriod.DAILY, zoneId = "Europe/Rome")
    val at = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
    assertEquals(Instant.parse("2026-10-24T22:00:00Z").toEpochMilli(), settings.periodStart(at))
  }

  @Test fun `unfinished atomic commit recovers previous reservations and corrupt ledger fails closed`() {
    val dir = temp.newFolder()
    val file = File(dir, "costs.json")
    val s = DurableCloudCostStore(file)
    s.reserve(charge("a"), estimate("0.1"))
    file.copyTo(File(file.path + ".bak"))
    file.writeText("{broken new commit")
    assertEquals("a", DurableCloudCostStore(file).read().charges.single().id)
    file.writeText("corrupt with no recovery copy")
    assertTrue(runCatching { DurableCloudCostStore(file).read() }.isFailure)
    file.writeText("{}") // Valid JSON is still not a valid previously persisted ledger.
    assertTrue(runCatching { DurableCloudCostStore(file).read() }.isFailure)
  }

  @Test fun `actual cost exceeding reservation stops following requests rather than hiding overrun`() {
    val s = store("1")
    s.reserve(charge("a"), estimate("0.4")); s.settle("a", "1.1", now)
    assertEquals("1.1", SpendingLedger.totals(s.read(), now).actualUsd)
    assertEquals("cloud_budget_exceeded", blocked { s.reserve(charge("b"), estimate("0.01")) })
  }
}
