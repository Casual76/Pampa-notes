package dev.pampa.pampanotes.ui.export

import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.export.*
import dev.pampa.pampanotes.core.settings.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExportFailureDiagnosticTest {
  private val dispatcher = StandardTestDispatcher()
  @Before fun setup() { Dispatchers.setMain(dispatcher) }
  @After fun cleanup() { Dispatchers.resetMain() }

  @Test fun `both destinations expose the same underlying support code and dismiss clears it`() = runTest {
    val service = mockk<ExportService>()
    val settings = mockk<PampaSettingsStore>()
    coEvery { settings.current() } returns PampaSettings(exportDefaultsJson = ExportOptionsCodec.encode(ExportTarget.PAMPA.defaults()))
    val note = NoteEntity("n", "f", "Title", createdAt = 1, updatedAt = 1)
    coEvery { service.gather(any(), any(), any(), any()) } returns ExportSet("Title", "title",
      listOf(ExportNote(note, emptyList(), emptyList(), emptyList(), emptyList())), "test", 1)
    val plan = mockk<ExportFetchPlan>()
    every { plan.toFetch } returns emptyList()
    every { plan.unobtainable } returns emptyList()
    coEvery { service.plan(any(), any()) } returns plan
    coEvery { service.export(any(), any(), any(), any(), any()) } coAnswers {
      val reason = if (arg<ExportDestination>(2) is ExportDestination.Share) ExportFailure.Reason.WRITE else ExportFailure.Reason.INTERRUPTED
      throw ExportFailure(reason, IllegalArgumentException("Invalid word timings"))
    }
    val model = ExportViewModel(mockk(), service, settings)
    model.start(ExportScope.Note("n"), ExportLabels(), "All"); runCurrent()
    model.share(); runCurrent()
    assertEquals(ExportStage.FAILED, model.uiState.value.stage)
    assertEquals(ExportFailure.Reason.WRITE, model.uiState.value.failure)
    assertEquals("PN_WORD_TIMINGS", model.uiState.value.failureCode)
    model.dismissResult(); assertNull(model.uiState.value.failureCode)
    model.saveTo(mockk(), remember = false); runCurrent()
    assertEquals(ExportFailure.Reason.INTERRUPTED, model.uiState.value.failure)
    assertEquals("PN_WORD_TIMINGS", model.uiState.value.failureCode)
    model.dismissResult(); assertNull(model.uiState.value.failureCode)
  }
}
