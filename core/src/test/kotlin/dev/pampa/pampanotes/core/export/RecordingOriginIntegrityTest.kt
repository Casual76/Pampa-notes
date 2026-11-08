package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.importing.PampaBundleRecordingOrigin
import dev.pampa.pampanotes.core.importing.RecordingOrigin
import org.junit.Assert.*
import org.junit.Test

class RecordingOriginIntegrityTest {
  private fun part(origin: RecordingOrigin?) = AudioPartEntity("p", "s", 0, "p.wav", "p.wav",
    "audio/wav", 1, 1000, "a".repeat(64), 1, recordedAtEpochMs = origin?.epochMs,
    recordedAtLocal = origin?.local, recordedAtZoneId = origin?.zoneId,
    recordedAtSource = origin?.source, recordedAtPrecision = origin?.precision)
  private fun origin(part: AudioPartEntity) = RecordingOrigin(part.recordedAtEpochMs, part.recordedAtLocal,
    part.recordedAtZoneId, part.recordedAtSource, part.recordedAtPrecision)

  @Test fun `contradictory imported origin remains evidence but cannot print a precise clock`() {
    val inconsistent = RecordingOrigin(1793523600000L, "2026-11-01T11:00:00", "Europe/Rome", "media_metadata", "millisecond")
    val saved = PampaBundleRecordingOrigin.retain(part(null), part(inconsistent))
    assertEquals(inconsistent, origin(saved))
    assertEquals(0, origin(saved).evidenceQuality())
    val labels = ExportLabels()
    val clock = SpeechClock.label(origin(saved), 0, labels)
    assertTrue(clock.contains(labels.clockTimeUnknown))
    assertFalse(clock.contains("10:00:00"))
    assertFalse(clock.contains("11:00:00"))
  }

  @Test fun `nanosecond metadata upgrades second precision consistently at stored millisecond boundary`() {
    val old = RecordingOrigin.fromMetadata("2026-11-01T10:00:00+01:00")!!
    val next = RecordingOrigin.fromMetadata("2026-11-01T10:00:00.123456789+01:00")!!
    assertTrue(next.evidenceQuality() > old.evidenceQuality())
    val saved = PampaBundleRecordingOrigin.retain(part(old), part(next))
    assertEquals(next, origin(saved))
    assertEquals("2026-11-01T10:00:00.123456789", saved.recordedAtLocal)
    assertTrue(SpeechClock.label(origin(saved), 0).contains("10:00:00.123"))
  }

  @Test fun `numeric dotted filename suffix is time evidence with or without a real extension`() {
    for ((name, precision) in listOf("2025.09.22_10.15" to "minute", "2025.09.22_10.15.30" to "second")) {
      val plain = RecordingOrigin.fromFileName(name)!!
      assertEquals(precision, plain.precision)
      for (extension in listOf("m4a", "MP3", "3gp")) assertEquals(plain, RecordingOrigin.fromFileName("$name.$extension"))
      assertNull(plain.epochMs)
      assertNull(plain.zoneId)
    }
  }
}
