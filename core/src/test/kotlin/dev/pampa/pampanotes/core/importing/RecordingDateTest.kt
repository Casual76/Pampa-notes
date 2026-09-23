package dev.pampa.pampanotes.core.importing

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingDateTest {

  private val rome = ZoneId.of("Europe/Rome")
  private val today = LocalDate.of(2026, 9, 23)
  private val sept22 = LocalDate.of(2025, 9, 22)

  // --- metadati ---

  @Test
  fun `la data del contenitore MP4 si legge nel giorno del telefono`() {
    assertEquals(sept22, RecordingDate.parseMetadata("20250922T101500.000Z", rome))
    // Le 23:15 UTC a Roma sono gia' il giorno dopo.
    assertEquals(LocalDate.of(2025, 9, 23), RecordingDate.parseMetadata("20250922T231500.000Z", rome))
    assertEquals(sept22, RecordingDate.parseMetadata("20250922T101500", rome))
    assertEquals(sept22, RecordingDate.parseMetadata("2025-09-22T10:15:00Z", rome))
    assertEquals(sept22, RecordingDate.parseMetadata("2025:09:22 10:15:00", rome))
    assertEquals(sept22, RecordingDate.parseMetadata("2025-09-22T10:15:00+02:00", rome))
  }

  @Test
  fun `la data senza ora si legge com'e'`() {
    assertEquals(sept22, RecordingDate.parseMetadata("2025 09 22", rome))
    assertEquals(sept22, RecordingDate.parseMetadata("2025-09-22", rome))
  }

  @Test
  fun `metadati che non sono una data non sono una data`() {
    assertNull(RecordingDate.parseMetadata(null, rome))
    assertNull(RecordingDate.parseMetadata("", rome))
    assertNull(RecordingDate.parseMetadata("2025", rome))
    assertNull(RecordingDate.parseMetadata("20251322T101500.000Z", rome))
    assertNull(RecordingDate.parseMetadata("ieri", rome))
  }

  // --- nome del file ---

  @Test
  fun `i nomi dei registratori piu' comuni portano la data`() {
    assertEquals(sept22, RecordingDate.fromFileName("Voce 001_250922_1015.m4a"))
    assertEquals(sept22, RecordingDate.fromFileName("Registrazione_20250922_101500.m4a"))
    assertEquals(sept22, RecordingDate.fromFileName("20250922_101500.mp3"))
    assertEquals(sept22, RecordingDate.fromFileName("2025-09-22 10.15.00.m4a"))
    assertEquals(sept22, RecordingDate.fromFileName("AUD-20250922-WA0001.opus"))
    assertEquals(sept22, RecordingDate.fromFileName("PTT-20250922-WA0001.opus"))
    assertEquals(sept22, RecordingDate.fromFileName("lezione 22-09-2025.m4a"))
  }

  @Test
  fun `un nome senza data non ne inventa una`() {
    assertNull(RecordingDate.fromFileName("Voce 001.m4a"))
    assertNull(RecordingDate.fromFileName("Lezione di filosofia 3.m4a"))
    assertNull(RecordingDate.fromFileName("12:04:33.m4a"))
    // Sei cifre da sole non bastano: potrebbero essere qualunque cosa.
    assertNull(RecordingDate.fromFileName("registrazione 250922.m4a"))
    // Un mese tredici non e' un mese.
    assertNull(RecordingDate.fromFileName("AUD-20251322-WA0001.opus"))
  }

  // --- la scelta ---

  @Test
  fun `i metadati vincono sul nome, il nome sulla data del file`() {
    val fromMetadata = RecordingDate.resolve("20250921T090000.000Z", "AUD-20250922-WA0001.opus", millis(2025, 9, 30), today, rome)
    assertEquals(RecordedOn(LocalDate.of(2025, 9, 21), RecordingDateSource.METADATA), fromMetadata)

    val fromName = RecordingDate.resolve(null, "AUD-20250922-WA0001.opus", millis(2025, 9, 30), today, rome)
    assertEquals(RecordedOn(sept22, RecordingDateSource.FILE_NAME), fromName)

    val fromModified = RecordingDate.resolve(null, "Voce 001.m4a", millis(2025, 9, 30), today, rome)
    assertEquals(RecordedOn(LocalDate.of(2025, 9, 30), RecordingDateSource.FILE_MODIFIED), fromModified)

    val nothing = RecordingDate.resolve(null, "Voce 001.m4a", null, today, rome)
    assertEquals(RecordedOn(today, RecordingDateSource.TODAY), nothing)
  }

  @Test
  fun `le date impossibili si scartano e si passa alla fonte dopo`() {
    // Lo zero di MP4 (1904) e quello di Unix (1970): un file senza data, non una lezione antica.
    val mp4Zero = RecordingDate.resolve("19040101T000000.000Z", "AUD-20250922-WA0001.opus", null, today, rome)
    assertEquals(RecordingDateSource.FILE_NAME, mp4Zero.source)
    val unixZero = RecordingDate.resolve("19700101T000000.000Z", "Voce 001.m4a", millis(2025, 9, 30), today, rome)
    assertEquals(RecordingDateSource.FILE_MODIFIED, unixZero.source)
    // Un orologio avanti di un anno.
    val future = RecordingDate.resolve("20270101T100000.000Z", "Voce 001.m4a", null, today, rome)
    assertEquals(RecordedOn(today, RecordingDateSource.TODAY), future)
    // Oggi va bene: e' la lezione di stamattina.
    assertEquals(RecordingDateSource.METADATA, RecordingDate.resolve("20260923T080000.000Z", "x.m4a", null, today, rome).source)
  }

  @Test
  fun `le registrazioni di giorni diversi fanno una sessione per giorno, in ordine`() {
    data class Part(val name: String, val day: LocalDate?)
    val parts = listOf(
      Part("b", LocalDate.of(2025, 9, 23)),
      Part("a", LocalDate.of(2025, 9, 22)),
      Part("c", LocalDate.of(2025, 9, 22)),
      Part("senza data", null),
    )
    val groups = RecordingDate.groupByDay(parts, { it.day }, today)

    assertEquals(listOf(LocalDate.of(2025, 9, 22), LocalDate.of(2025, 9, 23)), groups.map { it.first })
    // Chi non ha data va col giorno piu' vecchio, non con oggi.
    assertEquals(listOf("a", "c", "senza data"), groups[0].second.map { it.name })
    assertEquals(listOf("b"), groups[1].second.map { it.name })
  }

  @Test
  fun `senza nessuna data le parti stanno insieme, oggi`() {
    val groups = RecordingDate.groupByDay(listOf("a", "b"), { null }, today)
    assertEquals(listOf(today to listOf("a", "b")), groups)
  }

  // --- il momento, per le date della nota ---

  @Test
  fun `i metadati danno anche l'ora`() {
    val utc = java.time.Instant.parse("2025-09-22T10:15:00Z").toEpochMilli()
    assertEquals(utc, RecordingDate.parseMetadataInstant("20250922T101500.000Z", rome))
    assertEquals(utc, RecordingDate.parseMetadataInstant("2025-09-22T12:15:00+02:00", rome))
    // Senza fuso e' l'ora di chi ha registrato: a Roma, in settembre, due ore avanti.
    assertEquals(utc, RecordingDate.parseMetadataInstant("20250922T121500", rome))
    // Solo il giorno: niente ora da dare.
    assertNull(RecordingDate.parseMetadataInstant("2025 09 22", rome))
  }

  @Test
  fun `il momento viene dalla stessa fonte del giorno`() {
    val metadata = RecordingDate.resolve("20250922T101500.000Z", "Voce 001.m4a", null, today, rome)
    assertEquals(java.time.Instant.parse("2025-09-22T10:15:00Z").toEpochMilli(), RecordingDate.momentOf(metadata, "20250922T101500.000Z", null, rome))

    val fromName = RecordingDate.resolve(null, "Registrazione_20250922_101500.m4a", null, today, rome)
    assertEquals(millis(2025, 9, 22), RecordingDate.momentOf(fromName, null, null, rome))

    val modified = millis(2025, 9, 30) + 3_600_000
    val fromModified = RecordingDate.resolve(null, "audio.m4a", modified, today, rome)
    assertEquals(modified, RecordingDate.momentOf(fromModified, null, modified, rome))

    // Niente di vero: nessun momento, la nota resta col momento dell'import.
    val nothing = RecordingDate.resolve(null, "audio.m4a", null, today, rome)
    assertNull(RecordingDate.momentOf(nothing, null, null, rome))
  }

  private fun millis(year: Int, month: Int, day: Int): Long =
    LocalDate.of(year, month, day).atTime(12, 0).atZone(rome).toInstant().toEpochMilli()
}
