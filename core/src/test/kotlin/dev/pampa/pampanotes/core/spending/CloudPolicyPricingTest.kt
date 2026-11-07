package dev.pampa.pampanotes.core.spending

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class CloudPolicyPricingTest {
  private val folders = listOf(CloudFolder("root", null, "Root"), CloudFolder("child", "root", "Child"), CloudFolder("leaf", "child", "Leaf"))
  private val now = 1_800_000_000_000L

  @Test fun `nearest explicit permission and nearest model inherit independently`() {
    val policy = FolderCloudPolicy.resolve("leaf", folders, mapOf(
      "root" to FolderCloudSetting(CloudFolderRule.DENY, "root/model"),
      "child" to FolderCloudSetting(CloudFolderRule.ALLOW),
    ), false)
    assertTrue(policy.allowed)
    assertEquals("Child", policy.sourceName)
    assertEquals("root/model", policy.model)
    assertEquals("Root", policy.modelSourceName)
  }

  @Test fun `global companion restriction always overrides explicit allow`() {
    assertEquals("companion_only", FolderCloudPolicy.resolve("leaf", folders,
      mapOf("leaf" to FolderCloudSetting(CloudFolderRule.ALLOW)), true).reason)
  }

  @Test fun `cycles missing ancestors and deleted folders fail closed even after a nearest allow`() {
    val allow = mapOf("leaf" to FolderCloudSetting(CloudFolderRule.ALLOW))
    val cycle = folders.map { if (it.id == "root") it.copy(parentId = "leaf") else it }
    assertFalse(FolderCloudPolicy.resolve("leaf", cycle, allow, false).allowed)
    assertFalse(FolderCloudPolicy.resolve("leaf", folders.filter { it.id != "root" }, allow, false).allowed)
    assertFalse(FolderCloudPolicy.resolve("gone", folders, allow, false).allowed)
    assertFalse(FolderCloudPolicy.resolve(null, folders, allow, false).allowed)
  }

  @Test fun `moving subtree re-resolves inheritance instead of reusing queued rule`() {
    val all = folders + CloudFolder("private", null, "Private")
    val rules = mapOf("private" to FolderCloudSetting(CloudFolderRule.DENY))
    assertTrue(FolderCloudPolicy.resolve("leaf", all, rules, false).allowed)
    val moved = all.map { if (it.id == "child") it.copy(parentId = "private") else it }
    assertFalse(FolderCloudPolicy.resolve("leaf", moved, rules, false).allowed)
    assertEquals("Private", FolderCloudPolicy.resolve("leaf", moved, rules, false).sourceName)
  }

  @Test fun `decimal comma zero and micro units preserve exact budget boundaries`() {
    assertEquals("12.345678", Usd.parseInput("12,345678"))
    assertEquals("0", Usd.parseInput("0,000000"))
    assertEquals("0.000001", Usd.reserve(BigDecimal("0.00000000001")))
    for (invalid in listOf("", "-1", "NaN", "Infinity", "1e3", "1,2.3", "0.0000001", "1 000", ".5")) {
      assertNull(invalid, Usd.parseInput(invalid))
    }
    assertEquals("1.23", Usd.parseInput("1.2300000"))
  }

  private fun price(model: String, endpoints: String, catalogue: String = "{}") = OpenRouterCostMetadata.parsePrice(
    model, Json.parseToJsonElement(catalogue) as JsonObject,
    Json.parseToJsonElement("""{"data":{"endpoints":$endpoints}}"""), now)

  @Test fun `duration price uses all routing endpoints and rounds reservation upwards`() {
    val p = price("openai/whisper-large-v3", """[
      {"pricing":{"prompt":"0.0000075","completion":"0"}},
      {"pricing":{"prompt":"0.0000308333333333","completion":"0"}}
    ]""")
    val e = CloudEstimates.estimate("openrouter", p.model, 60_001, p, CloudCostSettings(), now)
    assertEquals("0.000451", e.minUsd)
    assertEquals("0.001851", e.maxUsd)
    assertTrue(e.reservable)
  }

  @Test fun `prompt is not a universal per second unit including zero completion`() {
    val p = price("future/new-model", """[{"pricing":{"prompt":"0.002","completion":"0"}}]""")
    assertEquals(PriceUnit.UNKNOWN, p.unit)
    assertNull(CloudEstimates.estimate("openrouter", p.model, 60_000, p, CloudCostSettings(), now).maxUsd)
  }

  @Test fun `token model exposes token metadata but never a duration cost maximum`() {
    val p = price("google/gemini-3.5-transcribe", """[{"pricing":{"prompt":"0.000002","completion":"0.000012"}}]""")
    assertEquals(PriceUnit.TOKEN, p.unit)
    val e = CloudEstimates.estimate("openrouter", p.model, 3600_000, p, CloudCostSettings(budgetUsd = "10"), now)
    assertNull(e.maxUsd)
    assertFalse(e.reservable)
    assertEquals("cloud_price_unknown", SpendingLedger.check(CloudCostData(settings = CloudCostSettings(budgetUsd = "10")), "j", e, now))
  }

  @Test fun `missing bad or extra fee endpoint invalidates duration reservation`() {
    for (endpoints in listOf("[]", """[{"pricing":{"prompt":"NaN","completion":"0"}}]""",
      """[{"pricing":{"prompt":"0.01","completion":"0"}},{"pricing":{}}]""",
      """[{"pricing":{"prompt":"0.01","completion":"0","request":"0.5"}}]""")) {
      val p = price("openai/whisper-1", endpoints)
      assertFalse(p.complete)
      assertFalse(CloudEstimates.estimate("openrouter", p.model, 60_000, p, CloudCostSettings(), now).reservable)
    }
  }

  @Test fun `stale future dated and unknown duration are never free`() {
    val p = ModelPrice("openai/whisper-1", PriceUnit.AUDIO_SECOND, "0.01", "0.02", fetchedAt = now, complete = true)
    for (at in listOf(now - 1, now + ModelPrice.MAX_AGE_MS + 1)) {
      val e = CloudEstimates.estimate("openrouter", p.model, 60_000, p, CloudCostSettings(), at)
      assertTrue(e.stale); assertNull(e.maxUsd)
    }
    assertNull(CloudEstimates.estimate("openrouter", p.model, 0, p, CloudCostSettings(), now).maxUsd)
  }

  @Test fun `user duration rate remains an explicit estimate and cannot bypass enabled caps`() {
    val s = CloudCostSettings(unknownAction = UnknownCostAction.USER_RATE, userRatesPerMinute = mapOf("openrouter/token" to "0.1"))
    val e = CloudEstimates.estimate("openrouter", "token", 90_000, null, s, now)
    assertEquals("0.150000", e.maxUsd); assertTrue(e.userEstimated); assertFalse(e.reservable)
    assertNull(SpendingLedger.check(CloudCostData(settings = s), "j", e, now))
    assertEquals("cloud_price_unknown", SpendingLedger.check(CloudCostData(settings = s.copy(perJobUsd = "1")), "j", e, now))
  }

  @Test fun `normal key endpoint snapshot is separated by fingerprint and retains remote periods`() {
    val key = OpenRouterCostMetadata.parseQuota(Json.parseToJsonElement("""{"data":{
      "limit":10,"limit_remaining":-0.1,"limit_reset":"monthly","usage":10.1,
      "usage_daily":1,"usage_weekly":3,"usage_monthly":9,
      "effective_budget":{"limit_usd":8,"remaining_usd":0,"resets_at":"2026-12-01T00:00:00Z"}}}"""), "fingerprint", now)
    assertEquals("-0.1", key.remaining)
    assertEquals("monthly", key.reset)
    assertEquals("0", key.effectiveRemaining)
    assertEquals("fingerprint", key.fingerprint)
    assertNotEquals(OpenRouterCostMetadata.fingerprint("old-secret"), OpenRouterCostMetadata.fingerprint("new-secret"))
  }

  @Test fun `pricing refresh uses filtered models and every endpoint with normal key GET only`() = kotlinx.coroutines.runBlocking {
    val server = dev.pampa.pampanotes.core.testing.TinyHttpServer()
    try {
      server.respond { req ->
        val body = when (req.path) {
          "/api/v1/models?output_modalities=transcription" -> """{"data":[{"id":"openai/whisper-1","architecture":{"output_modalities":["transcription"]}}]}"""
          "/api/v1/models/openai/whisper-1/endpoints" -> """{"data":{"endpoints":[{"pricing":{"prompt":"0.01","completion":"0"}},{"pricing":{"prompt":"0.02","completion":"0"}}]}}"""
          "/api/v1/key" -> """{"data":{"limit":10,"limit_remaining":8,"usage":2}}"""
          else -> error("Unexpected network endpoint: ${req.path}")
        }
        dev.pampa.pampanotes.core.testing.TinyHttpServer.Response(200, body)
      }
      val api = OpenRouterCostMetadata(dev.pampa.pampanotes.core.transcription.TranscriptionHttp("test"), server.url("/api/v1"))
      val p = api.prices("ordinary-test-key", "openai/whisper-1", now)
      assertEquals("0.02", p.maxRate)
      assertEquals("8", api.quota("ordinary-test-key", now).remaining)
      assertEquals(3, server.requests.size)
      assertTrue(server.requests.all { it.method == "GET" && it.body.isEmpty() && it.header("Authorization") == "Bearer ordinary-test-key" })
    } finally { server.close() }
  }
}
