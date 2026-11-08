package dev.pampa.pampanotes.core.importing

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.export.*
import dev.pampa.pampanotes.core.transcription.VoiceNames
import dev.pampa.pampanotes.core.transcription.WordTimings
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Fixed output of the unmodified 1.2.0 writer, not output of the current BundleWriter.
 * See resources/released120/README.md for source hashes, generation and evidence limits. */
class ReleasedPampa120CompatibilityTest {
  @get:Rule val temp = TemporaryFolder()

  private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/released120/$name")) {
    "Missing released120 resource: $name"
  }.use { it.readBytes() }

  private fun archive(): File = temp.newFile("released120-${System.nanoTime()}.zip").also {
    it.writeBytes(resource("released120-synthetic.zip"))
  }

  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

  private fun lesson(data: PampaBundleData) = data.notes.single { it.note.id == "r120-note" }

  @Test fun `fixture is pinned to released source provenance and exact entry bytes`() {
    val bytes = resource("released120-synthetic.zip")
    assertEquals(28681, bytes.size)
    assertEquals("25114f4834dc3b59bfc90b2d8c50a25d3361f9c492dcf214b3d2bb8078701481", sha(bytes))
    val provenance = Json.parseToJsonElement(resource("provenance.json").toString(Charsets.UTF_8)).jsonObject
    assertEquals("stable-pampa-notes-v1.2.0", provenance.getValue("tag").jsonPrimitive.content)
    assertEquals("e8c9ba4f2d0b7cc4a5f138e622be62da61979845", provenance.getValue("commit").jsonPrimitive.content)
    val sources = provenance.getValue("sources").jsonArray.map { it.jsonObject }
    assertEquals(16, sources.size)
    assertEquals("65323147d5d05f4afb62915951d00e9d2df3685e", sources.single {
      it.getValue("path").jsonPrimitive.content.endsWith("/export/BundleWriter.kt")
    }.getValue("gitBlob").jsonPrimitive.content)
    assertEquals("8eb1d0f794fb4d22d4040ed0162a79951dddfbf1", sources.single {
      it.getValue("path").jsonPrimitive.content.endsWith("/export/PampaBundleExport.kt")
    }.getValue("gitBlob").jsonPrimitive.content)
    assertEquals(provenance.getValue("generatorSha256").jsonPrimitive.content, sha(resource("Released120Fixture.kt.txt").toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)))
    ZipFile(archive()).use { zip ->
      val entries = provenance.getValue("entries").jsonArray.map { it.jsonObject }
      assertEquals(entries.map { it.getValue("path").jsonPrimitive.content }.toSet(),
        zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet())
      entries.forEach { expected ->
        val entry = zip.getEntry(expected.getValue("path").jsonPrimitive.content)
        val content = zip.getInputStream(entry).use { it.readBytes() }
        assertEquals(expected.getValue("bytes").jsonPrimitive.long, content.size.toLong())
        assertEquals(expected.getValue("sha256").jsonPrimitive.content, sha(content))
      }
      val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry(ROOT + "manifest.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
      assertEquals(3, manifest.getValue("schema").jsonPrimitive.int)
      assertEquals("Pampa Notes 1.2.0", manifest.getValue("generator").jsonPrimitive.content)
      assertEquals("2026-01-02T03:04:15Z", manifest.getValue("exportedAt").jsonPrimitive.content)
    }
  }

  @Test fun `current full reader and metadata preflight preserve released text hierarchy and dates`() {
    val file = archive()
    assertTrue(PampaBundleArchive.isBundle(file))
    val read = PampaBundleArchive.read(file)
    assertEquals(read, PampaBundleArchive.read(file, verifyAttachments = false))
    assertEquals(ROOT, read.root)
    assertTrue(read.warnings.isEmpty())
    assertTrue(read.precise)
    assertEquals(setOf("r120-root", "r120-child"), read.data.folders.map { it.id }.toSet())
    val root = read.data.folders.single { it.id == "r120-root" }
    assertEquals(FolderEntity("r120-root", "Laboratorio", tone = "Blue", icon = "science", createdAt = TIME, updatedAt = TIME + 1000), root)
    assertEquals(FolderEntity("r120-child", "Fisica", root.id, 2, "Green", "atom", TIME + 100, TIME + 1100),
      read.data.folders.single { it.id == "r120-child" })
    assertEquals(2, read.data.notes.size)
    val note = lesson(read.data)
    assertEquals(NoteEntity("r120-note", "r120-child", "Energia e unità", BODY, true, "it", TIME + 200, TIME + 4000), note.note)
    assertEquals(listOf("fisica", "sintetico"), note.tags)
    val plain = read.data.notes.single { it.note.id == "r120-plain" }
    assertEquals("r120-root", plain.note.folderId)
    assertEquals("Testo senza registrazioni.\n", plain.note.body)
    assertEquals(TIME + 300, plain.note.createdAt)
    assertEquals(TIME + 3000, plain.note.updatedAt)
    assertTrue(plain.sessions.isEmpty() && plain.parts.isEmpty() && plain.transcripts.isEmpty() && plain.segments.isEmpty())
    assertEquals(setOf("r120-source-a", "r120-source-b"), note.sources.map { it.id }.toSet())
    note.sources.forEach { source ->
      assertEquals(SourceKind.MARKDOWN, source.kind)
      assertEquals("text/markdown", source.mime)
      assertEquals(SOURCE_SHA, source.sha256)
      assertEquals(60L, source.sizeBytes)
      assertEquals(59, source.extractedChars)
      assertEquals(SourceStatus.OK, source.status)
      assertEquals("Fixture sintetica", source.detail)
      assertEquals(TIME + 700, source.importedAt)
    }
  }

  @Test fun `released selected raw and refinement branches retain exact words offsets and speakers`() {
    val note = lesson(PampaBundleArchive.read(archive()).data)
    assertEquals(listOf("r120-ref-session", "r120-raw-session"), note.sessions.map { it.id })
    assertEquals(listOf("r120-refined", "r120-raw"), note.sessions.map { it.activeTranscriptId })
    assertEquals(setOf("r120-ref-raw", "r120-refined", "r120-raw"), note.transcripts.map { it.id }.toSet())
    // The released writer intentionally omitted both alternatives supplied by the generator.
    assertFalse(note.transcripts.any { "unselected" in it.id })
    val refined = note.transcripts.single { it.id == "r120-refined" }
    assertEquals(TranscriptKind.REFINED, refined.kind)
    assertEquals("r120-ref-raw", refined.parentId)
    assertEquals("Energia sintetica, ripulita.", refined.text)
    assertEquals("CLEAN", refined.preset)
    assertEquals("a22d46319bc8433360517a62306b26befdc30ac1a738e2f7ebd7c9148c93e6d7", refined.promptHash)
    assertEquals(TIME + 2000, refined.createdAt)
    assertEquals(listOf(2, 3, 4), note.transcripts.map { it.wordCount })
    assertTrue(note.segments.none { it.transcriptId == refined.id })
    assertEquals(listOf(101L, 102L, 103L), note.segments.map { it.id })
    val words = listOf("0,300,Energia\n400,800,sintetica.", "0,300,Prima\n400,800,parte.", "0,300,Seconda\n400,800,parte.")
    note.segments.forEachIndexed { index, segment ->
      assertEquals(0, segment.indexInPart)
      assertEquals(100L, segment.partStartMs)
      assertEquals(900L, segment.partEndMs)
      assertEquals(if (index == 2) 1100L else 100L, segment.sessionStartMs)
      assertEquals(if (index == 2) 1900L else 900L, segment.sessionEndMs)
      assertEquals(words[index], segment.wordsJson)
      assertFalse(segment.wordsEstimated)
      assertEquals(0.02f, requireNotNull(segment.noSpeechProb), 0f)
      assertEquals(-0.1f, requireNotNull(segment.avgLogProb), 0f)
      assertEquals(if (index == 0) "SPEAKER_00" else "SPEAKER_01", segment.speaker)
      val decoded = WordTimings.decode(segment.wordsJson, segment.sessionStartMs)
      assertEquals(listOf(segment.sessionStartMs, segment.sessionStartMs + 400), decoded.map { it.startMs })
      assertEquals(listOf(segment.sessionStartMs + 300, segment.sessionStartMs + 800), decoded.map { it.endMs })
    }
    assertEquals(mapOf("r120-a|SPEAKER_00" to "Voce sintetica"), VoiceNames.decode(note.sessions[0].voiceNames))
    assertEquals(mapOf("r120-b|SPEAKER_01" to "Seconda voce", "r120-c|SPEAKER_01" to "Terza voce"), VoiceNames.decode(note.sessions[1].voiceNames))
    assertEquals(listOf(1000L, 1000L, 1000L), note.parts.map { it.durationMs })
    assertEquals(listOf(0, 0, 1), note.parts.map { it.position })
    assertEquals(listOf(TIME + 600, TIME + 601, TIME + 602), note.parts.map { it.createdAt })
  }

  @Test fun `actual staging verifies original bytes independently for update and copy entity keys`() {
    val file = archive()
    val read = PampaBundleArchive.read(file, verifyAttachments = false)
    val tone = requireNotNull(javaClass.getResourceAsStream("/pn/tone.m4a")).use { it.readBytes() }
    for (namespace in listOf("origin", "released120-copy")) {
      val data = PampaBundleImporter.remap(read.data, namespace)
      val audio = temp.newFolder()
      val sources = temp.newFolder()
      val staging = PampaBundleStaging(audio, sources)
      try {
        val attachments = data.notes.flatMap { it.attachments }
        val progress = mutableListOf<Float>()
        val stored = staging.stage(file, read.root, attachments, {}, progress::add)
        assertEquals(attachments.map { it.audio to it.id }.toSet(), stored.keys)
        assertEquals(5, stored.values.toSet().size)
        attachments.forEach { attachment ->
          val bytes = File(if (attachment.audio) audio else sources, stored.getValue(attachment.audio to attachment.id)).readBytes()
          assertArrayEquals(if (attachment.audio) tone else SOURCE.toByteArray(Charsets.UTF_8), bytes)
          assertEquals(if (attachment.audio) AUDIO_SHA else SOURCE_SHA, sha(bytes))
        }
        // Equal source bytes in the released archive even share a path; their local lifetimes must not.
        val sourceFiles = attachments.filterNot { it.audio }.map { File(sources, stored.getValue(false to it.id)) }
        assertTrue(sourceFiles.first().delete())
        assertArrayEquals(SOURCE.toByteArray(Charsets.UTF_8), sourceFiles.last().readBytes())
        val audioFiles = attachments.filter { it.audio }.map { File(audio, stored.getValue(true to it.id)) }
        assertTrue(audioFiles.first().delete())
        audioFiles.drop(1).forEach { assertArrayEquals(tone, it.readBytes()) }
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1f, progress.last())
      } finally { staging.cleanup() }
      assertTrue(audio.listFiles()!!.isEmpty() && sources.listFiles()!!.isEmpty())
    }
  }

  @Test fun `origin keeps released identities and copy rekeys every graph and voice reference`() {
    val data = PampaBundleArchive.read(archive()).data
    val origin = PampaBundleImporter.remap(data, "origin")
    assertEquals(data.copy(notes = data.notes.map { n -> n.copy(parts = n.parts.map { it.copy(fileName = "") },
      sources = n.sources.map { it.copy(storedFileName = null) }, segments = n.segments.map { it.copy(id = 0) }) }), origin)
    val copy = PampaBundleImporter.remap(data, "released120-copy")
    assertEquals(copy, PampaBundleImporter.remap(data, "released120-copy"))
    assertNotEquals(copy, PampaBundleImporter.remap(data, "another-copy"))
    PampaBundleArchive.validate(origin)
    PampaBundleArchive.validate(copy)
    val folders = data.folders.zip(copy.folders).associate { (old, new) -> old.id to new.id }
    assertTrue(data.folders.none { it.id in folders.values })
    data.folders.zip(copy.folders).forEach { (old, new) -> assertEquals(old.copy(id = new.id, parentId = old.parentId?.let(folders::getValue)), new) }
    data.notes.zip(copy.notes).forEach { (old, new) ->
      assertNotEquals(old.note.id, new.note.id)
      assertEquals(old.note.copy(id = new.note.id, folderId = folders.getValue(old.note.folderId)), new.note)
      val sessions = old.sessions.zip(new.sessions).associate { (a, b) -> a.id to b.id }
      val parts = old.parts.zip(new.parts).associate { (a, b) -> a.id to b.id }
      val transcripts = old.transcripts.zip(new.transcripts).associate { (a, b) -> a.id to b.id }
      val sources = old.sources.zip(new.sources).associate { (a, b) -> a.id to b.id }
      assertTrue((sessions.keys + parts.keys + transcripts.keys + sources.keys).intersect(
        (sessions.values + parts.values + transcripts.values + sources.values).toSet()).isEmpty())
      old.parts.zip(new.parts).forEach { (a, b) -> assertEquals(a.copy(id = b.id, sessionId = sessions.getValue(a.sessionId), fileName = ""), b) }
      old.transcripts.zip(new.transcripts).forEach { (a, b) -> assertEquals(a.copy(id = b.id, sessionId = sessions.getValue(a.sessionId), parentId = a.parentId?.let(transcripts::getValue)), b) }
      old.segments.zip(new.segments).forEach { (a, b) -> assertEquals(a.copy(id = 0, transcriptId = transcripts.getValue(a.transcriptId), partId = parts.getValue(a.partId)), b) }
      old.sessions.zip(new.sessions).forEach { (a, b) ->
        assertEquals(transcripts[a.activeTranscriptId], b.activeTranscriptId)
        assertEquals(new.note.id, b.noteId)
        assertEquals(VoiceNames.decode(a.voiceNames).mapKeys { (key, _) -> parts.getValue(key.substringBefore('|')) + "|" + key.substringAfter('|') }, VoiceNames.decode(b.voiceNames))
      }
      old.sources.zip(new.sources).forEach { (a, b) -> assertEquals(a.copy(id = b.id, noteId = new.note.id, storedFileName = null), b) }
      old.attachments.zip(new.attachments).forEach { (a, b) -> assertEquals(a.copy(id = (if (a.audio) parts else sources).getValue(a.id)), b) }
    }
  }

  @Test fun `repeated released update preserves local text and local refinement without entity duplication`() {
    val incoming = lesson(PampaBundleImporter.remap(PampaBundleArchive.read(archive()).data, "origin"))
    val imported = PampaBundleMerge.merge(null, incoming)
    val extra = incoming.transcripts.single { it.id == "r120-raw" }.copy(id = "local-refinement", kind = TranscriptKind.REFINED,
      parentId = "r120-raw", text = "Ripulita aggiunta localmente.", wordCount = 3, createdAt = TIME + 20000)
    val local = imported.copy(note = imported.note.copy(body = BODY + "\nAppendice locale.\n"), transcripts = imported.transcripts + extra)
    val merged = PampaBundleMerge.merge(local, incoming)
    assertEquals(local.note.body, merged.note.body)
    assertEquals(extra, merged.transcripts.single { it.id == extra.id })
    assertEquals(incoming.sessions.map { it.activeTranscriptId }, merged.sessions.map { it.activeTranscriptId })
    assertEquals(incoming.parts, merged.parts)
    assertEquals(incoming.sources, merged.sources)
    assertEquals(incoming.segments, merged.segments)
    assertEquals(3, merged.parts.map { it.id }.toSet().size)
    assertEquals(2, merged.sources.map { it.id }.toSet().size)
    assertEquals(4, merged.transcripts.map { it.id }.toSet().size)
    assertEquals(merged, PampaBundleMerge.merge(merged, incoming))
  }

  @Test fun `released archive converts to pn with identical content and deduplicated bytes but distinct entities`() {
    val file = archive()
    val read = PampaBundleArchive.read(file)
    val audio = temp.newFolder()
    val sources = temp.newFolder()
    val staging = PampaBundleStaging(audio, sources)
    try {
      val stored = staging.stage(file, read.root, read.data.notes.flatMap { it.attachments }, {}, {})
      val notes = read.data.notes.map { n -> n.copy(
        parts = n.parts.map { it.copy(fileName = stored.getValue(true to it.id)) },
        sources = n.sources.map { it.copy(storedFileName = stored.getValue(false to it.id)) }) }
      val set = ExportSet("Released compatibility", "released120", notes.map { n ->
        ExportNote(n.note, emptyList(), n.tags, emptyList(), emptyList(), native = n)
      }, "Pampa Notes compatibility test", TIME + 30000, folders = read.data.folders)
      val pn = temp.newFile("converted.pn")
      pn.outputStream().use { PampaNoteWriter(audio, sources).write(set, it) }
      assertTrue(PampaBundleArchive.isBundle(pn))
      val converted = PampaBundleArchive.read(pn)
      assertTrue(converted.warnings.isEmpty())
      assertTrue(converted.precise)
      fun portable(data: PampaBundleData) = data.copy(notes = data.notes.map { n -> n.copy(
        parts = n.parts.map { it.copy(fileName = "") }, sources = n.sources.map { it.copy(storedFileName = null) },
        attachments = n.attachments.map { it.copy(path = null) }) })
      assertEquals(portable(read.data), portable(converted.data))
      val attachments = lesson(converted.data).attachments
      assertEquals(5, attachments.map { it.audio to it.id }.toSet().size)
      assertEquals(2, attachments.map { it.path }.toSet().size)
      ZipFile(pn).use { zip ->
        listOf(AUDIO_SHA, SOURCE_SHA).forEach { hash ->
          assertEquals(1, zip.entries().asSequence().count { it.name == "blobs/$hash" })
          val bytes = zip.getInputStream(zip.getEntry("blobs/$hash")).use { it.readBytes() }
          assertEquals(hash, sha(bytes))
        }
      }
    } finally { staging.cleanup() }
  }

  companion object {
    private const val ROOT = "pampa-notes-released120/"
    private const val TIME = 1767323045000L
    private const val BODY = "# Appunti sintetici\n\nEnergia: E = mc².\nUn caffè e un'unità: µs.\n"
    private const val SOURCE = "# Fonte sintetica\nEnergia: E = mc².\nNessun dato personale.\n"
    private const val AUDIO_SHA = "1ba6b5929c65382d145c0887726a3deba6d81f528dcf590aed88f29642556452"
    private const val SOURCE_SHA = "4c4c61dd1c19616665f3c818615834e21adac7efa946ccc93476013edf5fa7e4"
  }
}
