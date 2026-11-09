package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.relay.*
import dev.pampa.pampanotes.core.transcription.VoiceNames
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual wire bytes, with no Android, Room database or provider doubles. */
class PampaNoteContainerTest {
  @get:Rule val temp = TemporaryFolder()
  private val audio by lazy { temp.newFolder("audio") }
  private val sources by lazy { temp.newFolder("sources") }
  private val folder = FolderEntity("leaf", "Physics", "root", tone = "Blue", icon = "book", createdAt = 11, updatedAt = 12)
  private val root = FolderEntity("root", "Semester", createdAt = 9, updatedAt = 10)
  private val note = NoteEntity("note", "leaf", "Original title", "# Body\nUnicode: è 📝\n", pinned = true,
    language = "it", createdAt = 100, updatedAt = 200, bundleBodyBase = "PRIVATE-BASE", bundleNativeAdopted = true)

  private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  private fun graph(bytes: ByteArray = byteArrayOf(1, 2, 3), includeFiles: Boolean = true): PampaBundleNote {
    val parts = listOf("a", "b").mapIndexed { index, id ->
      if (includeFiles) File(audio, "$id.m4a").writeBytes(bytes)
      AudioPartEntity(id, "session", index, "$id.m4a", "Recording $id.m4a", "audio/mp4", bytes.size.toLong(),
        1000, hash(bytes), 300 + index.toLong(), archivedAt = 999)
    }
    val original = SourceEntity("source", note.id, SourceKind.SDOCX, "Original.sdocx", "application/zip", bytes.size.toLong(),
      hash(bytes), "source.sdocx", extractedChars = 123, status = SourceStatus.PARTIAL, detail = "One blank page", importedAt = 400,
      archivedAt = 999, sdocxCreatedAtMillis = 50)
    val page = original.copy(id = "page", kind = SourceKind.IMAGE, originalName = "Page.png", mime = "image/png", storedFileName = "page.png", derivedFromId = "source")
    if (includeFiles) listOf(original, page).forEach { File(sources, it.storedFileName!!).writeBytes(bytes) }
    val raw = TranscriptEntity("raw1", "session", TranscriptKind.RAW, "custom", "whisperx", "it", "Hello world", wordCount = 2, createdAt = 500)
    val second = raw.copy(id = "raw2", text = "Alternative words", createdAt = 600)
    val refined = raw.copy(id = "refined1", kind = TranscriptKind.REFINED, text = "Hello, world!", parentId = raw.id,
      preset = "CLEAN", promptHash = "prompt-digest", createdAt = 550)
    val refined2 = refined.copy(id = "refined2", text = "Alternative, words!", parentId = second.id, createdAt = 650)
    val sessions = listOf(SessionEntity("session", note.id, "Lesson", "2026-11-08", 0, refined.id, 250, 700,
      transcribingOn = "PRIVATE-DEVICE", transcribingSince = 999,
      voiceNames = VoiceNames.encode(mapOf("a|SPEAKER_00" to "Teacher", "b|SPEAKER_01" to "Student"))))
    val segments = listOf(raw, second).flatMapIndexed { version, t -> parts.mapIndexed { index, p ->
      SegmentEntity(id = 1L + version * 2 + index, transcriptId = t.id, partId = p.id, indexInPart = 0, partStartMs = 100,
        partEndMs = 800, sessionStartMs = index * 1000L + 100, sessionEndMs = index * 1000L + 800,
        text = if (version == 0) "Hello world" else "Alternative words", noSpeechProb = 0.01f, avgLogProb = -0.2f,
        wordsJson = if (version == 0) "0,300,Hello\n300,700,world" else "0,350,Alternative\n350,700,words",
        wordsEstimated = version != 0, speaker = "SPEAKER_0$index")
    } }
    return PampaBundleNote(note, listOf("exam", "revision"), sessions, parts, listOf(raw, refined, second, refined2), segments, listOf(original, page))
  }

  private fun set(values: List<PampaBundleNote>, extraFolders: List<FolderEntity> = emptyList()): ExportSet = ExportSet(
    "Physics", "physics", values.map { n ->
      ExportNote(n.note, listOf("Semester", "Physics"), n.tags,
        n.sources.filter { it.derivedFromId == null }.map { ExportSource(it.originalName, it.kind, it.sha256, it.sizeBytes, it.storedFileName, it.status, it.extractedChars, it.id) },
        n.sessions.mapIndexed { index, s ->
          val selected = n.transcripts.find { it.id == s.activeTranscriptId }
          val raw = dev.pampa.pampanotes.core.repo.RawTranscripts.forSelection(n.transcripts.filter { it.sessionId == s.id }, selected?.id)
          ExportSession(s.id, index + 1, s.title, s.date, n.parts.filter { it.sessionId == s.id }.map { ExportPart(it.id, it.originalName, it.fileName, it.durationMs, it.position * 1000L, it.sizeBytes) },
            selected, raw, n.segments.filter { it.transcriptId == raw?.id })
        }, n.sources.filter { it.derivedFromId != null }.mapIndexed { index, s -> ExportImage(s.storedFileName!!, index + 1, s.sizeBytes, s.id) }, n)
    }, "Pampa Notes test", 123456789, folders = listOf(root, folder) + extraFolders)

  private fun write(values: List<PampaBundleNote>, checkpoint: () -> Unit = {}, codecOnly: Boolean = false, progress: (Float) -> Unit = {}): File = temp.newFile().also { file ->
    file.outputStream().use { out ->
      val writer = PampaNoteWriter(audio, sources, checkpoint)
      if (codecOnly) writer.writeContainer(set(values), out, progress) else writer.write(set(values), out, progress)
    }
  }
  private fun entries(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
    zip.entries().asSequence().associate { it.name to zip.getInputStream(it).use { input -> input.readBytes() } }
  }
  private fun rewrite(file: File, change: (String, ByteArray) -> Pair<String, ByteArray>?): File = temp.newFile().also { output ->
    ZipOutputStream(output.outputStream()).use { zip -> entries(file).forEach { (name, bytes) -> change(name, bytes)?.let { (path, content) ->
      zip.putNextEntry(ZipEntry(path)); zip.write(content); zip.closeEntry()
    } } }
  }
  private fun header(file: File, field: String, value: JsonElement): File = rewrite(file) { name, bytes ->
    name to if (name == PampaNoteContainer.HEADER) JsonObject(Json.parseToJsonElement(String(bytes)).jsonObject + (field to value)).toString().toByteArray() else bytes
  }
  private fun rejected(file: File, metadataOnly: Boolean = false) {
    assertThrows(Exception::class.java) { PampaBundleArchive.read(file, !metadataOnly) }
  }

  @Test fun `roundtrip all native histories dates voices selection and distinct IDs sharing bytes`() {
    val original = graph()
    val file = write(listOf(original))
    assertTrue(PampaBundleArchive.isBundle(file))
    val read = PampaBundleArchive.read(file)
    assertTrue(read.precise)
    assertEquals("", read.root)
    assertTrue(read.warnings.isEmpty())
    val value = read.data.notes.single()
    assertEquals(original.note.copy(bundleBodyBase = null, bundleNativeAdopted = null), value.note)
    assertEquals(original.tags, value.tags)
    assertEquals(original.transcripts, value.transcripts)
    assertEquals(original.segments, value.segments)
    assertEquals(original.sessions.map { it.copy(transcribingOn = null, transcribingSince = null) }, value.sessions)
    assertEquals(original.parts.map { it.copy(fileName = "", archivedAt = 0) }, value.parts)
    assertEquals(original.sources.map { it.copy(storedFileName = null, archivedAt = 0) }, value.sources)
    assertEquals(listOf(root, folder), read.data.folders)
    assertEquals(4, value.attachments.size)
    assertEquals(1, value.attachments.map { it.path }.distinct().size)
    assertEquals(4, value.attachments.map { it.audio to it.id }.distinct().size)
    value.attachments.forEach { a -> assertArrayEquals(byteArrayOf(1, 2, 3), entries(file).getValue(a.path!!)) }
    val metadata = entries(file).filterKeys { it.endsWith("json") || it.endsWith("jsonl") }.values.joinToString { String(it) }
    listOf("PRIVATE-BASE", "PRIVATE-DEVICE", "bundleBodyBase", "bundleNativeAdopted", "transcribingOn", "archivedAt", "source.sdocx").forEach {
      assertFalse("private/local field leaked: $it", metadata.contains(it))
    }
  }

  @Test fun `recording origins and exact cloud source survive null empty and large values`() {
    val base = graph()
    val exact = "{\"schema\":1,\"text\":\"" + "Caffè,  due spazi!  ".repeat(90_000) + "\"}"
    val first = base.parts.first()
    val parts = listOf(
      first.copy(recordedAtEpochMs = java.time.Instant.parse("2026-11-08T22:59:59.123Z").toEpochMilli(),
        recordedAtLocal = "2026-11-08T23:59:59.123", recordedAtZoneId = "Europe/Rome",
        recordedAtSource = "media_metadata", recordedAtPrecision = "millisecond"),
      base.parts.last().copy(recordedAtLocal = "2026-11-09", recordedAtSource = "filename", recordedAtPrecision = "day"),
      first.copy(id = "unknown-origin", position = 2),
      first.copy(id = "local-origin", position = 3, recordedAtLocal = "2026-11-08T14:32",
        recordedAtSource = "samsung_recording", recordedAtPrecision = "minute"),
    )
    val sources = listOf(null, "", exact, "{\"schema\":1,\"text\":\"  source\\nunchanged  \"}")
    val transcripts = base.transcripts.mapIndexed { index, transcript -> transcript.copy(cloudSourceJson = sources[index]) }
    // Deliberately opaque/future metadata exercises lossless blobs, not typed authorization.
    val file = write(listOf(base.copy(parts = parts, transcripts = transcripts)), codecOnly = true)
    val result = PampaBundleArchive.read(file, verifyAttachments = false).data.notes.single()
    assertEquals(parts.map { it.copy(fileName = "", archivedAt = 0) }, result.parts)
    assertEquals(transcripts, result.transcripts)
    assertNull(result.parts[1].recordedAtEpochMs)
    assertNull(result.parts[2].recordedAtPrecision)
    assertNull(result.parts[3].recordedAtZoneId)
    val stored = entries(file)
    assertTrue(stored.filterKeys { it.endsWith("jsonl") }.values.all { it.size <= 1024 * 1024 })
    assertArrayEquals(exact.toByteArray(), stored.getValue("blobs/" + hash(exact.toByteArray())))
    rejected(rewrite(file) { name, bytes -> name to if (name == "blobs/" + hash(exact.toByteArray()))
      bytes.copyOf().also { it[0] = '['.code.toByte() } else bytes }, metadataOnly = true)
  }

  @Test fun `older PN and schema three updates retain known origin and exact cloud source through reexport`() {
    val unknown = graph()
    val rich = unknown.copy(parts = unknown.parts.map { it.copy(recordedAtEpochMs = java.time.Instant.parse("2026-11-08T23:00:00.123Z").toEpochMilli(),
      recordedAtLocal = "2026-11-09T00:00:00.123", recordedAtZoneId = "Europe/Rome",
      recordedAtSource = "media_metadata", recordedAtPrecision = "millisecond") },
      transcripts = unknown.transcripts.map { it.copy(cloudSourceJson = if (it.id == "raw1") "{\"text\":\"  Exact cloud source  \"}" else "") })
    val local = PampaBundleArchive.read(write(listOf(rich), codecOnly = true)).data.notes.single()
    val oldZip = temp.newFile().also { f -> f.outputStream().use { BundleWriter(audio, sources).write(set(listOf(unknown)), ExportOptions(), it) } }
    val newKeys = setOf("recordedAtEpochMs", "recordedAtLocal", "recordedAtZoneId", "recordedAtSource", "recordedAtPrecision", "cloudSourceJson")
    fun strip(value: JsonElement): JsonElement = when (value) {
      is JsonObject -> JsonObject(value.filterKeys { it !in newKeys }.mapValues { strip(it.value) })
      is JsonArray -> JsonArray(value.map(::strip))
      else -> value
    }
    val legacy = rewrite(oldZip) { name, bytes -> name to if (name.endsWith("native.json"))
      strip(Json.parseToJsonElement(String(bytes, Charsets.UTF_8))).toString().toByteArray() else bytes }
    for (archive in listOf(write(listOf(unknown)), legacy)) {
      val incoming = PampaBundleArchive.read(archive).data.notes.single()
      val plan = PampaBundleLegacyReconcile.plan(local, incoming, publicLegacy = false)
      val merged = PampaBundleMerge.merge(plan.local, plan.incoming)
      // Restore the local storage references that staging/persistence supply in the app.
      val stored = merged.copy(parts = merged.parts.map { part -> part.copy(fileName = unknown.parts.single { it.id == part.id }.fileName) },
        sources = merged.sources.map { source -> source.copy(storedFileName = unknown.sources.single { it.id == source.id }.storedFileName) })
      val reexported = PampaBundleArchive.read(write(listOf(stored), codecOnly = true)).data.notes.single()
      assertEquals(rich.parts.map { it.recordedAtEpochMs }, reexported.parts.map { it.recordedAtEpochMs })
      assertEquals(rich.parts.map { it.recordedAtLocal }, reexported.parts.map { it.recordedAtLocal })
      assertEquals(rich.parts.map { it.recordedAtZoneId }, reexported.parts.map { it.recordedAtZoneId })
      assertEquals(rich.parts.map { it.recordedAtSource }, reexported.parts.map { it.recordedAtSource })
      assertEquals(rich.parts.map { it.recordedAtPrecision }, reexported.parts.map { it.recordedAtPrecision })
      assertEquals(local.transcripts.associate { it.id to it.cloudSourceJson }, reexported.transcripts.associate { it.id to it.cloudSourceJson })
    }
  }

  @Test fun `selected note includes ancestors and excludes unrelated folders`() {
    val n = PampaBundleNote(note)
    val output = temp.newFile()
    output.outputStream().use { PampaNoteWriter(audio, sources).write(set(listOf(n), listOf(FolderEntity("private", "PRIVATE-FOLDER", createdAt = 1, updatedAt = 2))), it) }
    assertEquals(setOf("root", "leaf"), PampaBundleArchive.read(output).data.folders.map { it.id }.toSet())
    assertFalse(entries(output).values.any { String(it).contains("PRIVATE-FOLDER") })
  }

  @Test fun `empty text only and untimed OpenRouter histories do not acquire synthetic timing`() {
    assertTrue(PampaBundleArchive.read(write(emptyList())).data.notes.isEmpty())
    val g = graph()
    val untimed = g.copy(transcripts = g.transcripts.map { it.copy(provider = "openrouter", model = "audio-model", text = "Unaligned text 📝") }, segments = emptyList())
    val read = PampaBundleArchive.read(write(listOf(untimed)))
    assertFalse(read.precise)
    assertTrue(read.data.notes.single().segments.isEmpty())
    assertEquals(untimed.transcripts, read.data.notes.single().transcripts)
    val plain = PampaBundleNote(note.copy(body = ""))
    assertEquals("", PampaBundleArchive.read(write(listOf(plain))).data.notes.single().note.body)
  }

  @Test fun `missing attachment declarations retain expected sizes hashes and identities`() {
    val g = graph(includeFiles = false)
    val read = PampaBundleArchive.read(write(listOf(g)), false)
    assertEquals(listOf(PampaBundleWarning.OMITTED_ATTACHMENTS), read.warnings)
    assertEquals(4, read.data.notes.single().attachments.size)
    assertTrue(read.data.notes.single().attachments.all { it.path == null && it.bytes == 3L && it.sha256 == hash(byteArrayOf(1, 2, 3)) })
    assertEquals(g.parts.map { it.id }, read.data.notes.single().parts.map { it.id })
  }

  @Test fun `writer requires full native graph and refuses known wrong local audio sizes hashes or paths`() {
    val g = graph()
    val incomplete = set(listOf(g)).let { it.copy(notes = it.notes.map { n -> n.copy(native = null) }) }
    assertThrows(IllegalArgumentException::class.java) { PampaNoteWriter(audio, sources).write(incomplete, ByteArrayOutputStream()) }
    listOf(g.parts.first().copy(sizeBytes = 8), g.parts.first().copy(sha256 = "a".repeat(64)), g.parts.first().copy(fileName = "../outside.m4a")).forEach { bad ->
      assertThrows(IllegalArgumentException::class.java) { write(listOf(g.copy(parts = listOf(bad) + g.parts.drop(1)))) }
    }
  }

  @Test fun `metadata read skips attachment content but staging and full read reject tampered bytes`() {
    val good = write(listOf(graph()))
    val path = PampaBundleArchive.read(good).data.notes.single().attachments.first().path!!
    val corrupt = rewrite(good) { name, bytes -> name to if (name == path) byteArrayOf(9, 9, 9) else bytes }
    assertEquals(1, PampaBundleArchive.read(corrupt, verifyAttachments = false).data.notes.size)
    rejected(corrupt)
    val a = PampaBundleArchive.read(corrupt, false).data.notes.single().attachments.first()
    assertThrows(IllegalArgumentException::class.java) {
      ZipFile(corrupt).use { zip -> zip.getInputStream(zip.getEntry(path)).use { PampaBundleArchive.verify(it, a.bytes, a.sha256, {}, ByteArrayOutputStream()) } }
    }
    // Both existing positional and trailing-lambda call forms remain source compatible.
    PampaBundleArchive.read(good, {})
    PampaBundleArchive.read(good) { }
    PampaBundleArchive.read(good, false) { }
  }

  @Test fun `metadata read rejects missing and declared size conflicts before media verification`() {
    val good = write(listOf(graph()))
    val path = PampaBundleArchive.read(good).data.notes.single().attachments.first().path!!
    rejected(rewrite(good) { name, bytes -> if (name == path) null else name to bytes }, true)
    rejected(rewrite(good) { name, bytes -> name to if (name == path) byteArrayOf(1, 2) else bytes }, true)
    rejected(header(good, "records", JsonPrimitive(999)), true)
    rejected(header(good, "blobs", JsonPrimitive(999)), true)
  }

  @Test fun `future versions remain recognizable but reject with version detail`() {
    val future = header(header(write(listOf(PampaBundleNote(note))), "version", JsonPrimitive(200)), "futureField", JsonPrimitive(true))
    assertTrue(PampaBundleArchive.isBundle(future))
    val failure = assertThrows(IllegalArgumentException::class.java) { PampaBundleArchive.read(future) }
    assertTrue(failure.message!!.contains("version 200"))
  }

  @Test fun `reject unsafe duplicate extra entries corrupt text and truncated metadata or ZIP`() {
    val good = write(listOf(graph()))
    rejected(rewrite(good) { name, bytes -> (if (name == PampaNoteContainer.HEADER) "../pampa-note.json" else name) to bytes })
    rejected(rewrite(good) { name, bytes -> name to if (name.endsWith("jsonl")) bytes.dropLast(1).toByteArray() else bytes })
    rejected(rewrite(good) { name, bytes -> name to if (name.endsWith("jsonl")) byteArrayOf(0xff.toByte(), 10) else bytes })
    val extra = temp.newFile()
    ZipOutputStream(extra.outputStream()).use { zip ->
      entries(good).forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
      zip.putNextEntry(ZipEntry("PRIVATE-UNREFERENCED")); zip.write(1); zip.closeEntry()
    }
    rejected(extra, true)
    val duplicate = temp.newFile()
    ZipOutputStream(duplicate.outputStream()).use { zip ->
      entries(good).forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
      zip.putNextEntry(ZipEntry(PampaNoteContainer.HEADER.uppercase())); zip.write("{}".toByteArray()); zip.closeEntry()
    }
    rejected(duplicate, true)
    val truncated = temp.newFile().also { it.writeBytes(good.readBytes().dropLast(30).toByteArray()) }
    assertFalse(PampaBundleArchive.isBundle(truncated))
    rejected(truncated)
    val textPath = PampaBundleArchive.read(good).data.notes.single().note.body.let { "blobs/${hash(it.toByteArray())}" }
    rejected(rewrite(good) { name, bytes -> name to if (name == textPath) bytes.map { (it.toInt() xor 1).toByte() }.toByteArray() else bytes }, true)
  }

  @Test fun `graph validation rejects bad timing cross history references and folder cycles`() {
    val g = graph()
    val bad = listOf(
      g.copy(segments = g.segments.map { it.copy(partEndMs = 1001) }),
      g.copy(segments = g.segments.map { it.copy(sessionStartMs = 99999) }),
      g.copy(segments = g.segments.map { it.copy(wordsJson = "-101,100,Hello") }),
      g.copy(segments = g.segments.map { it.copy(wordsJson = "0,${Long.MAX_VALUE},Hello") }),
      g.copy(transcripts = g.transcripts.map { if (it.id == "refined1") it.copy(parentId = "absent") else it }),
      g.copy(segments = g.segments + g.segments.first()),
    )
    bad.forEach { assertThrows(IllegalArgumentException::class.java) { write(listOf(it)) } }
    assertThrows(IllegalArgumentException::class.java) {
      PampaNoteWriter(audio, sources).write(set(listOf(g)).copy(folders = listOf(root.copy(parentId = "leaf"), folder)), ByteArrayOutputStream())
    }
  }

  @Test fun `forged native metadata cannot bypass timing reference and omission integrity checks`() {
    val good = write(listOf(graph()))
    val replacements = listOf(
      "\"partEndMs\":800" to "\"partEndMs\":1001",
      "\"activeTranscriptId\":\"refined1\"" to "\"activeTranscriptId\":\"missing-version\"",
      "\"derivedFromId\":\"source\"" to "\"derivedFromId\":\"page\"",
      "0,300,Hello" to "-101,300,Hello",
    )
    replacements.forEach { (before, after) ->
      var changed = false
      val forged = rewrite(good) { name, bytes -> name to if (name.endsWith("jsonl")) {
        val value = String(bytes)
        changed = changed || value.contains(before)
        value.replace(before, after).toByteArray()
      } else bytes }
      assertTrue(changed)
      rejected(forged, true)
    }
    audio.listFiles()!!.forEach { assertTrue(it.delete()) }
    sources.listFiles()!!.forEach { assertTrue(it.delete()) }
    val missing = write(listOf(graph(includeFiles = false)))
    rejected(rewrite(missing) { name, bytes -> name to if (name.endsWith("jsonl")) String(bytes).replace("\"bytes\":3,", "\"bytes\":4,").toByteArray() else bytes }, true)
  }

  @Test fun `large semester beyond old global JSON limit uses bounded entries and content dedup`() {
    val body = "x".repeat(1024 * 1024)
    val notes = (1..65).map { PampaBundleNote(note.copy(id = "note-$it", body = body)) }
    assertTrue(notes.sumOf { it.note.body.toByteArray().size.toLong() } > 64L * 1024 * 1024)
    val file = write(notes)
    val data = PampaBundleArchive.read(file).data
    assertEquals(65, data.notes.size)
    assertTrue(data.notes.all { it.note.body == body })
    assertEquals(1, entries(file).keys.count { it.startsWith("blobs/") })
    // Construct the oversized legacy payload incrementally. Asking the old global JSON writer to
    // materialize 65 MiB also requires several complete intermediate strings and can exhaust the
    // default test heap before the reader's actual size guard is reached.
    val seed = temp.newFile().also { f -> f.outputStream().use { BundleWriter(audio, sources).write(set(listOf(notes.first())), ExportOptions(), it) } }
    val legacy = temp.newFile().also { f ->
      ZipFile(seed).use { input -> ZipOutputStream(f.outputStream()).use { output ->
        input.entries().asSequence().forEach { entry ->
          output.putNextEntry(ZipEntry(entry.name))
          if (entry.name.endsWith("native.json")) {
            output.write("{\"version\":1,\"notes\":[".toByteArray())
            notes.forEachIndexed { index, item ->
              if (index > 0) output.write(','.code)
              output.write(PampaBundleArchive.json.encodeToString(PampaBundleNote.serializer(), item).toByteArray())
            }
            output.write("]}".toByteArray())
          } else input.getInputStream(entry).use { it.copyTo(output) }
          output.closeEntry()
        }
      } }
    }
    val failure = assertThrows(IllegalArgumentException::class.java) { PampaBundleArchive.read(legacy) }
    assertTrue(failure.message!!.contains("Text entry too large"))
  }

  @Test fun `many records split metadata chunks without losing ordering or dates`() {
    val base = graph()
    val size = 10000
    val segments = (0 until size).map { index -> base.segments.first().copy(id = index + 1L, indexInPart = index,
      partStartMs = 0, partEndMs = 1, sessionStartMs = 0, sessionEndMs = 1, text = "x", wordsJson = null) }
    val g = base.copy(segments = segments)
    val file = write(listOf(g))
    ZipFile(file).use { zip ->
      val chunks = zip.entries().asSequence().filter { it.name.startsWith("records/") }.toList()
      assertTrue(chunks.size > 1)
      assertTrue(chunks.all { it.size <= PampaNoteContainer.CHUNK_BYTES })
    }
    assertEquals(segments, PampaBundleArchive.read(file).data.notes.single().segments)
  }

  @Test fun `oversized segment text uses a blob while ordinary words stay in compressed chunks`() {
    val g = graph()
    val segments = listOf(g.segments.first().copy(text = "Long phrase.\n".repeat(4000), wordsJson = null))
    val file = write(listOf(g.copy(segments = segments)))
    assertEquals(segments, PampaBundleArchive.read(file).data.notes.single().segments)
    val records = entries(file).filterKeys { it.endsWith("jsonl") }.values.joinToString { String(it) }
    assertFalse(records.contains("Long phrase."))
    assertTrue(records.contains(hash(segments.single().text.toByteArray())))
  }

  @Test fun `timed lecture remains compact with unique words and per segment timing`() {
    val aac = requireNotNull(javaClass.getResourceAsStream("/pn/tone.m4a")).use { it.readBytes() }
    val base = graph(aac)
    val segments = (0 until 1500).map { index -> base.segments.first().copy(id = index + 1L, indexInPart = index,
      partStartMs = index * 1000L, partEndMs = index * 1000L + 700, sessionStartMs = index * 1000L,
      sessionEndMs = index * 1000L + 700, text = "Hello $index", wordsJson = "0,300,Hello\n300,700,$index") }
    val text = segments.joinToString(" ") { it.text }
    val g = base.copy(parts = base.parts.take(1).map { it.copy(durationMs = 1500000) }, sources = emptyList(),
      transcripts = base.transcripts.take(2).map { it.copy(text = text, wordCount = 3000) }, segments = segments,
      sessions = base.sessions.map { it.copy(voiceNames = VoiceNames.encode(mapOf("a|SPEAKER_00" to "Teacher"))) })
    val pn = write(listOf(g))
    val old = temp.newFile().also { file -> file.outputStream().use { BundleWriter(audio, sources).write(set(listOf(g)), ExportOptions(includeAudio = true), it) } }
    assertEquals(segments, PampaBundleArchive.read(pn).data.notes.single().segments)
    assertTrue("Native timed lecture should be smaller than AI ZIP", pn.length() < old.length())
    println("PN-MEASURE timed-lecture segments=${segments.size} words=3000 aiZip=${old.length()} pn=${pn.length()}")
  }

  @Test fun `cancellation works in hashing metadata and attachment copying with monotonic progress`() {
    val g = graph(ByteArray(2 * 1024 * 1024) { (it * 13).toByte() })
    for (threshold in listOf(0f, 0.46f, 0.56f)) {
      var progress = 0f
      val steps = mutableListOf<Float>()
      val failure = assertThrows(CancellationException::class.java) {
        write(listOf(g), { if (progress > threshold) throw CancellationException("cancel") }, progress = { progress = it; steps += it })
      }
      assertEquals("cancel", failure.message)
      assertTrue(steps.zipWithNext().all { it.first <= it.second })
      assertFalse(steps.contains(1f))
    }
    val steps = mutableListOf<Float>()
    val file = write(listOf(g), progress = { steps += it })
    assertEquals(0f, steps.first())
    assertEquals(1f, steps.last())
    assertTrue(steps.all { it in 0f..1f } && steps.zipWithNext().all { it.first <= it.second })
    var reads = 0
    assertThrows(CancellationException::class.java) { PampaBundleArchive.read(file, false) { if (++reads > 10) throw CancellationException() } }
  }

  @Test fun `attachment mutation during export fails rather than producing stale integrity metadata`() {
    val g = graph()
    var mutated = false
    assertThrows(IllegalArgumentException::class.java) {
      write(listOf(g), progress = { if (it >= 0.45f && !mutated) { File(audio, "a.m4a").writeBytes(byteArrayOf(8, 8, 8)); mutated = true } })
    }
    assertTrue(mutated)
  }

  @Test fun `update copy and multi hop retain entity graphs and local only histories`() {
    val first = write(listOf(graph()))
    val data = PampaBundleArchive.read(first).data
    val origin = PampaBundleImporter.remap(data, "origin")
    assertEquals(data.notes.single().note.id, origin.notes.single().note.id)
    val copy = PampaBundleImporter.remap(data, "independent-copy")
    assertNotEquals(origin.notes.single().note.id, copy.notes.single().note.id)
    assertEquals(2, copy.notes.single().parts.map { it.id }.distinct().size)
    assertEquals(2, copy.notes.single().sources.map { it.id }.distinct().size)
    PampaBundleArchive.validate(copy)
    // Recreate the existing staging boundary: verify each entity's bytes into a fresh local file.
    val staged = copy.copy(notes = copy.notes.map { n ->
      val names = mutableMapOf<Pair<Boolean, String>, String>()
      ZipFile(first).use { zip -> n.attachments.forEach { a ->
        val name = "stage-${a.id}.bin"
        File(if (a.audio) audio else sources, name).outputStream().use { out ->
          zip.getInputStream(zip.getEntry(a.path)).use { PampaBundleArchive.verify(it, a.bytes, a.sha256, {}, out) }
        }
        names[a.audio to a.id] = name
      } }
      n.copy(parts = n.parts.map { it.copy(fileName = names.getValue(true to it.id)) },
        sources = n.sources.map { it.copy(storedFileName = names.getValue(false to it.id)) })
    })
    val second = temp.newFile()
    second.outputStream().use { PampaNoteWriter(audio, sources).write(set(staged.notes).copy(folders = staged.folders), it) }
    val next = PampaBundleImporter.remap(PampaBundleArchive.read(second).data, "origin")
    assertEquals(copy, next)

    val incoming = origin.notes.single()
    val localRaw = incoming.transcripts.first { it.kind == TranscriptKind.RAW }.copy(id = "local-raw", text = "Local speech")
    val local = incoming.copy(note = incoming.note.copy(body = incoming.note.body + "\nLocal edit", bundleBodyBase = incoming.note.body),
      transcripts = incoming.transcripts + localRaw,
      segments = incoming.segments + incoming.segments.first().copy(id = 0, transcriptId = localRaw.id, text = "Local speech", wordsJson = null))
    val merged = PampaBundleMerge.merge(local, incoming)
    assertTrue(merged.note.body.contains("Local edit"))
    assertTrue(merged.transcripts.any { it.id == localRaw.id })
    assertTrue(merged.segments.any { it.transcriptId == localRaw.id && it.text == "Local speech" })
    PampaBundleArchive.validate(PampaBundleData(folders = origin.folders, notes = listOf(merged)))
  }

  @Test fun `native attachment bytes fit existing per entity relay chunk hash and TTL contract`() {
    val original = ByteArray(RelayLimits.CHUNK_BYTES + 17) { (it * 17).toByte() }
    val file = write(listOf(graph(original)))
    val data = PampaBundleArchive.read(file).data.notes.single()
    ZipFile(file).use { zip ->
      data.attachments.forEach { a ->
        val chunks = mutableListOf<RelayChunk>()
        zip.getInputStream(zip.getEntry(a.path)).use { input ->
          val buffer = ByteArray(RelayLimits.CHUNK_BYTES)
          while (true) {
            var bytes = 0
            while (bytes < buffer.size) { val count = input.read(buffer, bytes, buffer.size - bytes); if (count < 0) break; bytes += count }
            if (bytes == 0) break
            val digest = MessageDigest.getInstance("SHA-256").apply { update(buffer, 0, bytes) }.digest().joinToString("") { "%02x".format(it) }
            chunks += RelayChunk(chunks.size, bytes, digest)
          }
        }
        assertEquals(2, chunks.size)
        val transfer = RelayTransfer("transfer-${a.id}", "request-${a.id}", if (a.audio) "audio" else "source", a.id,
          a.sha256!!, a.bytes, "application/octet-stream", "original", "test-device", "Test", "ready",
          100, 100 + RelayLimits.TTL_MS, 101, RelayLimits.CHUNK_BYTES, chunks.size, chunks)
        assertEquals(transfer, transfer.validate(manifest = true))
        assertEquals(hash(original), a.sha256)
      }
    }
  }

  @Test fun `thread interruption and malformed Unicode fail explicitly without substituting content`() {
    val plain = PampaBundleNote(note)
    val file = write(listOf(plain))
    Thread.currentThread().interrupt()
    try {
      assertThrows(java.io.InterruptedIOException::class.java) { write(listOf(plain)) }
      assertThrows(java.io.InterruptedIOException::class.java) { PampaBundleArchive.read(file, false) }
    } finally { Thread.interrupted() }
    assertThrows(java.nio.charset.CharacterCodingException::class.java) { write(listOf(plain.copy(note = note.copy(body = "\ud800")))) }
    assertThrows(java.nio.charset.CharacterCodingException::class.java) { write(listOf(plain.copy(note = note.copy(title = "\ud800")))) }
  }

  @Test fun `reject exact duplicate ZIP entries and corrupt metadata CRC`() {
    val good = write(listOf(PampaBundleNote(note)))
    val duplicate = temp.newFile()
    ZipOutputStream(duplicate.outputStream()).use { zip ->
      entries(good).forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
      zip.putNextEntry(ZipEntry("pampa-n0te.json")); zip.write("{}".toByteArray()); zip.closeEntry()
    }
    val bytes = duplicate.readBytes()
    val before = "pampa-n0te.json".toByteArray()
    val after = PampaNoteContainer.HEADER.toByteArray()
    for (i in 0..bytes.size - before.size) if (before.indices.all { bytes[i + it] == before[it] }) after.copyInto(bytes, i)
    duplicate.writeBytes(bytes)
    rejected(duplicate, true)
    val badCrc = temp.newFile()
    val raw = good.readBytes()
    var changed = false
    for (i in 0 until raw.size - 46) if (raw[i] == 0x50.toByte() && raw[i + 1] == 0x4b.toByte() && raw[i + 2] == 1.toByte() && raw[i + 3] == 2.toByte()) {
      val nameLength = (raw[i + 28].toInt() and 255) + ((raw[i + 29].toInt() and 255) shl 8)
      val name = String(raw, i + 46, nameLength)
      if (name == PampaNoteContainer.HEADER) { raw[i + 16] = (raw[i + 16].toInt() xor 1).toByte(); changed = true; break }
    }
    assertTrue(changed)
    badCrc.writeBytes(raw)
    rejected(badCrc, true)
  }

  @Test fun `media CRC tampering rejects full PN read and cleans protected staging`() {
    val valid = write(listOf(graph()))
    val preview = PampaBundleArchive.read(valid, false)
    val path = preview.data.notes.single().attachments.first().path!!
    val corrupt = corruptCentralCrc(valid, path)
    // Preview intentionally does not decompress media. Full verification and staging both must.
    val read = PampaBundleArchive.read(corrupt, false)
    assertEquals(preview.data.notes, read.data.notes)
    rejected(corrupt)
    val stage = PampaBundleStaging(temp.newFolder("crc-audio"), temp.newFolder("crc-sources"))
    assertThrows(IllegalArgumentException::class.java) {
      stage.stage(corrupt, read.root, read.data.notes.flatMap { it.attachments }, {}, {})
    }
    assertTrue(stage.files.isNotEmpty())
    assertTrue(stage.files.none { it.exists() })
  }

  @Test fun `schema three ZIP media CRC is checked on full read and staging`() {
    val valid = temp.newFile().also { file ->
      file.outputStream().use { BundleWriter(audio, sources).write(set(listOf(graph())), ExportOptions(includeAudio = true, includeSources = true), it) }
    }
    val original = PampaBundleArchive.read(valid, false)
    val corrupt = corruptCentralCrc(valid, original.root + original.data.notes.single().attachments.first().path!!)
    val read = PampaBundleArchive.read(corrupt, false)
    rejected(corrupt)
    val stage = PampaBundleStaging(temp.newFolder("zip-crc-audio"), temp.newFolder("zip-crc-sources"))
    assertThrows(IllegalArgumentException::class.java) {
      stage.stage(corrupt, read.root, read.data.notes.flatMap { it.attachments }, {}, {})
    }
    assertTrue(stage.files.none { it.exists() })
  }

  @Test fun `zero CRC empty media remains valid including shared blob staging`() {
    val valid = write(listOf(graph(byteArrayOf())))
    val read = PampaBundleArchive.read(valid)
    val stage = PampaBundleStaging(temp.newFolder("empty-crc-audio"), temp.newFolder("empty-crc-sources"))
    try {
      val stored = stage.stage(valid, read.root, read.data.notes.flatMap { it.attachments }, {}, {})
      assertEquals(4, stored.size)
      assertEquals(4, stage.files.size)
      assertTrue(stage.files.all { it.isFile && it.length() == 0L })
    } finally { stage.cleanup() }
  }

  private fun corruptCentralCrc(valid: File, path: String): File {
    val raw = valid.readBytes()
    var changed = false
    for (i in 0 until raw.size - 46) {
      if (raw[i] != 0x50.toByte() || raw[i + 1] != 0x4b.toByte() || raw[i + 2] != 1.toByte() || raw[i + 3] != 2.toByte()) continue
      val length = (raw[i + 28].toInt() and 255) + ((raw[i + 29].toInt() and 255) shl 8)
      if (String(raw, i + 46, length, Charsets.UTF_8) == path) {
        raw[i + 16] = (raw[i + 16].toInt() xor 1).toByte()
        changed = true
        break
      }
    }
    assertTrue("Expected media central-directory entry", changed)
    return temp.newFile().also { it.writeBytes(raw) }
  }

  @Test fun `already compressed media and lossless fixtures measured against actual AI ZIP`() {
    val wav = waveFixture()
    val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    val compressed = requireNotNull(javaClass.getResourceAsStream("/pn/tone.m4a")).use { it.readBytes() }
    val fixtures = linkedMapOf("repetitive-text" to null, "pcm-wav" to wav, "png" to png, "aac-m4a" to compressed)
    fixtures.forEach { (label, bytes) ->
      val body = if (bytes == null) "Repeated study note: energy and motion.\n".repeat(2000) else "Fixture $label"
      val g = if (bytes == null) PampaBundleNote(note.copy(body = body)) else graph(bytes).copy(note = note.copy(body = body))
      val value = set(listOf(g))
      val pn = write(listOf(g))
      val old = temp.newFile().also { f -> f.outputStream().use { BundleWriter(audio, sources).write(value, ExportOptions(includeAudio = true, includeSources = true), it) } }
      val imported = PampaBundleArchive.read(pn).data.notes.single()
      if (bytes != null) imported.attachments.forEach { assertArrayEquals(bytes, entries(pn).getValue(it.path!!)) }
      assertEquals(body, imported.note.body)
      assertTrue("PN should remove AI duplication and deduplicate blobs for $label", pn.length() < old.length())
      val compressedBytes = ZipFile(pn).use { zip -> imported.attachments.mapNotNull { it.path }.distinct().sumOf { zip.getEntry(it).compressedSize } }
      println("PN-MEASURE $label input=${bytes?.size ?: body.toByteArray().size} aiZip=${old.length()} pn=${pn.length()} entities=${imported.attachments.size} mediaBlobs=${imported.attachments.mapNotNull { it.path }.distinct().size} compressedMedia=$compressedBytes")
    }
  }

  private fun waveFixture(): ByteArray {
    val data = ByteArray(128 * 1024) // PCM silence; ZIP compression is reversible.
    val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVEfmt ".toByteArray()).putInt(16)
      .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(data.size)
    return header.array() + data
  }
}
