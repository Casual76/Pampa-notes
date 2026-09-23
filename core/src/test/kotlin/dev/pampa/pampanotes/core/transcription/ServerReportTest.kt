package dev.pampa.pampanotes.core.transcription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerReportTest {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private fun report(text: String) = VerboseJson.serverReport(json.parseToJsonElement(text).jsonObject)

  @Test
  fun `il companion dice su cosa ha girato e quanto ci ha messo`() {
    val report = report("""{"text":"ciao","processing_s":48.2,"audio_s":"2400.5","device_used":"CUDA"}""")!!
    assertEquals(48_200L, report.processingMs)
    assertEquals(2_400_500L, report.audioMs)
    assertEquals("cuda", report.device)
  }

  @Test
  fun `Groq non dice niente, e niente vuol dire null`() {
    assertNull(report("""{"text":"ciao","duration":12.4}"""))
  }

  @Test
  fun `un companion di prima dice solo il dispositivo`() {
    val report = report("""{"text":"ciao","device_used":"cpu"}""")!!
    assertEquals("cpu", report.device)
    assertNull(report.processingMs)
    assertNull(report.audioMs)
  }

  @Test
  fun `i valori impossibili restano fuori`() {
    val report = report("""{"processing_s":-3,"audio_s":"nan","device_used":"  "}""")
    assertNull(report)
  }

  @Test
  fun `i pezzi di un lavoro si sommano, e uno sul processore basta`() {
    val merged = ServerReport.merge(
      listOf(ServerReport(10_000, 600_000, "cuda"), ServerReport(20_000, 600_000, "cpu")),
    )!!
    assertEquals(30_000L, merged.processingMs)
    assertEquals(1_200_000L, merged.audioMs)
    assertEquals("cpu", merged.device)
    assertNull(ServerReport.merge(emptyList()))
    // Un pezzo senza tempi: la somma degli altri sarebbe un tempo a meta', quindi niente.
    assertNull(ServerReport.merge(listOf(ServerReport(10_000, null, "cuda"), ServerReport(null, null, "cuda")))!!.processingMs)
  }

  @Test
  fun `una risposta letta pubblica il suo resoconto, e chi lo legge lo toglie`() {
    val before = System.currentTimeMillis()
    ServerReports.drainSince(0)
    VerboseJson.parse(
      json.parseToJsonElement(
        """{"text":"ciao","segments":[{"start":0,"end":1,"text":"ciao"}],"processing_s":1.5,"device_used":"cuda"}""",
      ),
    )
    val drained = ServerReports.drainSince(before)
    assertEquals(1, drained.size)
    assertEquals(1_500L, drained.single().processingMs)
    assertTrue(ServerReports.drainSince(before).isEmpty())
  }
}
