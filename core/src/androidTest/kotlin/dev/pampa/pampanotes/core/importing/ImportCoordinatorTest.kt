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
import dev.pampa.pampanotes.core.relay.*
import dev.pampa.pampanotes.core.transcription.ComputerAuth
import dev.pampa.pampanotes.core.repo.StorageRepository
import dev.pampa.pampanotes.core.repo.DatabaseTransactions
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
  private lateinit var identities: SdocxSourceIdentity
  private lateinit var coordinator: ImportCoordinator
  private lateinit var workDir: File
  private var providerResolver: android.content.ContentResolver? = null

  @Before
  fun setUp() {
    context = object : android.content.ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
      override fun getContentResolver() = providerResolver ?: super.getContentResolver()
    }
    db = PampaDatabase.inMemory(context)
    files = AppFiles(context)
    workDir = File(context.cacheDir, "import-test").apply { deleteRecursively(); mkdirs() }

    // Il computer di casa qui non c'e': nessun indirizzo configurato, quindi archivio e download non
    // fanno richieste. Servono solo perche' le pagine a mano sanno dove chiedere un originale.
    val settings = PampaSettingsStore(context)
    val computerOnly = ComputerOnlyScope(settings, db.folders(), db.sync())
    val extractors = TextExtractorRegistry(PlainTextExtractor(), PdfTextExtractor(context), DocxTextExtractor())
    val audio = AudioImporter(files, db.sessions(), db.audioParts(), db)
    val resolver = EndpointResolver()
    val http = ArchiveHttp(userAgent = "PampaNotes-test")
    // Senza account e senza codice: il companion qui non viene mai chiamato.
    val auth = ComputerAuth(account = { null }, manualCode = { null }, fetch = { _, _ -> error("nessun Worker nei test") }, clock = System::currentTimeMillis)
    val archive = ArchiveRepository(db.audioParts(), db.sources(), files, settings, resolver, http, auth)
    val storage = StorageRepository(db.audioParts(), db.sources(), db.jobs(), files, computerOnly, archive, FilesInUse(), DatabaseTransactions(db))
    val notes = NoteRepository(db.notes(), db.tags(), storage)
    // Explicitly no relay account: the target app's saved settings must never reach a real API.
    val relay = RelayRepository(object : RelayAccounts {
      override suspend fun snapshot(): RelaySession = throw RelayFailure(RelayState.AUTH_REQUIRED, "test_no_session")
      override suspend fun isCurrent(session: RelaySession) = false
    }, AndroidRelayLocalFiles(db, files, FilesInUse()), RelayHttp(), RelayJournal(File(workDir, "relay")), object : RelayClock {
      override fun elapsedMillis() = android.os.SystemClock.elapsedRealtime()
      override fun wallMillis() = System.currentTimeMillis()
    })
    val fetcher = ArchiveFetcher(files, settings, resolver, http, db.audioParts(), db.sources(), auth, computerOnly, relay)
    identities = SdocxSourceIdentity(db.sources(), files, dev.pampa.pampanotes.core.archive.FileMetaApi(
      dev.pampa.pampanotes.core.transcription.TranscriptionHttp("PampaNotes-test"), resolver, settings, auth))
    coordinator = ImportCoordinator(
      context = context,
      files = files,
      notes = notes,
      noteDao = db.notes(),
      sources = db.sources(),
      sourceIdentity = identities,
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
    val bundleImporter = PampaBundleImporter(db, files)
    coordinator.bundleImporter = dagger.Lazy { bundleImporter }
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

  @Test
  fun samsung_update_preserves_personal_editor_additions() = runTest {
    val folder = seedFolder()
    val original = samsungFile("personal-old.sdocx", "Samsung personal notes", body = "Original imported body.")
    val first = coordinator.importAll(coordinator.inspect(listOf(android.net.Uri.fromFile(original))),
      ImportTarget.NewNote(folder, "Samsung personal notes"))
    val note = db.notes().get(first.noteId)!!
    db.notes().upsert(note.copy(body = note.body + "\n\nMy personal editor additions."))
    val revised = samsungFile("personal-new.sdocx", "Samsung personal notes", body = "Revised imported body.")
    val candidates = coordinator.inspect(listOf(android.net.Uri.fromFile(revised)))
    assertEquals(note.id, candidates.single().updateOfNoteId)
    coordinator.importAll(candidates, ImportTarget.UpdateNote(note.id))
    assertEquals("Revised imported body.\n\nMy personal editor additions.", db.notes().get(note.id)!!.body)
    assertEquals(1, db.sources().byNote(note.id).count { it.kind == SourceKind.SDOCX })
  }

  @Test
  fun samsung_identity_survives_original_eviction_and_renamed_incoming_title() = runTest {
    val folder = seedFolder()
    val original = samsungFile("Alpha.sdocx", "Alpha original note", 1_700_000_000_000L, "Original body.")
    val first = coordinator.importAll(coordinator.inspect(listOf(android.net.Uri.fromFile(original))),
      ImportTarget.NewNote(folder, "Alpha original note"))
    val source = db.sources().byNote(first.noteId).single()
    assertEquals(1_700_000_000_000L, source.sdocxCreatedAtMillis)
    assertTrue(files.sourceFile(source.storedFileName!!).delete())
    val renamed = samsungFile("Beta.sdocx", "Beta renamed note", 1_700_000_000_000L, "Updated body.")
    val candidate = coordinator.inspect(listOf(android.net.Uri.fromFile(renamed))).single()
    assertEquals(first.noteId, candidate.updateOfNoteId)
    assertEquals(source.id, candidate.updateOfSourceId)
    val unrelated = samsungFile("Alpha-again.sdocx", "Alpha original note", 1_700_000_100_000L, "Unrelated body.")
    assertEquals(null, coordinator.inspect(listOf(android.net.Uri.fromFile(unrelated))).single().updateOfNoteId)
  }

  @Test
  fun legacy_local_identity_backfill_survives_eviction_without_changing_source_content() = runTest {
    val folder = seedFolder()
    val original = samsungFile("Legacy.sdocx", "Legacy Samsung title", 1_700_000_000_000L, "Original body.")
    val first = coordinator.importAll(coordinator.inspect(listOf(android.net.Uri.fromFile(original))),
      ImportTarget.NewNote(folder, "Local title"))
    val source = db.sources().byNote(first.noteId).single().copy(sdocxCreatedAtMillis = null)
    db.sources().upsert(source)
    assertEquals(1, identities.backfill())
    assertEquals(0, identities.backfill())
    val backfilled = db.sources().get(source.id)!!
    assertEquals(source, backfilled.copy(sdocxCreatedAtMillis = null))
    assertTrue(files.sourceFile(source.storedFileName!!).delete())
    val renamed = samsungFile("Renamed.sdocx", "Renamed Samsung title", 1_700_000_000_000L, "Updated body.")
    assertEquals(first.noteId, coordinator.inspect(listOf(android.net.Uri.fromFile(renamed))).single().updateOfNoteId)
  }

  private fun samsungFile(name: String, title: String, createdAt: Long? = null, body: String? = null): File {
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
      if (body != null) {
        zip.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(body.length).array())
        zip.write(body.toByteArray(Charsets.UTF_16LE))
      }
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

  /** In-process real ContentProvider transport, without relying on a particular file manager. */
  @android.annotation.TargetApi(29)
  private fun provider(file: File, name: String?, mime: String?, failMetadata: Boolean = false): android.net.Uri {
    org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
    val provider = object : android.content.ContentProvider() {
      override fun onCreate() = true
      override fun getType(uri: android.net.Uri): String? {
        if (failMetadata) throw SecurityException("provider refuses optional MIME metadata")
        return mime
      }
      override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor {
        if (failMetadata) throw UnsupportedOperationException("provider has no display name")
        return android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf<Any?>(name)) }
      }
      override fun openFile(uri: android.net.Uri, mode: String) = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
      override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
      override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
      override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }
    provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "pnfixture"; exported = false })
    providerResolver = android.content.ContentResolver.wrap(provider)
    return android.net.Uri.parse("content://pnfixture/document/opaque-provider-id")
  }

  private fun portableText(name: String = "shared.PN"): File {
    val folder = FolderEntity("portable-folder", "Subject", createdAt = 1, updatedAt = 2)
    val note = dev.pampa.pampanotes.core.db.NoteEntity("portable-note", folder.id, "Text only", "Complete note text", createdAt = 1, updatedAt = 2)
    val graph = PampaBundleNote(note)
    val export = dev.pampa.pampanotes.core.export.ExportNote(note, listOf(folder.name), emptyList(), emptyList(), emptyList(), native = graph)
    return File(workDir, name).also { file -> file.outputStream().use {
      dev.pampa.pampanotes.core.export.PampaNoteWriter(files.audio, files.sources).write(
        dev.pampa.pampanotes.core.export.ExportSet("Subject", "subject", listOf(export), "Pampa Notes test", 1, folders = listOf(folder)), it)
    } }
  }

  @Test fun portable_content_is_recognized_from_generic_wrong_and_missing_provider_metadata() = runTest {
    val file = portableText()
    val cases = listOf(Triple("Lezione.PN", "application/octet-stream", false), Triple("opaque", "audio/mpeg", false), Triple(null, null, true))
    for ((name, mime, failMetadata) in cases) {
      val candidate = coordinator.inspect(listOf(provider(file, name, mime, failMetadata))).single()
      assertEquals(null, candidate.inspectionError)
      assertEquals(1, candidate.bundle!!.noteCount)
      assertEquals(SourceKind.OTHER, candidate.kind)
      candidate.file!!.delete()
    }
    assertTrue(db.notes().all().isEmpty())
  }

  @Test fun empty_foreign_and_invalid_portable_inputs_do_not_succeed_or_leave_cache_files() = runTest {
    val before = files.temp.listFiles()!!.map { it.name }.toSet()
    val empty = File(workDir, "empty.pn").apply { writeBytes(byteArrayOf()) }
    val foreign = File(workDir, "foreign.zip").apply { java.util.zip.ZipOutputStream(outputStream()).use { } }
    val invalid = write("damaged.PN", "Not a portable package")
    for ((file, expected) in listOf(empty to ImportFailure.EMPTY, foreign to ImportFailure.UNSUPPORTED_ARCHIVE, invalid to ImportFailure.CORRUPT)) {
      val candidate = coordinator.inspect(listOf(android.net.Uri.fromFile(file))).single()
      assertEquals(expected.name, candidate.inspectionError)
      assertEquals(null, candidate.file)
    }
    assertTrue(db.notes().all().isEmpty())
    assertEquals(before, files.temp.listFiles()!!.map { it.name }.toSet())
  }

  @Test fun generic_provider_zip_still_routes_word_and_samsung_documents_by_content() = runTest {
    val docx = File(workDir, "word.bin").also { file -> java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
      zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml")); zip.write("<document/>".toByteArray()); zip.closeEntry()
    } }
    val word = coordinator.inspect(listOf(provider(docx, "opaque", "application/octet-stream"))).single()
    assertEquals(SourceKind.DOCX, word.kind); assertEquals(null, word.bundle); word.file!!.delete()
    val mislabeledWord = coordinator.inspect(listOf(provider(docx, "word.PN", "application/vnd.pampa.notes"))).single()
    assertEquals(SourceKind.DOCX, mislabeledWord.kind); assertEquals(null, mislabeledWord.inspectionError); mislabeledWord.file!!.delete()
    val samsung = coordinator.inspect(listOf(provider(samsungFile("note.bin", "Samsung title"), "opaque", "application/octet-stream"))).single()
    assertEquals(SourceKind.SDOCX, samsung.kind); assertEquals(null, samsung.bundle); samsung.file!!.delete()
  }
}
