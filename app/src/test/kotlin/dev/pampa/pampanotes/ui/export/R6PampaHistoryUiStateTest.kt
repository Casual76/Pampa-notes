package dev.pampa.pampanotes.ui.export

import dev.pampa.pampanotes.core.db.NoteEntity
import dev.pampa.pampanotes.core.export.*
import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.PampaSettingsStore
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** PREPARED ONLY: real ViewModel state/actions with in-memory service/settings doubles.
 * No Compose rendering, Android device, file output, provider or network. */
@OptIn(ExperimentalCoroutinesApi::class)
class R6PampaHistoryUiStateTest {
  private val dispatcher = StandardTestDispatcher()
  @Before fun main() { Dispatchers.setMain(dispatcher) }
  @After fun reset() { Dispatchers.resetMain() }
  private val selected = ExportScope.Sessions(listOf("s"), "Selected recording")
  private val pn = ExportTarget.PAMPA.defaults()
  private fun set(scope: ExportScope, ids: List<String> = listOf("n")) = ExportSet("Scope", "scope", ids.map { id ->
    ExportNote(NoteEntity(id, "f", id, createdAt = 1, updatedAt = 2), emptyList(), emptyList(), emptyList(), emptyList(),
      selectedSessionIds = if (scope is ExportScope.Sessions) scope.sessionIds.toSet() else null)
  }, "synthetic", 1)

  private inner class Fixture(val noteIds: List<String> = listOf("n"), defaults: ExportOptions = pn) {
    val service = mockk<ExportService>()
    val settings = mockk<PampaSettingsStore>()
    val model: ExportViewModel
    init {
      coEvery { settings.current() } returns PampaSettings(exportDefaultsJson = ExportOptionsCodec.encode(defaults))
      coEvery { service.gather(any(), any(), any(), any()) } answers {
        val scope = firstArg<ExportScope>()
        val options = secondArg<ExportOptions>()
        if (scope is ExportScope.Sessions && options.isPampa) throw PampaHistoryScopeException(noteIds)
        set(scope, noteIds)
      }
      model = ExportViewModel(mockk(), service, settings)
    }
    fun start() = model.start(selected, ExportLabels(), "All")
    fun noOutputOrFetch() {
      coVerify(exactly = 0) { service.plan(any(), any()) }
      coVerify(exactly = 0) { service.fetchMissing(any(), any()) }
      coVerify(exactly = 0) { service.export(any(), any(), any(), any(), any()) }
    }
  }

  @Test fun `blocked restricted PN does not broaden on share decline cancel or unprompted confirmation`() = runTest {
    val f = Fixture(); f.start(); runCurrent()
    assertEquals(listOf("n"), f.model.uiState.value.historyScope!!.noteIds)
    assertFalse(f.model.uiState.value.ready)
    assertNull(f.model.uiState.value.set)
    f.model.share()
    f.model.confirmWholeNotes("Whole notes") // No review prompt, no authorization.
    f.model.requestWholeNotes(); assertTrue(f.model.uiState.value.confirmingWholeNotes)
    f.model.declineWholeNotes(); assertFalse(f.model.uiState.value.confirmingWholeNotes)
    f.model.requestWholeNotes(); f.model.cancel()
    f.model.confirmWholeNotes("Whole notes"); runCurrent()
    assertFalse(f.model.uiState.value.wholeNotesAuthorized)
    assertFalse(f.model.uiState.value.ready)
    coVerify(exactly = 1) { f.service.gather(selected, pn, any(), "All") }
    f.noOutputOrFetch()
  }

  @Test fun `confirmation explicitly gathers only backing notes and waits for a separate export action`() = runTest {
    val f = Fixture(listOf("n", "other-backing-note")); f.start(); runCurrent()
    f.model.requestWholeNotes(); f.model.confirmWholeNotes("Whole notes")
    assertFalse(f.model.uiState.value.ready) // Old reduced set cannot be reused synchronously.
    runCurrent()
    val state = f.model.uiState.value
    assertTrue(state.wholeNotesAuthorized)
    assertTrue(state.ready)
    assertNull(state.historyScope)
    val gathered = requireNotNull(state.set)
    assertEquals(listOf("n", "other-backing-note"), gathered.notes.map { it.note.id })
    assertTrue(gathered.notes.all { it.selectedSessionIds == null })
    coVerify(exactly = 1) { f.service.gather(ExportScope.Notes(listOf("n", "other-backing-note"), "Whole notes"), pn, any(), "All") }
    coVerify(exactly = 0) { f.service.gather(ExportScope.Everything, any(), any(), any()) }
    f.noOutputOrFetch()
  }

  @Test fun `confirmed single note can return to exact recording scope and confirmation never persists on reopening`() = runTest {
    val f = Fixture(); f.start(); runCurrent()
    f.model.requestWholeNotes(); f.model.confirmWholeNotes("Whole notes"); runCurrent()
    coVerify(exactly = 1) { f.service.gather(ExportScope.Note("n"), pn, any(), "All") }
    f.model.restoreRequestedScope()
    assertFalse(f.model.uiState.value.ready)
    runCurrent()
    assertNotNull(f.model.uiState.value.historyScope)
    assertFalse(f.model.uiState.value.wholeNotesAuthorized)
    f.model.requestWholeNotes(); f.model.confirmWholeNotes("Whole notes"); runCurrent()
    f.start()
    assertFalse(f.model.uiState.value.ready)
    assertFalse(f.model.uiState.value.wholeNotesAuthorized)
    runCurrent()
    assertNotNull(f.model.uiState.value.historyScope)
    f.noOutputOrFetch()
  }

  @Test fun `switching AI to PN rereads native history before any export instead of reusing pruned AI snapshot`() = runTest {
    val ai = ExportOptions()
    val f = Fixture(defaults = ai); f.start(); runCurrent()
    assertTrue(f.model.uiState.value.ready)
    f.model.setOptions(pn)
    assertNull(f.model.uiState.value.set)
    f.model.share(); runCurrent()
    assertNotNull(f.model.uiState.value.historyScope)
    coVerify(exactly = 1) { f.service.gather(selected, pn, any(), "All") }
    f.model.setOptions(ai); runCurrent()
    assertTrue(f.model.uiState.value.ready)
    assertNull(f.model.uiState.value.historyScope)
    assertFalse(f.model.uiState.value.wholeNotesAuthorized)
    coVerify(exactly = 2) { f.service.gather(selected, ai, any(), "All") }
    f.noOutputOrFetch()
  }
}
