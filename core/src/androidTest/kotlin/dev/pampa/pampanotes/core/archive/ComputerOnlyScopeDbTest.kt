package dev.pampa.pampanotes.core.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.db.PampaDatabase
import dev.pampa.pampanotes.core.db.SessionEntity
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.StorageRepository
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.files.FilesInUse
import dev.pampa.pampanotes.core.transcription.ComputerAuth
import dev.pampa.pampanotes.core.transcription.EndpointResolver
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * «Solo sul computer» contro un database vero: le query di `SyncDao` e le chiavi esterne, che il
 * test in JVM (`ComputerOnlyTest`) sostituisce con delle mappe. Le regole si passano a `resolve`,
 * non si scrivono nel DataStore: il test non lascia niente nelle impostazioni del dispositivo.
 */
@RunWith(AndroidJUnit4::class)
class ComputerOnlyScopeDbTest {

  private lateinit var db: PampaDatabase
  private lateinit var scope: ComputerOnlyScope

  @Before
  fun setUp() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    db = PampaDatabase.inMemory(context)
    scope = ComputerOnlyScope(PampaSettingsStore(context), db.folders(), db.sync())
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun una_cartella_copre_le_sottocartelle_e_lascia_le_pagine_a_mano() = runTest {
    folder("storia", null)
    folder("novecento", "storia")
    folder("guerre", "novecento")
    folder("filosofia", null)
    note("n-storia", "storia")
    note("n-guerre", "guerre")
    note("n-fichte", "filosofia")
    session("s-storia", "n-storia")
    session("s-guerre", "n-guerre")
    session("s-fichte", "n-fichte")
    part("p-storia", "s-storia")
    part("p-guerre", "s-guerre")
    part("p-fichte", "s-fichte")
    source("sdocx", "n-guerre", derivedFrom = null)
    source("pagina-1", "n-guerre", derivedFrom = "sdocx")
    source("pdf", "n-fichte", derivedFrom = null)

    assertEquals(setOf("storia", "novecento", "guerre"), FolderRepository(db.folders(), storage()).descendantIds("storia"))

    val items = scope.resolve(setOf("storia"), emptySet())
    assertEquals(setOf("n-storia", "n-guerre"), items.noteIds)
    assertEquals(setOf("p-storia", "p-guerre"), items.parts.map { it.id }.toSet())
    assertEquals(setOf("sdocx"), items.sources.map { it.id }.toSet())

    val single = scope.resolve(emptySet(), setOf("n-fichte"))
    assertEquals(setOf("p-fichte"), single.parts.map { it.id }.toSet())
    assertEquals(setOf("pdf"), single.sources.map { it.id }.toSet())
  }

  private fun storage(): StorageRepository {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val files = AppFiles(context)
    // Il computer qui non c'e': l'archivio serve solo al costruttore, e non chiama nessuno.
    val auth = ComputerAuth(account = { null }, manualCode = { null }, fetch = { _, _ -> error("nessun Worker nei test") }, clock = System::currentTimeMillis)
    val archive = ArchiveRepository(db.audioParts(), db.sources(), files, PampaSettingsStore(context), EndpointResolver(), ArchiveHttp(userAgent = "PampaNotes-test"), auth)
    return StorageRepository(db.audioParts(), db.sources(), db.jobs(), files, scope, archive, FilesInUse())
  }

  private suspend fun folder(id: String, parent: String?) =
    db.folders().upsert(FolderEntity(id = id, name = id, parentId = parent, createdAt = 0, updatedAt = 0))

  private suspend fun note(id: String, folder: String) =
    db.notes().upsert(NoteEntity(id = id, folderId = folder, title = id, createdAt = 0, updatedAt = 0))

  private suspend fun session(id: String, note: String) =
    db.sessions().upsert(SessionEntity(id = id, noteId = note, date = "2026-09-23", position = 0, createdAt = 0, updatedAt = 0))

  private suspend fun part(id: String, session: String) = db.audioParts().upsert(
    AudioPartEntity(
      id = id, sessionId = session, position = 0, fileName = "$id.m4a", originalName = id, mime = "audio/mp4",
      sizeBytes = 1, durationMs = 60_000, sha256 = id, createdAt = 0, archivedAt = 1,
    ),
  )

  private suspend fun source(id: String, note: String, derivedFrom: String?) = db.sources().upsert(
    SourceEntity(
      id = id, noteId = note, kind = if (derivedFrom == null) SourceKind.SDOCX else SourceKind.IMAGE, originalName = id,
      mime = "application/octet-stream", sizeBytes = 1, sha256 = id, storedFileName = "$id.bin", importedAt = 0,
      archivedAt = 1, derivedFromId = derivedFrom,
    ),
  )
}
