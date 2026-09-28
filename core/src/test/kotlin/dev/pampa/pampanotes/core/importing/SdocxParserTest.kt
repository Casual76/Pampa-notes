package dev.pampa.pampanotes.core.importing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Sul file vero: una nota di filosofia con quattro paragrafi e due registrazioni, esportata da
 * Samsung Notes. Gli audio nella fixture sono ridotti a un byte, perche' 66 MB in un repo sono
 * troppi e il parser non li apre comunque: legge solo i nomi.
 */
class SdocxParserTest {

  @get:Rule
  val temp = TemporaryFolder()

  private fun fixture(): File {
    val file = temp.newFile("fichte.sdocx")
    javaClass.getResourceAsStream("/sdocx/fichte.sdocx")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
    return file
  }

  @Test
  fun `il titolo e' la prima stringa, corta`() {
    val doc = SdocxParser.parse(fixture())

    assertEquals("Johann Gottlieh Fichte", doc.title)
  }

  @Test
  fun `il corpo e' il testo battuto, con i suoi a capo`() {
    val doc = SdocxParser.parse(fixture())

    assertTrue(doc.body.startsWith("Fichte nasce nel 1752 e muore nel 1814 a Berlino."))
    assertTrue(doc.body.contains("Lo stato commerciale chiuso"))
    assertTrue(doc.body.contains("Ur-volk"))
    assertTrue(doc.body.endsWith("sotto forma biologica al posto di linguistica."))
    assertEquals(4, doc.paragraphCount)
    // Niente byte di struttura scambiati per testo: il corpo e' lungo quanto la nota, non di piu'.
    assertTrue("${doc.body.length} caratteri", doc.body.length in 5_500..5_800)
  }

  @Test
  fun `le registrazioni sono due, in ordine, con nome e durata`() {
    val doc = SdocxParser.parse(fixture())

    assertEquals(2, doc.recordings.size)
    val (first, second) = doc.recordings
    assertEquals("media/3@6aabb551_60b18.m4a", first.entryName)
    assertEquals("Voce 001", first.title)
    assertEquals(27 * 60_000L + 29_000L, first.durationMs)
    assertEquals("media/0@6aacf6fc_11657.m4a", second.entryName)
    assertEquals("Voce 002", second.title)
    assertEquals(40 * 60_000L + 58_000L, second.durationMs)
    assertEquals(68 * 60_000L + 27_000L, doc.totalDurationMs)
  }

  @Test
  fun `ogni registrazione porta il suo sha256, il suo peso e l'ora in cui e' cominciata`() {
    val doc = SdocxParser.parse(fixture())

    assertEquals("26d05f809b6635cc9b35f0b6a7ca35de1a054cea606f15f025f63234ca2ad5e0", doc.recordings[0].sha256)
    assertEquals("6b8b39134b4a4e1a2cdc28f3daed0085992223802c4d2d8706962581fe2be5f3", doc.recordings[1].sha256)
    // L'ora e' quella nel nome della voce (`3@6aabb551`: 17/09 09:39:29 UTC), non quella di
    // mediaInfo.dat, che per «Voce 002» e' l'inizio piu' la durata: quando il file e' stato chiuso.
    assertEquals(1_789_637_969_000L, doc.recordings[0].createdAtMillis)
    assertEquals(1_789_720_316_000L, doc.recordings[1].createdAtMillis)
    assertTrue(doc.recordings.all { it.sizeBytes >= 0 })
    assertEquals(listOf("Voce 001", "Voce 002"), doc.voices.map { it.title })
  }

  /**
   * Un `.sdocx` come quello di «Impressionismo» del 28/09: quattro registrazioni di quattro giorni,
   * i record di mediaInfo.dat nell'ordine 0, 1, 3, 2, tutti con l'ora della condivisione.
   */
  private fun impressionismo(names: List<String> = IMPRESSIONISMO_ENTRIES): File {
    val file = temp.newFile("impressionismo.sdocx")
    val shared = 1_790_589_697_603_000L // 28/09/2026 10:01:37 UTC, in microsecondi
    val mediaInfo = java.io.ByteArrayOutputStream()
    listOf(0, 1, 3, 2).forEach { slot ->
      val name = names[slot].substringAfter("media/")
      val buffer = java.nio.ByteBuffer.allocate(10 + name.length * 2 + 64 + 2 + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      buffer.putInt(0x79).putInt(slot).putShort(name.length.toShort())
      buffer.put(name.toByteArray(Charsets.UTF_16LE))
      buffer.put(slot.toString().repeat(64).toByteArray(Charsets.US_ASCII))
      buffer.putShort(0).putLong(shared + slot)
      mediaInfo.write(buffer.array())
    }
    val note = java.io.ByteArrayOutputStream()
    listOf("Voce 001" to "00:35:15", "Voce 002" to "00:51:22", "Voce 003" to "00:48:30", "Voce 004" to "00:44:10").forEach { (title, duration) ->
      note.write(byteArrayOf(0, 0, 0, 0))
      note.write(byteArrayOf(title.length.toByte(), 0))
      note.write(title.toByteArray(Charsets.UTF_16LE))
      note.write(byteArrayOf(8, 0))
      note.write(duration.toByteArray(Charsets.UTF_16LE))
    }
    java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
      zip.putNextEntry(java.util.zip.ZipEntry("note.note"))
      zip.write(note.toByteArray())
      zip.closeEntry()
      zip.putNextEntry(java.util.zip.ZipEntry("media/mediaInfo.dat"))
      zip.write(mediaInfo.toByteArray())
      zip.closeEntry()
      listOf(0, 1, 3, 2).forEach { slot ->
        zip.putNextEntry(java.util.zip.ZipEntry(names[slot]))
        zip.write(ByteArray(slot + 1))
        zip.closeEntry()
      }
    }
    return file
  }

  @Test
  fun `le registrazioni vanno in fila per quando sono state fatte, non per mediaInfo`() {
    val doc = SdocxParser.parse(impressionismo())

    assertEquals(IMPRESSIONISMO_ENTRIES, doc.recordings.map { it.entryName })
    assertEquals(listOf("Voce 001", "Voce 002", "Voce 003", "Voce 004"), doc.recordings.map { it.title })
    assertEquals(listOf(2_910_000L, 2_650_000L), doc.recordings.drop(2).map { it.durationMs })
    // 19/09, 21/09, 24/09, 28/09: le ore dei nomi, non il 28/09 della condivisione per tutte.
    assertEquals(
      listOf(1_789_802_739_000L, 1_789_974_894_000L, 1_790_230_557_000L, 1_790_586_899_000L),
      doc.recordings.map { it.createdAtMillis },
    )
  }

  @Test
  fun `senza l'ora nel nome, le ore della condivisione non sono una data`() {
    val names = listOf("media/a.m4a", "media/b.m4a", "media/c.m4a", "media/d.m4a")
    val doc = SdocxParser.parse(impressionismo(names))

    // L'ordine resta quello dei record, e nessuna registrazione finisce datata al giorno della condivisione.
    assertEquals(listOf("media/a.m4a", "media/b.m4a", "media/d.m4a", "media/c.m4a"), doc.recordings.map { it.entryName })
    assertTrue(doc.recordings.all { it.createdAtMillis == null })
  }

  @Test
  fun `un file senza note_note non produce niente ma non esplode`() {
    val file = temp.newFile("vuoto.sdocx")
    java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
      zip.putNextEntry(java.util.zip.ZipEntry("pageIdInfo.dat"))
      zip.write(ByteArray(16))
      zip.closeEntry()
    }

    val doc = SdocxParser.parse(file)

    assertNull(doc.title)
    assertEquals("", doc.body)
    assertTrue(doc.recordings.isEmpty())
    assertTrue(doc.isEmpty)
  }

  @Test
  fun `una nota senza titolo ha il corpo e basta`() {
    // Il titolo e' "la prima stringa corta": una nota che comincia con un paragrafo lungo non ne ha.
    val doc = SdocxParser.parse(fixture())
    val paragraph = doc.body.lineSequence().first().trim()

    assertTrue(paragraph.length > 160)
    // Ricostruendo un note.note con solo quel paragrafo, il parser non deve inventare un titolo.
    val bytes = utf16Record(paragraph)
    val prose = SdocxParser.readProse(bytes)
    assertEquals(listOf(paragraph), prose)
  }

  @Test
  fun `i tratti di penna non passano per testo`() {
    // Coppie di byte qualsiasi che decodificano a caratteri CJK: lettere per Character.isLetter,
    // ma non di un alfabeto che uno scriva a mano in una nota italiana.
    assertFalse(SdocxParser.looksLikeProse("뜐頣ጶ駚⧔駮㞆騆ꩇ騒"))
    assertFalse(SdocxParser.looksLikeProse("........,,,,,,,"))
    assertTrue(SdocxParser.looksLikeProse("Fichte nasce nel 1752 e muore nel 1814 a Berlino."))
    assertTrue(SdocxParser.looksLikeProse("Ελληνικά και latino insieme, va bene."))
    // In «Romanticismo», scritta tutta a mano, l'unica «frase» era un identificatore di Samsung.
    assertFalse(SdocxParser.looksLikeProse("0com.samsung"))
    assertFalse(SdocxParser.looksLikeProse("com.samsung.android.app.notes"))
  }

  @Test
  fun `le lunghezze plausibili sono l'unico modo di non leggere coordinate come parole`() {
    // Un int32 seguito da byte che non sono testo: si salta di un byte e si va avanti.
    val junk = ByteArray(200) { (it * 37).toByte() }
    assertTrue(SdocxParser.readProse(junk).isEmpty())
    assertTrue(SdocxParser.readVoices(junk).isEmpty())
    assertTrue(SdocxParser.readMediaInfo(junk).isEmpty())
  }

  // --- Le date della nota -------------------------------------------------------------------------

  /** Il 23 settembre 2026 a mezzogiorno UTC: dopo le date della fixture, e fisso. */
  private val now = 1_790_164_800_000L

  @Test
  fun `le date vengono da end_tag, non dallo zip`() {
    val dates = SdocxParser.readDates(fixture(), now)!!

    // 17/09/2026 09:39:43 UTC la creazione, 18/09/2026 09:13:23 UTC l'ultima modifica; le date
    // dello ZIP dicono invece il 18 alle 13:17, cioe' la condivisione.
    assertEquals(1_789_637_983_096L, dates.createdAtMillis)
    assertEquals(1_789_722_803_594L, dates.modifiedAtMillis)
  }

  @Test
  fun `parse porta le date insieme al resto`() {
    val doc = SdocxParser.parse(fixture())

    assertEquals(1_789_637_983_096L, doc.dates?.createdAtMillis)
    assertEquals(1_789_722_803_594L, doc.dates?.modifiedAtMillis)
  }

  @Test
  fun `senza end_tag le date si leggono in testa a note_note`() {
    val file = temp.newFile("senza-end-tag.sdocx")
    val header = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      .putLong(24, 1_789_637_983_096_228L)
      .putLong(32, 1_789_722_803_594_379L)
      .array()
    java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
      zip.putNextEntry(java.util.zip.ZipEntry("note.note"))
      zip.write(header)
      zip.closeEntry()
    }

    val dates = SdocxParser.readDates(file, now)!!

    assertEquals(1_789_637_983_096L, dates.createdAtMillis)
    assertEquals(1_789_722_803_594L, dates.modifiedAtMillis)
  }

  @Test
  fun `date impossibili non passano`() {
    // Prima del 2010, e nel futuro: niente.
    assertNull(SdocxParser.plausibleDates(created = 1_000_000L, modified = (now + 86_400_000L) * 1000, now = now))
    // Creazione dopo la modifica: gli offset non erano quelli giusti, e non vale nessuna delle due.
    assertNull(SdocxParser.plausibleDates(created = 1_789_722_803_594_379L, modified = 1_789_637_983_096_228L, now = now))
    // Una sola buona: resta quella.
    val onlyModified = SdocxParser.plausibleDates(created = 0L, modified = 1_789_722_803_594_379L, now = now)!!
    assertNull(onlyModified.createdAtMillis)
    assertEquals(1_789_722_803_594L, onlyModified.modifiedAtMillis)
    // Un end_tag troppo corto non si legge.
    assertNull(SdocxParser.parseEndTag(ByteArray(20), now))
  }

  @Test
  fun `un file che non e' uno zip non ha date`() {
    val file = temp.newFile("rotto.sdocx")
    file.writeBytes(ByteArray(100) { it.toByte() })

    assertNull(SdocxParser.readDates(file, now))
  }

  /** Un record come quelli di note.note: int32 lunghezza in caratteri + UTF-16LE. */
  @Test
  fun `una registrazione datata nel futuro non ha data`() {
    val now = 1_790_164_800_000L
    fun record(atMillis: Long): ByteArray {
      val name = "a.m4a"
      val buffer = java.nio.ByteBuffer.allocate(10 + name.length * 2 + 64 + 2 + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      buffer.putInt(0x79).putInt(0).putShort(name.length.toShort())
      buffer.put(name.toByteArray(Charsets.UTF_16LE))
      buffer.put("0".repeat(64).toByteArray(Charsets.US_ASCII))
      buffer.putShort(0).putLong(atMillis * 1000)
      return buffer.array()
    }
    // Un'ora fa: vale. L'anno prossimo (prima si accettava fino al 2100): no.
    assertEquals(now - 3_600_000, SdocxParser.readMediaInfo(record(now - 3_600_000), now).single().createdAtMillis)
    assertNull(SdocxParser.readMediaInfo(record(now + 365L * 24 * 3_600_000), now).single().createdAtMillis)
  }

  private companion object {
    val IMPRESSIONISMO_ENTRIES = listOf(
      "media/0@6aae38f3_bd0c8.m4a",
      "media/1@6ab0d96e_7ef25.m4a",
      "media/2@6ab4c01d_d9379.m4a",
      "media/3@6aba3013_63dbb.m4a",
    )
  }

  private fun utf16Record(text: String): ByteArray {
    val encoded = text.toByteArray(Charsets.UTF_16LE)
    val out = java.io.ByteArrayOutputStream()
    out.write(byteArrayOf((text.length and 0xFF).toByte(), ((text.length shr 8) and 0xFF).toByte(), ((text.length shr 16) and 0xFF).toByte(), ((text.length shr 24) and 0xFF).toByte()))
    out.write(encoded)
    return out.toByteArray()
  }
}
