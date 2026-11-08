package dev.pampa.pampanotes.core.relay

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.*
import dev.pampa.pampanotes.core.export.*
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.importing.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Prepared only: real Room reservation/import, no relay network, server or device run by author. */
@RunWith(AndroidJUnit4::class)
class PampaRoundTwoSourceReservationRoomTest {
  private lateinit var db: PampaDatabase
  private lateinit var directory: File
  private lateinit var files: AppFiles
  private lateinit var adapter: AndroidRelayLocalFiles
  private val folder = FolderEntity("folder", "Notes", createdAt = 1, updatedAt = 1)
  private val note = NoteEntity("note", folder.id, "Text", "Retained inline text", createdAt = 1, updatedAt = 1)

  @Before fun setup() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    directory = File(context.cacheDir, "pn-round-two-${UUID.randomUUID()}").also { it.mkdirs() }
    files = AppFiles(object : ContextWrapper(context) {
      override fun getFilesDir() = File(directory, "files").also { it.mkdirs() }
      override fun getCacheDir() = File(directory, "cache").also { it.mkdirs() }
    })
    db = PampaDatabase.inMemory(context)
    adapter = AndroidRelayLocalFiles(db, files, FilesInUse())
  }
  @After fun cleanup() { db.close(); directory.deleteRecursively() }

  @Test fun genuineNullRefOriginalsReserveIndependentNamesIdempotently() = runTest {
    db.folders().upsert(folder)
    db.notes().upsert(note)
    val source = source("original", SourceKind.PDF).copy(mime = "application/pdf")
    val independent = source.copy(id = "independent")
    db.sources().upsert(source)
    db.sources().upsert(independent)
    val first = adapter.get(RelayKind.SOURCE, source.id)!!
    assertEquals(PampaBundleOmissions.expectedSourceName(source), first.fileName)
    assertEquals(first, adapter.get(RelayKind.SOURCE, source.id))
    assertEquals(source.copy(storedFileName = first.fileName), db.sources().get(source.id))
    assertEquals(0L, db.sources().get(source.id)!!.archivedAt)
    assertFalse(adapter.path(first).exists())
    assertNotEquals(first.fileName, adapter.get(RelayKind.SOURCE, independent.id)!!.fileName)
  }

  @Test fun textClipboardShareAndUnknownOriginalNeverAcquireFabricatedFileRefs() = runTest {
    db.folders().upsert(folder)
    db.notes().upsert(note)
    val rows = listOf(source("clipboard", SourceKind.CLIPBOARD), source("share", SourceKind.SHARE),
      source("unknown", SourceKind.PDF).copy(sha256 = ""))
    rows.forEach { db.sources().upsert(it) }
    rows.forEach { row ->
      assertNull(adapter.get(RelayKind.SOURCE, row.id))
      assertEquals(row, db.sources().get(row.id))
    }
  }

  @Test fun filelessClipboardAndShareImportAndReexportWithoutWarningOrLostProvenance() = runTest {
    val sources = listOf(source("clipboard", SourceKind.CLIPBOARD), source("share", SourceKind.SHARE))
    val initial = PampaBundleNote(note, sources = sources)
    val importer = PampaBundleImporter(db, files)
    val first = archive(initial, "first")
    assertTrue(importer.inspect(first).warnings.isEmpty())
    val result = importer.import(first)
    assertTrue(result.warnings.isEmpty())
    assertEquals(note.body, db.notes().get(note.id)!!.body)
    assertEquals(sources.toSet(), db.sources().byNote(note.id).toSet())
    val next = archive(PampaBundleNote(db.notes().get(note.id)!!, sources = db.sources().byNote(note.id)), "next")
    assertTrue(PampaBundleArchive.read(next).warnings.isEmpty())
    assertTrue(db.sources().byNote(note.id).all { it.storedFileName == null })
  }

  @Test fun actualClipboardFileImportsWithVerifiedBytesAndNormalFileSemantics() = runTest {
    val bytes = "Attached text file".toByteArray()
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val source = source("attached", SourceKind.CLIPBOARD).copy(storedFileName = "attached.txt", sizeBytes = bytes.size.toLong(), sha256 = hash)
    files.sourceFile(source.storedFileName!!).writeBytes(bytes)
    val first = archive(PampaBundleNote(note, sources = listOf(source)), "attached")
    val result = PampaBundleImporter(db, files).import(first)
    assertTrue(result.warnings.isEmpty())
    val imported = db.sources().get(source.id)!!
    assertNotNull(imported.storedFileName)
    assertArrayEquals(bytes, files.sourceFile(imported.storedFileName!!).readBytes())
    assertEquals(source.copy(storedFileName = imported.storedFileName), imported)
    assertFalse(PampaBundleArchive.read(archive(PampaBundleNote(db.notes().get(note.id)!!,
      sources = listOf(imported)), "attached-next")).data.notes.single().attachments.single().fileless)
  }

  /** Integration dependency: importer must retain an explicit non-fileless text-file omission. */
  @Test fun omittedAttachedShareKeepsMissingFileWarningAfterRoomImportAndReexport() = runTest {
    val source = source("omitted-share", SourceKind.SHARE).copy(storedFileName = "evicted.txt")
    val first = archive(PampaBundleNote(note, sources = listOf(source)), "omitted")
    val importer = PampaBundleImporter(db, files)
    assertEquals(listOf(PampaBundleWarning.OMITTED_ATTACHMENTS), importer.inspect(first).warnings)
    assertEquals(listOf(PampaBundleWarning.OMITTED_ATTACHMENTS), importer.import(first).warnings)
    val imported = db.sources().get(source.id)!!
    // This name records known omitted original bytes, not a fabricated playable blob.
    assertNotNull(imported.storedFileName)
    assertFalse(files.sourceFile(imported.storedFileName!!).isFile)
    val next = archive(PampaBundleNote(db.notes().get(note.id)!!, sources = listOf(imported)), "omitted-next")
    assertEquals(listOf(PampaBundleWarning.OMITTED_ATTACHMENTS), PampaBundleArchive.read(next).warnings)
  }

  private fun archive(native: PampaBundleNote, suffix: String): File {
    val set = ExportSet("Text", "text", listOf(ExportNote(native.note, listOf(folder.name), emptyList(), emptyList(), emptyList(),
      native = native)), "test", 1, folders = listOf(folder))
    return File(directory, "$suffix.pn").also { file ->
      file.outputStream().use { PampaNoteWriter(files.audio, files.sources).write(set, it) }
    }
  }

  private fun source(id: String, kind: SourceKind) = SourceEntity(id, note.id, kind, "Text", "text/plain", 3,
    "a".repeat(64), extractedChars = 3, importedAt = 2)
}
