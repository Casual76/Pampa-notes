package dev.pampa.pampanotes.ui.importing

import android.util.Log
import dev.pampa.pampanotes.core.db.FolderEntity
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.importing.*
import dev.pampa.pampanotes.core.repo.FolderRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ImportDiagnosticFlowTest {
  @get:Rule val temp = TemporaryFolder()
  private val dispatcher = StandardTestDispatcher()
  @Before fun setup() {
    Dispatchers.setMain(dispatcher)
    mockkStatic(Log::class)
    every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
  }
  @After fun cleanup() { unmockkStatic(Log::class); Dispatchers.resetMain() }

  private fun model(coordinator: ImportCoordinator, folders: FolderRepository, importer: PampaBundleImporter): ImportViewModel {
    val requests = ImportRequestHolder().also { it.offer(ImportRequest(uris = listOf(mockk()))) }
    return ImportViewModel(mockk(relaxed = true), coordinator, folders, mockk(), mockk(), requests,
      mockk(), mockk(), importer, mockk())
  }

  @Test fun preparationFailureReportsItsStageWithoutDisplayingThePrivateException() = runTest {
    val coordinator = mockk<ImportCoordinator>()
    coEvery { coordinator.inspect(any()) } throws IllegalStateException("private note content and local path")
    val model = model(coordinator, mockk(), mockk())
    runCurrent()
    assertEquals(ImportStep.DONE, model.uiState.value.step)
    assertEquals("IMPORT_PREPARE_CORRUPT", model.uiState.value.errorDiagnostic)
    assertNull(model.uiState.value.error)
    assertNotNull(model.uiState.value.errorRes)
    coVerify(exactly = 1) { coordinator.inspect(any()) }
  }

  @Test fun failingBundlePrecommitReportsLocalFileCodeAndPreservesTheArchiveOwner() = runTest {
    val coordinator = mockk<ImportCoordinator>()
    val folders = mockk<FolderRepository>()
    val importer = mockk<PampaBundleImporter>()
    val candidate = ImportCandidate("pn", null, temp.newFile(), "note.pn", SourceKind.OTHER,
      "application/octet-stream", 1, "hash", bundle = PampaBundlePreview(1, 1, 1, 0, listOf("Note"), false))
    coEvery { coordinator.inspect(any()) } returns listOf(candidate)
    coEvery { folders.all() } returns listOf(FolderEntity("f", "Subject", createdAt = 1, updatedAt = 1))
    coEvery { folders.parentPathString(any()) } returns "Subject"
    coEvery { importer.import(any(), any(), any(), any()) } throws IllegalArgumentException("Included attachment integrity unavailable")
    val model = model(coordinator, folders, importer)
    runCurrent()
    assertEquals(ImportStep.REVIEW, model.uiState.value.step)
    assertNull(model.uiState.value.errorDiagnostic)
    model.next(); runCurrent()
    assertEquals(ImportStep.DONE, model.uiState.value.step)
    assertEquals("IMPORT_PERSIST_LOCAL_FILE", model.uiState.value.errorDiagnostic)
    assertNull(model.uiState.value.error)
    // The wizard owns only its inspected temporary copy; original provider files are never referenced here.
    assertFalse(requireNotNull(candidate.file).exists())
    coVerify(exactly = 1) { importer.import(any(), any(), any(), any()) }
  }
}
