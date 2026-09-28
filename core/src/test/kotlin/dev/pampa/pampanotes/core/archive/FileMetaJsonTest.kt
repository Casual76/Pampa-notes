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

  @Test
  fun `l'indice di un sdocx`() {
    val note = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
    val index = FileMetaJson.parseIndex(
      json("""{"sha256": "abc", "entries": [{"name": "media/0@6aae38f3_bd0c8.m4a", "size": 34268413}, {"name": "x.m4a"}], "note_b64": "$note", "media_info_b64": null, "end_tag_b64": "!!"}"""),
      "abc",
    )!!
    assertEquals(listOf("media/0@6aae38f3_bd0c8.m4a" to 34_268_413L, "x.m4a" to -1L), index.entries)
    assertEquals(listOf<Byte>(1, 2, 3), index.note!!.toList())
    assertNull(index.mediaInfo)
    // Un base64 rotto e' un pezzo che manca, non un errore.
    assertNull(index.endTag)
    assertNull(FileMetaJson.parseIndex(json("""{"sha256": "altro", "entries": []}"""), "abc"))
    assertNull(FileMetaJson.parseIndex(json("""{"sha256": "abc"}"""), "abc"))
  }
}
