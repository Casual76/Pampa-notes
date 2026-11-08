package dev.pampa.pampanotes.core.importing

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecordingOriginTest {
  @Test fun `offset metadata retains source wall clock fraction and exact instant`() {
    val origin = RecordingOrigin.fromMetadata("2025-09-22T23:59:10.125+0530")!!
    assertEquals(Instant.parse("2025-09-22T18:29:10.125Z").toEpochMilli(), origin.epochMs)
    assertEquals("2025-09-22T23:59:10.125", origin.local)
    assertEquals("+05:30", origin.zoneId)
    assertEquals("millisecond", origin.precision)
    assertEquals("media_metadata", origin.source)
  }

  @Test fun `unzoned metadata never uses the device zone`() {
    val origin = RecordingOrigin.fromMetadata("20250922T101500")!!
    assertEquals("2025-09-22T10:15", origin.local)
    assertNull(origin.epochMs)
    assertNull(origin.zoneId)
    assertEquals("second", origin.precision)
  }

  @Test fun `minute metadata and recorder filenames never acquire seconds`() {
    assertEquals("minute", RecordingOrigin.fromMetadata("2025-09-22T10:15Z")!!.precision)
    val named = RecordingOrigin.fromFileName("Voce 001_250922_1015.m4a")!!
    assertEquals("2025-09-22T10:15", named.local)
    assertEquals("minute", named.precision)
    assertNull(named.epochMs)
    assertNull(named.zoneId)
    assertEquals("second", RecordingOrigin.fromFileName("Registrazione_20250922_101503.m4a")!!.precision)
  }

  @Test fun `date only metadata and WhatsApp filenames remain days never noon`() {
    listOf(RecordingOrigin.fromMetadata("20250922")!!,
      RecordingOrigin.fromFileName("AUD-20250922-WA0001.opus")!!).forEach {
      assertEquals("2025-09-22", it.local)
      assertEquals("day", it.precision)
      assertNull(it.epochMs)
      assertNull(it.zoneId)
    }
  }

  @Test fun `file modified is at most a day and unknown has no evidence`() {
    val date = LocalDate.parse("2025-09-22")
    val origin = RecordingOrigin.resolve(RecordedOn(date, RecordingDateSource.FILE_MODIFIED), null, "Voice.m4a")!!
    assertEquals("file_modified", origin.source)
    assertEquals("day", origin.precision)
    assertNull(origin.epochMs)
    assertEquals("2025-09-22", origin.local)
    assertNull(RecordingOrigin.resolve(RecordedOn(date, RecordingDateSource.TODAY), null, "Voice.m4a"))
    assertNotNull(RecordingDate.noonOf(date, ZoneId.of("UTC"))) // sorting compatibility only
  }

  @Test fun `extensionless dotted dates retain only the attested day`() {
    for (name in listOf("2025.09.22", "22.09.2025", "2025.09.22.m4a", "2025.09.22.3gp", "Voce_2025.09.22")) {
      val origin = RecordingOrigin.fromFileName(name)!!
      assertEquals(name, "2025-09-22", origin.local)
      assertEquals("day", origin.precision)
      assertNull(origin.epochMs)
      assertNull(origin.zoneId)
      assertEquals("filename", origin.source)
    }
    assertNull(RecordingOrigin.fromFileName("2025.02.30"))
  }

  @Test fun `Samsung embedded epoch is source evidence and mediaInfo fallback is not`() {
    val epoch = Instant.parse("2025-09-22T10:15:00Z").toEpochMilli()
    val origin = RecordingOrigin.fromSamsungEntry("media/3@${(epoch / 1000).toString(16)}_abc.m4a", epoch + 86_400_000)!!
    assertEquals(epoch, origin.epochMs)
    assertEquals("second", origin.precision)
    assertEquals("samsung_recording", origin.source)
    assertNull(origin.zoneId)
    assertNull(origin.local)
    // SdocxRecording.createdAtMillis can have an entirely different mediaInfo rewrite timestamp.
    assertNull(RecordingOrigin.fromSamsungEntry("media/3@unknown_abc.m4a", epoch))
  }

  @Test fun `invalid dates offsets and ambiguous DST never yield guessed instants`() {
    assertNull(RecordingOrigin.fromMetadata("2025-02-30T10:15Z"))
    assertNull(RecordingOrigin.fromMetadata("2025-09-22T10:15+25:00"))
    assertNull(RecordingOrigin.fromMetadata("2025-09-22T10:15+01:00[Europe/Rome]"))
    assertNull(RecordingOrigin.fromMetadata("2025-10-26T02:30[Europe/Rome]")!!.epochMs)
    assertNull(RecordingOrigin.fromMetadata("2025-03-30T02:30[Europe/Rome]")!!.epochMs)
    assertNotNull(RecordingOrigin.fromMetadata("2025-09-22T10:15[Europe/Rome]")!!.epochMs)
  }

  @Test fun `share rewritten metadata does not escape through origin`() {
    val now = Instant.parse("2025-09-23T12:00:00Z").toEpochMilli()
    val metadata = "2025-09-23T11:59:00Z"
    val on = RecordingDate.resolve(metadata, "AUD-20250922-WA0001.opus", null,
      today = LocalDate.parse("2025-09-23"), zone = ZoneId.of("UTC"), durationMs = 3_600_000, now = now)
    val origin = RecordingOrigin.resolve(on, metadata, "AUD-20250922-WA0001.opus")!!
    assertEquals("filename", origin.source)
    assertEquals("day", origin.precision)
    assertNull(origin.epochMs)
  }
}
