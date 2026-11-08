package dev.pampa.pampanotes.core.sync

import android.content.ContextWrapper
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.export.ExportNote
import dev.pampa.pampanotes.core.export.ExportSet
import dev.pampa.pampanotes.core.export.PampaNoteWriter
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.Hashing
import dev.pampa.pampanotes.core.importing.PampaBundleArchive
import dev.pampa.pampanotes.core.importing.PampaBundleNote
import dev.pampa.pampanotes.core.importing.PampaBundleOmissions
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Prepared for parent compilation/integration; no Android runtime is run in this correction. */
@RunWith(AndroidJUnit4::class)
class PampaSyncWriteLifecycleRoundFourRoomTest {
  private lateinit var directory: File
  private lateinit var db: PampaDatabase
  private lateinit var files: AppFiles
  private lateinit var applier: SyncApplier
  private val bytes = "Identical donor bytes for independent sync entities".toByteArray()
  private val sha = Hashing.sha256(bytes.inputStream())
  private val future get() = System.currentTimeMillis() + 60 * 60_000L
  private val folder = FolderEntity("folder", "Folder", createdAt = 1, updatedAt = 1)
  private val note = NoteEntity("note", folder.id, "Note", String(bytes), createdAt = 2, updatedAt = 2)
  private val donorPart get() = AudioPartEntity("donor-part", "session", 0, "donor.m4a", "Donor.m4a",
    "audio/mp4", bytes.size.toLong(), 1000, sha, 3)
  private val donorSource get() = SourceEntity("donor-source", note.id, SourceKind.SHARE, "Donor.txt", "text/plain",
    bytes.size.toLong(), sha, storedFileName = "donor.txt", importedAt = 4)

  @Before fun setUp() = runBlocking {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    directory = File(base.cacheDir, "pn-sync-lifecycle-r4-${UUID.randomUUID()}").apply { mkdirs() }
    val context = object : ContextWrapper(base) {
      override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
      override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
    }
    db = PampaDatabase.inMemory(context)
    files = AppFiles(context)
    val payloads = SyncPayloads(db.folders(), db.notes(), db.tags(), db.sessions(), db.audioParts(), db.transcripts(),
      db.segments(), db.sources(), db.exportPresets(), db.stats(), db.sync())
    applier = SyncApplier(db, files, payloads)
    apply(up("folders", folder.id, FolderEntity.serializer(), folder),
      up("notes", note.id, NotePayload.serializer(), NotePayload(note)),
      up("sessions", "session", SessionEntity.serializer(),
        SessionEntity("session", note.id, date = "2026-11-08", position = 0, createdAt = 3, updatedAt = 3)))
    files.audioFile(donorPart.fileName).writeBytes(bytes)
    files.sourceFile(donorSource.storedFileName!!).writeBytes(bytes)
    apply(up("audio_parts", donorPart.id, AudioPartEntity.serializer(), donorPart),
      up("sources", donorSource.id, SourceEntity.serializer(), donorSource))
  }

  @After fun tearDown() {
    db.close()
    directory.deleteRecursively()
  }

  private fun <T> up(table: String, id: String, serializer: KSerializer<T>, value: T, seq: Long = 5): WireChange {
    val payload = SyncCodec.json.encodeToJsonElement(serializer, value)
    return WireChange(table, id, "U", 10, SyncCodec.hash(payload), payload, seq = seq, deviceId = "remote")
  }

  private suspend fun apply(vararg changes: WireChange) = applier.apply(changes.toList(), "account", "device")
  private suspend fun retained(): Set<File> = db.audioParts().fileNames().map { files.audioFile(it).canonicalFile }.toSet() +
    db.sources().storedFileNames().map { files.sourceFile(it).canonicalFile }

  @Test fun actualSyncPageCommitAndOwnerReleaseSurviveASweepWhoseReferencesPredateBothCopies() = runBlocking {
    val targets = files.beginOrphanSweep().use { stale ->
      val audioRefs = db.audioParts().fileNames().toSet()
      val sourceRefs = db.sources().storedFileNames().toSet()
      apply(up("audio_parts", "copy-part", AudioPartEntity.serializer(), donorPart.copy(id = "copy-part", position = 1), 20),
        up("sources", "copy-source", SourceEntity.serializer(), donorSource.copy(id = "copy-source"), 21))
      val part = db.audioParts().get("copy-part")!!
      val source = db.sources().get("copy-source")!!
      assertNotEquals(donorPart.fileName, part.fileName)
      assertNotEquals(donorSource.storedFileName, source.storedFileName)
      val targets = listOf(files.audioFile(part.fileName), files.sourceFile(source.storedFileName!!))
      // apply has completed its commit, postcommit cleanup and write-owner release.
      assertEquals(0, files.sweepOrphans(audioRefs, sourceRefs, emptySet(), stale, future))
      targets.forEach { assertArrayEquals(bytes, it.readBytes()) }
      targets
    }
    assertEquals(0, files.sweepOrphans(db.audioParts().fileNames().toSet(), db.sources().storedFileNames().toSet(), emptySet(), future))
    db.audioParts().delete(donorPart.id)
    db.sources().delete(donorSource.id)
    assertTrue(files.audioFile(donorPart.fileName).delete())
    assertTrue(files.sourceFile(donorSource.storedFileName!!).delete())
    targets.forEach { assertArrayEquals(bytes, it.readBytes()) }
    db.audioParts().delete("copy-part")
    db.sources().delete("copy-source")
    assertEquals("No page owner may leak beyond finally", 2, files.sweepOrphans(emptySet(), emptySet(), emptySet(), future))
  }

  @Test fun realRoomRollbackAndPostcommitCancellationUseCurrentReferencesBeforeRelease() = runBlocking {
    for (committed in listOf(false, true)) {
      val created = mutableListOf<File>()
      val owner = SyncLocalFiles.beginWrites()
      var target: File? = null
      files.beginOrphanSweep().use { stale ->
        try {
          db.withTransaction {
            val name = SyncLocalFiles.selectName(files.audio, donorPart.fileName, donorPart.sizeBytes, donorPart.sha256,
              null, setOf(donorPart.fileName), sequenceOf(donorPart.fileName), created, owner)!!
            target = files.audioFile(name)
            db.audioParts().upsert(donorPart.copy(id = "cancel-part", fileName = name, position = 1))
            if (!committed) throw CancellationException("before commit")
          }
          throw CancellationException("after commit before caller observes success")
        } catch (_: CancellationException) {
          // Cancellation can represent either state; finally must query committed Room rows.
        } finally {
          withContext(NonCancellable) {
            try { db.withTransaction { SyncLocalFiles.cleanupCreated(created, retained()) } }
            finally { owner.close() }
          }
        }
        assertEquals(committed, db.audioParts().get("cancel-part") != null)
        assertEquals(committed, target!!.isFile)
        assertEquals(0, files.sweepOrphans(setOf(donorPart.fileName), setOf(donorSource.storedFileName!!), emptySet(), stale, future))
      }
      if (committed) {
        assertArrayEquals(bytes, target!!.readBytes())
        db.audioParts().delete("cancel-part")
      } else target!!.writeBytes(bytes) // A lingering hold on a deleted partial file must be detected.
      assertEquals(1, files.sweepOrphans(setOf(donorPart.fileName), setOf(donorSource.storedFileName!!), emptySet(), future))
      assertArrayEquals(bytes, files.audioFile(donorPart.fileName).readBytes())
    }
  }

  @Test fun actualSyncKeepsTrueTextFilelessWhileOmittedTextAttachmentsRecoverAndReexport() = runBlocking {
    for (kind in listOf(SourceKind.SHARE, SourceKind.CLIPBOARD)) {
      val fileless = donorSource.copy(id = "fileless-$kind", kind = kind, storedFileName = null)
      apply(up("sources", fileless.id, SourceEntity.serializer(), fileless, 30))
      apply(up("sources", fileless.id, SourceEntity.serializer(), fileless.copy(detail = "Remote metadata edit"), 31))
      val saved = db.sources().get(fileless.id)!!
      assertNull(saved.storedFileName)
      assertTrue(exportDeclaration(saved).fileless)
      val omitted = fileless.copy(id = "omitted-$kind").let {
        it.copy(storedFileName = PampaBundleOmissions.expectedFileName(it.id))
      }
      apply(up("sources", omitted.id, SourceEntity.serializer(), omitted, 32))
      val recovered = db.sources().get(omitted.id)!!
      assertNotNull(recovered.storedFileName)
      assertNotEquals(donorSource.storedFileName, recovered.storedFileName)
      assertArrayEquals(bytes, files.sourceFile(recovered.storedFileName!!).readBytes())
      val declaration = exportDeclaration(recovered)
      assertFalse(declaration.fileless)
      assertNotNull(declaration.path)
    }
  }

  private fun exportDeclaration(source: SourceEntity) = File(directory, "${source.id}.pn").let { archive ->
    val native = PampaBundleNote(note, sources = listOf(source))
    val set = ExportSet("Note", "note", listOf(ExportNote(note, listOf(folder.name), emptyList(), emptyList(),
      emptyList(), native = native)), "test", 1, folders = listOf(folder))
    archive.outputStream().use { PampaNoteWriter(files.audio, files.sources).write(set, it) }
    PampaBundleArchive.read(archive).data.notes.single().attachments.single()
  }
}
