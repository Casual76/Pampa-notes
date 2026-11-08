package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.importing.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipInputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingClockExportTest {
  @get:Rule val temp = TemporaryFolder()
  private val start = Instant.parse("2025-09-22T23:59:50Z").toEpochMilli()
  private fun origin(epoch: Long = start, zone: String = "Z", precision: String? = "second") =
    RecordingOrigin(epoch, Instant.ofEpochMilli(epoch).toString().removeSuffix("Z"), zone, "media_metadata", precision)
  private val raw = TranscriptEntity("r", "s", TranscriptKind.RAW, "fixture", "fixture", text = "Prima. Dopo.", wordCount = 2, createdAt = 999999)
  private fun part(id: String, origin: RecordingOrigin?, offset: Long) = ExportPart(id, "$id.wav", "$id.wav", 60_000, offset, 2, origin = origin)
  private fun session() = ExportSession("s", 1, "Interview", "2025-09-22",
    listOf(part("a", origin(), 0), part("b", origin(start + 3_600_000), 60_000)), raw, raw,
    listOf(SegmentEntity(transcriptId = "r", partId = "a", indexInPart = 0, partStartMs = 20_000, partEndMs = 21_000,
      sessionStartMs = 20_000, sessionEndMs = 21_000, text = "Prima."),
      SegmentEntity(transcriptId = "r", partId = "b", indexInPart = 0, partStartMs = 5_000, partEndMs = 6_000,
        sessionStartMs = 65_000, sessionEndMs = 66_000, text = "Dopo.")))
  private fun note(session: ExportSession = session()) = ExportNote(NoteEntity("n", "f", "Interview", "LEGACY BODY", createdAt = 5, updatedAt = 8),
    listOf("Personal"), listOf("legacy tag"), listOf(ExportSource("old.docx", SourceKind.DOCX, "hash", 5, null, SourceStatus.OK, 10)),
    listOf(session), selectedSessionIds = setOf("s"), personal = true)
  private fun set(note: ExportNote = note()) = ExportSet("Recordings", "recordings", listOf(note), "fixture", start, personal = true)

  @Test fun `each paragraph uses its own part origin preserving hour gap and midnight`() {
    val text = MarkdownWriter().singleNote(note(), ExportOptions(speechClock = true))
    assertTrue(text.contains("[00:20] [2025-09-23 00:00:10 UTC] Prima."))
    assertTrue(text.contains("[01:05] [2025-09-23 00:59:55 UTC] Dopo."))
    assertFalse(text.contains("00:00:55 UTC] Dopo"))
    assertEquals(listOf("a", "b"), TranscriptPieces.blocks(session(), ExportOptions(speechClock = true)).map { it.partId })
  }

  @Test fun `clock and relative toggles are independent and default clock is off`() {
    val normal = MarkdownWriter().singleNote(note(), ExportOptions())
    assertTrue(normal.contains("[00:20] Prima."))
    assertFalse(normal.contains("UTC"))
    val clockOnly = MarkdownWriter().singleNote(note(), ExportOptions(timestamps = false, speechClock = true))
    assertTrue(clockOnly.contains("[2025-09-23 00:00:10 UTC] Prima."))
    assertFalse(clockOnly.contains("[00:20]"))
    val neither = MarkdownWriter().singleNote(note(), ExportOptions(timestamps = false))
    assertFalse(neither.contains("[00:20]"))
    assertFalse(neither.contains("UTC"))
  }

  @Test fun `explicit IANA zone follows spring and fall DST offset changes`() {
    val spring = origin(Instant.parse("2025-03-30T00:59:50Z").toEpochMilli(), "Europe/Rome")
    assertEquals("2025-03-30 03:00:10 UTC+02:00 (Europe/Rome)", SpeechClock.label(spring, 20_000))
    val fall = origin(Instant.parse("2025-10-26T00:59:50Z").toEpochMilli(), "Europe/Rome")
    assertEquals("2025-10-26 02:00:10 UTC+01:00 (Europe/Rome)", SpeechClock.label(fall, 20_000))
    assertTrue(SpeechClock.label(origin(zone = "-03:30"), 0).contains("20:29:50 UTC-03:30"))
  }

  @Test fun `adjacent segments from different parts split and genuine word offset is part relative`() {
    val session = session()
    val first = session.segments.first().copy(wordsJson = "200,700,Prima.")
    val next = session.segments.last().copy(sessionStartMs = first.sessionEndMs, sessionEndMs = first.sessionEndMs + 1000)
    val blocks = TranscriptPieces.blocks(session.copy(segments = listOf(first, next)), ExportOptions(speechClock = true))
    assertEquals(listOf("a", "b"), blocks.map { it.partId })
    assertEquals(listOf(20_200L, 5_000L), blocks.map { it.partOffsetMs })
    assertEquals(20_000L, SpeechClock.partOffset(first.copy(wordsEstimated = true)))
    assertEquals(20_000L, SpeechClock.partOffset(first.copy(wordsJson = "200,700,Different")))
    assertEquals(20_000L, SpeechClock.partOffset(first.copy(wordsJson = "200,700,Pri")))
    assertEquals(20_000L, SpeechClock.partOffset(first.copy(wordsJson = "-500,700,Prima.")))
  }

  @Test fun `day file-modified unknown and minute precision never fabricate exact time`() {
    assertEquals("2025-09-22 · ora sconosciuta", SpeechClock.label(RecordingOrigin(local = "2025-09-22", source = "filename", precision = "day"), 86_400_000))
    assertEquals("2025-09-22 · ora sconosciuta", SpeechClock.label(origin().copy(source = "file_modified"), 0))
    assertFalse(SpeechClock.label(origin(precision = null), 0).contains("23:59"))
    assertEquals("data e ora sconosciute", SpeechClock.label(null, 0))
    assertEquals("data e ora sconosciute", SpeechClock.label(origin(), -1))
    val minute = SpeechClock.label(origin(precision = "minute"), 20_000)
    assertTrue(minute.startsWith("≈ 2025-09-23 00:00 UTC"))
    assertTrue(minute.endsWith("precisione al minuto"))
    assertFalse(minute.contains("00:00:10"))
  }

  @Test fun `unknown zone is qualified and never filled from device defaults`() {
    val local = RecordingOrigin.fromMetadata("2025-09-22T10:15:00")!!
    assertEquals("2025-09-22 10:16:00 · fuso della fonte sconosciuto", SpeechClock.label(local, 60_000))
    assertTrue(SpeechClock.label(origin().copy(zoneId = null), 0).endsWith("fuso della fonte sconosciuto"))
  }

  @Test fun `refined text never inherits RAW paragraph clocks`() {
    val refined = raw.copy(id = "refined", kind = TranscriptKind.REFINED, parentId = raw.id, text = "Rewritten body.")
    val text = MarkdownWriter().singleNote(note(session().copy(transcript = refined)), ExportOptions(speechClock = true))
    assertTrue(text.contains("Rewritten body."))
    assertTrue(text.contains("non sono orari attribuiti"))
    assertFalse(text.contains("UTC] Rewritten"))
  }

  @Test fun `clock options JSON roundtrip old defaults and target changes`() {
    val options = ExportOptions(speechClock = true, timestamps = false)
    assertEquals(options, ExportOptionsCodec.decode(ExportOptionsCodec.encode(options)))
    assertFalse(ExportOptionsCodec.decode("""{"timestamps":false}""").speechClock)
    assertTrue(options.withTarget(ExportTarget.PROJECT).speechClock)
    assertFalse(options.withTarget(ExportTarget.PROJECT).timestamps)
  }

  @Test fun `personal single bundle and loose files preserve material without school framing`() {
    val set = set()
    val options = ExportOptions(speechClock = true)
    val writer = BundleWriter(temp.root, temp.root)
    val single = writer.single(set, options)
    val out = ByteArrayOutputStream()
    writer.write(set, options, out)
    val zipTexts = ZipInputStream(out.toByteArray().inputStream()).use { zip -> buildMap {
      while (true) { val entry = zip.nextEntry ?: break; if (!entry.isDirectory) put(entry.name, zip.readBytes().toString(Charsets.UTF_8)) }
    } }
    val loose = writer.writeLoose(set, options.copy(format = ExportFormat.FILES), temp.newFolder()).filter { it.extension == "md" }.joinToString("\n") { it.readText() }
    listOf(single, loose, zipTexts.filterKeys { it.endsWith(".md") }.values.joinToString("\n")).forEach { text ->
      assertTrue(text.contains("LEGACY BODY"))
      assertTrue(text.contains("legacy tag"))
      assertTrue(text.contains("old.docx"))
      assertTrue(text.contains("Materiale associato"))
      assertFalse(text.contains("Appunti"))
      assertFalse(text.contains("lezioni"))
      assertTrue(text.contains("00:59:55 UTC"))
    }
    val transcriptFile = zipTexts.entries.first { it.key.contains("--2025-09-22") }.value
    assertTrue(transcriptFile.contains("collection: \"Interview\""))
    assertTrue(transcriptFile.contains("session_date: 2025-09-22"))
    assertTrue(transcriptFile.contains("material_file:"))
    assertFalse(transcriptFile.contains("notes_file:"))
  }

  @Test fun `native fallback roundtrip preserves all five origin fields and null old rows`() {
    val set = set(note(session().copy(parts = session().parts + part("old", null, 120_000))))
    val data = PampaBundleExport.build(set, BundleLayout(set, ExportOptions()), ExportOptions(), temp.root, temp.root) {}
    val restored = Json.decodeFromString<PampaBundleData>(Json.encodeToString(data)).notes.single()
    val first = restored.parts.first()
    assertEquals(start, first.recordedAtEpochMs)
    assertEquals(origin().local, first.recordedAtLocal)
    assertEquals("Z", first.recordedAtZoneId)
    assertEquals("media_metadata", first.recordedAtSource)
    assertEquals("second", first.recordedAtPrecision)
    assertNull(restored.parts.last().recordedAtEpochMs)
    assertNull(restored.parts.last().recordedAtLocal)
  }

  @Test fun `native entity serialization preserves date only minute unzoned and source null empty states`() {
    val day = AudioPartEntity("day", "s", 0, "day.wav", "day.wav", "audio/wav", 0, 1000, "hash", 999999,
      recordedAtLocal = "2025-09-22", recordedAtSource = "filename", recordedAtPrecision = "day")
    val minute = day.copy(id = "minute", recordedAtLocal = "2025-09-22T10:15", recordedAtSource = "media_metadata", recordedAtPrecision = "minute")
    listOf(day, minute, day.copy(id = "unknown", recordedAtLocal = null, recordedAtSource = null, recordedAtPrecision = null)).forEach {
      val restored = Json.decodeFromString<AudioPartEntity>(Json.encodeToString(it))
      assertEquals(it, restored)
      assertNull(restored.recordedAtEpochMs)
      assertNull(restored.recordedAtZoneId)
    }
    listOf<String?>(null, "", "{\"text\":\"  exact\\ntext\"}").forEach { source ->
      val transcript = raw.copy(cloudSourceJson = source)
      assertEquals(source, Json.decodeFromString<TranscriptEntity>(Json.encodeToString(transcript)).cloudSourceJson)
    }
  }
}
