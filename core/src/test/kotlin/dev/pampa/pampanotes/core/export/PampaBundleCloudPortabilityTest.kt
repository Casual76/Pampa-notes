package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.alignment.*
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.importing.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

/** Production ZIP writer/reader and typed import graph adapters, with synthetic text/audio only. */
class PampaBundleCloudPortabilityTest {
  @get:Rule val temp = TemporaryFolder()
  private val text = "  Ciao,\t😀 mondo!\r\n"
  private val secret = "UNSELECTED_SIBLING_TEXT"
  private val folder = FolderEntity("f", "Recordings", createdAt = 1, updatedAt = 2)
  private val note = NoteEntity("n", "f", "Interview", "Associated note body", createdAt = 1, updatedAt = 2)
  private fun part(id: String, session: String, pos: Int = 0) = AudioPartEntity(id, session, pos, "$id.wav", "$id.wav", "audio/wav", 1, 3000, AlignmentProtocol.sha256(id), 1)
  private val a = part("a", "s")
  private val b = part("b", "other")
  private fun session(id: String, rawId: String) = SessionEntity(id, "n", date = "2026-11-08", position = if (id == "s") 0 else 1, activeTranscriptId = rawId, createdAt = 1, updatedAt = 2)
  private fun raw(id: String = "r", body: String = text, p: AudioPartEntity = a): TranscriptEntity {
    val row = TranscriptEntity(id, p.sessionId, TranscriptKind.RAW, "groq", "whisper", "it", body, wordCount = 3, createdAt = 42)
    val parts = listOf(CloudTextPart(p.id, p.sha256, body, "it", row.provider, row.model))
    val doc = CloudSources.create(row, body, parts)
    val timings = AlignmentProtocol.tokens(body).mapIndexed { i, span -> AlignmentTiming(span.first, span.last + 1,
      if (i == 0) 100 else null, if (i == 0) 500 else null, if (i == 0) "measured" else "missing") }
    return row.copy(cloudSourceJson = CloudSources.encode(doc.copy(overlays = listOf(CloudOverlay(p.id, AlignmentProtocol.sha256(body), "it", timings)))))
  }
  private fun splitNote(): PampaBundleNote {
    val selected = raw()
    val doc = CloudSources.decode(selected.cloudSourceJson)!!
    val original = doc.originals.single().copy(text = text + "\n\n" + secret, textSha256 = AlignmentProtocol.sha256(text + "\n\n" + secret),
      parts = doc.parts + CloudTextPart("b", b.sha256, secret, "en", "openrouter", "public-model"), reportedText = "AGGREGATE_$secret")
    return PampaBundleNote(note, sessions = listOf(session("s", "r"), session("other", "other-r")), parts = listOf(a, b),
      transcripts = listOf(selected.copy(cloudSourceJson = CloudSources.encode(doc.copy(originals = listOf(original)))), raw("other-r", secret, b)))
  }
  private fun export(value: PampaBundleNote, restricted: Boolean, selectedIds: Set<String> = setOf("s")): java.io.File {
    val sessions = value.sessions.filter { !restricted || it.id in selectedIds }.mapIndexed { index, s ->
      val raw = value.transcripts.single { it.id == s.activeTranscriptId }
      ExportSession(s.id, index + 1, s.title, s.date, value.parts.filter { it.sessionId == s.id }.map { p ->
        ExportPart(p.id, p.originalName, p.fileName, p.durationMs, 0, p.sizeBytes)
      }, raw, raw, value.segments.filter { it.transcriptId == raw.id })
    }
    val item = ExportNote(value.note, listOf("Recordings"), emptyList(), emptyList(), sessions,
      native = value, selectedSessionIds = if (restricted) selectedIds else null)
    val set = ExportSet("Recordings", "recordings", listOf(item), "Pampa Notes test", 1, folders = listOf(folder))
    return temp.newFile().also { file -> file.outputStream().use { BundleWriter(temp.root, temp.root).write(set, ExportOptions(includeAudio = false), it) } }
  }
  @Test fun `exact session ZIP cannot leak originals or reported sibling after split and merge`() {
    val source = splitNote()
    val before = source.transcripts.first().cloudSourceJson
    val file = export(source, true)
    val read = PampaBundleArchive.read(file).data.notes.single()
    assertEquals(listOf("s"), read.sessions.map { it.id })
    val raw = read.transcripts.single()
    assertArrayEquals(text.toByteArray(), raw.text.toByteArray())
    assertEquals(note.body, read.note.body)
    assertTrue(CloudSources.validatePortable(raw, read.parts))
    val doc = CloudSources.decode(raw.cloudSourceJson)!!
    assertEquals("portable_projection", doc.originals.single().contentScope)
    assertEquals(listOf("measured", "missing", "missing"), doc.overlays.single().timings.map { it.status })
    ZipFile(file).use { zip ->
      zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
        assertFalse(entry.name, zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }.contains(secret))
      }
    }
    assertEquals(before, source.transcripts.first().cloudSourceJson)
    // Source graphs produced by a merge still retain old multipart originals.
    val doc2 = CloudSources.combine(source.transcripts.first(), listOf(a), listOf(CloudSources.decode(before)!!), emptyList())
    val recomposed = source.copy(transcripts = listOf(source.transcripts.first().copy(text = doc2.first, cloudSourceJson = CloudSources.encode(doc2.second))) + source.transcripts.drop(1))
    assertFalse(PampaBundleArchive.read(export(recomposed, true)).data.notes.single().transcripts.single().cloudSourceJson!!.contains(secret))
  }
  @Test fun `whole native roundtrip preserves public immutable originals reported text and measured evidence`() {
    val source = splitNote()
    val data = PampaBundleArchive.read(export(source, false)).data
    val imported = PampaBundleImporter.remap(data, "origin").notes.single()
    source.transcripts.forEach { before ->
      val after = imported.transcripts.single { it.id == before.id }
      assertEquals(before.text, after.text)
      val old = CloudSources.decode(before.cloudSourceJson)!!
      val doc = CloudSources.decode(after.cloudSourceJson)!!
      assertEquals(old.originals, doc.originals)
      assertEquals(old.overlays, doc.overlays)
      assertTrue(CloudSources.validatePortable(after, imported.parts.filter { it.sessionId == after.sessionId }))
      assertNull(CloudSources.bindSource(doc, imported.parts))
    }
    assertTrue(imported.transcripts.first().cloudSourceJson!!.contains(secret))
  }
  @Test fun `native copy alias and new RAW version use the actual destination graph and preserve history`() {
    val source = splitNote()
    val copied = PampaBundleImporter.remap(PampaBundleData(folders = listOf(folder), notes = listOf(source)), "copy").notes.single()
    copied.transcripts.forEach { raw ->
      assertTrue(CloudSources.validatePortable(raw, copied.parts.filter { it.sessionId == raw.sessionId }))
      assertFalse(source.transcripts.any { it.id == raw.id })
      assertNull(CloudSources.bindSource(CloudSources.decode(raw.cloudSourceJson)!!, copied.parts))
    }
    val destination = source.copy(parts = source.parts.map { if (it.id == "a") it.copy(id = "alias-a") else it },
      transcripts = source.transcripts.map { if (it.id == "r") it.copy(id = "new-r", createdAt = 99) else it })
    val alias = PampaBundleCloudSources.remap(source, destination, mapOf("a" to "alias-a"), mapOf("r" to "new-r"))
    val changed = alias.transcripts.first()
    assertTrue(CloudSources.validatePortable(changed, alias.parts.filter { it.sessionId == "s" }))
    val doc = CloudSources.decode(changed.cloudSourceJson)!!
    assertEquals("alias-a", doc.overlays.single().partId)
    assertEquals(99L, doc.rawVersion)
    assertEquals(42L, doc.originals.single().rawVersion)
    // A subsequent restricted transfer still recognizes the remapped original part.
    val restricted = CloudSources.portableProjection(changed, alias.parts.filter { it.sessionId == "s" })
    assertFalse(restricted.cloudSourceJson!!.contains(secret))
  }
  @Test fun `native update preserves each local RAW provenance and exact text when offsets change`() {
    val localRaw = raw("local")
    val local = PampaBundleNote(note, sessions = listOf(session("s", "local")), parts = listOf(a), transcripts = listOf(localRaw),
      segments = listOf(SegmentEntity(transcriptId = "local", partId = "a", indexInPart = 0, partStartMs = 100, partEndMs = 500,
        sessionStartMs = 100, sessionEndMs = 500, text = "coarse text must not replace exact cloud RAW")))
    val newPart = b.copy(sessionId = "s", position = 0)
    val incomingRaw = raw("incoming", "New public history", newPart)
    val incoming = PampaBundleNote(note, sessions = listOf(session("s", "incoming")), parts = listOf(newPart), transcripts = listOf(incomingRaw))
    val merged = PampaBundleCloudSources.merge(local, PampaBundleCloudSources.remap(incoming, incoming), false)
    assertEquals("incoming", merged.sessions.single().activeTranscriptId)
    for (before in listOf(localRaw, incomingRaw)) {
      val after = merged.transcripts.single { it.id == before.id }
      assertEquals(before.text, after.text)
      assertEquals(CloudSources.decode(before.cloudSourceJson)!!.originals, CloudSources.decode(after.cloudSourceJson)!!.originals)
      assertTrue(CloudSources.validatePortable(after, merged.parts))
    }
    assertEquals(3100, merged.segments.single().sessionStartMs)
  }
  @Test fun `opaque empty and null states do not collapse and unsupported data fails before ZIP output`() {
    val source = splitNote()
    for (state in listOf(null, "")) {
      val value = source.copy(transcripts = source.transcripts.map { it.copy(cloudSourceJson = state) })
      val copy = PampaBundleImporter.remap(PampaBundleArchive.read(export(value, false)).data, "copy").notes.single()
      assertTrue(copy.transcripts.all { it.cloudSourceJson == state })
    }
    val opaque = source.copy(transcripts = source.transcripts.map { it.copy(cloudSourceJson = "opaque") })
    try { export(opaque, false); fail("Unsupported metadata must fail") } catch (_: IllegalArgumentException) { }
    assertEquals("opaque", opaque.transcripts.first().cloudSourceJson)
    val out = ByteArrayOutputStream()
    val row = opaque.transcripts.first()
    val item = ExportNote(note, listOf("Recordings"), emptyList(), emptyList(), listOf(ExportSession("s", 1, "", "2026-11-08", emptyList(), row, row, emptyList())), native = opaque)
    try { BundleWriter(temp.root, temp.root).write(ExportSet("x", "x", listOf(item), "test", 1), ExportOptions(), out); fail("Must reject") } catch (_: IllegalArgumentException) { }
    assertEquals(0, out.size())
  }
  @Test fun `same ID update retains older public RAW and measured evidence as an independent history`() {
    val before = raw()
    val local = PampaBundleNote(note, sessions = listOf(session("s", before.id)), parts = listOf(a), transcripts = listOf(before))
    val after = raw(body = "Replacement public version").copy(createdAt = 80).let { row ->
      val doc = CloudSources.decode(row.cloudSourceJson)!!
      row.copy(cloudSourceJson = CloudSources.encode(doc.copy(rawVersion = 80)))
    }
    val incoming = local.copy(transcripts = listOf(after))
    val merged = PampaBundleCloudSources.merge(local, PampaBundleCloudSources.remap(incoming, incoming), false)
    assertEquals("r", merged.sessions.single().activeTranscriptId)
    assertEquals(after.text, merged.transcripts.single { it.id == "r" }.text)
    val history = merged.transcripts.single { it.id != "r" }
    assertEquals(before.text, history.text)
    assertEquals(CloudSources.decode(before.cloudSourceJson)!!.overlays, CloudSources.decode(history.cloudSourceJson)!!.overlays)
    assertEquals(CloudSources.decode(before.cloudSourceJson)!!.originals, CloudSources.decode(history.cloudSourceJson)!!.originals)
    assertTrue(CloudSources.validatePortable(history, merged.parts))
    val repeated = PampaBundleCloudSources.merge(merged, PampaBundleCloudSources.remap(incoming, incoming), false)
    assertEquals(2, repeated.transcripts.size)
  }
  @Test fun `legacy alias swaps remap stored overlays using source identities rather than previous occupants`() {
    val sid = PampaBundleLegacyReconcile.id("session", "old")
    val pid = PampaBundleLegacyReconcile.id("part", "old")
    val oldPart = a.copy(id = pid, sessionId = sid)
    val before = raw(p = oldPart)
    val source = PampaBundleNote(note, sessions = listOf(session(sid, before.id)), parts = listOf(oldPart), transcripts = listOf(before))
    val incoming = PampaBundleNote(note, sessions = listOf(session("new-s", "incoming")), parts = listOf(a.copy(id = "new-p", sessionId = "new-s")))
    val plan = PampaBundleLegacyReconcile.plan(source, incoming, publicLegacy = false)
    val adapted = PampaBundleCloudSources.remap(source, plan.local!!, plan.parts, transfer = false)
    val row = adapted.transcripts.single()
    assertEquals("new-s", row.sessionId)
    assertEquals("new-p", CloudSources.decode(row.cloudSourceJson)!!.overlays.single().partId)
    assertTrue(CloudSources.validatePortable(row, adapted.parts))
    assertNull(CloudSources.bindSource(CloudSources.decode(row.cloudSourceJson)!!, adapted.parts))
  }
  @Test fun `export hashing cannot certify measured evidence with replacement audio bytes`() {
    val value = PampaBundleNote(note, sessions = listOf(session("s", "r")), parts = listOf(a), transcripts = listOf(raw()))
    temp.newFile(a.fileName).writeText("replacement bytes")
    val row = value.transcripts.single()
    val item = ExportNote(note, listOf("Recordings"), emptyList(), emptyList(), listOf(ExportSession("s", 1, "", "2026-11-08",
      listOf(ExportPart("a", "a.wav", "a.wav", 3000, 0, 1)), row, row, emptyList())), native = value)
    val out = ByteArrayOutputStream()
    try { BundleWriter(temp.root, temp.root).write(ExportSet("x", "x", listOf(item), "Pampa Notes test", 1), ExportOptions(includeAudio = true), out); fail("Must reject changed file hash") } catch (_: IllegalArgumentException) { }
    assertEquals(0, out.size())
  }
  @Test fun `unselected RAW history cannot replace or block the selected valid cloud source`() {
    val source = splitNote()
    val old = raw("unselected", "OLDER_PUBLIC_HISTORY").copy(cloudSourceJson = "opaque-unselected-history")
    val value = source.copy(transcripts = source.transcripts + old)
    val result = PampaBundleArchive.read(export(value, true)).data.notes.single()
    assertEquals("r", result.sessions.single().activeTranscriptId)
    assertEquals(listOf("r"), result.transcripts.map { it.id })
    assertEquals(text, result.transcripts.single().text)
    assertEquals("opaque-unselected-history", value.transcripts.last().cloudSourceJson)
  }
  @Test fun `caller supplied display text must consume scoped projection before any ZIP bytes`() {
    val source = splitNote()
    val first = source.transcripts.first()
    val doc = CloudSources.decode(first.cloudSourceJson)!!
    val stale = first.copy(text = text + "\n\n" + secret, cloudSourceJson = CloudSources.encode(doc.copy(
      textSha256 = AlignmentProtocol.sha256(text + "\n\n" + secret), parts = doc.originals.single().parts)))
    try { export(source.copy(transcripts = listOf(stale) + source.transcripts.drop(1)), true); fail("Unscoped display text must fail") } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("scoped RAW"))
    }
    assertEquals(first.cloudSourceJson, source.transcripts.first().cloudSourceJson)
  }
  @Test fun `multiple selected sessions retain a complete original wholly inside their combined authorized graph`() {
    val source = splitNote()
    val restored = PampaBundleArchive.read(export(source, true, setOf("s", "other"))).data.notes.single()
    val original = CloudSources.decode(source.transcripts.first().cloudSourceJson)!!.originals.single()
    assertEquals(original, CloudSources.decode(restored.transcripts.first().cloudSourceJson)!!.originals.single())
    restored.transcripts.forEach { row -> assertTrue(CloudSources.validatePortable(row, restored.parts.filter { it.sessionId == row.sessionId })) }
  }
}
