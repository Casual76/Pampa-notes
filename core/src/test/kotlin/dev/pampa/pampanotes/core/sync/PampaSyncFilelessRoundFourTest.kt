package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.export.ExportNote
import dev.pampa.pampanotes.core.export.ExportSet
import dev.pampa.pampanotes.core.export.PampaNoteWriter
import dev.pampa.pampanotes.core.files.Hashing
import dev.pampa.pampanotes.core.importing.PampaBundleArchive
import dev.pampa.pampanotes.core.importing.PampaBundleNote
import dev.pampa.pampanotes.core.importing.PampaBundleOmissions
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Fileless text must keep its provenance even when another entity has identical original bytes. */
class PampaSyncFilelessRoundFourTest {
  @get:Rule val temp = TemporaryFolder()
  private val bytes = "Shared text with an equal attachment donor".toByteArray()
  private val sha = Hashing.sha256(bytes.inputStream())
  private val folder = FolderEntity("folder", "Folder", createdAt = 1, updatedAt = 1)
  private val note = NoteEntity("note", folder.id, "Note", String(bytes), createdAt = 2, updatedAt = 2)
  private fun text(kind: SourceKind = SourceKind.SHARE) =
    SourceEntity("source", note.id, kind, "Shared text", "text/plain", bytes.size.toLong(), sha, importedAt = 3)

  private fun select(root: File, incoming: SourceEntity, local: SourceEntity?, donor: File,
    created: MutableList<File>, owner: SyncLocalFiles.WriteOwner, candidates: Sequence<String?> = sequenceOf(donor.name)) =
    SyncLocalFiles.selectName(root, incoming.storedFileName, incoming.sizeBytes, incoming.sha256, local?.storedFileName,
      setOf(donor.name), candidates, created, owner,
      preserveFileless = SyncLocalFiles.preserveFilelessSource(local, incoming))

  private fun declaration(source: SourceEntity, root: File) = temp.newFile().let { archive ->
    val native = PampaBundleNote(note, sources = listOf(source))
    val set = ExportSet("Note", "note", listOf(ExportNote(note, listOf(folder.name), emptyList(), emptyList(),
      emptyList(), native = native)), "test", 1, folders = listOf(folder))
    archive.outputStream().use { PampaNoteWriter(temp.newFolder(), root).write(set, it) }
    PampaBundleArchive.read(archive).data.notes.single().attachments.single()
  }

  @Test fun trueShareAndClipboardRemainNullWithoutReadingEqualDonorsAndReexportFileless() {
    for (kind in listOf(SourceKind.SHARE, SourceKind.CLIPBOARD)) {
      val root = temp.newFolder()
      val donor = File(root, "donor.txt").also { it.writeBytes(bytes) }
      val source = text(kind)
      val before = declaration(source, root)
      assertTrue(before.fileless)
      for (local in listOf(null, source)) {
        val created = mutableListOf<File>()
        SyncLocalFiles.beginWrites().use { owner ->
          val name = select(root, source, local, donor, created, owner,
            sequence { error("Fileless source must not enumerate donor candidates") })
          assertNull(name)
          assertTrue(created.isEmpty())
          assertEquals(before, declaration(source.copy(storedFileName = name), root))
        }
      }
      assertEquals(listOf(donor), root.listFiles()!!.toList())
    }
  }

  @Test fun actualOmittedShareAndClipboardAttachmentsRecoverDespiteTheirTextMime() {
    for (kind in listOf(SourceKind.SHARE, SourceKind.CLIPBOARD)) {
      val root = temp.newFolder()
      val donor = File(root, "donor.txt").also { it.writeBytes(bytes) }
      val source = text(kind).let { it.copy(storedFileName = PampaBundleOmissions.expectedFileName(it.id)) }
      assertFalse(declaration(source, root).fileless)
      val created = mutableListOf<File>()
      SyncLocalFiles.beginWrites().use { owner ->
        val name = select(root, source, source, donor, created, owner)!!
        assertNotEquals(donor.name, name)
        val recovered = File(root, name)
        assertArrayEquals(bytes, recovered.readBytes())
        SyncLocalFiles.cleanupCreated(created, setOf(recovered.canonicalFile))
        val exported = declaration(source.copy(storedFileName = name), root)
        assertFalse(exported.fileless)
        assertNotNull(exported.path)
        assertTrue(donor.delete())
        assertArrayEquals(bytes, recovered.readBytes())
      }
    }
  }

  @Test fun nullReferencesForBinaryArchivedDerivedAndOtherKindsRemainRecoverable() {
    val base = text()
    val originals = listOf(base.copy(kind = SourceKind.PDF, mime = "application/pdf"),
      base.copy(kind = SourceKind.TEXT), base.copy(mime = "application/octet-stream"),
      base.copy(archivedAt = 7), base.copy(derivedFromId = "parent"))
    for (source in originals) {
      val root = temp.newFolder()
      val donor = File(root, "donor.bin").also { it.writeBytes(bytes) }
      val created = mutableListOf<File>()
      assertFalse(SyncLocalFiles.preserveFilelessSource(source, source))
      SyncLocalFiles.beginWrites().use { owner ->
        val recovered = File(root, select(root, source, source, donor, created, owner)!!)
        assertNotEquals(donor, recovered)
        assertArrayEquals(bytes, recovered.readBytes())
        SyncLocalFiles.cleanupCreated(created, emptySet())
      }
    }
    assertTrue(SyncLocalFiles.preserveFilelessSource(null, base.copy(mime = " Text/Plain ; charset=UTF-8")))
  }

  @Test fun existingOriginalAndExpectedMissingLocalReferenceCannotBeWaivedByIncomingNull() {
    val root = temp.newFolder()
    val donor = File(root, "donor.txt").also { it.writeBytes(bytes) }
    val incoming = text()
    for (name in listOf("own.txt", PampaBundleOmissions.expectedFileName(incoming.id))) {
      val local = incoming.copy(storedFileName = name)
      if (name == "own.txt") File(root, name).writeBytes(bytes)
      assertFalse(SyncLocalFiles.preserveFilelessSource(local, incoming))
      val created = mutableListOf<File>()
      SyncLocalFiles.beginWrites().use { owner ->
        val selected = select(root, incoming, local, donor, created, owner)!!
        assertArrayEquals(bytes, File(root, selected).readBytes())
        if (name == "own.txt") assertEquals(name, selected) else assertNotEquals(donor.name, selected)
        SyncLocalFiles.cleanupCreated(created, emptySet())
      }
    }
    assertFalse(SyncLocalFiles.preserveFilelessSource(incoming.copy(archivedAt = 7), incoming))
    assertFalse(SyncLocalFiles.preserveFilelessSource(incoming, incoming.copy(storedFileName = "remote.txt")))
  }
}
