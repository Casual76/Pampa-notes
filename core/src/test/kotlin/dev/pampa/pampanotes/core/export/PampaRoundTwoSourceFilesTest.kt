package dev.pampa.pampanotes.core.export

import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.importing.*
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression sources for PN R2-2; no Android, transport, server or model is needed. */
class PampaRoundTwoSourceFilesTest {
  @get:Rule val temporary = TemporaryFolder()
  private val body = "Testo incollato: è già qui.\nSpazi  e righe conservati."
  private val folder = FolderEntity("folder", "Notes", createdAt = 1, updatedAt = 2)
  private val note = NoteEntity("note", folder.id, "Text", body, createdAt = 3, updatedAt = 4)
  private val content get() = body.toByteArray(Charsets.UTF_8)

  @Test fun clipboardAndTextShareRoundtripAllMetadataWithoutMissingFiles() {
    for (kind in listOf(SourceKind.CLIPBOARD, SourceKind.SHARE)) {
      val source = source(kind.name, kind).copy(extractedChars = body.length, detail = "Imported text")
      val set = set(listOf(source))
      assertTrue(ExportFiles.wanted(set, options).isEmpty())
      assertTrue(ExportFiles.plan(set, options) { false }.isEmpty)
      val read = writeRead(set)
      assertTrue(read.warnings.isEmpty())
      assertEquals(note, read.data.notes.single().note)
      assertEquals(listOf(source), read.data.notes.single().sources)
      val declaration = read.data.notes.single().attachments.single()
      assertTrue(declaration.fileless)
      assertNull(declaration.path)
      assertEquals(source.sizeBytes, declaration.bytes)
      assertEquals(source.sha256, declaration.sha256)
      // Re-encoding the portable graph must not invent an attachment after filenames are stripped.
      assertTrue(writeRead(set.copy(notes = set.notes.map { it.copy(native = read.data.notes.single()) })).warnings.isEmpty())
    }
  }

  @Test fun filelessSourceKeepsDerivedPageRelationshipWhileOnlyTheMissingPageWarns() {
    val text = source("text", SourceKind.CLIPBOARD)
    val page = source("page", SourceKind.IMAGE).copy(mime = "image/png", derivedFromId = text.id)
    val set = set(listOf(text, page))
    assertEquals(listOf(page.id), ExportFiles.wanted(set, options).map { it.id })
    val read = writeRead(set)
    assertEquals(listOf(PampaBundleWarning.OMITTED_ATTACHMENTS), read.warnings)
    assertEquals(listOf(text, page), read.data.notes.single().sources)
    assertEquals(text.id, read.data.notes.single().sources.last().derivedFromId)
    assertEquals(listOf(true, false), read.data.notes.single().attachments.map { it.fileless })
  }

  @Test fun omittedOriginalAndBinaryShareStillWarnAndRetainExpectedHashAndSize() {
    val sources = listOf(source("pdf", SourceKind.PDF).copy(mime = "application/pdf"),
      source("image", SourceKind.IMAGE).copy(mime = "image/png"),
      source("binary-share", SourceKind.SHARE).copy(mime = "application/octet-stream"))
    val plan = ExportFiles.plan(set(sources), options) { false }
    assertEquals(sources.map { it.id }, plan.unobtainable.map { it.file.id })
    assertTrue(plan.unobtainable.all { it.reason == MissingReason.NO_FILE })
    val read = writeRead(set(sources))
    assertEquals(listOf(PampaBundleWarning.OMITTED_ATTACHMENTS), read.warnings)
    assertEquals(sources, read.data.notes.single().sources)
    assertTrue(read.data.notes.single().attachments.all { !it.fileless && it.path == null && it.bytes == content.size.toLong() && it.sha256 == hash(content) })
  }

  @Test fun actualClipboardOrShareFileIsIncludedAndStagedWithVerifiedBytes() {
    for (kind in listOf(SourceKind.CLIPBOARD, SourceKind.SHARE)) {
      val directory = temporary.newFolder()
      File(directory, "original.txt").writeBytes(content)
      val source = source(kind.name, kind).copy(storedFileName = "original.txt")
      val set = set(listOf(source))
      assertEquals(listOf(source.id), ExportFiles.wanted(set, options).map { it.id })
      assertTrue(ExportFiles.plan(set, options) { File(directory, it.fileName).isFile }.isEmpty)
      val archive = write(set, directory)
      val read = PampaBundleArchive.read(archive)
      assertTrue(read.warnings.isEmpty())
      assertFalse(read.data.notes.single().attachments.single().fileless)
      val output = temporary.newFolder()
      val staged = PampaBundleStaging(temporary.newFolder(), output).stage(archive, read.root,
        read.data.notes.single().attachments, {}, {})
      assertArrayEquals(content, File(output, staged.getValue(false to source.id)).readBytes())
    }
  }

  @Test fun missingActualTextAttachmentRemainsOmittedAfterPortableGraphIsReencoded() {
    val source = source("attached", SourceKind.SHARE).copy(storedFileName = "evicted.txt")
    val read = writeRead(set(listOf(source)))
    assertEquals(listOf(PampaBundleWarning.OMITTED_ATTACHMENTS), read.warnings)
    assertFalse(read.data.notes.single().attachments.single().fileless)
    val portable = set(listOf(source)).let { it.copy(notes = it.notes.map { n -> n.copy(native = read.data.notes.single()) }) }
    assertEquals(listOf(source.id), ExportFiles.wanted(portable, options).map { it.id })
    assertEquals(listOf(PampaBundleWarning.OMITTED_ATTACHMENTS), writeRead(portable).warnings)
  }

  @Test fun attachedTextIntegrityFailureIsNotConvertedIntoFilelessProvenance() {
    val directory = temporary.newFolder()
    File(directory, "original.txt").writeBytes(ByteArray(content.size) { 'x'.code.toByte() })
    assertRejected { write(set(listOf(source("attached", SourceKind.CLIPBOARD).copy(storedFileName = "original.txt"))), directory) }
  }

  @Test fun forgedFilelessDeclarationsCannotHideOriginalsAudioOrDerivedContent() {
    val text = source("text", SourceKind.CLIPBOARD)
    val declaration = PampaBundleAttachment(text.id, false, bytes = text.sizeBytes, sha256 = text.sha256, fileless = true)
    fun validate(source: SourceEntity, attachment: PampaBundleAttachment = declaration.copy(id = source.id)) =
      PampaBundleArchive.validate(PampaBundleData(folders = listOf(folder),
        notes = listOf(PampaBundleNote(note, sources = listOf(source), attachments = listOf(attachment)))))
    validate(text)
    assertRejected { validate(text.copy(kind = SourceKind.PDF, mime = "application/pdf")) }
    assertRejected { validate(text.copy(mime = "application/octet-stream")) }
    assertRejected { validate(text.copy(storedFileName = "had-a-file.txt")) }
    assertRejected { validate(text.copy(archivedAt = 1)) }
    assertRejected { validate(text, declaration.copy(bytes = text.sizeBytes + 1)) }
    assertRejected { validate(text, declaration.copy(sha256 = "a".repeat(64))) }
    assertRejected { validate(text, declaration.copy(path = "blobs/${text.sha256}")) }
    val session = SessionEntity("session", note.id, date = "2026-11-08", position = 0, createdAt = 1, updatedAt = 1)
    val part = AudioPartEntity("part", session.id, 0, "", "Audio", "audio/mp4", 1, 10, "b".repeat(64), 1)
    assertRejected { PampaBundleArchive.validate(PampaBundleData(folders = listOf(folder), notes = listOf(PampaBundleNote(note,
      sessions = listOf(session), parts = listOf(part), attachments = listOf(PampaBundleAttachment(part.id, true, bytes = 1, sha256 = part.sha256, fileless = true)))))) }
    val derived = text.copy(id = "child", derivedFromId = text.id)
    assertRejected { PampaBundleArchive.validate(PampaBundleData(folders = listOf(folder), notes = listOf(PampaBundleNote(note,
      sources = listOf(text, derived), attachments = listOf(declaration, declaration.copy(id = derived.id)))))) }
  }

  @Test fun oldDeclarationDefaultsToOmissionAndStillRequiresCompleteSourceDeclarations() {
    val old = Json.decodeFromString(PampaBundleAttachment.serializer(), """{"id":"text","audio":false}""")
    assertFalse(old.fileless)
    val text = source("text", SourceKind.CLIPBOARD)
    val native = PampaBundleNote(note, sources = listOf(text), attachments = listOf(old.copy(bytes = text.sizeBytes, sha256 = text.sha256)))
    val set = set(listOf(text)).let { it.copy(notes = it.notes.map { n -> n.copy(native = native) }) }
    assertEquals(listOf(PampaBundleWarning.OMITTED_ATTACHMENTS), writeRead(set).warnings)
    assertRejected { PampaBundleArchive.validate(PampaBundleData(folders = listOf(folder), notes = listOf(native.copy(attachments = emptyList())))) }
  }

  private val options get() = ExportTarget.PAMPA.defaults()
  private fun source(id: String, kind: SourceKind) = SourceEntity(id, note.id, kind, "Original", "text/plain",
    content.size.toLong(), hash(content), importedAt = 5)
  private fun set(sources: List<SourceEntity>) = ExportSet("Text", "text", listOf(ExportNote(note, listOf(folder.name), emptyList(),
    emptyList(), emptyList(), native = PampaBundleNote(note, sources = sources))), "test", 1, folders = listOf(folder))
  private fun write(set: ExportSet, directory: File = temporary.newFolder()): File = temporary.newFile().also { file ->
    file.outputStream().use { PampaNoteWriter(temporary.newFolder(), directory).write(set, it) }
  }
  private fun writeRead(set: ExportSet) = PampaBundleArchive.read(write(set))
  private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  private fun assertRejected(action: () -> Unit) {
    try { action(); fail("Invalid attachment was accepted") } catch (_: IllegalArgumentException) { }
  }
}
