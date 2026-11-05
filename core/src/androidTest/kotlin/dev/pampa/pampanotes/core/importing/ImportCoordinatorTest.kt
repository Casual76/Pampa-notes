package dev.pampa.pampanotes.core.importing

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.archive.ArchiveFetcher
import dev.pampa.pampanotes.core.archive.ArchiveHttp
import dev.pampa.pampanotes.core.archive.ArchiveRepository
import dev.pampa.pampanotes.core.archive.ComputerOnlyScope
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SourceStatus
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.repo.NoteRepository
import dev.pampa.pampanotes.core.transcription.ComputerAuth
import dev.pampa.pampanotes.core.repo.StorageRepository
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.EndpointResolver
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L'import dal vero: file su disco, database vero, nessun mock.
 *
 * Gli URI arrivano da un `file://`, non da un provider: quello che si prova qui e' la catena
 * copia -> riconoscimento -> estrazione -> scrittura, e per quella la sorgente dell'URI non conta.
 */
@RunWith(AndroidJUnit4::class)
class ImportCoordinatorTest {

  private lateinit var context: Context
  private lateinit var db: PampaDatabase
  private lateinit var files: AppFiles
  private lateinit var coordinator: ImportCoordinator
  private lateinit var workDir: File

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    db = PampaDatabase.inMemory(context)
    files = AppFiles(context)
    workDir = File(context.cacheDir, "import-test").apply { deleteRecursively(); mkdirs() }

    // Il computer di casa qui non c'e': nessun indirizzo configurato, quindi archivio e download non
    // fanno richieste. Servono solo perche' le pagine a mano sanno dove chiedere un originale.
    val settings = PampaSettingsStore(context)
    val computerOnly = ComputerOnlyScope(settings, db.folders(), db.sync())
    val extractors = TextExtractorRegistry(PlainTextExtractor(), PdfTextExtractor(context), DocxTextExtractor())
    val audio = AudioImporter(files, db.sessions(), db.audioParts())
    val resolver = EndpointResolver()
    val http = ArchiveHttp(userAgent = "PampaNotes-test")
    // Senza account e senza codice: il companion qui non viene mai chiamato.
    val auth = ComputerAuth(account = { null }, manualCode = { null }, fetch = { _, _ -> error("nessun Worker nei test") }, clock = System::currentTimeMillis)
    val archive = ArchiveRepository(db.audioParts(), db.sources(), files, settings, resolver, http, auth)
    val storage = StorageRepository(db.audioParts(), db.sources(), db.jobs(), files, computerOnly, archive, FilesInUse())
    val notes = NoteRepository(db.notes(), db.tags(), storage)
    val fetcher = ArchiveFetcher(files, settings, resolver, http, db.audioParts(), db.sources(), auth, computerOnly)
    coordinator = ImportCoordinator(
      context = context,
      files = files,
      notes = notes,
      noteDao = db.notes(),
      sources = db.sources(),
      audioParts = db.audioParts(),
      sessions = db.sessions(),
      extractors = extractors,
      audioImporter = audio,
      archive = archive,
      handwriting = HandwritingPages(db.sources(), files, fetcher, archive),
      // La riparazione ha i suoi test (SdocxRepairPlanTest, SessionRepositoryTest): qui un
      // aggiornamento che la chiede la trova assente, e l'import non se ne accorge.
      repairer = { error("nessuna riparazione in questi test") },
    )
  }

  @After
  fun tearDown() {
    db.close()
    workDir.deleteRecursively()
    files.audio.listFiles()?.forEach { it.delete() }
    files.sources.listFiles()?.forEach { it.delete() }
  }

  @Test
  fun un_markdown_diventa_il_corpo_di_una_nota_nuova() = runTest {
    val folder = seedFolder()
    val file = write("kant.md", "# Kant\n\nLa ragione pratica viene prima.")

    val candidates = coordinator.inspect(listOf(android.net.Uri.fromFile(file)))
    assertEquals(1, candidates.size)
    assertEquals(SourceKind.MARKDOWN, candidates.first().kind)

    val outcome = coordinator.importAll(candidates, ImportTarget.NewNote(folder, "Immanuel Kant"))

    val note = db.notes().get(outcome.noteId)
    assertNotNull(note)
    assertEquals("Immanuel Kant", note!!.title)
    assertTrue(note.body.contains("La ragione pratica viene prima."))
    // Il titolo del file resta come intestazione: tre PDF in una nota devono restare distinguibili.
    assertTrue(note.body.contains("## kant.md"))

    val source = db.sources().byNote(outcome.noteId).single()
    assertEquals(SourceStatus.OK, source.status)
    assertTrue(source.extractedChars > 0)
    // L'originale si conserva, per riestrarlo e per l'export.
    assertTrue(files.sourceFile(source.storedFileName!!).exists())
  }

  @Test
  fun un_secondo_import_si_aggiunge_in_fondo_alla_stessa_nota() = runTest {
    val folder = seedFolder()
    val first = coordinator.inspect(listOf(android.net.Uri.fromFile(write("uno.md", "Primo pezzo."))))
    val outcome = coordinator.importAll(first, ImportTarget.NewNote(folder, "Nota"))

    val second = coordinator.inspect(listOf(android.net.Uri.fromFile(write("due.md", "Secondo pezzo."))))
    coordinator.importAll(second, ImportTarget.ExistingNote(outcome.noteId))

    val body = db.notes().get(outcome.noteId)!!.body
    assertTrue(body.indexOf("Primo pezzo.") < body.indexOf("Secondo pezzo."))
    assertEquals(2, db.sources().byNote(outcome.noteId).size)
  }

  @Test
  fun lo_stesso_file_importato_due_volte_viene_segnalato() = runTest {
    val folder = seedFolder()
    val file = write("kant.md", "Sempre lo stesso testo.")
    val first = coordinator.inspect(listOf(android.net.Uri.fromFile(file)))
    coordinator.importAll(first, ImportTarget.NewNote(folder, "Prima"))

    val again = coordinator.inspect(listOf(android.net.Uri.fromFile(write("copia.md", "Sempre lo stesso testo."))))

    assertTrue(again.single().isDuplicate)
    assertEquals("Prima", again.single().duplicateOfNoteTitle)
  }

  @Test
  fun il_testo_incollato_non_lascia_un_file_dietro() = runTest {
    val folder = seedFolder()
    val candidate = coordinator.inspectText("Appunti presi al volo.", "Appunti")
    val outcome = coordinator.importAll(listOf(candidate), ImportTarget.NewNote(folder, "Al volo"))

    val source = db.sources().byNote(outcome.noteId).single()
    assertEquals(SourceKind.CLIPBOARD, source.kind)
    assertEquals(null, source.storedFileName)
    assertTrue(db.notes().get(outcome.noteId)!!.body.contains("Appunti presi al volo."))
  }

  @Test
  fun un_tipo_che_non_sappiamo_leggere_resta_allegato_invece_di_sparire() = runTest {
    val folder = seedFolder()
    val file = File(workDir, "misterioso.xyz").apply { writeBytes(byteArrayOf(0, 1, 2, 3, 0, 9, 7)) }

    val candidates = coordinator.inspect(listOf(android.net.Uri.fromFile(file)))
    val outcome = coordinator.importAll(candidates, ImportTarget.NewNote(folder, "Con allegato"))

    val source = db.sources().byNote(outcome.noteId).single()
    assertEquals(SourceKind.OTHER, source.kind)
    assertEquals(SourceStatus.PARTIAL, source.status)
    assertNotNull(source.storedFileName)
    assertTrue(files.sourceFile(source.storedFileName!!).exists())
  }

  @Test
  fun samsung_update_follows_original_source_after_note_rename_and_rejects_ambiguity() = runTest {
    val folder = seedFolder()
    val now = System.currentTimeMillis()
    val note = dev.pampa.pampanotes.core.db.NoteEntity(id = "renamed", folderId = folder, title = "My merged notes", createdAt = now, updatedAt = now)
    db.notes().upsert(note)
    val original = samsungFile("original.sdocx", "Caf\u00e9 filosofia")
    val storedName = "old-source.sdocx"
    original.copyTo(files.sourceFile(storedName), overwrite = true)
    val source = dev.pampa.pampanotes.core.db.SourceEntity(id = "old-source", noteId = note.id,
      kind = SourceKind.SDOCX, originalName = "dirty_260921_101530.sdocx", mime = "application/sdoc",
      sizeBytes = original.length(), sha256 = "old", storedFileName = storedName, importedAt = now)
    db.sources().upsert(source)
    val incoming = samsungFile("new_260921_101531.sdocx", "Cafe\u0301\u00a0filosofia")
    val candidate = coordinator.inspect(listOf(android.net.Uri.fromFile(incoming))).single()
    assertEquals(note.id, candidate.updateOfNoteId)
    assertEquals(source.id, candidate.updateOfSourceId)
    db.notes().upsert(note.copy(id = "ambiguous"))
    db.sources().upsert(source.copy(id = "another-source", noteId = "ambiguous"))
    val ambiguous = coordinator.inspect(listOf(android.net.Uri.fromFile(incoming))).single()
    assertEquals(null, ambiguous.updateOfNoteId)
  }

  @Test
  fun samsung_creation_matches_even_after_original_title_changes() = runTest {
    val folder = seedFolder()
    val now = System.currentTimeMillis()
    val note = dev.pampa.pampanotes.core.db.NoteEntity(id = "renamed-source", folderId = folder, title = "Merged notes", createdAt = now, updatedAt = now)
    db.notes().upsert(note)
    val original = samsungFile("old.sdocx", "Original title", 1_700_000_000_000L)
    original.copyTo(files.sourceFile("renamed-source.sdocx"), overwrite = true)
    db.sources().upsert(dev.pampa.pampanotes.core.db.SourceEntity(id = "renamed-original", noteId = note.id,
      kind = SourceKind.SDOCX, originalName = "old.sdocx", mime = "application/sdoc", sizeBytes = original.length(),
      sha256 = "old", storedFileName = "renamed-source.sdocx", importedAt = now))
    val incoming = samsungFile("new.sdocx", "Completely renamed title", 1_700_000_000_000L)
    assertEquals(note.id, coordinator.inspect(listOf(android.net.Uri.fromFile(incoming))).single().updateOfNoteId)
  }

  private fun samsungFile(name: String, title: String, createdAt: Long? = null): File {
    val file = File(workDir, name)
    java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
      zip.putNextEntry(java.util.zip.ZipEntry("note.note"))
      if (createdAt != null) {
        zip.write(java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN)
          .putLong(24, createdAt * 1000).putLong(32, (createdAt + 1000) * 1000).array())
      }
      val bytes = title.toByteArray(Charsets.UTF_16LE)
      zip.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(title.length).array())
      zip.write(bytes)
      zip.closeEntry()
    }
    return file
  }

  private suspend fun seedFolder(): String {
    val now = System.currentTimeMillis()
    val folder = FolderEntity(id = "folder-test", name = "Filosofia", createdAt = now, updatedAt = now)
    db.folders().upsert(folder)
    return folder.id
  }

  private fun write(name: String, content: String): File =
    File(workDir, name).apply { writeText(content) }
}
