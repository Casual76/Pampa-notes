package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.transcription.*
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PampaProviderDurationTimingTest {
  @get:Rule val temp = TemporaryFolder()
  private val audio by lazy { temp.newFolder("audio") }
  private val sources by lazy { temp.newFolder("sources") }
  private val folder = FolderEntity("synthetic-folder", "Synthetic subject", createdAt = 1, updatedAt = 1)

  // Only these deidentified duration/start/end triples come from the incident observations.
  // All IDs, text, words, provider payloads and bytes below are invented test content.
  // The byte fixtures exercise lossless transport, not media decoding or acoustic accuracy.
  private data class Observation(val duration: Long, val start: Long, val end: Long, val measured: Boolean = true)
  private val observed = listOf(
    Observation(2283, 453, 2309),
    Observation(1183747, 1158141, 1183795),
    Observation(1527379, 1523300, 1527500, measured = false),
  )

  private fun graph(id: String, observation: Observation): PampaBundleNote {
    val note = NoteEntity("note-$id", folder.id, "Synthetic note $id", "  Invented body.\nSecond line.\n",
      createdAt = 1, updatedAt = 3)
    val sessionId = "session-$id"
    fun part(name: String, position: Int, duration: Long): AudioPartEntity {
      val file = File(audio, "$id-$name.bin").also { it.writeText("Invented attachment bytes: $id $name\n") }
      val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
      return AudioPartEntity("part-$id-$name", sessionId, position, file.name, file.name,
        "application/octet-stream", file.length(), duration, hash, 1)
    }
    val prefix = part("prefix", 0, 1000)
    val timed = part("timed", 1, observation.duration)
    val middle = observation.start + (observation.end - observation.start) / 2
    val response = buildJsonObject {
      put("text", "Synthetic sample"); put("language", "en")
      putJsonArray("segments") {
        add(buildJsonObject {
          put("start", observation.start / 1000.0); put("end", observation.end / 1000.0)
          put("text", "Synthetic sample"); put("speaker", "SYNTHETIC_SPEAKER")
          if (observation.measured) putJsonArray("words") {
            add(buildJsonObject { put("start", observation.start / 1000.0); put("end", middle / 1000.0); put("word", "Synthetic") })
            add(buildJsonObject { put("start", middle / 1000.0); put("end", observation.end / 1000.0); put("word", "sample") })
          }
        })
      }
    }
    // Exercise both compatible provider words and segment-only cloud responses. The assembler
    // estimates words only for the latter, exactly as the recording writer does.
    val parsed = if (observation.measured) VerboseJson.parse(response)
      else VerboseJson.parseExactText(response, "  Synthetic sample\n")
    val stitched = parsed.segments.map { StitchedSegment(it.startMs, it.endMs, it.text,
      it.noSpeechProb, it.avgLogProb, 0, it.words, it.speaker) }
    val assembled = SessionAssembler.assemble(listOf(
      PartTranscript(prefix, "", emptyList(), "en"),
      PartTranscript(timed, parsed.text, stitched, parsed.language)), "synthetic-provider", "synthetic-model")
    val raw = TranscriptEntity("raw-$id", sessionId, TranscriptKind.RAW, "synthetic-provider", "synthetic-model",
      "en", assembled.text, wordCount = 2, createdAt = 2)
    val alternate = raw.copy(id = "alternate-$id", text = "  A different, invented RAW history.\n", createdAt = 3)
    val refined = raw.copy(id = "refined-$id", kind = TranscriptKind.REFINED, parentId = raw.id,
      text = "An invented refinement.", preset = "CUSTOM", promptHash = "synthetic-prompt", createdAt = 4)
    val segments = assembled.segments.map { SegmentEntity(transcriptId = raw.id, partId = it.partId,
      indexInPart = it.indexInPart, partStartMs = it.partStartMs, partEndMs = it.partEndMs,
      sessionStartMs = it.sessionStartMs, sessionEndMs = it.sessionEndMs, text = it.text,
      noSpeechProb = it.noSpeechProb, avgLogProb = it.avgLogProb, wordsJson = it.wordsEncoded,
      wordsEstimated = it.wordsEstimated, speaker = it.speaker) }
    val session = SessionEntity(sessionId, note.id, date = "2026-01-01", position = 0,
      activeTranscriptId = refined.id, createdAt = 1, updatedAt = 3,
      voiceNames = VoiceNames.encode(mapOf((timed.id + "|SYNTHETIC_SPEAKER") to "Invented voice")))
    return PampaBundleNote(note, tags = listOf("synthetic"), sessions = listOf(session),
      parts = listOf(prefix, timed), transcripts = listOf(raw, alternate, refined),
      segments = segments + segments.map { it.copy(transcriptId = alternate.id) })
  }

  private fun write(value: PampaBundleNote, native: Boolean = true, audioDir: File = audio): File {
    val sessions = value.sessions.mapIndexed { index, session ->
      val selected = value.transcripts.firstOrNull { it.id == session.activeTranscriptId }
      val raw = value.transcripts.firstOrNull { it.id == selected?.parentId } ?: selected
      val ordered = value.parts.filter { it.sessionId == session.id }.sortedBy { it.position }
      val offsets = SessionAssembler.offsets(ordered.map { SessionAssembler.Part(it.id, it.durationMs) })
      val parts = ordered.map { part ->
        ExportPart(part.id, part.originalName, part.fileName, part.durationMs, offsets.getValue(part.id), part.sizeBytes)
      }
      ExportSession(session.id, index + 1, session.title, session.date, parts, selected, raw,
        value.segments.filter { it.transcriptId == raw?.id })
    }
    val export = ExportNote(value.note, listOf(folder.name), value.tags, emptyList(), sessions, native = value)
    val set = ExportSet("Synthetic subject", "synthetic", listOf(export), "Pampa Notes test", 1, folders = listOf(folder))
    return temp.newFile().also { file -> file.outputStream().use {
      if (native) PampaNoteWriter(audioDir, sources).write(set, it)
      else BundleWriter(audioDir, sources).write(set, ExportOptions(includeAudio = true), it)
    } }
  }

  private fun assertPreserved(expected: PampaBundleNote, actual: PampaBundleNote) {
    assertEquals(expected.note.copy(bundleBodyBase = null, bundleNativeAdopted = null),
      actual.note.copy(bundleBodyBase = null, bundleNativeAdopted = null))
    assertEquals(expected.tags, actual.tags)
    assertEquals(expected.sessions, actual.sessions)
    assertEquals(expected.transcripts, actual.transcripts)
    assertEquals(expected.segments, actual.segments)
    assertEquals(expected.parts.map { it.copy(fileName = "", archivedAt = 0) },
      actual.parts.map { it.copy(fileName = "", archivedAt = 0) })
  }

  @Test fun observedNumericalBoundsSurviveNativeAndSelectedZipPipelines() {
    for ((index, observation) in observed.withIndex()) for (native in listOf(true, false)) {
      val original = graph("observed-$index-$native", observation)
      val segment = original.segments.first()
      assertEquals(observation.start, segment.partStartMs)
      assertEquals(observation.end, segment.partEndMs)
      assertEquals(!observation.measured, segment.wordsEstimated)
      assertEquals(1000 + observation.end, segment.sessionEndMs)
      val archive = write(original, native)
      val read = PampaBundleArchive.read(archive)
      val received = read.data.notes.single()
      // PN carries all histories. The older AI ZIP deliberately carries only the selected
      // refinement and its RAW parent; this test preserves that existing selection contract.
      val expected = if (native) original else original.copy(
        transcripts = original.transcripts.filterNot { it.id.startsWith("alternate-") },
        segments = original.segments.filterNot { it.transcriptId.startsWith("alternate-") })
      assertPreserved(expected, received)
      val stagedAudio = temp.newFolder()
      val staging = PampaBundleStaging(stagedAudio, temp.newFolder())
      try {
        val stored = staging.stage(archive, read.root, received.attachments, {}, {})
        original.parts.forEach { part ->
          assertArrayEquals(File(audio, part.fileName).readBytes(),
            File(stagedAudio, stored.getValue(true to part.id)).readBytes())
        }
        staging.requireProtected()
        val incoming = received.copy(parts = received.parts.map { it.copy(fileName = stored.getValue(true to it.id)) })
        val merged = PampaBundleCloudSources.merge(null, incoming, preserveLocalNote = false)
        assertPreserved(expected, merged)
        PampaBundleArchive.validate(read.data.copy(notes = listOf(merged)))
        assertPreserved(expected, PampaBundleArchive.read(write(merged, native, stagedAudio)).data.notes.single())
        assertPreserved(expected, PampaBundleCloudSources.merge(merged, incoming, preserveLocalNote = false))
      } finally { staging.cleanup() }
    }
  }

  @Test fun observationsAreIndependentOfDurationRatherThanAnIncidentSizedTolerance() {
    val original = graph("independent", Observation(1000, 2000, 1000000))
    assertPreserved(original, PampaBundleArchive.read(write(original)).data.notes.single())
    // The start itself is beyond duration metadata; no clipping or metadata extension is used.
    val merged = PampaBundleCloudSources.merge(null, original, preserveLocalNote = false)
    assertPreserved(original, merged)
  }

  private fun changedSegment(value: PampaBundleNote, change: (SegmentEntity) -> SegmentEntity) =
    value.copy(segments = value.segments.map(change))

  @Test fun negativeReversedMalformedOffsetsReferencesAndDuplicateIdentitiesStayRejected() {
    val original = PampaBundleArchive.read(write(graph("hostile", observed.first()))).data.notes.single()
    val bad = listOf(
      changedSegment(original) { it.copy(partStartMs = -1) },
      changedSegment(original) { it.copy(partEndMs = it.partStartMs - 1) },
      changedSegment(original) { it.copy(sessionStartMs = it.sessionStartMs + 1) },
      changedSegment(original) { it.copy(sessionEndMs = it.sessionEndMs + 1) },
      changedSegment(original) { it.copy(indexInPart = -1) },
      changedSegment(original) { it.copy(transcriptId = "nonexistent-synthetic-raw") },
      changedSegment(original) { it.copy(wordsJson = "-454,0,negative") },
      changedSegment(original) { it.copy(wordsJson = "100,99,reversed") },
      changedSegment(original) { it.copy(wordsJson = "bad,100,malformed") },
      changedSegment(original) { it.copy(wordsJson = "0,9223372036854775808,unrepresentable") },
      changedSegment(original) { it.copy(wordsJson = "0," + Long.MAX_VALUE + ",overflow") },
      original.copy(segments = original.segments + original.segments.first()),
      original.copy(parts = original.parts.map { it.copy(position = 0) }),
    )
    for (value in bad) {
      assertThrows(IllegalArgumentException::class.java) {
        PampaBundleArchive.validate(PampaBundleData(folders = listOf(folder), notes = listOf(value)))
      }
      // Typed export scopes segment membership before serialization. Missing foreign IDs
      // therefore exercise the archive validator/reader directly, independently of scoping.
      if (value.segments.all { s -> value.parts.any { it.id == s.partId } && value.transcripts.any { it.id == s.transcriptId } }) {
        assertThrows(IllegalArgumentException::class.java) { write(value) }
      }
    }
    val missingPart = changedSegment(original) { it.copy(partId = "nonexistent-synthetic-part") }
    assertEquals("Missing segment part", assertThrows(IllegalStateException::class.java) {
      PampaBundleArchive.validate(PampaBundleData(folders = listOf(folder), notes = listOf(missingPart)))
    }.message)
    val alternate = original.transcripts.single { it.id.startsWith("alternate-") }
    val otherSession = original.sessions.single().copy(id = "other-synthetic-session", position = 1,
      activeTranscriptId = alternate.id, voiceNames = null)
    val wrongSession = original.copy(sessions = original.sessions + otherSession,
      transcripts = original.transcripts.map { if (it.id == alternate.id) it.copy(sessionId = otherSession.id) else it },
      segments = original.segments.filter { it.transcriptId == alternate.id })
    assertEquals("Invalid segment timing/reference", assertThrows(IllegalArgumentException::class.java) {
      PampaBundleArchive.validate(PampaBundleData(folders = listOf(folder), notes = listOf(wrongSession)))
    }.message)
  }

  // A schema-3 ZIP permits hostile native metadata to reach the reader independently of the
  // current writer's validation; all contents remain synthetic and ZIP CRCs are recomputed.
  private fun rewriteNative(archive: File, change: (PampaBundleData) -> PampaBundleData): File =
    temp.newFile().also { target ->
      ZipFile(archive).use { input -> ZipOutputStream(target.outputStream()).use { output ->
        input.entries().asSequence().forEach { entry ->
          output.putNextEntry(ZipEntry(entry.name))
          val bytes = input.getInputStream(entry).use { it.readBytes() }
          if (entry.name.endsWith("/native.json")) {
            val data = PampaBundleArchive.json.decodeFromString(PampaBundleData.serializer(), bytes.toString(Charsets.UTF_8))
            output.write(PampaBundleArchive.json.encodeToString(change(data)).toByteArray(Charsets.UTF_8))
          } else output.write(bytes)
          output.closeEntry()
        }
      } }
    }

  @Test fun readerIndependentlyRejectsCorruptOffsetsWordIntervalsAndReferences() {
    val original = graph("reader-hostile", observed.first())
    val archive = write(original, native = false)
    val mutations: List<(PampaBundleNote) -> PampaBundleNote> = listOf(
      { value -> changedSegment(value) { it.copy(sessionEndMs = -1) } },
      { value -> changedSegment(value) { it.copy(wordsJson = "-454,0,negative") } },
      { value -> changedSegment(value) { it.copy(wordsJson = "0," + Long.MAX_VALUE + ",overflow") } },
      { value -> changedSegment(value) { it.copy(transcriptId = "nonexistent-synthetic-raw") } },
    )
    for (mutation in mutations) {
      val corrupt = rewriteNative(archive) { data -> data.copy(notes = data.notes.map(mutation)) }
      assertThrows(IllegalArgumentException::class.java) { PampaBundleArchive.read(corrupt) }
    }
  }

  @Test fun maximumSessionTimestampIsPreservedAndTheNextMillisecondIsRejected() {
    val original = graph("long-boundary", observed.first())
    val offset = Long.MAX_VALUE - 10000
    val boundary = original.copy(parts = original.parts.map {
      if (it.position == 0) it.copy(durationMs = offset) else it.copy(durationMs = 1000)
    }, segments = original.segments.map { it.copy(partStartMs = 100, partEndMs = 10000,
      sessionStartMs = offset + 100, sessionEndMs = Long.MAX_VALUE, wordsJson = "-100,9900,whole") })
    assertPreserved(boundary, PampaBundleArchive.read(write(boundary)).data.notes.single())
    assertPreserved(boundary, PampaBundleCloudSources.merge(null, boundary, preserveLocalNote = false))
    val wordOverflow = changedSegment(boundary) { it.copy(wordsJson = "-100,9901,whole") }
    assertThrows(IllegalArgumentException::class.java) { write(wordOverflow) }
    val segmentOverflow = changedSegment(boundary) { it.copy(partEndMs = 10001, sessionEndMs = Long.MIN_VALUE) }
    assertThrows(IllegalArgumentException::class.java) { write(segmentOverflow) }
    val durationOverflow = original.copy(parts = original.parts.map {
      if (it.position == 0) it.copy(durationMs = Long.MAX_VALUE) else it
    })
    assertThrows(ArithmeticException::class.java) { write(durationOverflow) }
  }

  @Test fun mergeRechecksRetainedSegmentAndWordRepresentabilityAtTheNewOffset() {
    val original = graph("merge-overflow", observed.first())
    val incoming = original.copy(
      parts = original.parts.filter { it.position == 0 }.map { it.copy(durationMs = Long.MAX_VALUE - 10000) },
      transcripts = emptyList(), segments = emptyList(),
      sessions = original.sessions.map { it.copy(activeTranscriptId = null, voiceNames = null) })
    val badLocals = listOf(
      changedSegment(original) { it.copy(wordsJson = "0,50000,whole") },
      changedSegment(original) { it.copy(partEndMs = 50000, sessionEndMs = 51000) },
    )
    for (local in badLocals) {
      val acceptedBeforeRebase = PampaBundleArchive.read(write(local)).data.notes.single()
      assertThrows(IllegalArgumentException::class.java) {
        PampaBundleCloudSources.merge(acceptedBeforeRebase, incoming, preserveLocalNote = false)
      }
    }
  }

  @Test fun portableTimingBoundsAgreeWithUnboundedIntegerArithmetic() {
    val original = graph("arithmetic", observed.first()).segments.first()
    val maximum = BigInteger.valueOf(Long.MAX_VALUE)
    val times = listOf(Long.MIN_VALUE, -454L, -453L, -1L, 0L, 1L, 453L, 2309L,
      Long.MAX_VALUE - 1453, Long.MAX_VALUE - 453, Long.MAX_VALUE)
    for (offset in listOf(0L, 1000L, Long.MAX_VALUE - 2309)) for (from in times) for (to in times) {
      val segment = original.copy(wordsJson = "$from,$to,synthetic")
      val absoluteStart = BigInteger.valueOf(segment.partStartMs).add(BigInteger.valueOf(from))
      val absoluteEnd = BigInteger.valueOf(segment.partStartMs).add(BigInteger.valueOf(to))
      val sessionEnd = absoluteEnd.add(BigInteger.valueOf(offset))
      val expected = absoluteStart.signum() >= 0 && absoluteEnd >= absoluteStart && sessionEnd <= maximum
      val actual = runCatching { PampaBundleTimings.validate(segment, offset) }.isSuccess
      assertEquals("offset=$offset from=$from to=$to", expected, actual)
    }
  }
}
