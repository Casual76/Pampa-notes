package dev.pampa.pampanotes.ui.importing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.antigravity.fluidengine.ui.fluid.FluidLabels
import dev.antigravity.fluidengine.ui.fluid.LocalFluidLabels
import dev.antigravity.fluidengine.ui.theme.FluidTheme
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.db.SourceKind
import dev.pampa.pampanotes.core.importing.ImportCandidate
import dev.pampa.pampanotes.core.importing.ImportFailure
import dev.pampa.pampanotes.core.importing.PampaBundlePreview
import dev.pampa.pampanotes.core.importing.PampaBundleWarning
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PortableImportScreenTest {
  @get:Rule val compose = createComposeRule()
  private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
  private fun show(state: ImportUiState, onImport: () -> Unit = {}) {
    compose.setContent {
      FluidTheme(EngineSettings()) {
        CompositionLocalProvider(LocalFluidLabels provides FluidLabels.english()) {
          ImportScreen(state, onClose = {}, onOpenNote = {}, onToggle = {}, onSelectFolder = {}, onSelectNote = {},
            onTitleChange = {}, onUpdateExisting = {}, onCreateFolder = { _, _, _ -> }, onSelectSession = {},
            onSessionDate = {}, onToggleGroupStart = {}, onSplitAll = {}, onJoinAll = {}, onGroupTitle = { _, _ -> },
            onGroupsAsNotes = {}, onNext = onImport, onBack = {})
        }
      }
    }
  }

  @Test fun invalid_file_shows_actionable_category_and_cannot_be_imported() {
    val candidate = ImportCandidate("bad", null, null, "lesson.PN", SourceKind.OTHER, "application/octet-stream", 0, "",
      inspectionError = ImportFailure.ACCESS.name)
    show(ImportUiState(step = ImportStep.REVIEW, candidates = listOf(candidate)))
    compose.onNodeWithText("lesson.PN").assertIsDisplayed()
    compose.onNodeWithText(text(R.string.pn_import_access)).assertIsDisplayed()
    compose.onNodeWithText(text(R.string.action_import)).assertDoesNotExist()
  }

  @Test fun portable_review_explains_missing_media_and_offers_update_copy_and_original_hierarchy() {
    val preview = PampaBundlePreview(1, 0, 1, 0, listOf("Lesson"), false, listOf(PampaBundleWarning.OMITTED_ATTACHMENTS))
    val candidate = ImportCandidate("pn", null, null, "lesson.PN", SourceKind.OTHER, "application/vnd.pampa.notes", 100, "", bundle = preview)
    var imported = 0
    show(ImportUiState(step = ImportStep.REVIEW, candidates = listOf(candidate))) { imported++ }
    compose.onNodeWithText(text(R.string.pn_import_contents)).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText(text(R.string.import_bundle_omitted_warning)).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText(text(R.string.import_bundle_update)).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText(text(R.string.import_bundle_copies)).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText(text(R.string.import_bundle_hierarchy)).performScrollTo().assertIsDisplayed()
    compose.onNodeWithText(text(R.string.action_import)).performScrollTo().performClick()
    compose.runOnIdle { assertEquals(1, imported) }
  }
}
