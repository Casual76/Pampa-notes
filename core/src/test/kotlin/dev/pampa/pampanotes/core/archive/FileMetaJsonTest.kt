package dev.pampa.pampanotes.core.archive

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileMetaJsonTest {

  private fun json(text: String) = Json.parseToJsonElement(text)

  @Test
  fun `la funzione la dice health`() {
    assertTrue(FileMetaJson.hasFeature(json("""{"ok": true, "features": ["jobs", "file_meta"]}"""), "file_meta"))
    assertTrue(FileMetaJson.hasFeature(json("""{"features": {"file_meta": true}}"""), "file_meta"))
    // Un companion di prima: niente `features`, o senza quella.
    assertFalse(FileMetaJson.hasFeature(json("""{"ok": true}"""), "file_meta"))
    assertFalse(FileMetaJson.hasFeature(json("""{"features": ["jobs"]}"""), "file_meta"))
    assertFalse(FileMetaJson.hasFeature(null, "file_meta"))
  }

  @Test
  fun `le date di un sdocx`() {
    val meta = FileMetaJson.parse(
      json("""{"sha256": "abc", "kind": "sdocx", "created_us": 1789637983096228, "modified_us": 1789722803594379, "recorded_us": null}"""),
      "abc",
    )!!

    assertEquals("sdocx", meta.kind)
    assertEquals(1_789_637_983_096_228L, meta.createdUs)
    assertEquals(1_789_722_803_594_379L, meta.modifiedUs)
    assertNull(meta.recordedUs)
  }

  @Test
  fun `la data di un audio`() {
    val meta = FileMetaJson.parse(json("""{"sha256": "ABC", "kind": "audio", "recorded_us": 1758536100000000}"""), "abc")!!

    assertEquals("audio", meta.kind)
    assertEquals(1_758_536_100_000_000L, meta.recordedUs)
    assertNull(meta.createdUs)
  }

  @Test
  fun `una risposta che parla d'altro non vale`() {
    assertNull(FileMetaJson.parse(json("""{"sha256": "altro", "kind": "audio"}"""), "abc"))
    assertNull(FileMetaJson.parse(json("""[1, 2]"""), "abc"))
    assertNull(FileMetaJson.parse(null, "abc"))
  }
}
