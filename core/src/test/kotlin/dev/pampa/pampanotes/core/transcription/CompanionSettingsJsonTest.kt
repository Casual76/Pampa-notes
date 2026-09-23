package dev.pampa.pampanotes.core.transcription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionSettingsJsonTest {

  private fun json(text: String) = Json.parseToJsonElement(text)

  @Test
  fun `health con scheda e stima`() {
    val health = CompanionSettingsJson.parseHealth(
      json(
        """{"status":"ok","model":"large-v3",
          "gpu":{"name":"NVIDIA GeForce RTX 4070 Ti","total_gb":12.0,"free_gb":10.4},
          "vram":{"mode":"auto","budget_gb":11.2,"estimate_gb":5.9,"batch_size":16,"model":"large-v3","compute_type":"float16"}}""",
      ),
    )!!
    assertEquals(CompanionGpu("NVIDIA GeForce RTX 4070 Ti", 12.0, 10.4), health.gpu)
    assertEquals(VramEstimate(5.9, 11.2, 16, "large-v3", "float16", VramMode.AUTO), health.vram)
    assertFalse(health.vram!!.exceedsBudget)
  }

  @Test
  fun `health sul processore - niente scheda, ma la sezione c'e'`() {
    val health = CompanionSettingsJson.parseHealth(json("""{"gpu":null,"vram":{"mode":"auto","estimate_gb":0,"batch_size":4,"model":"small"}}"""))!!
    assertNull(health.gpu)
    assertEquals(0.0, health.vram!!.estimateGb, 0.0)
  }

  @Test
  fun `un companion vecchio non ha vram, e la sezione non si mostra`() {
    assertNull(CompanionSettingsJson.parseHealth(json("""{"status":"ok","model":"large-v3"}""")))
    assertNull(CompanionSettingsJson.parseHealth(json("""{"vram":null}""")))
    assertNull(CompanionSettingsJson.parseHealth(null))
  }

  @Test
  fun `le impostazioni si leggono piatte o dentro settings`() {
    val flat = CompanionSettingsJson.parseSettings(
      json("""{"model":"medium","compute_type":"int8_float16","vram_mode":"manual","vram_gb":6,"batch_size_max":8,"idle_minutes":15}"""),
    )
    val expected = CompanionSettings("medium", "int8_float16", VramMode.MANUAL, 6.0, 8, 15)
    assertEquals(expected, flat)
    assertEquals(expected, CompanionSettingsJson.parseSettings(json("""{"settings":{"model":"medium","compute_type":"int8_float16","vram_mode":"manual","vram_gb":6,"batch_size_max":8,"idle_minutes":15}}""")))
    // Senza modello non sono impostazioni.
    assertNull(CompanionSettingsJson.parseSettings(json("""{"error":"forbidden"}""")))
  }

  @Test
  fun `la stima si trova piatta, in vram o in estimate`() {
    val flat = CompanionSettingsJson.parseEstimate(json("""{"estimate_gb":7.5,"budget_gb":6,"batch_size":16}"""))!!
    assertTrue(flat.exceedsBudget)
    assertEquals(3.1, CompanionSettingsJson.parseEstimate(json("""{"settings":{"model":"small"},"vram":{"estimate_gb":3.1}}"""))!!.estimateGb, 0.0)
    assertEquals(2.0, CompanionSettingsJson.parseEstimate(json("""{"estimate":{"estimate_gb":"2.0"}}"""))!!.estimateGb, 0.0)
    assertNull(CompanionSettingsJson.parseEstimate(json("""{"ok":true}""")))
  }

  @Test
  fun `il corpo manda i gigabyte solo quando si indicano a mano`() {
    val auto = json(CompanionSettingsJson.encode(CompanionSettings("large-v3", "float16", VramMode.AUTO, 6.0, 16, 10))).jsonObject
    assertEquals("auto", auto["vram_mode"]!!.jsonPrimitive.content)
    assertNull(auto["vram_gb"])
    assertEquals("16", auto["batch_size_max"]!!.jsonPrimitive.content)

    val manual = json(CompanionSettingsJson.encode(CompanionSettings("small", vramMode = VramMode.MANUAL, vramGb = 4.0))).jsonObject
    assertEquals("manual", manual["vram_mode"]!!.jsonPrimitive.content)
    assertEquals(4.0, manual["vram_gb"]!!.jsonPrimitive.content.toDouble(), 0.0)
    assertNull(manual["compute_type"])
  }
}
