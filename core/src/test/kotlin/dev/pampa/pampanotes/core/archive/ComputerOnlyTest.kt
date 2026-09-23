package dev.pampa.pampanotes.core.archive

import android.content.Context
import dev.pampa.pampanotes.core.db.AudioPartDao
import dev.pampa.pampanotes.core.db.AudioPartEntity
import dev.pampa.pampanotes.core.db.FolderDao
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.JobDao
import dev.pampa.pampanotes.core.db.JobEntity
import dev.pampa.pampanotes.core.db.JobState
import dev.pampa.pampanotes.core.db.JobType
import dev.pampa.pampanotes.core.db.SourceDao
import dev.pampa.pampanotes.core.db.SourceEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.db.SyncDao
import dev.pampa.pampanotes.core.files.AppFiles
import dev.pampa.pampanotes.core.repo.FolderRepository
import dev.pampa.pampanotes.core.repo.StorageRepository
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import dev.pampa.pampanotes.core.transcription.EndpointResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * «Solo sul computer»: cosa copre una regola, cosa salta il mirror, cosa se ne va dal dispositivo.
 *
 * Il database e' finto (le query di `SyncDao` rispondono da mappe), i file sono veri: quello che si
 * prova e' la risoluzione cartelle -> note -> sessioni -> file e le guardie, non l'SQL.
 */
class ComputerOnlyTest {

  @get:Rule val temp = TemporaryFolder()

  private lateinit var files: AppFiles

  // Storia (f1) > Novecento (f2) > Guerre (f3); Filosofia (f4) a parte.
  private val folders = listOf(
    folder("f1", null), folder("f2", "f1"), folder("f3", "f2"), folder("f4", null),
  )
  private val notesByFolder = mapOf("f1" to listOf("n1"), "f3" to listOf("n3"), "f4" to listOf("n4", "n5"))
  private val sessionsByNote = mapOf("n1" to listOf("s1"), "n3" to listOf("s3"), "n4" to listOf("s4"), "n5" to listOf("s5"))
  private val partsBySession = mutableMapOf<String, List<AudioPartEntity>>()
  private val sourcesByNote = mutableMapOf<String, List<SourceEntity>>()

  private var folderRules = emptySet<String>()
  private var noteRules = emptySet<String>()
  private val jobs = mutableListOf<JobEntity>()

  private lateinit var settings: PampaSettingsStore
  private lateinit var scope: ComputerOnlyScope

  @Before
  fun setUp() {
    val context = mockk<Context>()
    every { context.filesDir } returns temp.newFolder("files")
    every { context.cacheDir } returns temp.newFolder("cache")
    files = AppFiles(context)

    settings = mockk()
    every { settings.computerOnlyFolders } answers { flowOf(folderRules) }
    every { settings.computerOnlyNotes } answers { flowOf(noteRules) }
    coEvery { settings.current() } returns PampaSettings()

    val folderDao = mockk<FolderDao>()
    coEvery { folderDao.all() } returns folders

    val sync = mockk<SyncDao>()
    coEvery { sync.noteIdsInFolders(any()) } answers { firstArg<List<String>>().flatMap { notesByFolder[it].orEmpty() } }
    coEvery { sync.sessionIdsOfNotes(any()) } answers { firstArg<List<String>>().flatMap { sessionsByNote[it].orEmpty() } }
    coEvery { sync.partsOfSessions(any()) } answers { firstArg<List<String>>().flatMap { partsBySession[it].orEmpty() } }
    coEvery { sync.sourcesOfNotes(any()) } answers { firstArg<List<String>>().flatMap { sourcesByNote[it].orEmpty() } }

    scope = ComputerOnlyScope(settings, folderDao, sync)

    partsBySession["s1"] = listOf(part("p1", "s1", archived = true))
    partsBySession["s3"] = listOf(part("p3", "s3", archived = true), part("p3b", "s3", archived = false))
    partsBySession["s4"] = listOf(part("p4", "s4", archived = true))
    partsBySession["s5"] = listOf(part("p5", "s5", archived = true))
    sourcesByNote["n3"] = listOf(
      source("pdf3", "n3", archived = true),
      source("sdocx3", "n3", archived = true),
      // La pagina a mano ricavata dal .sdocx: resta qui.
      source("ink3", "n3", archived = true, derivedFrom = "sdocx3"),
      // Un appunto incollato non ha file.
      source("clip3", "n3", archived = false, stored = false),
    )
    sourcesByNote["n4"] = listOf(source("pdf4", "n4", archived = true))
  }

  // --- la risoluzione ---

  @Test
  fun `le sottocartelle a qualunque profondita' sono dentro, le sorelle no`() {
    assertEquals(setOf("f1", "f2", "f3"), ComputerOnlyScope.closure(setOf("f1"), folders))
    assertEquals(setOf("f2", "f3", "f4"), ComputerOnlyScope.closure(setOf("f2", "f4"), folders))
    assertEquals(listOf("f1", "f2", "f3"), FolderRepository.descendants("f1", folders).toList())
  }

  @Test
  fun `un ciclo nei genitori non gira per sempre`() {
    val loop = listOf(folder("a", "b"), folder("b", "a"))
    assertEquals(setOf("a", "b"), FolderRepository.descendants("a", loop))
  }

  @Test
  fun `una cartella porta le note delle sottocartelle, le sessioni e i loro file`() = runBlocking {
    val items = scope.resolve(setOf("f1"), emptySet())
    assertEquals(setOf("n1", "n3"), items.noteIds)
    assertEquals(setOf("s1", "s3"), items.sessionIds)
    assertEquals(setOf("p1", "p3", "p3b"), items.parts.map { it.id }.toSet())
    // Le pagine a mano e gli appunti senza file non sono della regola.
    assertEquals(setOf("pdf3", "sdocx3"), items.sources.map { it.id }.toSet())
  }

  @Test
  fun `una nota da sola copre solo se stessa`() = runBlocking {
    val items = scope.resolve(emptySet(), setOf("n5"))
    assertEquals(setOf("n5"), items.noteIds)
    assertEquals(listOf("p5"), items.parts.map { it.id })
    assertTrue(items.sources.isEmpty())
    assertFalse(items.covers(part("p4", "s4", archived = true)))
  }

  @Test
  fun `nessuna regola, niente`() = runBlocking {
    assertTrue(scope.current().isEmpty)
  }

  // --- il mirror ---

  @Test
  fun `tieni tutto anche qui salta quello che sta solo sul computer`() = runBlocking {
    folderRules = setOf("f1")
    // Nessun file e' qui: il computer li ha tutti tranne p3b.
    val audioParts = mockk<AudioPartDao>()
    coEvery { audioParts.archived() } returns partsBySession.values.flatten().filter { it.archivedAt > 0 }
    val sources = mockk<SourceDao>()
    coEvery { sources.archived() } returns sourcesByNote.values.flatten().filter { it.archivedAt > 0 }
    val resolver = mockk<EndpointResolver>()
    coEvery { resolver.resolve(any(), any()) } returns null
    val fetcher = ArchiveFetcher(files, settings, resolver, mockk(relaxed = true), audioParts, sources, mockk(relaxed = true), scope)

    // Fuori dalla regola: p4, p5, pdf4, e la pagina a mano di n3, che resta qui anche dentro una
    // cartella esclusa e quindi si scarica come sempre.
    assertEquals(4, fetcher.pendingCount())

    // Senza computer configurato il primo file fallisce e il giro si ferma: basta che il primo
    // tentato non sia uno di quelli coperti.
    val tried = mutableListOf<String>()
    val outcome = fetcher.fetchAll { if (it.label.isNotEmpty()) tried += it.label }
    assertTrue(outcome.unreachable)
    assertTrue(tried.isNotEmpty())
    assertEquals("p4", tried.first())

    folderRules = emptySet()
    // Senza regole tornano tutti: quattro registrazioni archiviate e quattro originali.
    assertEquals(8, fetcher.pendingCount())
  }

  // --- via dal dispositivo ---

  private fun storage(): StorageRepository {
    val audioParts = mockk<AudioPartDao>()
    val sources = mockk<SourceDao>()
    val jobDao = mockk<JobDao>()
    coEvery { jobDao.all() } answers { jobs.toList() }
    return StorageRepository(audioParts, sources, jobDao, files, scope)
  }

  private fun allHere() {
    partsBySession.values.flatten().forEach { File(files.audio, it.fileName).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(100)) }
    sourcesByNote.values.flatten().forEach { src ->
      src.storedFileName?.let { File(files.sources, it).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(10)) }
    }
  }

  private fun audioHere(id: String) = files.audioFile("$id.m4a").exists()
  private fun sourceHere(id: String) = files.sourceFile("$id.bin").exists()

  @Test
  fun `se ne va l'archiviato coperto, resta il resto`() = runBlocking {
    allHere()
    folderRules = setOf("f2")
    val gone = storage().evictComputerOnly()
    // p3 e i due originali di n3.
    assertEquals(3, gone.count)
    assertEquals(120L, gone.bytes)
    assertFalse(audioHere("p3"))
    assertFalse(sourceHere("pdf3"))
    assertFalse(sourceHere("sdocx3"))
    // Non ancora archiviata: resta finche' il computer non l'ha presa.
    assertTrue(audioHere("p3b"))
    // La pagina a mano resta.
    assertTrue(sourceHere("ink3"))
    // Fuori dalla regola: non si toccano.
    assertTrue(audioHere("p1"))
    assertTrue(audioHere("p4"))
    assertTrue(sourceHere("pdf4"))
  }

  @Test
  fun `una sessione con un lavoro in corso non si tocca, una finita si`() = runBlocking {
    allHere()
    noteRules = setOf("n4", "n5")
    jobs += job("s4", JobState.TRANSCRIBING)
    jobs += job("s5", JobState.DONE)
    storage().evictComputerOnly()
    assertTrue(audioHere("p4"))
    assertFalse(audioHere("p5"))
    // L'originale della nota non e' legato alla coda.
    assertFalse(sourceHere("pdf4"))
  }

  @Test
  fun `la sessione protetta resta`() = runBlocking {
    allHere()
    noteRules = setOf("n4", "n5")
    val storage = storage()
    storage.protectedSessionIds = { setOf("s5") }
    storage.evictComputerOnly()
    assertFalse(audioHere("p4"))
    assertTrue(audioHere("p5"))
  }

  @Test
  fun `la conferma conta quello che c'e' qui e quello che se ne va subito`() = runBlocking {
    allHere()
    File(files.audio, "p1.m4a").delete()
    val preview = storage().computerOnlyPreview(setOf("f1"), emptySet())
    // Qui: p3, p3b; pdf3, sdocx3.
    assertEquals(2, preview.recordings.count)
    assertEquals(2, preview.originals.count)
    // Subito: p3, pdf3, sdocx3 (p3b aspetta l'archivio).
    assertEquals(3, preview.leavingNow.count)
  }

  // --- costruttori ---

  private fun folder(id: String, parent: String?) = FolderEntity(id = id, name = id, parentId = parent, createdAt = 0, updatedAt = 0)

  private fun part(id: String, session: String, archived: Boolean) = AudioPartEntity(
    id = id, sessionId = session, position = 0, fileName = "$id.m4a", originalName = id, mime = "audio/mp4",
    sizeBytes = 100, durationMs = 60_000, sha256 = id, createdAt = 0, archivedAt = if (archived) 1 else 0,
  )

  private fun source(id: String, note: String, archived: Boolean, derivedFrom: String? = null, stored: Boolean = true) = SourceEntity(
    id = id, noteId = note, kind = if (derivedFrom != null) SourceKind.IMAGE else SourceKind.PDF, originalName = id,
    mime = "application/pdf", sizeBytes = 10, sha256 = id, storedFileName = if (stored) "$id.bin" else null,
    importedAt = 0, archivedAt = if (archived) 1 else 0, derivedFromId = derivedFrom,
  )

  private fun job(session: String, state: JobState) = JobEntity(
    id = "j-$session", sessionId = session, type = JobType.TRANSCRIBE, provider = "custom", state = state, createdAt = 0, updatedAt = 0,
  )
}
