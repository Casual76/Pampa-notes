package dev.pampa.pampanotes.core.spending

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** USD decimals throughout: never a Float/Double at a budget boundary. */
object Usd {
  fun parseInput(text: String): String? = runCatching {
    val value = text.trim().replace(',', '.')
    require(value.length in 1..32 && value.matches(Regex("[0-9]+(\\.[0-9]+)?")))
    BigDecimal(value).setScale(6, RoundingMode.UNNECESSARY).stripTrailingZeros().toPlainString()
  }.getOrNull()

  fun amount(text: String?): BigDecimal? = text?.let {
    runCatching {
      require(it.length <= 256)
      BigDecimal(it).takeIf { n -> n.signum() >= 0 && n.scale() in -1000..1000 && n.precision() <= 256 }
    }.getOrNull()
  }
  fun reserve(value: BigDecimal): String = value.setScale(6, RoundingMode.CEILING).toPlainString()
}

@Serializable enum class BudgetPeriod { DAILY, MONTHLY }
@Serializable enum class UnknownCostAction { BLOCK, ALLOW, USER_RATE }
@Serializable enum class CloudFolderRule { INHERIT, ALLOW, DENY }
@Serializable enum class PriceUnit { AUDIO_SECOND, TOKEN, UNKNOWN }

@Serializable data class CloudCostSettings(
  val budgetUsd: String? = null,
  val period: BudgetPeriod = BudgetPeriod.MONTHLY,
  val zoneId: String = ZoneId.systemDefault().id,
  val perJobUsd: String? = null,
  val unknownAction: UnknownCostAction = UnknownCostAction.ALLOW,
  /** Explicit user estimates, never represented as a guaranteed bound. Key = provider/model. */
  val userRatesPerMinute: Map<String, String> = emptyMap(),
  val resetAt: Long = 0,
) {
  val capped: Boolean get() = budgetUsd != null || perJobUsd != null
  fun periodStart(now: Long): Long {
    val date = Instant.ofEpochMilli(now).atZone(ZoneId.of(zoneId)).toLocalDate()
    return (if (period == BudgetPeriod.MONTHLY) date.withDayOfMonth(1) else date)
      .atStartOfDay(ZoneId.of(zoneId)).toInstant().toEpochMilli()
  }
  fun validated(): CloudCostSettings {
    require(budgetUsd == null || Usd.parseInput(budgetUsd) != null)
    require(perJobUsd == null || Usd.parseInput(perJobUsd) != null)
    require(userRatesPerMinute.values.all { Usd.parseInput(it) != null })
    ZoneId.of(zoneId)
    return copy(budgetUsd = budgetUsd?.let { Usd.parseInput(it)!! }, perJobUsd = perJobUsd?.let { Usd.parseInput(it)!! },
      userRatesPerMinute = userRatesPerMinute.mapValues { Usd.parseInput(it.value)!! })
  }
}

@Serializable data class FolderCloudSetting(
  val rule: CloudFolderRule = CloudFolderRule.INHERIT,
  val openRouterModel: String? = null,
)
data class CloudFolder(val id: String, val parentId: String?, val name: String)
data class EffectiveCloudPolicy(
  val allowed: Boolean,
  val sourceFolderId: String? = null,
  val sourceName: String? = null,
  val model: String? = null,
  val modelSourceName: String? = null,
  val reason: String? = null,
)
data class FolderCloudSnapshot(val setting: FolderCloudSetting, val effective: EffectiveCloudPolicy)

object FolderCloudPolicy {
  /** Nearest explicit rule/model wins independently. Broken ancestry fails closed. */
  fun resolve(folderId: String?, folders: List<CloudFolder>, rules: Map<String, FolderCloudSetting>, customOnly: Boolean): EffectiveCloudPolicy {
    if (customOnly) return EffectiveCloudPolicy(false, reason = "companion_only")
    val byId = folders.associateBy { it.id }
    var cursor = folderId
    val visited = mutableSetOf<String>()
    var decision: Pair<CloudFolder, Boolean>? = null
    var model: Pair<CloudFolder, String>? = null
    if (cursor == null) return EffectiveCloudPolicy(false, reason = "cloud_folder_blocked")
    while (cursor != null) {
      if (!visited.add(cursor)) return EffectiveCloudPolicy(false, reason = "cloud_folder_blocked")
      val folder = byId[cursor] ?: return EffectiveCloudPolicy(false, reason = "cloud_folder_blocked")
      val setting = rules[cursor]
      if (decision == null && setting != null && setting.rule != CloudFolderRule.INHERIT) {
        decision = folder to (setting.rule == CloudFolderRule.ALLOW)
      }
      if (model == null) setting?.openRouterModel?.takeIf { it.isNotBlank() }?.let { model = folder to it }
      cursor = folder.parentId
    }
    return EffectiveCloudPolicy(
      allowed = decision?.second ?: true,
      sourceFolderId = decision?.first?.id,
      sourceName = decision?.first?.name,
      model = model?.second,
      modelSourceName = model?.first?.name,
      reason = if (decision?.second == false) "cloud_folder_blocked" else null,
    )
  }
}

@Serializable data class ModelPrice(
  val model: String,
  val unit: PriceUnit = PriceUnit.UNKNOWN,
  val minRate: String? = null,
  val maxRate: String? = null,
  val outputRate: String? = null,
  val fetchedAt: Long = 0,
  /** False when an endpoint is missing/malformed or additional fees are not understood. */
  val complete: Boolean = false,
) {
  fun fresh(now: Long): Boolean = fetchedAt > 0 && now >= fetchedAt && now - fetchedAt <= MAX_AGE_MS
  companion object { const val MAX_AGE_MS = 24 * 60 * 60 * 1000L }
}

data class CostEstimate(
  val minUsd: String? = null,
  val maxUsd: String? = null,
  val unit: PriceUnit = PriceUnit.UNKNOWN,
  val stale: Boolean = false,
  val userEstimated: Boolean = false,
) {
  val reservable: Boolean get() = maxUsd != null && !stale && !userEstimated && unit == PriceUnit.AUDIO_SECOND
}

object CloudEstimates {
  fun estimate(provider: String, model: String, durationMs: Long, price: ModelPrice?, settings: CloudCostSettings, now: Long): CostEstimate {
    if (provider == "custom") return CostEstimate("0", "0", PriceUnit.AUDIO_SECOND)
    val rate = settings.userRatesPerMinute["$provider/$model"]
    if (settings.unknownAction == UnknownCostAction.USER_RATE && rate != null && durationMs > 0) {
      val cost = Usd.amount(rate)!!.multiply(BigDecimal(durationMs)).divide(BigDecimal(60_000), 12, RoundingMode.CEILING)
      return CostEstimate(Usd.reserve(cost), Usd.reserve(cost), userEstimated = true)
    }
    val fresh = price?.fresh(now) == true
    if (durationMs <= 0 || price == null || !fresh || !price.complete || price.unit != PriceUnit.AUDIO_SECOND) {
      return CostEstimate(unit = price?.unit ?: PriceUnit.UNKNOWN, stale = price != null && !fresh)
    }
    val seconds = BigDecimal(durationMs).divide(BigDecimal(1000))
    return CostEstimate(
      Usd.reserve(seconds.multiply(Usd.amount(price.minRate) ?: return CostEstimate())),
      Usd.reserve(seconds.multiply(Usd.amount(price.maxRate) ?: return CostEstimate())),
      PriceUnit.AUDIO_SECOND,
    )
  }
}

@Serializable data class KeyQuota(
  val fingerprint: String,
  val fetchedAt: Long,
  val limit: String? = null,
  val remaining: String? = null,
  val reset: String? = null,
  val usage: String? = null,
  val daily: String? = null,
  val weekly: String? = null,
  val monthly: String? = null,
  val effectiveLimit: String? = null,
  val effectiveRemaining: String? = null,
  val effectiveReset: String? = null,
)

@Serializable data class CloudCharge(
  val id: String,
  val jobId: String,
  val provider: String,
  val keyFingerprint: String,
  val model: String,
  val durationMs: Long,
  val createdAt: Long,
  val reservedUsd: String? = null,
  /** Original decimal response value, saved before transcript parsing. */
  val actualUsd: String? = null,
  val settledAt: Long? = null,
  val reconciled: Boolean = false,
  val priceVerified: Boolean = false,
  /** Private definitive refusal, provisionally zero until any reported usage arrives. */
  val refusedAt: Long? = null,
)

@Serializable data class CloudCostData(
  val schema: Int = 1,
  val settings: CloudCostSettings = CloudCostSettings(),
  val folders: Map<String, FolderCloudSetting> = emptyMap(),
  val prices: Map<String, ModelPrice> = emptyMap(),
  val quotas: Map<String, KeyQuota> = emptyMap(),
  val charges: List<CloudCharge> = emptyList(),
)
data class SpendingTotals(val actualUsd: String, val reservedUsd: String, val unknownRequests: Int, val availableUsd: String?)

object SpendingLedger {
  fun totals(data: CloudCostData, now: Long): SpendingTotals {
    val from = maxOf(data.settings.periodStart(now), data.settings.resetAt)
    val actual = data.charges.filter { it.settledAt != null && it.settledAt >= from }
      .fold(BigDecimal.ZERO) { sum, it -> sum + (Usd.amount(it.actualUsd) ?: BigDecimal.ZERO) }
    // Unknown requests survive periods/resets. Only exact refusals or reconciliation release them.
    val pending = data.charges.filter { it.settledAt == null }
    val reserved = pending.fold(BigDecimal.ZERO) { sum, it -> sum + (Usd.amount(it.reservedUsd) ?: BigDecimal.ZERO) }
    return SpendingTotals(actual.toPlainString(), reserved.toPlainString(), pending.count { !it.priceVerified },
      Usd.amount(data.settings.budgetUsd)?.subtract(actual + reserved)?.max(BigDecimal.ZERO)?.toPlainString())
  }

  fun check(data: CloudCostData, jobId: String, estimate: CostEstimate, now: Long): String? {
    val settings = data.settings
    if (!estimate.reservable && settings.capped) return "cloud_price_unknown"
    if (estimate.maxUsd == null && settings.unknownAction != UnknownCostAction.ALLOW) return "cloud_price_unknown"
    val totals = totals(data, now)
    if (settings.capped && data.charges.any { it.settledAt == null && !it.priceVerified }) return "cloud_charge_unresolved"
    val cost = Usd.amount(estimate.maxUsd) ?: BigDecimal.ZERO
    val used = Usd.amount(totals.actualUsd)!! + Usd.amount(totals.reservedUsd)!!
    Usd.amount(settings.budgetUsd)?.let { if (used + cost > it) return "cloud_budget_exceeded" }
    val jobCharges = data.charges.filter { it.jobId == jobId }
    val jobUsed = jobCharges.fold(BigDecimal.ZERO) { sum, it -> sum + (Usd.amount(it.actualUsd ?: it.reservedUsd) ?: BigDecimal.ZERO) }
    Usd.amount(settings.perJobUsd)?.let { if (jobUsed + cost > it) return "cloud_job_limit" }
    return null
  }
}
